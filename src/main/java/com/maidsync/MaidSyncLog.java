package com.maidsync;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节流日志：同一实体同类事件 5 秒最多一条，避免修复逻辑本身把日志刷爆。
 *
 * <p>只保留与<b>已证实机制</b>相关的条目。历史上这里还有 Sable 子关卡追踪、陈旧插值起点、
 * 剔除箱重建、乘骑态强制下骑四条，它们的注释都写着"这才是真正原因"——但四条都已被现场
 * 数据否证（见归档 02-已验证事实），所以连同对应的修复代码一起删掉了。
 */
public final class MaidSyncLog {
    private static final long THROTTLE_TICKS = 100L;
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private MaidSyncLog() {
    }

    /**
     * 在传送包发出之前把它拦下、改成让客户端重建实体。
     *
     * <p>这是本模组的核心动作：大跳变会让客户端走 {@code lerpTo(目标, 3步)}，而插值第一步
     * 的落点若在未加载的客户端区块里，实体就会脱离 tick 列表、插值永久冻结在 1/3 处
     * （实测：tickCount 停在 4 不动、坐标恰好是起终点的 1/3、模型和碰撞箱一起消失）。
     */
    public static void rebuilt(Entity entity, double distance, int viewers, boolean marked) {
        if (!MaidSyncConfig.debugLog()) {
            return;
        }
        if (throttled(entity, "rebuilt")) {
            return;
        }
        MaidSyncMod.LOGGER.info(
                "[maidsync] 跳变 {} 格（{}）→ 已让 {} 个客户端重建该实体，绕开 lerpTo 插值",
                String.format("%.1f", distance),
                marked ? "传送点已标记" : "位置基线判定",
                viewers);
    }

    /**
     * 延迟补包发出去了 —— 这是真正让女仆恢复可见的那一下，务必看得见。
     *
     * <p>四包序列（删+生成+实体数据+装备）与 promaid 的 {@code /maid_smart resync} 一致，
     * 那条命令实测「敲一下当场恢复」。
     */
    public static void deferredResynced(Entity entity, int viewers) {
        MaidSyncMod.LOGGER.info(
                "[maidsync] 延迟补包：已给 {} 个客户端重建 {}（删+生成+数据+装备）",
                viewers, describe(entity));
    }

    /**
     * 延迟补包到点了，但没有任何客户端在追踪她 —— 跳过。
     *
     * <p>这不是故障，是<b>刻意的保守</b>：补包用的是裸包，服务端不会因此登记追踪关系，
     * 给没在追踪她的客户端发只会造出幽灵实体。最常见的原因是传送最终没把她落到主人身边
     * （被挡住 / 失败），此时她本来也不在主人的客户端上，补了也白补。
     */
    public static void deferredNoRecipient(Entity entity, int sameDimensionPlayers) {
        if (!MaidSyncConfig.debugLog()) {
            return;
        }
        if (throttled(entity, "no-recipient")) {
            return;
        }
        MaidSyncMod.LOGGER.info(
                "[maidsync] 延迟补包：到点时没有任何客户端在追踪 {}（同维度在线 {} 人）—— 跳过，不补",
                describe(entity), sameDimensionPlayers);
    }

    /** 诊断：女仆被 moveTo 挪动。用来确认"召唤到底有没有真的移动她"。 */
    public static void maidMoved(Entity entity, double distance) {
        if (!MaidSyncConfig.diagnose()) {
            return;
        }
        if (throttled(entity, "moved")) {
            return;
        }
        MaidSyncMod.LOGGER.info(
                "[maidsync] maid-moved {} 移动 {} 格 → {}/{}/{}",
                describe(entity), String.format("%.1f", distance),
                String.format("%.1f", entity.getX()),
                String.format("%.1f", entity.getY()),
                String.format("%.1f", entity.getZ()));
    }

    private static boolean throttled(Entity entity, String kind) {
        long now = entity.level().getGameTime();
        String key = entity.getUUID() + "|" + kind;
        Long last = LAST.get(key);
        if (last != null && now - last < THROTTLE_TICKS) {
            return true;
        }
        if (LAST.size() > 4096) {
            LAST.clear();
        }
        LAST.put(key, now);
        return false;
    }

    private static String describe(Entity entity) {
        String name = entity.getName().getString();
        return name + "(" + entity.getType().toShortString() + "#" + entity.getId() + ")";
    }
}
