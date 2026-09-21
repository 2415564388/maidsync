package com.maidsync.mixin;

import com.maidsync.MaidSyncConfig;
import com.maidsync.MaidSyncLog;
import com.maidsync.MaidSyncPending;
import com.maidsync.MaidTarget;
import com.maidsync.compat.SableGate;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 传送动作的嗅探点：给实体打一个"下一次发包时让客户端重建"的标记。
 *
 * <p>{@code moveTo(double,double,double,float,float)} 是最常见的一条瞬移路径
 * （{@code Entity.teleportTo} 的同维度分支正是走它），而正常行走走的是
 * {@code move(MoverType, Vec3)} → {@code setPos}，不经过这里。
 *
 * <p><b>但它不是唯一的路，所以这里只当"提前一拍"用。</b>真正的兜底在
 * {@link ServerEntityMixin}：那里盯的是"位置基线出现大跳变"这个<b>结果</b>，
 * 而不是"谁调用了哪个传送方法"这个<b>过程</b>——因为导致客户端插值冻结的是
 * 那个大跳变本身，跟它是怎么来的无关。
 *
 * <p>历史包袱：这里曾经还做"强制下骑"和"清 Sable 子关卡追踪"两件事，注释都写着
 * "这才是真正原因"。两条都已被现场数据否证，已删除。
 */
@Mixin(Entity.class)
public abstract class EntityMoveToMixin {

    @Inject(method = "moveTo(DDDFF)V", at = @At("HEAD"))
    private void maidsync$markTeleport(double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
        if (!MaidSyncConfig.enabled()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        // 加载实体时也会走 moveTo，那时还没进世界，不能当传送处理
        if (self.level().isClientSide() || !self.isAddedToLevel() || self.isRemoved()) {
            return;
        }
        if (!MaidTarget.matches(self)) {
            return;
        }

        // ★ Sable 物理子关卡：女仆不在 sable:retain_in_sub_level 标签里，Sable 每 tick 会
        //   在碰撞解算里把她从 plot 坐标系【踢】回世界坐标系 —— 那是一次 Entity.moveTo，
        //   位移是两套坐标系的间距（几百到上千格），正好落到下面那句阈值判定上。
        //   不拦的话每 tick 都打标记、每 tick 重建一次实体，表现为女仆持续抖动抽搐。
        //   （判定全程反射，见 SableGate；没装 Sable 时这一次调用就是一次 ModList 查表。）
        if (MaidSyncConfig.skipSubLevels() && SableGate.inSubLevel(self)) {
            MaidSyncLog.skippedSubLevel(self, "moveTo");
            return;
        }

        double moved = Math.sqrt(self.position().distanceToSqr(x, y, z));
        MaidSyncLog.maidMoved(self, moved);

        if (moved <= MaidSyncConfig.rebuildDistance()) {
            return;
        }
        MaidSyncPending.mark(self);
    }
}
