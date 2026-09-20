package com.maidsync.probe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端收包记录用的限流器。
 *
 * <p>状态必须放在这个<b>普通类</b>里，不能放进 mixin —— mixin 的静态初始化器不执行，
 * 带初始化器的静态字段会保持 null，handler 第一行就 NPE，反过来把被注入的方法打断。
 *
 * <p>{@code ClientboundMoveEntityPacket} 对一只走动中的女仆每秒会来十几条，
 * 全打会刷爆日志，所以按次数抽样。
 */
public final class ClientPacketProbe {

    private static final int LOG_EVERY = 20;
    private static final Map<Integer, Integer> MOVE_COUNT = new ConcurrentHashMap<>();

    private ClientPacketProbe() {
    }

    /** 每收到 {@value #LOG_EVERY} 条位移包才记一条。 */
    public static boolean sampleMove(int entityId) {
        try {
            int n = MOVE_COUNT.merge(entityId, 1, Integer::sum);
            if (n >= LOG_EVERY) {
                MOVE_COUNT.put(entityId, 0);
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
