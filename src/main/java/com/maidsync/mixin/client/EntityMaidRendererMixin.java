package com.maidsync.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsync.MaidSyncConfig;
import com.maidsync.client.MaidRendererProbe;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 进 TLM 女仆渲染器内部看：它到底有没有跑、有没有跑完。
 *
 * <p>探针状态放在 {@link MaidRendererProbe} 这个普通类里 —— mixin 类不能持有带初始化器的静态字段。
 */
@Mixin(EntityMaidRenderer.class)
public class EntityMaidRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD")
    )
    private void maidsync$head(Entity entity, float yaw, float partialTick, PoseStack poseStack,
                               MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
        if (MaidSyncConfig.diagnose() && entity instanceof EntityMaid maid) {
            MaidRendererProbe.onHead(maid);
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN")
    )
    private void maidsync$return(Entity entity, float yaw, float partialTick, PoseStack poseStack,
                                 MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
        if (entity instanceof EntityMaid maid) {
            MaidRendererProbe.onReturn(maid);
        }
    }
}
