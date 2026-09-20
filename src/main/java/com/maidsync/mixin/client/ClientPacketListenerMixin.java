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
