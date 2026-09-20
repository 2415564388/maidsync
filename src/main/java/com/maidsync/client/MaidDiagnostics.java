package com.maidsync.client;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsync.MaidSyncConfig;
import com.maidsync.MaidSyncMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * 一次性把"女仆为什么看不见"所有可能相关的状态全打出来，并且直接给出判定结论。
 *
 * <p>目的是不再一轮一个假设地猜。每 2 秒对 64 格内每个女仆输出一块：
 * 实体状态 / 渲染管线各道门 / 模型数据 / 结论。
 */
public final class MaidDiagnostics {
    private static int dumps;

    private MaidDiagnostics() {
    }

    public static void dump(ClientLevel level, Player player) {
        if (!MaidSyncConfig.diagnose()) {
            return;
        }
        if (++dumps % 40 != 0) {
            return;   // 每 2 秒一次
        }

        Minecraft minecraft = Minecraft.getInstance();
        LevelRenderer levelRenderer = minecraft.levelRenderer;
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        Frustum frustum = levelRenderer.getFrustum();

        Set<Integer> inRenderList = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            inRenderList.add(entity.getId());
        }

        for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, player.getBoundingBox().inflate(64.0D))) {
            dumpOne(level, player, levelRenderer, dispatcher, camera, cam, frustum, inRenderList, maid);
        }
    }

    private static void dumpOne(ClientLevel level, Player player, LevelRenderer levelRenderer,
                                EntityRenderDispatcher dispatcher, Camera camera, Vec3 cam, Frustum frustum,
                                Set<Integer> inRenderList, EntityMaid maid) {
        double relX = maid.getX() - cam.x;
        double relY = maid.getY() - cam.y;
        double relZ = maid.getZ() - cam.z;

        AABB cull = maid.getBoundingBoxForCulling();
        AABB inflated = cull.inflate(0.5D);

        // ---- 逐道门 ----
        // 注意 shouldRender 的 x/y/z 参数是【相机绝对坐标】，不是相对坐标。
        // 字节码实证（LevelRenderer.renderLevel 偏移 121-146）：三个 double 直接取自
        // camera.getPosition() 的 x/y/z。之前传了相对坐标，于是 Entity.shouldRender
        // 算出来变成 "相机离世界原点多远"，判定不可信。
        boolean gateInList = inRenderList.contains(maid.getId());
        boolean gateSection = level.isOutsideBuildHeight(maid.blockPosition().getY())
                || levelRenderer.isSectionCompiled(maid.blockPosition());
        boolean gateDistance = maid.shouldRender(cam.x, cam.y, cam.z);
        boolean gateNoCulling = maid.noCulling;
        boolean gateFrustum = frustum != null && frustum.isVisible(inflated);
        boolean gateDispatcher = frustum != null && dispatcher.shouldRender(maid, frustum, cam.x, cam.y, cam.z);
        boolean renderedSinceTick = MaidRendererProbe.wasRendered(maid.getId());

        StringBuilder verdict = new StringBuilder();
        if (!gateInList) {
            verdict.append("不在客户端渲染列表 → 客户端实体追踪问题");
        } else if (renderedSinceTick) {
            verdict.append("渲染器【被调用了】但画不出东西 → 查模型/贴图/骨骼");
        } else if (!gateSection) {
            verdict.append("被 section 门挡住（她所在 section 未编译）");
        } else if (!gateDispatcher) {
            if (!gateDistance) {
                verdict.append("被距离门挡住");
            } else if (!gateFrustum) {
                verdict.append("被视锥门挡住");
            } else {
                verdict.append("dispatcher.shouldRender 为 false 但子项都通过 → 渲染器重写了 shouldRender");
            }
        } else {
            verdict.append("各道门都通过、但本 tick 渲染器没被调用 → 循环里还有别的分支");
        }

        MaidSyncMod.LOGGER.info(
                "[maidsync/诊断] ===== 女仆#{} {} =====",
                maid.getId(), maid.getName().getString());
        MaidSyncMod.LOGGER.info(
                "[maidsync/诊断] 位置 {}/{}/{} | 相对相机 {}/{}/{} | 距玩家 {} 格 | 区块 {} | 姿势 {} | 尺寸 {}x{}",
                f(maid.getX()), f(maid.getY()), f(maid.getZ()),
                f(relX), f(relY), f(relZ),
                f(Math.sqrt(maid.distanceToSqr(player))),
                maid.chunkPosition(),
                maid.getPose(), f(maid.getBbWidth()), f(maid.getBbHeight()));
        MaidSyncMod.LOGGER.info(
                "[maidsync/诊断] 门: 渲染列表={} section={} 距离={} noCulling={} 视锥={} dispatcher.shouldRender={} 本tick渲染过={}",
                gateInList, gateSection, gateDistance, gateNoCulling, gateFrustum, gateDispatcher, renderedSinceTick);
        MaidSyncMod.LOGGER.info(
                "[maidsync/诊断] 实体: 不可见={} 可见度={} 已移除={} 在世界上={} 乘客={} tickCount={} xOld偏离={} 速度={}",
                maid.isInvisible(), f(maid.getVisibilityPercent(player)), maid.isRemoved(),
                maid.isAddedToLevel(), maid.isPassenger(), maid.tickCount,
                f(Math.sqrt(Math.pow(maid.xOld - maid.getX(), 2) + Math.pow(maid.yOld - maid.getY(), 2)
                        + Math.pow(maid.zOld - maid.getZ(), 2))),
                vec(maid.getDeltaMovement()));
        MaidSyncMod.LOGGER.info(
                "[maidsync/诊断] 箱: 碰撞箱={} 剔除箱={} | 渲染器={}",
                box(cull), box(maid.getBoundingBox()),
                safeRendererName(dispatcher, maid));
        MaidSyncMod.LOGGER.info(
                "[maidsync/诊断] 模型数据: {} | 贴图={}",
                MaidModelInfo.describe(maid), safeTexture(dispatcher, maid));
        MaidSyncMod.LOGGER.info(
                "[maidsync/诊断] 渲染模型: {}", safeRenderModel(dispatcher, maid));
        MaidSyncMod.LOGGER.info(
                "[maidsync/诊断] ★结论: {}", verdict);
    }

    private static String safeRendererName(EntityRenderDispatcher dispatcher, EntityMaid maid) {
        try {
            return dispatcher.getRenderer(maid).getClass().getName();
        } catch (Throwable t) {
            return "<异常:" + t + ">";
        }
    }

    /**
     * TLM 每个模型包带一个 {@code render_entity_scale}；如果它是 0，模型会被缩成一个点 ——
     * "渲染器正常跑完、什么都不显示"就是这么来的。同时报一下走的哪条模型体系
     * （Gecko 还是 Bedrock）以及渲染器实际持有的 model 对象。
     */
    private static String safeRenderModel(EntityRenderDispatcher dispatcher, EntityMaid maid) {
        try {
            Object renderer = dispatcher.getRenderer(maid);
            if (!(renderer instanceof EntityMaidRenderer maidRenderer)) {
                return "<渲染器不是 EntityMaidRenderer:" + renderer.getClass().getName() + ">";
            }
            com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.MaidModelInfo info =
                    maidRenderer.getMainInfo();
            String scale = info == null
                    ? "<mainInfo=null>"
                    : String.format("实体缩放=%.4f 物品缩放=%.4f 是Gecko模型=%s",
                            info.getRenderEntityScale(), info.getRenderItemScale(), info.isGeckoModel());
            Object model = maidRenderer.getModel();
            return scale + " | model=" + (model == null ? "null" : model.getClass().getName());
        } catch (Throwable t) {
            return "<异常:" + t + ">";
        }
    }

    private static String safeTexture(EntityRenderDispatcher dispatcher, EntityMaid maid) {
        try {
            Object location = dispatcher.getRenderer(maid).getTextureLocation(maid);
            return String.valueOf(location);
        } catch (Throwable t) {
            return "<异常:" + t + ">";
        }
    }

    private static String vec(Vec3 v) {
        return String.format("%.2f/%.2f/%.2f", v.x, v.y, v.z);
    }

    private static String box(AABB b) {
        return String.format("%.1f/%.1f/%.1f~%.1f/%.1f/%.1f",
                b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    private static String f(double d) {
        return String.format("%.1f", d);
    }
}
