package com.maidsync.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsync.MaidSyncConfig;
import com.maidsync.MaidSyncLog;
import com.maidsync.MaidSyncMod;
import com.maidsync.client.MaidDiagnostics;
import com.maidsync.client.MaidRendererProbe;
import com.maidsync.client.MaidVisibilityWatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 诊断探针：每 3 秒把客户端世界里所有女仆实体的 id / 位置 / 距玩家距离 / 是否不可见 打一遍。
 *
 * <p>配合 {@link ClientPacketListenerMixin} 就能确定三件事之一：
 * <ul>
 *   <li>客户端有她、位置也对 → 问题在渲染，不在同步；</li>
 *   <li>客户端有她、位置是旧的 → 位置同步问题；</li>
 *   <li>客户端根本没有她 → 生成包问题，补发位置包永远治不好。</li>
 * </ul>
 *
 * <p>挂 {@code ClientLevel.tickEntities} 而不是事件总线：NeoForge 的
 * {@code @EventBusSubscriber(value = Dist.CLIENT)} 需要 {@code net.neoforged.api.distmarker.Dist}，
 * 而那个类只存在于编译期的 mergetool-api jar 里，用它会把运行时能不能加载变成一个未知数。
 */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    private static final double PROBE_RADIUS = 256.0D;
    private static int maidsync$tickCounter;

    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void maidsync$probeMaids(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        ClientLevel level = (ClientLevel) (Object) this;
        if (player == null) {
            return;
        }

        // 读取本 tick 的渲染情况（必须在 MaidRendererProbe.endTick() 之前读）
        MaidVisibilityWatch.checkTick();
        MaidDiagnostics.dump(level, player);
        // ★ 清空放在最后：渲染发生在 tick 之后，先清空再判定会永远误报"没被渲染"
        MaidRendererProbe.endTick();

        List<EntityMaid> nearby = level.getEntitiesOfClass(EntityMaid.class, player.getBoundingBox().inflate(PROBE_RADIUS));

        if (!MaidSyncConfig.diagnose()) {
            return;
        }
        if (++maidsync$tickCounter % 60 != 0) {
            return;
        }

        List<EntityMaid> maids = nearby;
        if (maids.isEmpty()) {
            MaidSyncMod.LOGGER.info("[maidsync/客户端] {} 格内没有任何女仆实体", (int) PROBE_RADIUS);
            return;
        }
        for (EntityMaid maid : maids) {
            MaidSyncMod.LOGGER.info(
                    "[maidsync/客户端] 女仆#{} 位置 {}/{}/{} 距玩家 {} 格 不可见={} 已移除={}",
                    maid.getId(),
                    f(maid.getX()), f(maid.getY()), f(maid.getZ()),
                    f(Math.sqrt(maid.distanceToSqr(player))),
                    maid.isInvisible(), maid.isRemoved());
        }
    }

    private static String f(double d) {
        return String.format("%.1f", d);
    }
}
