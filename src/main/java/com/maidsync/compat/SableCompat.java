package com.maidsync.compat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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
 * <h2>⚠️ 本类只能在确认 Sable 已加载之后再触碰，门用 {@link SableGate#modPresent()}</h2>
 * 本类直接 import 了 Sable 类型，而且有一个以 {@code SubLevel} 为参数类型的方法
 * （{@code describe(SubLevel)}）—— <b>方法签名的类型在类校验时就要解析</b>。
 * 也就是说"加载 SableCompat 这个类"本身就是致命的：没装 Sable 时一加载就
 * {@code NoClassDefFoundError}，<b>它自己的任何 isLoaded() 都来不及执行</b>。
 *
 * <p>所以守卫必须是 {@link SableGate#modPresent()} —— 那个类一个 Sable 类型都不出现。
 * 门后的 {@code describeTracking} 只在 Sable 真在时才被解析
 * （JVM 的符号引用是首次执行该指令时才解析）。
 */
public final class SableCompat {

    private SableCompat() {
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

}
