package com.example.guardianparrot;

import org.bukkit.persistence.PersistentDataType;import com.sk89q.worldedit.bukkit.BukkitAdapter;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import com.example.guardianparrot.GPPlugin;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/** Loads worldsdenyspawn.yml and answers whether a player's current region is denied. */
public final class WorldsDenyService {
    private final GPPlugin plugin;
    private final Map<String, Set<String>> denyMap = new HashMap<>();
    private File file;
    private FileConfiguration cfg;

    public WorldsDenyService(GPPlugin plugin){
        this.plugin = plugin;
    }

    public void load(){
        try {
            if (file == null){
                file = new File(plugin.getDataFolder(), "worldsdenyspawn.yml");
            }
            if (!file.exists()){
                plugin.saveResource("worldsdenyspawn.yml", false);
            }
            cfg = YamlConfiguration.loadConfiguration(file);
            denyMap.clear();
            if (cfg.contains("worlds")){
                // Expect structure:
                // worlds:
                //   - world1:
                //       - regionA
                //       - regionB
                //   - world2:
                //       - regionX
                List<?> worlds = cfg.getList("worlds");
                if (worlds != null){
                    for (Object o : worlds){
                        if (o instanceof Map){
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) o;
                            for (Map.Entry<String, Object> e : m.entrySet()){
                                String world = e.getKey();
                                List<String> regions = new ArrayList<>();
                                Object val = e.getValue();
                                if (val instanceof List){
                                    for (Object r : (List<?>)val){
                                        if (r != null) regions.add(String.valueOf(r));
                                    }
                                }
                                denyMap.put(world, regions.stream().map(String::toLowerCase).collect(Collectors.toSet()));
                            }
                        }
                    }
                }
            }
            plugin.getLogger().info("[WorldsDeny] Loaded worldsdenyspawn.yml with "+denyMap.size()+" world entries.");
        } catch (Throwable t){
            plugin.getLogger().warning("[WorldsDeny] Failed to load: " + t.getMessage());
        }
    }

    /** Return true if the player's current location is within a denied region for their world. */
    public boolean isDeniedHere(Player player){
        if (denyMap.isEmpty()) return false;
        if (com.example.guardianparrot.integration.WGHook.isAvailable() == false) return false; // no WG, skip

        String world = player.getWorld().getName();
        Set<String> set = denyMap.get(world);
        if (set == null || set.isEmpty()) return false;

        // Query WG regions and check for name matches
        try {
            var loc = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player.getLocation());
            var container = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
            var query = container.createQuery();
            var rset = query.getApplicableRegions(loc);
            for (var r : rset){
                String id = r.getId();
                if (id != null && set.contains(id.toLowerCase())) return true;
            }
        } catch (Throwable t){
            // ignore and allow
        }
        return false;
    }
}
