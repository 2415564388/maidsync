package com.maidsync.compat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Sable 兼容层 —— 修「女仆被远距离传送后看不见」的真正原因。
 *
 * <p>Sable（物理子关卡）在客户端渲染实体的坐标时，会考察这个实体"正在追踪哪个子关卡"：
 * <pre>
 *   getContaining(entity) != null           → 另一条分支
 *   else getTrackingSubLevel(entity) == null → 直接 return，不做任何变换
 *   else entity.isPassenger()                → return
 *   else                                     → 用该子关卡的 pose 反变换再正变换实体的渲染坐标
 * </pre>
 *
 * <p>而 {@code getTrackingSubLevel} 读的是<b>存在实体自己身上</b>的字段
 * （{@code sable$getTrackingSubLevel()} / {@code sable$getLastTrackingSubLevelID()}），
 * 女仆被传送到几百格开外时它不会被清掉。于是 Sable 仍然拿那艘早就远去的飞船的 pose
 * 去变换她的渲染坐标 —— 她被画到一千多格以外，模型和碰撞箱一起消失，直到重进存档
 * 才重建这份映射。
 *
 * <p>所以：远距离传送后把这个陈旧追踪清掉即可。清掉是安全的 —— 她真的在飞船上时，
 * 传送本身就已经让她离开了那艘船。
 *
 * <p>本类引用了 Sable 的类型，只能在确认 Sable 已加载之后再触碰
 * （见 {@link #isLoaded()}）。Sable 不在时 JVM 不会加载本类。
 */
public final class SableCompat {
    private static Boolean loaded;

    private SableCompat() {
    }

    /** 不触碰任何 Sable 类型，所以即使 Sable 不在也能安全调用。 */
    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded("sable");
        }
        return loaded;
    }

    /**
     * 诊断用：报告 Sable 认为这个实体和哪些子关卡有关系。
     *
     * <p>渲染路径上有两条分支都会改写实体的渲染坐标：
     * <pre>
     *   getContaining(entity) != null           → 用 ClientSubLevel.renderPose() 变换相机相对坐标
     *   getTrackingSubLevel(entity) != null     → 用该子关卡的 pose 反变换再正变换
     * </pre>
     * 上一条是<b>空间查询</b>，跟实体身上存的字段完全是两回事，所以清字段治不了它。
     */
    public static String describeTracking(Entity entity) {
        try {
            SubLevel containing = Sable.HELPER.getContaining(entity);
            SubLevel tracking = Sable.HELPER.getTrackingSubLevel(entity);
            return "getContaining=" + describe(containing) + " | getTrackingSubLevel=" + describe(tracking);
        } catch (Throwable t) {
            return "查询异常：" + t;
        }
    }

    private static String describe(SubLevel subLevel) {
        if (subLevel == null) {
            return "null";
        }
        // transformPosition(Vec3.ZERO) 就是该 pose 的平移量，也就是子关卡的世界位置
        Vec3 origin = subLevel.logicalPose().transformPosition(Vec3.ZERO);
        return subLevel.getClass().getSimpleName()
                + "@" + String.format("%.1f/%.1f/%.1f", origin.x, origin.y, origin.z);
    }

    /** @return 是否真的清掉了东西（用来决定要不要打日志）。 */
    public static boolean clearStaleTracking(Entity entity) {
        if (!(entity instanceof EntityMovementExtension extension)) {
            return false;
        }
        boolean hadSomething = extension.sable$getTrackingSubLevel() != null
                || extension.sable$getLastTrackingSubLevelID() != null;
        if (!hadSomething) {
            return false;
        }
        extension.sable$setTrackingSubLevel(null);
        extension.sable$setLastTrackingSubLevelID(null);
        return true;
    }
}
