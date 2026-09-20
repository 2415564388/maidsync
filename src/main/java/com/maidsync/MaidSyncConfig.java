package com.maidsync;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * SERVER 侧配置。
 *
 * <p>配置项只围绕<b>已证实的那一个机制</b>：大跳变 → 客户端 {@code lerpTo} 插值冻结。
 * 历史上这里有一大批开关（清 Sable 子关卡追踪 / 修正陈旧插值起点 / 重建剔除箱 /
 * 乘骑态强制下骑 / 传送后延迟补包），它们的注释都写着"这才是真正原因"——但现场数据
 * 把五条全部否掉了，所以一并删除，只留下真正起作用的那条路径。
 *
 * <p>所有读取都走 {@link #read} 做兜底：配置尚未加载时返回默认值，
 * 而不是把一个 IllegalStateException 抛进服务端 tick 里。
 */
public final class MaidSyncConfig {
    public static final ModConfigSpec SPEC;
    private static final Values VALUES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        VALUES = new Values(builder);
        SPEC = builder.build();
    }

    private MaidSyncConfig() {
    }

    private static final class Values {
        private final ModConfigSpec.BooleanValue enabled;
        private final ModConfigSpec.DoubleValue rebuildDistance;
        private final ModConfigSpec.BooleanValue affectAllEntities;
        private final ModConfigSpec.ConfigValue<List<? extends String>> extraEntityTypes;
        private final ModConfigSpec.BooleanValue debugLog;
        private final ModConfigSpec.BooleanValue diagnose;
        private final ModConfigSpec.IntValue deferredRebuildDelayTicks;

        private Values(ModConfigSpec.Builder b) {
            b.comment("MaidSync —— 女仆远距离传送后客户端不可见的修复").push("maidsync");

            enabled = b.comment("总开关。关闭后本模组完全不干预服务端实体同步。")
                    .define("enabled", true);

            rebuildDistance = b.comment(
                            "重建阈值（格，默认 64）。",
                            "位置基线出现超过这个幅度的跳变时，本模组会在传送包发出之前把它拦下，",
                            "改成 removePairing+addPairing，让客户端把实体整个重建、坐标一次到位。",
                            "为什么需要：大跳变会让客户端走 Entity.lerpTo(目标, 3步)，插值第一步的落点",
                            "恰好是起终点的 1/3；若那个落点在客户端未加载的区块里，实体就脱离 tick 列表，",
                            "插值永久冻结在 1/3。此时该 section 未编译，渲染循环第 2 道门直接跳过她 ——",
                            "模型和碰撞箱一起消失。而服务端因为基线已同步、delta≈0，再也不会发位置包。",
                            "调小会更积极（正常高速移动约 1.5 格/tick，64 留足了余量）。")
                    .defineInRange("rebuildDistance", 64.0D, 8.0D, 512.0D);

            affectAllEntities = b.comment(
                            "对所有实体生效（默认关）。",
                            "开启后不再限于女仆——同一机制对任何实体都成立，可当作通用修复。",
                            "但重建实体对玩家/载具等有副作用，默认只对女仆开。")
                    .define("affectAllEntities", false);

            extraEntityTypes = b.comment(
                            "额外要管的实体注册名列表，例如 \"minecraft:wolf\"、\"minecraft:horse\"。")
                    .defineList("extraEntityTypes", List.of(), () -> "", o -> o instanceof String);

            debugLog = b.comment("打印重建详情（同一实体 5 秒最多一条）。")
                    .define("debugLog", true);

            diagnose = b.comment(
                            "诊断探针（默认关）：每 2 秒输出一次服务端的 sendChanges 调用次数、",
                            "追踪表归属、位置基线 vs 真实位置；客户端另做帧内「在渲染列表里但没渲染」比对。",
                            "排查用，平时关掉免得刷日志。")
                    .define("diagnose", false);

            deferredRebuildDelayTicks = b.comment(
                            "延迟补包的延迟（tick，默认 20 = 1 秒）—— 这是本模组的实际修复手段。",
                            "为什么必须延迟：实测收包序列是「移除 → 42ms 后按【旧位置】重新生成 → 才传送」。",
                            "跳变当帧就重建是无效的（那一刻她的服务端位置可能还是旧的）；",
                            "等传送真正生效后再补，生成包才会带【玩家身边的新坐标】，那个区块客户端是加载的。",
                            "太短会被传送后紧随的同步冲掉；太长则玩家要多等一会儿才看得见她。")
                    .defineInRange("deferredRebuildDelayTicks", 20, 1, 200);

            b.pop();
        }
    }

    private static <T> T read(ModConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T t = value.get();
            return t == null ? fallback : t;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static boolean enabled() {
        return read(VALUES.enabled, Boolean.TRUE);
    }

    public static double rebuildDistance() {
        return read(VALUES.rebuildDistance, 64.0D);
    }

    public static boolean affectAllEntities() {
        return read(VALUES.affectAllEntities, Boolean.FALSE);
    }

    public static List<? extends String> extraEntityTypes() {
        return read(VALUES.extraEntityTypes, List.of());
    }

    public static boolean debugLog() {
        return read(VALUES.debugLog, Boolean.TRUE);
    }

    public static boolean diagnose() {
        return read(VALUES.diagnose, Boolean.FALSE);
    }

    public static int deferredRebuildDelayTicks() {
        return read(VALUES.deferredRebuildDelayTicks, Integer.valueOf(20));
    }
}
