package com.maidsync.probe;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsync.MaidSyncConfig;
import com.maidsync.MaidSyncMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 帧内渲染探针：彻底消掉旧探针那个「先清空、后判定」的时序误报。
 *
 * <p>旧探针在客户端 tick 里清空"本 tick 渲染过"的集合、在渲染时标记，于是判定永远看到空集合 ——
 * 也就是归档里记的那条：
 * <i>「判定'这一 tick 有没有被渲染'时，清空必须放在 tick 的最末尾；渲染发生在 tick 之后。
 * 写成'先清空、后判定'会让判定永远看到空集合、无条件误报。」</i>
 *
 * <p>这里改成<b>全程在同一个 renderLevel 调用内部</b>完成，不存在任何 tick/frame 边界：
 * <ol>
 *   <li>{@code renderLevel} HEAD：把当前 {@code entitiesForRendering()} 里的女仆快照下来</li>
 *   <li>{@code renderEntity} HEAD：标记"这一帧真的被渲染了"</li>
 *   <li>{@code renderLevel} RETURN：拿快照和标记比对，差集就是"在渲染列表里但没被渲染"的</li>
 * </ol>
 *
 * <p>判读：
 * <ul>
 *   <li>差集长期非空 → 渲染循环里确实有我们还没找到的门</li>
 *   <li>差集恒为空 → 客户端渲染是好的，看不见的原因不在渲染这一步</li>
 * </ul>
 */
public final class RenderFrameProbe {

    /** 本帧 renderLevel 开始时，渲染列表里的女仆：id → 实体 */
    private static final Map<Integer, EntityMaid> SNAPSHOT = new HashMap<>();
    /** 本帧真的走进 renderEntity 的女仆 id */
    private static final Set<Integer> RENDERED = new HashSet<>();

    private static int frames;
    private static int lastLoggedFrame = -1000;

    private RenderFrameProbe() {
    }

    public static void onRenderLevelHead(ClientLevel level) {
        SNAPSHOT.clear();
        RENDERED.clear();
        frames++;
        if (level == null) {
            return;
        }
        try {
            for (Entity e : level.entitiesForRendering()) {
                if (e instanceof EntityMaid maid) {
                    SNAPSHOT.put(maid.getId(), maid);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void onRenderEntity(Entity entity) {
        if (entity instanceof EntityMaid maid) {
            RENDERED.add(maid.getId());
        }
    }

    public static void onRenderLevelReturn() {
        if (!MaidSyncConfig.diagnose() || SNAPSHOT.isEmpty()) {
            return;
        }
        try {
            // 每 20 帧最多报一次，且只在真的有差集时报
            if (frames - lastLoggedFrame < 20) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();

            for (Map.Entry<Integer, EntityMaid> entry : SNAPSHOT.entrySet()) {
                int id = entry.getKey();
                if (RENDERED.contains(id)) {
                    continue;
                }
                EntityMaid maid = entry.getValue();
                lastLoggedFrame = frames;

                // LivingEntity.lerpTargetX/Y/Z 在 lerpSteps > 0 时返回插值目标，否则返回当前位置。
                // 两者不相等 ⇒ 她正处在一次 lerpTo 的中途。若 tickCount 不再增长而目标还挂着，
                // 就是「插值走不完、冻在半路」的直接证据。
                double lx = maid.lerpTargetX();
                double ly = maid.lerpTargetY();
                double lz = maid.lerpTargetZ();
                boolean lerping = Math.abs(lx - maid.getX()) > 0.01
                        || Math.abs(ly - maid.getY()) > 0.01
                        || Math.abs(lz - maid.getZ()) > 0.01;

                // ★ 判定分支的关键三问：
                //   她到底在不在 ClientLevel.tickingEntities 里？（不在 ⇒ 她的 section 是 TRACKED）
                //   游戏本身是不是正常 tick 状态？（TickRateManager，1.20.3+ 才有）
                //   她有没有被判定为"冻结实体"？
                ClientLevel level = Minecraft.getInstance().level;
                String tickList = "?";
                String tickRate = "?";
                try {
                    if (level != null) {
                        EntityTickList list = ((com.maidsync.mixin.client.ClientLevelTickAccessor) level)
                                .maidsync$getTickingEntities();
                        tickList = (list != null && list.contains(maid)) ? "在" : "不在";
                        TickRateManager trm = level.tickRateManager();
                        if (trm != null) {
                            tickRate = String.format("正常=%s 已冻结=%s 她算冻结=%s",
                                    trm.runsNormally(), trm.isFrozen(), trm.isEntityFrozen(maid));
                        }
                    }
                } catch (Throwable ignored) {
                }

                MaidSyncMod.LOGGER.warn(
                        "[maidsync/帧探针] 女仆#{} 在渲染列表里、但这一帧 renderEntity 没被调用"
                                + " | 距相机 {} 格 | 位置 {}/{}/{} | 插值中={} 插值目标 {}/{}/{}"
                                + " | tick列表={} tick状态[{}]"
                                + " | 不可见={} 已移除={} 乘客={} tickCount={} | 列表内女仆数={} 本帧已渲染={}",
                        id,
                        String.format("%.1f", Math.sqrt(maid.distanceToSqr(cam.x, cam.y, cam.z))),
                        String.format("%.1f", maid.getX()),
                        String.format("%.1f", maid.getY()),
                        String.format("%.1f", maid.getZ()),
                        lerping,
                        String.format("%.1f", lx),
                        String.format("%.1f", ly),
                        String.format("%.1f", lz),
                        tickList, tickRate,
                        maid.isInvisible(), maid.isRemoved(), maid.isPassenger(), maid.tickCount,
                        SNAPSHOT.size(), RENDERED.size());
            }
        } catch (Throwable ignored) {
        }
    }
}
