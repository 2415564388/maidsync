package com.maidsync;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同一只女仆两次重建之间的最小间隔。
 *
 * <h2>为什么需要它</h2>
 * 没有冷却时，"大跳变"这个判定只要连续成立，就会<b>每 tick 重建一次</b>。而判定确实是会
 * 连续成立的 —— 最典型的是 Sable 的坐标系错配（见 README「2.1.2 的 skipSubLevels 实测没生效」一节）：
 * {@code ServerEntityMixin} 读世界坐标、{@code positionCodec} 基线却在 plot 空间，
 * 两者相减恒为几千万格，于是每 tick 都判定成"远距离传送"。
 *
 * <p>后果是三层浪费：
 * <ol>
 *   <li>每 tick 一次 {@code removePairing + addPairing}（客户端实体删了又建，还会闪）</li>
 *   <li>每次重建都排一次延迟补包 —— 也就是每 20 tick 一套六包的完整重传</li>
 *   <li>别的模组（promaid）本来也在做同样的"删+生成+数据+装备"，两边叠加</li>
 * </ol>
 *
 * <h2>为什么冷却不会把原来那个 bug 放回来</h2>
 * 冷却只挡<b>重复</b>动作，不挡第一次：跳过时会<b>把标记原样还回去</b>
 * （见 {@code ServerEntityMixin}），所以该修的那一次一定还会修，只是往后挪到冷却结束。
 * 真被冻住的女仆因此从"约 1 秒自愈"变成"最坏约 1 秒 + 冷却时长"，而重建风暴的
 * 频率从每 tick 一次降到每冷却时长一次。
 *
 * <p><b>跳过时刻意不 {@code ci.cancel()}</b>：cancel 是有代价的（它会吞掉本 tick 的
 * 旋转包与脏数据同步）。只有在我们<b>确实要重建</b>时，用重建去换掉那些包才是划算的；
 * 只是跳过的话，让原版照常发包更好 —— 而且在 Sable 那种错配场景里，原版自己算出来的
 * delta 是对的（它读的位置和基线在同一个坐标系里），根本不会发什么有害的包。
 *
 * <h2>为什么用 UUID 而不是实体 id</h2>
 * {@link MaidSyncPending} 用实体 id，因为它那边的误判后果只是"多重建一次"。这里反过来 ——
 * 误判的后果是<b>该修的没修</b>。实体 id 会被复用，一个刚冷却完就被回收的 id 可能把
 * 冷却扣到一只全新的女仆头上。所以这里用 UUID。
 */
public final class MaidSyncCooldown {

    private static final int MAX_ENTRIES = 512;
    private static final Map<UUID, Long> LAST_REBUILD = new ConcurrentHashMap<>();

    private MaidSyncCooldown() {
    }

    /** 这只女仆是否还在冷却里（距上次重建不足配置的间隔）。 */
    public static boolean coolingDown(Entity entity) {
        int window = MaidSyncConfig.rebuildCooldownTicks();
        if (window <= 0) {
            return false; // 关掉了
        }
        Long last = LAST_REBUILD.get(entity.getUUID());
        if (last == null) {
            return false;
        }
        return entity.level().getGameTime() - last < window;
    }

    /** 刚重建过 —— 记下时间。 */
    public static void mark(Entity entity) {
        try {
            if (LAST_REBUILD.size() > MAX_ENTRIES) {
                LAST_REBUILD.clear();
            }
            LAST_REBUILD.put(entity.getUUID(), entity.level().getGameTime());
        } catch (Throwable ignored) {
            // 记不上就只是没有冷却，不影响修复本身
        }
    }
}
