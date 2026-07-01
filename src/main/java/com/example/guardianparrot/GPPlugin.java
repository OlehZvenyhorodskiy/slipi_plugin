package com.example.guardianparrot;

import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Logger;

public interface GPPlugin {
    JavaPlugin host();

    default Server getServer() { return host().getServer(); }
    default PluginManager getPluginManager() { return getServer().getPluginManager(); }
    default Logger getLogger() { return host().getLogger(); }
    default File getDataFolder() { return host().getDataFolder(); }

    FileConfiguration getConfig();

    void saveResource(String resourcePath, boolean replace);

    /**
     * GuardianParrot was originally a standalone JavaPlugin and called saveDefaultConfig().
     * In the embedded-module version we keep compatibility, but the default config file
     * is named guardianparrot.yml.
     */
    default void saveDefaultConfig() {
        File f = new File(getDataFolder(), "guardianparrot.yml");
        if (!f.exists()) {
            getDataFolder().mkdirs();
            saveResource("guardianparrot.yml", false);
        }
    }
}
