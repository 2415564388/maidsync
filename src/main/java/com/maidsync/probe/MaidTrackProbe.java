package com.maidsync.probe;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsync.MaidSyncConfig;
import com.maidsync.MaidSyncMod;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 诊断探针：回答一个原版指令问不出来的问题——
 * <b>服务端到底有没有在为这只女仆调用 {@code ServerEntity.sendChanges()}？</b>
 *
 * <p>背景：{@code sendChanges()} 是原版唯一的位置包来源，而它在整个原版<b>只有一个调用点</b>
 * （{@code ChunkMap.tick()}，条件是 {@code entityMoved || inEntityTickingRange}）。
 * 所以「客户端位置永远停在旧坐标、连 /tp 都不动」这件事只有两种可能：
 * <ul>
 *   <li>它为她在跑，但包发不出去（卡在某个分支里）</li>
 *   <li>它<b>根本没在为她跑</b>——那没有任何一方会再给她发任何包</li>
 * </ul>
 * 两者现象完全一样，只能直接读。本探针每 2 秒输出一行：
 *
 * <pre>
 * [maidsync/探针] 斯塔·柏#34 | sendChanges 近2秒 13 次 | 追踪表=true | 乘客=false | 基线(..) 真实(..) 偏差0.0格
 * </pre>
 *
 * <p>判读：
 * <ul>
 *   <li>{@code sendChanges 近2秒 0 次} → 第二种情况，实锤。接下来查她为什么不在
 *       {@code ChunkMap.entityMap} 里（同一行的 {@code 追踪表=} 会直接告诉你）</li>
 *   <li>次数正常（≈13）但偏差很大 → 第一种情况，包被某个分支吞了</li>
 *   <li>次数正常且偏差 ≈0 → 包在正常发，问题不在这里</li>
 * </ul>
 *
 * <p>注意：静态状态放在这个<b>普通类</b>里，不放进 mixin —— mixin 的静态初始化器不执行，
 * 字段会保持 null 并在 handler 第一行就 NPE，反过来把被注入的方法打断。
 */
public final class MaidTrackProbe {

    /** entityId → 本窗口内 sendChanges 被调用的次数 */
    private static final Map<Integer, Integer> CALLS = new ConcurrentHashMap<>();
    /** entityId → 最近一次调用时抓到的状态快照 */
    private static final Map<Integer, String> SNAPSHOT = new ConcurrentHashMap<>();
    /**
     * 上次输出的 gameTime。
     *
     * <p>注意不能用 {@code Long.MIN_VALUE} 当哨兵：{@code now - Long.MIN_VALUE} 会溢出成负数，
     * 于是 {@code now - lastReport < WINDOW_TICKS} 恒为真，探针永远不输出（踩过一次）。
     */
    private static long lastReport = 0L;
    private static boolean firstRun = true;
    /** 输出窗口 */
    private static final int WINDOW_TICKS = 40;

    private MaidTrackProbe() {
    }

    /** 由 {@code ServerEntityMixin} 在 {@code sendChanges} 的 HEAD 调用。 */
    public static void onSendChanges(Entity entity, VecDeltaCodec codec) {
        if (!(entity instanceof EntityMaid)) {
            return;
        }
        try {
            CALLS.merge(entity.getId(), 1, Integer::sum);
            Vec3 current = entity.trackingPosition();
            Vec3 base = codec.getBase();
            SNAPSHOT.put(entity.getId(), String.format(
                    "乘客=%s 已移除=%s 基线(%.1f,%.1f,%.1f) 真实(%.1f,%.1f,%.1f) 偏差%.1f格",
                    entity.isPassenger(), entity.isRemoved(),
                    base.x, base.y, base.z, current.x, current.y, current.z,
                    Math.sqrt(codec.delta(current).lengthSqr())));
        } catch (Throwable ignored) {
            // 探针本身绝不能影响被注入的方法
        }
    }

    /** 由 {@code MaidSyncMod} 的 ServerTickEvent.Post 调用。 */
    public static void tick(MinecraftServer server) {
        if (!MaidSyncConfig.diagnose()) {
            return;
        }
        long now;
        try {
            now = server.overworld().getGameTime();
        } catch (Throwable t) {
            return;
        }
        if (firstRun) {
            firstRun = false;
        } else if (now - lastReport < WINDOW_TICKS) {
            return;
        }
        long window = now - lastReport;
        lastReport = now;

        try {
            int found = 0;
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity e : level.getAllEntities()) {
                    if (!(e instanceof EntityMaid maid)) {
                        continue;
                    }
                    found++;
                    int id = maid.getId();
                    Integer calls = CALLS.remove(id);
                    String snapshot = SNAPSHOT.remove(id);
                    boolean inMap = inEntityMap(level, id);

                    // 既没被调用过、又不在追踪表里 —— 也照样报一行，
                    // 因为"两条都是否"正是要抓的那个状态
                    MaidSyncMod.LOGGER.info(
                            "[maidsync/探针] {}#{} @ {} | sendChanges 本窗口({}tick) {} 次 | 追踪表={} | {}",
                            maid.getName().getString(), id, level.dimension().location(),
                            window, calls == null ? 0 : calls, inMap,
                            snapshot == null ? "（本窗口内一次都没被调用过）" : snapshot);
                }
            }
            // 心跳：一只女仆都没扫到也要留痕，否则分不清"探针没跑"和"没有女仆"
            if (found == 0) {
                MaidSyncMod.LOGGER.info("[maidsync/探针] 心跳：本窗口扫到 0 只女仆（tick 事件在跑，实体枚举为空）");
            }
        } catch (Throwable t) {
            MaidSyncMod.LOGGER.warn("[maidsync/探针] 输出失败：{}", t.toString());
        }
    }

    /**
     * 她还在不在 {@code ChunkMap.entityMap} 里。
     *
     * <p>字段是 {@code private final Int2ObjectMap<TrackedEntity>}，没有公开访问器，
     * 只能反射。Minecraft 在 NeoForge 下走 classpath（非 module path），
     * 所以 {@code setAccessible} 可用。
     */
    private static boolean inEntityMap(ServerLevel level, int entityId) {
        try {
            ServerChunkCache cache = level.getChunkSource();
            Object chunkMap = cache.chunkMap;
            for (Class<?> c = chunkMap.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("entityMap");
                    f.setAccessible(true);
                    Object map = f.get(chunkMap);
                    return map instanceof Map<?, ?> m && m.containsKey(entityId);
                } catch (NoSuchFieldException ignored) {
                    // 继续往父类找
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
