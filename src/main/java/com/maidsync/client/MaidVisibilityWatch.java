package com.maidsync.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsync.MaidSyncConfig;
import com.maidsync.MaidSyncMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/** 实时判定"该看见却没被画"。 */
public final class MaidVisibilityWatch {
    private static int reported;
    private static int frustumReported;
    private static int gateDumps;

    private MaidVisibilityWatch() {
    }

    public static void markRendered(int entityId) {
        MaidRendererProbe.markRendered(entityId);
    }

    /** 由客户端 tick 调用（清空在 tick 末尾，见 MaidRendererProbe.endTick）。 */
    public static void checkTick() {
        if (!MaidSyncConfig.diagnose()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }

        Set<Integer> inRenderList = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            inRenderList.add(entity.getId());
        }

        Vec3 look = player.getLookAngle();
        Vec3 eye = player.getEyePosition();
        for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, player.getBoundingBox().inflate(48.0D))) {
            if (maid.isInvisible()) {
                continue;
            }
            Vec3 to = maid.position().add(0.0D, maid.getBbHeight() * 0.5D, 0.0D).subtract(eye);
            if (to.lengthSqr() < 1.0E-4D || look.dot(to.normalize()) < 0.25D) {
                continue;
            }
            if (MaidRendererProbe.wasRendered(maid.getId())) {
                continue;
            }

            reported++;
            if (reported > 5 && reported % 20 != 0) {
                continue;
            }
            AABB cull = maid.getBoundingBoxForCulling();
            MaidSyncMod.LOGGER.warn(
                    "[maidsync/客户端] ★★ 女仆#{} 在你视野里、距你 {} 格，但这一 tick 渲染器没被调用（第 {} 次）"
                            + " | 在渲染列表里={} | 剔除箱={}",
                    maid.getId(),
                    String.format("%.1f", Math.sqrt(maid.distanceToSqr(player))),
                    reported,
                    inRenderList.contains(maid.getId()),
                    cull);
        }
    }

    /**
     * 渲染循环跑起来时才有有效视锥，所以在这里测 shouldRender 的最后一句。
     * 这个方法不受 tick 清空逻辑影响，数据始终可信。
     */
    public static void checkFrustumInRender(net.minecraft.client.renderer.LevelRenderer levelRenderer) {
        if (!MaidSyncConfig.diagnose()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        Frustum frustum = levelRenderer.getFrustum();
        if (frustum == null) {
            return;
        }

        // 这里是渲染时刻，视锥有效——把三道门的真实判定每 2 秒打一次。
        // 参数用【相机绝对坐标】（字节码实证），相对坐标是错的。
        if (++gateDumps % 40 == 0) {
            Vec3 cam = minecraft.gameRenderer.getMainCamera().getPosition();
            EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
            Set<Integer> inList = new HashSet<>();
            for (Entity e : level.entitiesForRendering()) {
                inList.add(e.getId());
            }
            for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, player.getBoundingBox().inflate(48.0D))) {
                MaidSyncMod.LOGGER.info(
                        "[maidsync/门] 女仆#{} 距你 {} 格 | 渲染列表={} section={} 距离门={} 视锥门={} shouldRender={} 本tick渲染过={}",
                        maid.getId(),
                        String.format("%.1f", Math.sqrt(maid.distanceToSqr(player))),
                        inList.contains(maid.getId()),
                        level.isOutsideBuildHeight(maid.blockPosition().getY())
                                || levelRenderer.isSectionCompiled(maid.blockPosition()),
                        maid.shouldRender(cam.x, cam.y, cam.z),
                        frustum.isVisible(maid.getBoundingBoxForCulling().inflate(0.5D)),
                        dispatcher.shouldRender(maid, frustum, cam.x, cam.y, cam.z),
                        MaidRendererProbe.wasRendered(maid.getId()));
            }
        }

        Vec3 look = player.getLookAngle();
        Vec3 eye = player.getEyePosition();
        for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, player.getBoundingBox().inflate(48.0D))) {
            if (maid.isInvisible()) {
                continue;
            }
            Vec3 to = maid.position().add(0.0D, maid.getBbHeight() * 0.5D, 0.0D).subtract(eye);
            if (to.lengthSqr() < 1.0E-4D || look.dot(to.normalize()) < 0.25D) {
                continue;
            }
            AABB inflated = maid.getBoundingBoxForCulling().inflate(0.5D);
            if (frustum.isVisible(inflated)) {
                continue;
            }
            frustumReported++;
            if (frustumReported > 5 && frustumReported % 100 != 0) {
                continue;
            }
            MaidSyncMod.LOGGER.warn(
                    "[maidsync/客户端] ▲▲ 视锥判定女仆#{} 不可见（距你 {} 格，就在视线方向上）第 {} 次 | 剔除箱={}",
                    maid.getId(),
                    String.format("%.1f", Math.sqrt(maid.distanceToSqr(player))),
                    frustumReported,
                    inflated);
        }
    }
}
