package com.maidsync.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsync.MaidSyncConfig;
import com.maidsync.MaidSyncMod;
import com.maidsync.client.MaidModelInfo;
import com.maidsync.client.MaidVisibilityWatch;
import com.maidsync.compat.SableCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 渲染探针：确认女仆到底有没有进入渲染循环、用的是哪个坐标。
 *
 * <p>{@code LevelRenderer.renderEntity} 只对"通过了可见性检查"的实体调用，所以：
 * <ul>
 *   <li>它压根没被调用 → 女仆被剔除了（视锥剔除，或者 entityculling 之类的优化模组）；</li>
 *   <li>被调用了，但传入的渲染坐标和实体的逻辑坐标对不上 → 她被画到了别的地方
 *       （这个包里有 Sable 物理子关卡，它会 wrap 掉实体渲染做坐标变换）。</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    private static int maidsync$frame;

    /** 渲染循环跑起来的时候才有有效的视锥，所以在这里测。 */
    @Inject(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("HEAD")
    )
    private void maidsync$checkFrustum(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                       GameRenderer gameRenderer, LightTexture lightTexture,
                                       org.joml.Matrix4f projectionMatrix, org.joml.Matrix4f modelViewMatrix,
                                       CallbackInfo ci) {
        MaidVisibilityWatch.checkFrustumInRender((LevelRenderer) (Object) this);
        // 帧内探针：在同一帧的 HEAD 快照渲染列表、RETURN 比对，中途没有 tick 边界
        com.maidsync.probe.RenderFrameProbe.onRenderLevelHead(Minecraft.getInstance().level);
    }

    /** 帧内探针的收尾：拿 HEAD 的快照和本帧实际渲染过的集合比对。 */
    @Inject(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("RETURN")
    )
    private void maidsync$frameProbeReturn(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                           GameRenderer gameRenderer, LightTexture lightTexture,
                                           org.joml.Matrix4f projectionMatrix, org.joml.Matrix4f modelViewMatrix,
                                           CallbackInfo ci) {
        com.maidsync.probe.RenderFrameProbe.onRenderLevelReturn();
    }

    @Inject(
            method = "renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD")
    )
    private void maidsync$probeRenderEntity(Entity entity, double x, double y, double z, float partialTick,
                                            PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci) {
        if (!(entity instanceof EntityMaid maid)) {
            return;
        }

        // 每帧都要记，不能被下面的日志节流挡掉
        MaidVisibilityWatch.markRendered(maid.getId());
        com.maidsync.probe.RenderFrameProbe.onRenderEntity(entity);

        if (!MaidSyncConfig.diagnose() || ++maidsync$frame % 120 != 0) {
            return;
        }

        // 注意：renderEntity 的 x/y/z 参数是【相机世界坐标】——它内部会做
        //     d0 = Mth.lerp(partialTick, entity.xOld, entity.getX()) - x
        // 所以正确的校验是拿 x 跟相机位置比，而不是跟"实体相对相机的坐标"比。
        // （之前那版比错了坐标系，报出来的"偏差 1670 格"是假象。）
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        double offset = Math.sqrt(
                (x - cam.x) * (x - cam.x)
                        + (y - cam.y) * (y - cam.y)
                        + (z - cam.z) * (z - cam.z));
        double expectedX = maid.getX() - cam.x;
        double expectedY = maid.getY() - cam.y;
        double expectedZ = maid.getZ() - cam.z;

        String renderer;
        try {
            renderer = Minecraft.getInstance().getEntityRenderDispatcher()
                    .getRenderer(maid).getClass().getName();
        } catch (Throwable t) {
            renderer = "<取渲染器失败:" + t + ">";
        }

        MaidSyncMod.LOGGER.info(
                "[maidsync/客户端] 进入渲染女仆#{} | {} | 渲染器={} | 不可见={} 可见度={}"
                        + " | 实体相对相机 {}/{}/{}",
                maid.getId(), MaidModelInfo.describe(maid), renderer,
                maid.isInvisible(),
                String.format("%.2f", maid.getVisibilityPercent(Minecraft.getInstance().player)),
                f(expectedX), f(expectedY), f(expectedZ));

        if (SableCompat.isLoaded()) {
            MaidSyncMod.LOGGER.info("[maidsync/客户端]   └ Sable 归属：{}", SableCompat.describeTracking(maid));
        }
    }

    private static String f(double d) {
        return String.format("%.1f", d);
    }
}
