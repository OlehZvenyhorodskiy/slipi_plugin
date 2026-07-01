
package com.fermerpets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ProtectionOfflineLocker {
    private final FermerPetsModule plugin;
    private final File file;
    private final YamlConfiguration cfg;

    public ProtectionOfflineLocker(FermerPetsModule plugin){
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "protection-locks.yml");
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    private String key(Location loc){
        return loc.getWorld().getUID().toString()+":"+loc.getBlockX()+":"+loc.getBlockY()+":"+loc.getBlockZ();
    }

    public void lockAllToBedrock(){
        PlayersHoppersStore store = new PlayersHoppersStore(plugin);
        PlayersStore ps = new PlayersStore(plugin.getManager().getPlayersYaml());
        Set<String> owners = plugin.getManager().getPlayersYaml().getConfigurationSection("players")==null
                ? Collections.emptySet()
                : plugin.getManager().getPlayersYaml().getConfigurationSection("players").getKeys(false);
        for (String ownerStr : owners){
            UUID owner;
            try { owner = UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }
            List<UUID> pets = ps.getPets(owner);
            if (pets==null) continue;
            for (UUID petId : pets){
                PlayersHoppersStore.Record h = store.getHopperByPet(petId);
                if (h!=null && h.loc!=null){
                    lockOne(h.loc);
                }
                PlayersHoppersStore.Record b = store.getBeaconByPet(petId);
                if (b!=null && b.loc!=null){
                    lockOne(b.loc);
                }
            }
        }
        try { cfg.save(file); } catch (IOException ignored){}
    }

    private void lockOne(Location loc){
        if (loc.getWorld()==null) return;
        Block bl = loc.getBlock();
        String k = key(loc);
        if (cfg.contains(k)) return; // already locked
        cfg.set(k+".world", loc.getWorld().getUID().toString());
        cfg.set(k+".type", bl.getType().name());
        cfg.set(k+".data", 0); // reserved
        bl.setType(Material.BEDROCK, false);
    }

    public void restoreAll(){
        Set<String> keys = cfg.getKeys(false);
        for (String k : keys){
            String wid = cfg.getString(k+".world", null);
            String type = cfg.getString(k+".type", "AIR");
            World w = wid==null? null : Bukkit.getWorld(UUID.fromString(wid));
            if (w==null) continue;
            String[] parts = k.split(":");
            if (parts.length<4) continue;
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            Location loc = new Location(w, x, y, z);
            try {
                Material m = Material.valueOf(type);
                loc.getBlock().setType(m, false);
            } catch (IllegalArgumentException ignored){}
        }
        // clean file
        for (String k : new ArrayList<>(cfg.getKeys(false))){
            cfg.set(k, null);
        }
        try { cfg.save(file); } catch (IOException ignored){}
    }
}
