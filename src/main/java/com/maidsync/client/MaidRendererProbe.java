package com.maidsync.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsync.MaidSyncMod;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渲染器探针：记录"这一 tick 里到底有没有被渲染过"，以及渲染有没有正常跑完。
 *
 * <p><b>清空必须在客户端 tick 的最末尾做，不能在做判定之前做。</b>
 * 渲染发生在 tick 之后，先清空后判定会让判定永远看到空集合、无条件误报"没被渲染"。
 * 之前就是这么写错的，白查了好几轮。
 */
public final class MaidRendererProbe {
    private static final Set<Integer> RENDERED_SINCE_TICK = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Boolean> COMPLETED = new ConcurrentHashMap<>();
    private static int heads;
    private static int missedReturns;

    private MaidRendererProbe() {
    }

    /** 由渲染路径每帧调用，不可被日志节流挡住。 */
    public static void markRendered(int entityId) {
        RENDERED_SINCE_TICK.add(entityId);
    }

    public static boolean wasRendered(int entityId) {
        return RENDERED_SINCE_TICK.contains(entityId);
    }

    /** 客户端 tick 最末尾调用。 */
    public static void endTick() {
        RENDERED_SINCE_TICK.clear();
    }

    /** 进入 TLM 渲染器。 */
    public static void onHead(EntityMaid maid) {
        markRendered(maid.getId());
        Boolean previous = COMPLETED.put(maid.getId(), Boolean.FALSE);
        if (previous != null && !previous) {
            missedReturns++;
        }
        if (++heads <= 3 || heads % 600 == 0) {
            MaidSyncMod.LOGGER.info(
                    "[maidsync/客户端] → 进入 EntityMaidRenderer.render 女仆#{}（累计 {} 次，上次未正常返回 {} 次）",
                    maid.getId(), heads, missedReturns);
        }
    }

    public static void onReturn(EntityMaid maid) {
        COMPLETED.put(maid.getId(), Boolean.TRUE);
    }
}
