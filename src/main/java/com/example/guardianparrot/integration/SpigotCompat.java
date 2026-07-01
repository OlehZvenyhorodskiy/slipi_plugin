package com.example.guardianparrot.integration;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Small Spigot API helpers used across the plugin. */
public final class SpigotCompat {
    private SpigotCompat() {}

    public static NamespacedKey key(JavaPlugin plugin, String path) {
        return new NamespacedKey(plugin, path);
    }

    public static void setFlag(PersistentDataContainer pdc, NamespacedKey key, boolean value) {
        if (value) pdc.set(key, PersistentDataType.BYTE, (byte)1);
        else pdc.remove(key);
    }

    public static boolean getFlag(PersistentDataContainer pdc, NamespacedKey key) {
        Byte b = pdc.get(key, PersistentDataType.BYTE);
        return b != null && b == (byte)1;
    }
}
