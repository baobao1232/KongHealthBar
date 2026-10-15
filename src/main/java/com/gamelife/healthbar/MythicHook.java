package com.gamelife.healthbar;

import io.lumine.mythic.bukkit.MythicBukkit;
import org.bukkit.entity.Entity;

final class MythicHook {
    static String idOf(Entity e) {
        return MythicBukkit.inst().getMobManager().getActiveMob(e.getUniqueId())
                .map(m -> m.getType().getInternalName()).orElse(null);
    }
}