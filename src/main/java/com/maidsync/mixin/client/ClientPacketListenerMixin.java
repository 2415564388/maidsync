package com.maidsync.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsync.MaidSyncConfig;
import com.maidsync.MaidSyncMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端收包记录：把每一个与女仆有关的包连同坐标记下来。
 *
 * <p>为什么需要它：现场实测里客户端那份实体曾"只移动 1/3 就永久停住"，而我们不知道
 * 是哪一条包把她推过去的。只靠服务端探针看不出来——服务端那边一切正常。
 *
 * <h2>关键背景：1/3 是怎么来的</h2>
 * <pre>
 * ClientboundTeleportEntityPacket
 *   → ClientPacketListener.handleTeleportEntity → entity.lerpTo(x,y,z,yRot,xRot,3)
 *   → LivingEntity.lerpTo 覆写版：只设置 lerpX/lerpY/lerpZ + lerpSteps = 3
 *   → 下一 tick，LivingEntity.tick() 执行
 *        lerpPositionAndRotationStep(lerpSteps, lerpX, lerpY, lerpZ, ...)
 *        其中 d0 = getX() + (lerpX - getX()) / steps
 *   → steps=3 时，走完第一步位置恰好是起终点的 1/3，然后 lerpSteps 变成 2
 * </pre>
 * <b>若实体在这一刻停止 tick（落点所在 section 不 tick），插值就永远走不完，
 * 永久冻在 1/3 —— 接下来 {@code LevelRenderer} 第 2 道门 {@code isSectionCompiled}
 * 会跳过她，模型和碰撞箱一起消失。</b>
 *
 * <p>注意 {@code Entity.lerpTo} 是直接 {@code setPos}、不插值；只有
 * {@code LivingEntity} 的覆写版才插值。女仆是 LivingEntity，走的是覆写版。
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleAddEntity", at = @At("HEAD"))
    private void maidsync$onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        if (!MaidSyncConfig.diagnose() || packet.getType() != EntityMaid.TYPE) {
            return;
        }
        MaidSyncMod.LOGGER.info(
                "[maidsync/客户端] ← 生成包 女仆#{} @ {}/{}/{}",
                packet.getId(), f(packet.getX()), f(packet.getY()), f(packet.getZ()));
    }

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"))
    private void maidsync$onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        if (!MaidSyncConfig.diagnose()) {
            return;
        }
        MaidSyncMod.LOGGER.info("[maidsync/客户端] ← 移除包 ids={}", packet.getEntityIds());
    }

    /**
     * 传送包：这条是插值的源头。记下包里的目标坐标和实体当前坐标 ——
     * 两者的 1/3 就是她即将冻住的位置。
     */
    @Inject(method = "handleTeleportEntity", at = @At("HEAD"))
    private void maidsync$onTeleportEntity(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        if (!MaidSyncConfig.diagnose()) {
            return;
        }
        EntityMaid maid = maidById(packet.getId());
        if (maid == null) {
            return;
        }
        MaidSyncMod.LOGGER.info(
                "[maidsync/客户端] ← 传送包 女仆#{} 目标 {}/{}/{} | 当前 {}/{}/{} | 若走不完会冻在 {}/{}/{}",
                packet.getId(),
                f(packet.getX()), f(packet.getY()), f(packet.getZ()),
                f(maid.getX()), f(maid.getY()), f(maid.getZ()),
                f(maid.getX() + (packet.getX() - maid.getX()) / 3.0),
                f(maid.getY() + (packet.getY() - maid.getY()) / 3.0),
                f(maid.getZ() + (packet.getZ() - maid.getZ()) / 3.0));
    }

    /** 相对位移包：女仆走动时的常规包，量很大，抽样记录。 */
    @Inject(method = "handleMoveEntity", at = @At("HEAD"))
    private void maidsync$onMoveEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        if (!MaidSyncConfig.diagnose() || !packet.hasPosition()) {
            return;
        }
        // ClientboundMoveEntityPacket 的 entityId 是 protected 且没有 getter，
        // 所以走原版自己的 getEntity(Level)（它在找不到实体时返回 null）。
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Entity entity = packet.getEntity(level);
        if (!(entity instanceof EntityMaid maid)
                || !com.maidsync.probe.ClientPacketProbe.sampleMove(maid.getId())) {
            return;
        }
        MaidSyncMod.LOGGER.info(
                "[maidsync/客户端] ← 位移包(相对) 女仆#{} 当前 {}/{}/{}",
                maid.getId(), f(maid.getX()), f(maid.getY()), f(maid.getZ()));
    }

    /**
     * 乘客包 —— 「女仆能不能正常坐下」这条线最关键的一条包，以前没记。
     *
     * <p>TLM 的坐下动画有两条路：{@code AnimationRegister} 里 {@code "chair"} 的触发条件是
     * {@code isPassenger()}（骑在坐垫/椅子/{@code EntitySit} 上走这条），{@code "sit"} 的
     * 条件是 {@code isMaidInSittingPose()}（同步数据，走另一条）。**前者完全依赖客户端
     * 那只实体有没有载具**，而载具关系<b>只有这一条包</b>能建立。
     *
     * <p>所以只要女仆坐着不播放动画，就要问：这条包到底来没来？来了之后有没有生效？
     * 两种失败都记进日志：
     * <ul>
     *   <li><b>载具/乘客查不到</b> —— 客户端还不认识这个 id（生成包没到 / 顺序反了），
     *       原版这时候会打一句 {@code Received passengers for unknown entity} 然后<b>整包丢掉</b>；
     *   <li><b>来了、也认识了，但女仆的 isPassenger 仍是 false</b> —— 包被谁吃掉了。
     * </ul>
     */
    @Inject(method = "handleSetEntityPassengersPacket", at = @At("HEAD"))
    private void maidsync$onSetPassengers(ClientboundSetPassengersPacket packet, CallbackInfo ci) {
        if (!MaidSyncConfig.diagnose()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        int vehicleId = packet.getVehicle();
        int[] passengers = packet.getPassengers();
        Entity vehicle = level.getEntity(vehicleId);

        // 与女仆无关的乘客包不记（矿车、船、别的模组的座位都走这条包，会刷屏）
        boolean involvesMaid = vehicle instanceof EntityMaid;
        for (int id : passengers) {
            if (!involvesMaid && level.getEntity(id) instanceof EntityMaid) {
                involvesMaid = true;
            }
        }
        if (!involvesMaid) {
            return;
        }

        MaidSyncMod.LOGGER.info(
                "[maidsync/客户端] ← 乘客包 载具#{}[{}] 乘客={} || 女仆状态：{}",
                vehicleId,
                vehicle == null ? "★客户端查无此实体" : vehicle.getType().toShortString(),
                describe(level, passengers),
                maidState(level, vehicleId, passengers));
    }

    /** 把 id 数组翻成「#12(类型)」，查不到就标星 —— 查不到正是原版整包丢掉的原因。 */
    private static String describe(ClientLevel level, int[] ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Entity e = level.getEntity(ids[i]);
            sb.append('#').append(ids[i]).append('(')
                    .append(e == null ? "★查无" : e.getType().toShortString()).append(')');
        }
        return sb.append(']').toString();
    }

    /** 这只女仆此刻在客户端眼中的乘骑状态；载具是不是她也一并说清。 */
    private static String maidState(ClientLevel level, int vehicleId, int[] passengers) {
        StringBuilder sb = new StringBuilder();
        if (level.getEntity(vehicleId) instanceof EntityMaid m) {
            sb.append("女仆#").append(m.getId()).append(" 载了 ")
                    .append(m.getPassengers().size()).append(" 个");
        }
        for (int id : passengers) {
            if (level.getEntity(id) instanceof EntityMaid m) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("女仆#").append(m.getId())
                        .append(" isPassenger=").append(m.isPassenger());
            }
        }
        return sb.length() == 0 ? "（没找到女仆实体）" : sb.toString();
    }

    private static EntityMaid maidById(int entityId) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        Entity entity = level.getEntity(entityId);
        return entity instanceof EntityMaid maid ? maid : null;
    }

    private static String f(double d) {
        return String.format("%.1f", d);
    }
}
