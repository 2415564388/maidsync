package com.maidsync;

import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 被判定"刚被远距离瞬移过、需要让客户端重建"的实体。
 *
 * <p>为什么需要这个：光靠"客户端位置基线离真实位置太远"这个启发式会漏。现场日志里
 * 女仆被召回的那一刻位置差是小的（前一次重建刚把基线对齐过），启发式不触发，
 * 但玩家依然看不见她。所以改成由传送动作本身直接打标记——
 * {@code Entity.moveTo} 里发现远距离瞬移就给实体打个标记，下一次
 * {@code ServerEntity.sendChanges()} 无条件重建。
 *
 * <p>实体 id 会被复用，标记条目万一没被消费也可能命中未来同 id 的实体——
 * 后果只是多一次客户端重建，无害；另外这里做了上限保护。
 */
public final class MaidSyncPending {
    private static final int MAX_ENTRIES = 512;
    private static final Set<Integer> PENDING = ConcurrentHashMap.newKeySet();

    private MaidSyncPending() {
    }

    public static void mark(Entity entity) {
        if (PENDING.size() > MAX_ENTRIES) {
            PENDING.clear();
        }
        PENDING.add(entity.getId());
    }

    /** 取走并清除标记；返回 true 表示这个实体刚被远距离瞬移过。 */
    public static boolean consume(int entityId) {
        return PENDING.remove(entityId);
    }
}
