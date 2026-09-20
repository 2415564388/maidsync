package com.maidsync;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「这个玩家现在是不是她的收件人」—— 补包前的收件人判定。
 *
 * <h2>为什么必须有这一层</h2>
 * 补包（删+生成+数据+装备）用的是<b>裸包</b>，服务端不会因此把玩家登记进追踪表：
 * {@code ChunkMap.TrackedEntity.seenBy} 只由 {@code updatePlayer} 维护，
 * {@code ServerEntity.addPairing} 只负责把包发出去。所以给一个<b>没在追踪她</b>的玩家补包，
 * 会在他的客户端上凭空造出一个幽灵实体 —— 服务端既不会再发位置更新，也不会再发移除包。
 *
 * <p>单人存档只有主人一个玩家、且女仆一定被传到他身边，这个口子看不出来。
 * 多人服上就会显形，最危险的是主人那一档：若传送没真正落到主人身边
 * （被挡住 / 失败 / 中途又被传走），给他发一份<b>远处未加载区块里</b>的生成包，
 * 正好又造出本模组要修的那个「实体建在未加载区块 → 不 tick → 永久冻住」的状态；
 * 而那时位置基线已对齐、服务端不会重发，这次没有第二次自愈机会。
 *
 * <h2>判定口径</h2>
 * <ol>
 *   <li><b>精确</b>：反射读 {@code ChunkMap.TrackedEntity.seenBy}（1.21.1 没有公开访问器）。
 *       在里面 = 服务端认为他的客户端现在有这只实体 —— 正是要补包的那批人。</li>
 *   <li><b>兜底</b>（反射读不到时）：原版 {@code TrackedEntity.updatePlayer} 的同一口径 ——
 *       距离 ≤ min(玩家视距, 实体类型追踪距离)、{@code broadcastToPlayer} 通过、
 *       且他的区块追踪视图包含她所在的区块。比精确口径略宽（少了
 *       {@code chunkSender.isPending} 那一项），但不会比原版更宽。</li>
 * </ol>
 *
 * <p>反射读不到时退回兜底而不是「一律不发」：宁可退回原版口径，也不能因为字段改名
 * 就让修复整个失效。真读不到时 {@link MaidSyncLog} 会留一行痕。
 */
public final class MaidTracking {

    /** 反射字段缓存。getDeclaredField 失败会抛异常，缓存起来免得每 tick 都抛。 */
    private static final Map<String, Optional<Field>> FIELDS = new ConcurrentHashMap<>();

    private MaidTracking() {
    }

    /**
     * 该不该给这个玩家补包。
     *
     * <p>跨维度直接 false —— 追踪关系不可能跨维度。
     */
    public static boolean shouldSendTo(ServerLevel level, Entity entity, ServerPlayer player) {
        if (player.level() != entity.level()) {
            return false;
        }
        try {
            Boolean exact = isRecipient(level, entity.getId(), player);
            return exact != null ? exact : mayTrack(level, entity, player);
        } catch (Throwable t) {
            // 判定本身绝不能把补包链路打断：读不到就当"没在追踪"，宁可不发也不造幽灵实体
            return false;
        }
    }

    /** 与原版同一个口径：min(实体的客户端追踪距离, 玩家视距)。宁小勿大，避免给没在追踪的玩家发。 */
    public static double trackingRange(ServerLevel level, Entity entity) {
        int viewDistance = level.getServer().getPlayerList().getViewDistance() * 16;
        int typeRange = entity.getType().clientTrackingRange() * 16;
        return Math.min(viewDistance, typeRange);
    }

    /**
     * 精确口径：他是不是在 {@code seenBy} 里。
     *
     * @return {@code null} 表示读不到（反射失败），调用方应退回 {@link #mayTrack}
     */
    private static Boolean isRecipient(ServerLevel level, int entityId, ServerPlayer player) {
        try {
            Object chunkMap = level.getChunkSource().chunkMap;

            Field entityMapField = field(chunkMap.getClass(), "entityMap");
            if (entityMapField == null) {
                return null;
            }
            if (!(entityMapField.get(chunkMap) instanceof Map<?, ?> entityMap)) {
                return null;
            }

            Object tracked = entityMap.get(entityId);
            // 她压根不在追踪表里 → 没有任何人追踪她，谁都别发（这正是「传送没落到主人身边」那一档）
            if (tracked == null) {
                return Boolean.FALSE;
            }

            Field seenByField = field(tracked.getClass(), "seenBy");
            if (seenByField == null) {
                return null;
            }
            if (!(seenByField.get(tracked) instanceof Set<?> seenBy)) {
                return null;
            }

            for (Object connection : seenBy) {
                // 用 getPlayer() 比对象本身：不依赖 ServerPlayerConnection 的 equals 实现
                if (connection instanceof ServerPlayerConnection c && c.getPlayer() == player) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Throwable t) {
            // Field.get 的 IllegalAccessException、映射/字段结构变化等 —— 一律交给兜底口径
            return null;
        }
    }

    /** 原版 {@code ChunkMap.TrackedEntity.updatePlayer} 的同一口径（少了 {@code isPending} 那一项）。 */
    private static boolean mayTrack(ServerLevel level, Entity entity, ServerPlayer player) {
        double range = trackingRange(level, entity);
        if (player.distanceToSqr(entity) > range * range) {
            return false;
        }
        if (!entity.broadcastToPlayer(player)) {
            return false;
        }
        var pos = entity.chunkPosition();
        return player.getChunkTrackingView().contains(pos.x, pos.z);
    }

    /** 沿继承链找字段并缓存；找不到或不允许访问时缓存空值。 */
    private static Field field(Class<?> owner, String name) {
        String key = owner.getName() + '#' + name;
        return FIELDS.computeIfAbsent(key, k -> {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return Optional.of(f);
                } catch (NoSuchFieldException ignored) {
                    // 继续往父类找
                } catch (Throwable ignored) {
                    // 例如模块系统拒绝 setAccessible —— 兜底口径接管
                    return Optional.<Field>empty();
                }
            }
            return Optional.<Field>empty();
        }).orElse(null);
    }
}
