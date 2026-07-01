package com.example.guardianparrot;


import org.bukkit.persistence.PersistentDataType;import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import com.example.guardianparrot.GPPlugin;

/** Loads optional integrations reflectively to avoid CNF if not installed. */
public final class OptionalHooks {
    private OptionalHooks(){}

    public static void initOptionalHooks(GPPlugin plugin){
        safeInit(plugin, "WorldEdit", "com.example.guardianparrot.integration.WEHook", "init");
        safeInit(plugin, "WorldGuard", "com.example.guardianparrot.integration.WGHook", "init");
    }

    private static void safeInit(GPPlugin plugin, String pluginName, String className, String method){
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
            if (p == null){
                plugin.getLogger().info("[" + pluginName + "] not found; skipping hook.");
                return;
            }
            Class<?> cls = Class.forName(className);
            cls.getMethod(method).invoke(null);
            plugin.getLogger().info("[" + pluginName + "] hook initialized.");
        } catch (Throwable t){
            plugin.getLogger().warning("Failed to init hook for "+pluginName+": " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }
}
