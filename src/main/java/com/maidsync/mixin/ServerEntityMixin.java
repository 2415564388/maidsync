package com.maidsync.mixin;

import com.maidsync.MaidSyncConfig;
import com.maidsync.MaidSyncCooldown;
import com.maidsync.MaidSyncDeferred;
import com.maidsync.MaidSyncLog;
import com.maidsync.MaidSyncMod;
import com.maidsync.MaidSyncPending;
import com.maidsync.MaidTarget;
import com.maidsync.MaidTracking;
import com.maidsync.compat.SableGate;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 核心修复：在大跳变的传送包发出之前把它拦下来，改成让客户端重建实体。
 *
 * <h2>为什么挂在这里</h2>
 * {@code ServerEntity.sendChanges()} 是全原版<b>唯一</b>的位置包出口
 * （唯一调用点是 {@code ChunkMap.tick()}，条件是 {@code entityMoved || inEntityTickingRange}）。
 * 挂在这里意味着无论女仆是被谁、用什么方法、从哪条代码路径传走的，都会经过这里 ——
 * 不需要去枚举 {@code teleportTo} / {@code moveTo} / {@code setPos} / 跨维度流程等等入口。
 *
 * <h2>机制（现场实测）</h2>
 * 大跳变会让服务端发 {@code ClientboundTeleportEntityPacket}，客户端收到后走
 * {@code Entity.lerpTo(目标, 3步)} 逐 tick 插值。而插值公式是
 * {@code d0 = getX() + (lerpX - getX()) / lerpSteps}，第一步的落点<b>恰好是起终点的 1/3</b>。
 * 如果那个落点在客户端没加载的区块里，实体就会脱离 tick 列表 —— 插值随即永久冻结在 1/3。
 *
 * <p>实测证据（女仆被传送 800 格）：
 * <pre>
 * tickCount 252 → 3 → 4 → 4 → 4 …          （重建后只 tick 了一次）
 * 冻结坐标恰好是起终点的 1/3（两轴都是 0.334）
 * 她所在 section 未编译 → LevelRenderer 渲染循环第 2 道门直接 continue
 *                        → 模型和碰撞箱一起消失
 * </pre>
 *
 * <p>而服务端不会自己修：{@code positionCodec} 基线 = 她的真实位置，{@code delta ≈ 0}，
 * 于是判定"客户端知道她在哪"、再也不发位置包。
 *
 * <h2>修法</h2>
 * 用 {@code removePairing} + {@code addPairing} 代替传送包：客户端把实体整个重建，
 * 坐标一次到位，<b>完全不经过插值</b>。这就是 {@code /maid_smart resync} 和重进存档
 * 之所以有效的原因，本模组只是把它自动化、并提前到传送包发出之前。
 *
 * <p>{@code ci.cancel()} 是关键：拦住本 tick 的后续处理，杜绝传送包漏出去。
 * 跳过的 {@code sendDirtyEntityData} 已由 {@code addPairing → sendPairingData} 补上。
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private VecDeltaCodec positionCodec;

    @Inject(method = "sendChanges", at = @At("HEAD"), cancellable = true)
    private void maidsync$rebuildOnBigJump(CallbackInfo ci) {
        Entity self = this.entity;
        if (self == null || self.isRemoved()) {
            return;
        }
        if (!MaidSyncConfig.enabled() || !MaidTarget.matches(self)) {
            return;
        }

        // ★ Sable 物理子关卡：见 EntityMoveToMixin 里那段说明。
        //
        // 【为什么这道门必须在 consume 之前】标记是"她被真正传送过"这件事的凭证。
        // 在子关卡上时她的每一次位置变化都是 Sable 踢出来的，不是传送；此时若把标记
        // 消费掉就等于白白丢弃。放在 consume 之前，标记会一直留着，等她真正离开子关卡后
        // 的第一次 sendChanges 再用它补一次重建 —— 那一刻如果确实是一次远距离传送，
        // 这次重建正是该做的。
        if (MaidSyncConfig.skipSubLevels() && SableGate.inSubLevel(self)) {
            MaidSyncLog.skippedSubLevel(self, "sendChanges");
            return;
        }

        Vec3 current = self.trackingPosition();
        double delta = Math.sqrt(this.positionCodec.delta(current).lengthSqr());
        // 两条触发路径：
        //  1) EntityMoveToMixin 已经标记过"这个实体被远距离挪动过"——直接重建；
        //  2) 兜底：位置基线出现大跳变。这条不依赖任何调用点，是真正的完备网。
        boolean marked = MaidSyncPending.consume(self.getId());
        if (!marked && delta <= MaidSyncConfig.rebuildDistance()) {
            return;
        }

        // ★ 冷却：刚修过就不再修。挡的是"判定连续成立 → 每 tick 重建一次"那种风暴
        //   （最典型是 Sable 的坐标系错配，delta 恒为几千万格）。
        //
        //   跳过时【刻意不 cancel】：cancel 会吞掉本 tick 的旋转包与脏数据同步，
        //   只有在我们确实要重建、用重建换掉它们时才划算。只是跳过的话让原版照常发更好 ——
        //   而且在 Sable 那种错配里，原版自己算的 delta 是对的，本来也不会发有害的包。
        //
        //   标记【原样还回去】：它是"她被真正传送过"的凭证，冷却结束了还得靠它。
        if (MaidSyncCooldown.coolingDown(self)) {
            if (marked) {
                MaidSyncPending.mark(self);
            }
            MaidSyncLog.cooldownSkipped(self, delta);
            return;
        }

        ServerEntity serverEntity = (ServerEntity) (Object) this;
        int rebuilt = 0;

        // 只给「服务端认为现在有这只实体」的玩家重建——判定见 MaidTracking。
        // 不能只按距离筛：removePairing+addPairing 发的是裸包，不会登记追踪关系，
        // 给一个没在追踪她的玩家发，只会在他客户端上造出一个服务端再也不管的幽灵实体。
        for (ServerPlayer player : this.level.players()) {
            if (!MaidTracking.shouldSendTo(this.level, self, player)) {
                continue;
            }
            try {
                serverEntity.removePairing(player);
                serverEntity.addPairing(player);
                rebuilt++;
            } catch (Throwable t) {
                MaidSyncMod.LOGGER.warn("[maidsync] 重建客户端实体失败：{}", t.toString());
            }
        }

        // 基线对齐到当前位置：否则下一 tick 会判定"还是大跳变"而反复重建
        this.positionCodec.setBase(current);
        // 记一下时间，冷却期内不再重建（见 MaidSyncCooldown）
        MaidSyncCooldown.mark(self);
        MaidSyncLog.rebuilt(self, delta, rebuilt, marked);

        // ★ 关键补充：光靠上面这次"当帧重建"救不了"落点在客户端未加载区块"的情况——
        // 那一刻她的服务端位置可能还是旧的，生成包会把客户端实体放到一个不 tick 的区块里。
        // 所以再排一次【延迟补包】，等传送真正生效后重建，生成包才会带玩家身边的新坐标。
        if (self instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
            MaidSyncDeferred.schedule(maid);
        }

        // ★ 拦住本 tick 的后续处理——绝不让那个会引发 lerpTo 的传送包发出去
        ci.cancel();
    }

    /**
     * 诊断探针：统计 {@code sendChanges} 到底有没有在为她跑。
     *
     * <p>与上面的修复方法相互独立——修复方法可以被大跳变取消掉，本探针只看
     * {@code MaidSyncConfig.diagnose()}，所以排查时把修复关掉（{@code enabled=false}）
     * 也照样能拿到数据。
     *
     * <p>⚠️ 2.0.0 首次发布时<b>漏掉了这个方法</b>（整文件重写时丢的），导致探针整列输出 0。
     * 改动这个文件后务必核对编译产物里有两个 {@code @Inject(method="sendChanges")}。
     */
    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void maidsync$probeTrack(CallbackInfo ci) {
        com.maidsync.probe.MaidTrackProbe.onSendChanges(this.entity, this.positionCodec);
    }

}
