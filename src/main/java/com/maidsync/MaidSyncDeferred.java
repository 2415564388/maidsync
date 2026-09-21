package com.maidsync;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsync.compat.SableGate;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>延迟补包 —— 本模组的实际修复手段。</b>
 *
 * <h2>为什么必须"延迟"</h2>
 * 现场收包序列（实测）：
 * <pre>
 * 15:02:45.749  ← 移除包 ids=[16]                          ← 玩家被传走，批量移除
 * 15:02:45.791  ← 生成包 女仆#16 @ -428.2/-60.0/-577.5     ← 42ms 后加回，坐标是【旧位置】
 * 15:02:45.925  ← 传送包 目标 -100.5/-60.0/156.5           ← 然后才把她传向玩家
 * </pre>
 * 客户端那个新建的实体落在**一个没加载的区块**里 → section = {@code TRACKED} →
 * 进不了 {@code ClientLevel.tickingEntities} → {@code Entity.tick()} 永不执行 →
 * {@code LivingEntity} 的 3 步插值永远走不完 → 永久卡在生成包给的旧坐标。
 *
 * <p>所以"跳变当帧就重建"是<b>没用的</b>（试过，无效）：那一刻她的服务端位置可能还是旧的。
 * 必须等传送真正生效之后再补，生成包才会带**玩家身边的新坐标** ——
 * 那个区块客户端是加载的，新实体能 tick，插值也不再有未完成状态。
 *
 * <p>四包序列与 promaid 的 {@code /maid_smart resync} 完全一致 —— 那条命令
 * 用户实测「敲一下当场恢复」，本类只是把它自动化。
 *
 * <p>用裸发包而不是 {@code removePairing/addPairing}：前者是已验证有效的路径，
 * 且不依赖能否拿到 {@code ServerEntity} 实例。
 */
public final class MaidSyncDeferred {

    /** 同一个女仆在这段时间内不重复排队（tick）。 */
    private static final long DEDUP_TICKS = 100L;

    private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();

    private MaidSyncDeferred() {
    }

    /** 由 {@code ServerEntityMixin} 在检测到大跳变时调用。 */
    public static void schedule(EntityMaid maid) {
        if (!MaidSyncConfig.enabled()) {
            return;
        }
        try {
            long now = maid.level().getGameTime();
            Long existing = PENDING.get(maid.getUUID());
            if (existing != null && existing > now) {
                return; // 已经排了一个更晚的，别插队
            }
            if (PENDING.size() > 256) {
                PENDING.clear();
            }
            PENDING.put(maid.getUUID(), now + MaidSyncConfig.deferredRebuildDelayTicks());
        } catch (Throwable ignored) {
        }
    }

    /** 由 {@code MaidSyncMod} 的 ServerTickEvent.Post 调用。 */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty() || !MaidSyncConfig.enabled()) {
            return;
        }
        try {
            long now = server.overworld().getGameTime();
            Iterator<Map.Entry<UUID, Long>> it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> entry = it.next();
                if (now < entry.getValue()) {
                    continue;
                }
                it.remove();
                EntityMaid maid = findMaid(server, entry.getKey());
                if (maid != null) {
                    resync(server, maid);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static EntityMaid findMaid(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(uuid) instanceof EntityMaid maid) {
                return maid;
            }
        }
        return null;
    }

    /**
     * 只给「当前真的在追踪她」的客户端补包 —— 判定见 {@link MaidTracking}。
     *
     * <p>收件人<b>不再</b>按「同维度 + 非主人 128 格内 / 主人无条件」硬筛：那套筛法不看追踪关系，
     * 而这个模组只能用裸包（服务端不会因此登记追踪关系），给没在追踪她的客户端发
     * 只会在他的客户端上造出一个服务端再也不管的幽灵实体。主人那一档的
     * {@code Double.MAX_VALUE} 尤其危险：传送没真正落到主人身边时，会给他发一份
     * <b>远处未加载区块里</b>的生成包 —— 正好又造出本模组要修的那个冻结态，且这次没有第二次自愈机会。
     */
    private static void resync(MinecraftServer server, EntityMaid maid) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return;
        }
        // ★ 排队之后、到点之前她可能已经上了 Sable 的子关卡。补包发的是【裸包】，
        //   而 Sable 在客户端收到生成包时会（因为她不在 retain_in_sub_level 标签里）
        //   把包里的世界坐标当成 plot 坐标【再变换一次】，把她摆到另一个错位置。
        //   所以子关卡上不补 —— 那只会把抖动再加一层。
        if (MaidSyncConfig.skipSubLevels() && SableGate.inSubLevel(maid)) {
            MaidSyncLog.skippedSubLevel(maid, "deferred");
            return;
        }
        int sent = 0;
        int sameDimension = 0;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.level() != level) {
                    continue;
                }
                sameDimension++;
                if (!MaidTracking.shouldSendTo(level, maid, player)) {
                    continue;
                }
                sendResync(player, maid);
                sent++;
            }
        } catch (Throwable t) {
            MaidSyncMod.LOGGER.warn("[maidsync] 延迟补包失败：{}", t.toString());
            return;
        }
        if (sent > 0) {
            MaidSyncLog.deferredResynced(maid, sent, maid.getVehicle() != null);
        } else {
            MaidSyncLog.deferredNoRecipient(maid, sameDimension);
        }
    }

    /**
     * 与 promaid 的 {@code /maid_smart resync} 同一套四包序列，<b>外加乘客包</b>。
     *
     * <h2>为什么必须补乘客包</h2>
     * 头一个 {@code ClientboundRemoveEntitiesPacket} 会让客户端走
     * {@code Entity.setRemoved(DISCARDED)}，而那个方法里有
     * {@code if (removalReason.shouldDestroy()) stopRiding()} —— <b>她被从载具上摘下来了</b>。
     * 紧接着的 {@code ClientboundAddEntityPacket} 是裸生成包，包里<b>没有载具字段</b>，
     * 她以"没骑任何东西"的状态重建。
     *
     * <p>而服务端并不知情，也不会自己纠正：乘客名单是<b>载具那一侧</b>的
     * {@code ServerEntity} 在"名单变了"时广播的，而服务端眼里名单从来没变过
     * （变的是客户端），所以那一包永远不发第二次。于是脱钩是<b>永久</b>的。
     *
     * <p>症状正是「坐下异常」：TLM 的坐下动画（{@code AnimationRegister} 里的
     * {@code "chair"}）触发条件就是 {@code maid.asEntity().isPassenger()}，掉了就永不播放；
     * 而且 {@code ServerEntity.sendChanges} 对乘客<b>只发转向、不发坐标</b>（位置本该由载具带），
     * 于是她还会永久卡在生成包给的那个坐标上 —— 看起来就是"错位"。
     *
     * <p>原版 {@code ServerEntity.sendPairingData} 在最后发的正是这两包，这里照抄它的条件与顺序。
     */
    private static void sendResync(ServerPlayer viewer, EntityMaid maid) {
        BlockPos pos = maid.blockPosition();
        viewer.connection.send(new ClientboundRemoveEntitiesPacket(maid.getId()));
        viewer.connection.send(new ClientboundAddEntityPacket(maid, 0, pos));
        viewer.connection.send(new ClientboundSetEntityDataPacket(maid.getId(),
                maid.getEntityData().getNonDefaultValues()));

        List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
        equipment.add(Pair.of(EquipmentSlot.MAINHAND, maid.getMainHandItem()));
        equipment.add(Pair.of(EquipmentSlot.OFFHAND, maid.getOffhandItem()));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                equipment.add(Pair.of(slot, maid.getItemBySlot(slot)));
            }
        }
        viewer.connection.send(new ClientboundSetEquipmentPacket(maid.getId(), equipment));

        // ★ 乘客关系。少了这两包，重建出来的客户端女仆会从载具上掉下来（见上面的长注释）。
        //   对应原版 ServerEntity.sendPairingData 结尾的那两段，连条件都一样。
        if (!maid.getPassengers().isEmpty()) {
            viewer.connection.send(new ClientboundSetPassengersPacket(maid));
        }
        Entity vehicle = maid.getVehicle();
        if (vehicle != null) {
            viewer.connection.send(new ClientboundSetPassengersPacket(vehicle));
        }
    }
}
