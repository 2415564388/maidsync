package com.maidsync.compat;

import com.maidsync.MaidSyncMod;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 「这个实体此刻是不是在 Sable 物理子关卡上」—— 全程反射，<b>不引用任何 Sable 类型</b>。
 *
 * <h2>为什么不用 {@link SableCompat}</h2>
 * {@code SableCompat} 是直接 import Sable 类的：它有一个以 {@code SubLevel} 为参数类型的
 * 私有方法，还有以 {@code EntityMovementExtension} 做 {@code instanceof} 的方法。
 * 这类引用在类被<b>校验</b>时就要解析，轮不到方法体执行 —— 所以只要有人加载
 * {@code SableCompat}，没装 Sable 的专用服务器就会 {@code NoClassDefFoundError}。
 *
 * <p>而本类要被 {@code EntityMoveToMixin} / {@code ServerEntityMixin} 从
 * <b>服务端每 tick 的路径</b>上调用，那是本模组第一次在服务端触碰 Sable 相关代码，
 * 必须做到「没装 Sable 时加载本类毫发无伤」。所以这里一个 Sable 类型都不出现：
 * 全部走 {@code Class.forName} + {@code Method.invoke}，句柄缓存一次，失败即永久降级。
 *
 * <h2>为什么需要这道门</h2>
 * Sable 的 {@code shouldKick} 是 {@code !type.is(sable:retain_in_sub_level)}，而
 * {@code touhou_little_maid:maid} 不在那个标签里 —— 于是女仆站在子关卡上时，
 * Sable 会在每 tick 的碰撞解算（{@code SubLevelEntityCollision.collide}）里把她
 * <b>从 plot 坐标系踢回世界坐标系</b>：一次 {@code Entity.moveTo}，位移是两套坐标系的间距，
 * 几百到上千格。那正好落在本模组的传送嗅探点上 —— 不拦就会每 tick 重建一次实体。
 */
public final class SableGate {

    private static final String SABLE_CLASS = "dev.ryanhcode.sable.Sable";
    private static final String MOD_ID = "sable";

    /** 三态：null = 还没查过。查过之后不再重复查。 */
    private static Boolean modPresent;

    private static boolean resolved;
    private static boolean usable;

    /** {@code Sable.HELPER} 的取值（ActiveSableCompanion 实例）。 */
    private static Object helper;

    /** {@code ActiveSableCompanion.getContaining(Entity)} —— 空间查询，权威。 */
    private static Method getContaining;

    /**
     * {@code Entity.sable$getTrackingSubLevel()} —— Sable 混入实体身上的字段读取器。
     * 拿不到就是 null，此时只靠 {@link #getContaining}。
     */
    private static Method getTrackingSubLevel;

    private SableGate() {
    }

    /** Sable 装没装。只查 ModList，不加载任何 Sable 类。 */
    public static boolean modPresent() {
        if (modPresent == null) {
            try {
                modPresent = ModList.get().isLoaded(MOD_ID);
            } catch (Throwable t) {
                // ModList 尚未就绪（极早的加载阶段）——当作没装，下次再问
                return false;
            }
        }
        return modPresent;
    }

    /**
     * 该实体此刻是否在子关卡里、或正追踪着某个子关卡。
     *
     * <p>任何一步失败都返回 false —— 这道门只用来<b>少做</b>事（跳过重建），
     * 误判成 false 的后果只是退回本模组原来的行为，不会更糟。
     */
    public static boolean inSubLevel(Entity entity) {
        if (entity == null || !modPresent()) {
            return false;
        }
        resolve();
        if (!usable) {
            return false;
        }

        // 权威判定：空间查询。Sable 踢人前用的就是这个。
        if (getContaining != null) {
            try {
                if (getContaining.invoke(helper, entity) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 反射调用失败（Sable 内部状态异常等）——落到下面那条兜底
            }
        }

        // 兜底：实体身上记着的追踪子关卡。踢人之前这个字段可能还没被清。
        if (getTrackingSubLevel != null) {
            try {
                if (getTrackingSubLevel.invoke(entity) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 查一次反射句柄并缓存。
     *
     * <p>刻意不用 {@code static {}} 初始化块：那会把异常包成 {@code ExceptionInInitializerError}，
     * 而且失败后整个类永久不可用、连 {@link #modPresent()} 都调不了。
     */
    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> sableCls = Class.forName(SABLE_CLASS);
            Field helperField = sableCls.getField("HELPER");
            helper = helperField.get(null);
            if (helper == null) {
                // Sable 装了但还没初始化。不设 usable，等它初始化好之后
                // 下次调用会因为 resolved 已置位而不再重试 —— 这是有意的：
                // 拿不到就永久降级，绝不在实体 tick 里反复做类查找。
                //
                // 但这条降级路必须留话：真踩上了（HELPER 在第一次实体 tick 时还是 null），
                // 表现是 skipSubLevels 整个会话都不生效，而外部完全看不出来。
                MaidSyncMod.LOGGER.warn(
                        "[maidsync] Sable 已加载但 Sable.HELPER 仍是 null —— 子关卡门本次会话不再重试，"
                                + "skipSubLevels 将一直不生效");
                return;
            }

            getContaining = helper.getClass().getMethod("getContaining", Entity.class);

            Method tracked = null;
            try {
                // 混入方法：运行时才存在于 Entity 上，编不出来，只能反射拿
                tracked = Entity.class.getMethod("sable$getTrackingSubLevel");
            } catch (NoSuchMethodException ignored) {
                // 字段改名 / 该方法不存在 —— 只靠 getContaining
            }
            getTrackingSubLevel = tracked;

            usable = true;
            MaidSyncMod.LOGGER.info(
                    "[maidsync] Sable 子关卡门已就绪（getContaining{}）",
                    tracked != null ? " + sable$getTrackingSubLevel" : "，无追踪兜底");
        } catch (Throwable t) {
            // Sable 版本对不上、字段改名、模块系统拒绝访问 —— 一律永久降级。
            // 同样必须留话：静默降级的后果是 skipSubLevels 看起来开着、实际从不生效。
            usable = false;
            MaidSyncMod.LOGGER.warn(
                    "[maidsync] Sable 子关卡门反射失败 —— skipSubLevels 将一直不生效：{}", t.toString());
        }
    }
}
