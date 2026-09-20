package com.maidsync.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;

/**
 * 诊断：把女仆身上跟"能不能画出来"有关的同步数据全部读出来。
 *
 * <p>TLM 女仆的模型信息是通过实体同步数据下发的：
 * {@code DATA_IS_YSM_MODEL / DATA_YSM_MODEL_ID / DATA_YSM_MODEL_TEXTURE / DATA_MODEL_ID}。
 * 如果其中任何一项在某个客户端上是空的或指向一个不存在的模型，
 * 渲染器就会"正常跑完但什么都不画" —— 这正好能解释联机时主机看得见、客机看不见。
 *
 * <p>同时报告碰撞箱尺寸：尺寸为 0 的话模型和碰撞箱会一起消失。
 */
public final class MaidModelInfo {
    private static boolean reflectionUnavailable;

    private MaidModelInfo() {
    }

    public static String describe(EntityMaid maid) {
        return "尺寸=" + f(maid.getBbWidth()) + "x" + f(maid.getBbHeight())
                + " 姿势=" + maid.getPose()
                + " YSM模型=" + read(maid, "DATA_IS_YSM_MODEL")
                + " YSM模型ID=" + read(maid, "DATA_YSM_MODEL_ID")
                + " YSM贴图=" + read(maid, "DATA_YSM_MODEL_TEXTURE")
                + " 模型包=" + read(maid, "DATA_MODEL_ID");
    }

    private static Object read(Entity entity, String fieldName) {
        if (reflectionUnavailable) {
            return "<不可用>";
        }
        try {
            Field field = EntityMaid.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof EntityDataAccessor<?> accessor)) {
                return "<不是 accessor>";
            }
            Object result = entity.getEntityData().get((EntityDataAccessor) accessor);
            if (result instanceof String s) {
                return s.isEmpty() ? "<空字符串>" : s;
            }
            return result;
        } catch (NoSuchFieldException e) {
            // TLM 改了字段名就整体放弃，别每帧刷异常
            reflectionUnavailable = true;
            return "<无此字段 " + fieldName + ">";
        } catch (Throwable t) {
            return "<读失败:" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String f(double d) {
        return String.format("%.2f", d);
    }
}
