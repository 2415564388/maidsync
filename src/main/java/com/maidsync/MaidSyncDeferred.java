package com.maidsync;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
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

    /** 给同维度、且主人一定覆盖到的玩家补包。 */
    private static void resync(MinecraftServer server, EntityMaid maid) {
        int sent = 0;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.level() != maid.level()) {
                    continue;
                }
                boolean isOwner = player.getUUID().equals(maid.getOwnerUUID());
                double range = isOwner ? Double.MAX_VALUE : 128.0D * 128.0D;
                if (player.distanceToSqr(maid) > range) {
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
            MaidSyncLog.deferredResynced(maid, sent);
        }
    }

    /** 与 promaid 的 /maid_smart resync 完全同一套四包序列。 */
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
    }
}
