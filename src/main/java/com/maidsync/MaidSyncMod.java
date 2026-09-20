package com.maidsync;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MaidSync —— 修复女仆远距离传送后客户端不可见。
 *
 * <p>核心修复在 {@code mixin/ServerEntityMixin}：女仆位置出现大跳变时，在传送包发出之前
 * 把它拦下，改成 {@code removePairing}+{@code addPairing}，让客户端把实体整个重建。
 *
 * <p>根因（现场实测，完整机制见归档 00-README）：大跳变会让客户端走
 * {@code Entity.lerpTo(目标, 3步)}，插值第一步的落点恰好是起终点的 1/3；若那个落点在
 * 客户端未加载的区块里，实体就脱离 tick 列表，插值<b>永久冻结在 1/3</b>。此时她所在的
 * section 未编译，渲染循环第 2 道门直接跳过 —— 模型和碰撞箱一起消失。而服务端因为
 * {@code positionCodec} 基线已同步、{@code delta≈0}，再也不会发位置包，所以不会自愈。
 *
 * <p>触发条件：该区块处于<b>常加载</b>状态（原版 {@code /forceload} 或任何模组的强载）。
 * 不强载时那个位置的区块根本没加载、实体不存在，也就没有"传送一个远处旧实体"这回事。
 */
@Mod(MaidSyncMod.MOD_ID)
public final class MaidSyncMod {
    public static final String MOD_ID = "maidsync";
    public static final Logger LOGGER = LoggerFactory.getLogger("maidsync");

    public MaidSyncMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, MaidSyncConfig.SPEC);

        // ★ 修复本体：大跳变后延迟若干 tick 补一次「删+生成+数据+装备」。
        // 必须延迟 —— 当帧补包带的是旧坐标，救不了"落点在客户端未加载区块"的情况。
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) ->
                MaidSyncDeferred.tick(event.getServer()));

        // 诊断探针（默认关）：每 2 秒输出一次「sendChanges 有没有在为她跑 / 她在不在追踪表里」
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) ->
                com.maidsync.probe.MaidTrackProbe.tick(event.getServer()));

        LOGGER.info("[maidsync] 已加载：大跳变后延迟补包（删+生成），修复客户端实体卡在未加载区块");
    }
}
