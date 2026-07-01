package com.fermerpets;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class WorldsDenyService {
    private final FermerPetsModule plugin;
    private File file;
    private FileConfiguration cfg;
    private final Map<String, Set<String>> allowed = new HashMap<>();

    public WorldsDenyService(FermerPetsModule plugin){
        this.plugin = plugin;
        reload();
    }

    public void reload(){
        try {
            if (file == null) {
                file = new File(plugin.getDataFolder(), "worldsdenyspawn.yml");
                if (!file.exists()) plugin.saveResource("worldsdenyspawn.yml", false);
            }
            cfg = YamlConfiguration.loadConfiguration(file);
            allowed.clear();
            if (cfg.isConfigurationSection("worlds")){
                for (String world : cfg.getConfigurationSection("worlds").getKeys(false)){
                    java.util.List<String> list = cfg.getStringList("worlds."+world);
                    allowed.put(world, new HashSet<>(list));
                }
            }
        } catch (Throwable ignored) {}
    }

    public Set<String> getAllowedForWorld(String world){
        return allowed.get(world);
    }

    public boolean isAllowed(org.bukkit.entity.Player p){
        if (p == null || p.getWorld() == null) return true;
        Set<String> set = allowed.get(p.getWorld().getName());
        if (set == null) return true; // world not configured -> allow
        if (set.isEmpty()) return false; // explicitly no regions -> deny
        return com.fermerpets.integration.WGHook.isAllowedForOwnerOrMember(p, set);
    }
}
