package com.example.guardianparrot.util;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Parrot;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import com.example.guardianparrot.GPPlugin;

public final class GuardMarkers {
    private static NamespacedKey GUARD_KEY;

    public static void init(GPPlugin plugin) {
        GUARD_KEY = new NamespacedKey(plugin.host(), "guardianparrot");
    }

    public static boolean isGuardianParrot(Entity e) {
        if (e == null) return false;
        try {
            if (e.hasMetadata("guardianparrot")) {
                for (MetadataValue mv : e.getMetadata("guardianparrot")) {
                    if (mv != null && mv.asBoolean()) return true;
                }
            }
        } catch (Throwable ignored) {}
        try {
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            return GUARD_KEY != null && pdc.has(GUARD_KEY, PersistentDataType.BYTE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private GuardMarkers() {}
}
