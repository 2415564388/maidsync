package com.maidsync.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读出 {@code ClientLevel.tickingEntities} —— 客户端"这一 tick 要 tick 哪些实体"的那个列表。
 *
 * <p>为什么需要它：实测里女仆的 {@code tickCount} 会永久冻在 0/1/5，而同期别的实体正常涨到 500+。
 * 要判定到底是"她压根不在列表里"还是"在列表里却没被 tick"，只能直接读这个列表。
 *
 * <p>{@code tickingEntities} 是包级私有字段，没有公开读法，所以用 {@code @Accessor}。
 * Mixin 生成的是接口默认实现，不会往 {@code ClientLevel} 里塞静态字段，
 * 所以不受"mixin 类里不能放带初始化器的静态字段"那条限制。
 */
@Mixin(ClientLevel.class)
public interface ClientLevelTickAccessor {

    @Accessor("tickingEntities")
    EntityTickList maidsync$getTickingEntities();
}
