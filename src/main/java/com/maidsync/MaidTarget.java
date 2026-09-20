package com.maidsync;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/** 判定"这个实体要不要管"。 */
public final class MaidTarget {
    private MaidTarget() {
    }

    public static boolean matches(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof EntityMaid) {
            return true;
        }
        if (MaidSyncConfig.affectAllEntities()) {
            return true;
        }
        var extra = MaidSyncConfig.extraEntityTypes();
        if (extra.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && extra.contains(id.toString());
    }
}
