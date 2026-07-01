
package com.fermerpets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PlayersHoppersStore {
    private final FermerPetsModule plugin;
    private final File file;
    final FileConfiguration cfg;

    public PlayersHoppersStore(FermerPetsModule plugin){
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playershoppers.yml");
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) {
            try { file.createNewFile(); } catch (IOException ignored) {}
        }
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    public void save(){
        try { cfg.save(file); } catch (IOException ignored) {}
    }
    public void reload(){
        try { cfg.load(file); } catch (Throwable ignored) {}
    }

    // ---------------- petId-centric API ----------------

    private String root(UUID petId){ return petId.toString(); }

    // ---------------- owner/index fallback storage (persists across re-summons) ----------------

    private String ownerRoot(UUID owner, int index){
        return "owner." + owner.toString() + "." + index;
    }

    // One shared hopper/beacon per owner (applies to all farmers)
    private String ownerGlobalRoot(UUID owner){
        return "owner." + owner.toString() + ".global";
    }

    private Record getGlobalHopper(UUID owner){
        String base = ownerGlobalRoot(owner) + ".hopper";
        if (!cfg.isConfigurationSection(base)) return null;
        String wid = cfg.getString(base + ".world", null);
        World w = wid==null? null : Bukkit.getWorld(UUID.fromString(wid));
        if (w==null) return null;
        int x=cfg.getInt(base+".x"), y=cfg.getInt(base+".y"), z=cfg.getInt(base+".z");
        Location loc = new Location(w, x, y, z);
        String idStr = cfg.getString(base+".id", null);
        UUID id = null;
        try { if (idStr!=null) id = UUID.fromString(idStr); } catch (IllegalArgumentException ignored){}
        return new Record(loc, id);
    }

    private void setGlobalHopper(UUID owner, UUID id, Location loc){
        String base = ownerGlobalRoot(owner) + ".hopper";
        cfg.set(base+".id", id.toString());
        cfg.set(base+".world", loc.getWorld().getUID().toString());
        cfg.set(base+".x", loc.getBlockX());
        cfg.set(base+".y", loc.getBlockY());
        cfg.set(base+".z", loc.getBlockZ());
    }

    private void clearGlobalHopper(UUID owner){
        cfg.set(ownerGlobalRoot(owner) + ".hopper", null);
    }

    // ---------------- public owner-wide API ----------------

    /** Returns the shared (global) hopper for this owner, if configured and world is loaded. */
    public Record getGlobalHopperForOwner(UUID owner){
        if (owner == null) return null;
        try { reload(); } catch (Throwable ignored) {}
        return getGlobalHopper(owner);
    }

    /** Clears all persisted hopper bindings for this owner (global + per-slot). */
    public void clearAllHoppersForOwner(UUID owner){
        if (owner == null) return;
        cfg.set(ownerGlobalRoot(owner) + ".hopper", null);
        // legacy per-slot entries (some servers may still have them)
        for (int i = 1; i <= 3; i++) {
            cfg.set(ownerRoot(owner, i) + ".hopper", null);
        }
        save();
    }

    private Record getHopperByOwnerIndex(UUID owner, int index){
        String base = ownerRoot(owner, index) + ".hopper";
        if (!cfg.isConfigurationSection(base)) return null;
        String wid = cfg.getString(base + ".world", null);
        World w = wid==null? null : Bukkit.getWorld(UUID.fromString(wid));
        if (w==null) return null;
        int x=cfg.getInt(base+".x"), y=cfg.getInt(base+".y"), z=cfg.getInt(base+".z");
        Location loc = new Location(w, x, y, z);
        String idStr = cfg.getString(base+".id", null);
        UUID id = null;
        try { if (idStr!=null) id = UUID.fromString(idStr); } catch (IllegalArgumentException ignored){}
        return new Record(loc, id);
    }

    private void setHopperByOwnerIndex(UUID owner, int index, UUID id, Location loc){
        String base = ownerRoot(owner, index) + ".hopper";
        cfg.set(base+".id", id.toString());
        cfg.set(base+".world", loc.getWorld().getUID().toString());
        cfg.set(base+".x", loc.getBlockX());
        cfg.set(base+".y", loc.getBlockY());
        cfg.set(base+".z", loc.getBlockZ());
    }

    private void clearHopperByOwnerIndex(UUID owner, int index){
        cfg.set(ownerRoot(owner, index) + ".hopper", null);
    }

    private Record getBeaconByOwnerIndex(UUID owner, int index){
        String base = ownerRoot(owner, index) + ".beacon";
        if (!cfg.isConfigurationSection(base)) return null;
        String wid = cfg.getString(base + ".world", null);
        World w = wid==null? null : Bukkit.getWorld(UUID.fromString(wid));
        if (w==null) return null;
        int x=cfg.getInt(base+".x"), y=cfg.getInt(base+".y"), z=cfg.getInt(base+".z");
        Location loc = new Location(w, x, y, z);
        String idStr = cfg.getString(base+".id", null);
        UUID id = null;
        try { if (idStr!=null) id = UUID.fromString(idStr); } catch (IllegalArgumentException ignored){}
        return new Record(loc, id);
    }

    private void setBeaconByOwnerIndex(UUID owner, int index, UUID id, Location loc){
        String base = ownerRoot(owner, index) + ".beacon";
        cfg.set(base+".id", id.toString());
        cfg.set(base+".world", loc.getWorld().getUID().toString());
        cfg.set(base+".x", loc.getBlockX());
        cfg.set(base+".y", loc.getBlockY());
        cfg.set(base+".z", loc.getBlockZ());
    }

    private void clearBeaconByOwnerIndex(UUID owner, int index){
        cfg.set(ownerRoot(owner, index) + ".beacon", null);
    }

    /** When a farmer is (re)spawned, copy any persisted owner/index bindings onto the new petId. */
    public void rebindPersistedToPet(UUID owner, int index, UUID petId){
        if (owner==null || petId==null) return;
        try { reload(); } catch (Throwable ignored) {}

        // Global hopper/beacon applies to all farmers
        Record gh = getGlobalHopper(owner);
        if (gh != null && gh.loc != null && gh.loc.getWorld() != null && gh.id != null){
            setHopperByPet(petId, gh.id, gh.loc);
        }

        Record h = getHopperByOwnerIndex(owner, index);
        if (h != null && h.loc != null && h.loc.getWorld() != null && h.id != null){
            setHopperByPet(petId, h.id, h.loc);
        }
        Record b = getBeaconByOwnerIndex(owner, index);
        if (b != null && b.loc != null && b.loc.getWorld() != null && b.id != null){
            setBeaconByPet(petId, b.id, b.loc);
        }
    }

    public int getFuelByPet(UUID petId){
        try { reload(); } catch (Throwable ignored) {}
        return cfg.getInt(root(petId)+".fuel", 0);
    }
    public void setFuelByPet(UUID petId, int amount){
        cfg.set(root(petId)+".fuel", amount);
        save();
    }

    // ---- binding to AquaPrivate region/marker (for cleanup + harvest bounds) ----
    public void setPrivateBindingByPet(UUID petId, String regionId, Location markerLoc){
        if (petId == null) return;
        String base = root(petId)+".private";
        if (regionId != null && !regionId.isBlank()) cfg.set(base+".regionId", regionId);
        if (markerLoc != null && markerLoc.getWorld() != null){
            cfg.set(base+".world", markerLoc.getWorld().getUID().toString());
            cfg.set(base+".x", markerLoc.getBlockX());
            cfg.set(base+".y", markerLoc.getBlockY());
            cfg.set(base+".z", markerLoc.getBlockZ());
        }
        save();
    }

    public String getPrivateRegionIdByPet(UUID petId){
        return cfg.getString(root(petId)+".private.regionId", null);
    }

    public Location getPrivateMarkerByPet(UUID petId){
        String base = root(petId)+".private";
        if (!cfg.isConfigurationSection(base)) return null;
        String wid = cfg.getString(base+".world", null);
        World w = wid==null? null : Bukkit.getWorld(UUID.fromString(wid));
        if (w==null) return null;
        int x=cfg.getInt(base+".x"), y=cfg.getInt(base+".y"), z=cfg.getInt(base+".z");
        return new Location(w, x, y, z);
    }

    public String getFilterByPet(UUID petId){ return null; }
    public void setFilterByPet(UUID petId, String materialName){ /* filter removed */ }


    public void setHopperByPet(UUID petId, UUID id, Location loc){
        String base = root(petId)+".hopper";
        cfg.set(base+".id", id.toString());
        cfg.set(base+".world", loc.getWorld().getUID().toString());
        cfg.set(base+".x", loc.getBlockX());
        cfg.set(base+".y", loc.getBlockY());
        cfg.set(base+".z", loc.getBlockZ());
        save();
    }
    public void clearHopperByPet(UUID petId){
        cfg.set(root(petId)+".hopper", null);
        save();
    }
    public Record getHopperByPet(UUID petId){
        String base = root(petId)+".hopper";
        if (!cfg.isConfigurationSection(base)) return null;
        String wid = cfg.getString(base+".world", null);
        World w = wid==null? null : Bukkit.getWorld(UUID.fromString(wid));
        if (w==null) return null;
        int x=cfg.getInt(base+".x"), y=cfg.getInt(base+".y"), z=cfg.getInt(base+".z");
        Location loc = new Location(w, x, y, z);
        String idStr = cfg.getString(base+".id", null);
        UUID id = null;
        try { if (idStr!=null) id = UUID.fromString(idStr); } catch (IllegalArgumentException ignored){}
        return new Record(loc, id);
    }

    public void setBeaconByPet(UUID petId, UUID id, Location loc){
        String base = root(petId)+".beacon";
        cfg.set(base+".id", id.toString());
        cfg.set(base+".world", loc.getWorld().getUID().toString());
        cfg.set(base+".x", loc.getBlockX());
        cfg.set(base+".y", loc.getBlockY());
        cfg.set(base+".z", loc.getBlockZ());
        save();
    }
    public void clearBeaconByPet(UUID petId){
        cfg.set(root(petId)+".beacon", null);
        save();
    }
    public Record getBeaconByPet(UUID petId){
        String base = root(petId)+".beacon";
        if (!cfg.isConfigurationSection(base)) return null;
        String wid = cfg.getString(base+".world", null);
        World w = wid==null? null : Bukkit.getWorld(UUID.fromString(wid));
        if (w==null) return null;
        int x=cfg.getInt(base+".x"), y=cfg.getInt(base+".y"), z=cfg.getInt(base+".z");
        Location loc = new Location(w, x, y, z);
        String idStr = cfg.getString(base+".id", null);
        UUID id = null;
        try { if (idStr!=null) id = UUID.fromString(idStr); } catch (IllegalArgumentException ignored){}
        return new Record(loc, id);
    }

    public static final class Record {
        public final Location loc;
        public final UUID id;
        public Record(Location l, UUID i){ this.loc = l; this.id = i; }
    }

    
    public String getFilter(UUID owner, int farmerIndex){ return null; }
    public void setFilter(UUID owner, int farmerIndex, String materialName){ /* filter removed */ }
// ------------- back-compat wrappers (owner+index) -------------

    
private UUID resolvePetId(UUID owner, int index){
    try {
        if (owner == null) return null;
        if (index < 1 || index > 3) return null;
        FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
        PlayersStore ps = new PlayersStore(playersCfg);
        ps.migrateIfNeeded(owner);

        // Prefer fixed-slot farmer mapping
        boolean hasFixedSchema = false;
        try {
            String base = "players."+owner+".farmers";
            hasFixedSchema = playersCfg.isConfigurationSection(base)
                    || playersCfg.contains(base + ".1")
                    || playersCfg.contains(base + ".2")
                    || playersCfg.contains(base + ".3");
        } catch (Throwable ignored2){}

        UUID id = ps.getFarmer(owner, index);
        if (id != null) return id;
        if (hasFixedSchema) return null;

        // Fallback to legacy list order (only when fixed-slot schema is absent)
        java.util.List<java.util.UUID> pets = ps.getPets(owner);
        if (pets != null && index >= 1 && index <= pets.size()) return pets.get(index-1);
    } catch (Throwable ignored){}
    return null;
}

    public int getFuel(UUID owner, int farmerIndex){
        UUID pet = resolvePetId(owner, farmerIndex);
        return pet==null? 0 : getFuelByPet(pet);
    }
    public void setFuel(UUID owner, int farmerIndex, int amount){
        UUID pet = resolvePetId(owner, farmerIndex);
        if (pet!=null) setFuelByPet(pet, amount);
    }
    public void setHopper(UUID owner, int farmerIndex, UUID id, Location loc){
        if (owner==null || loc==null || loc.getWorld()==null) return;
        if (farmerIndex < 1 || farmerIndex > 3) return;
        // One shared hopper for all farmers of this owner
        setGlobalHopper(owner, id, loc);
        // Also persist by owner/index for backwards-compat (and for features that still reference index)
        setHopperByOwnerIndex(owner, farmerIndex, id, loc);
        // Also bind to current petId if it exists
        UUID pet = resolvePetId(owner, farmerIndex);
        if (pet!=null) setHopperByPet(pet, id, loc);
        save();
    }
    public void clearHopper(UUID owner, int farmerIndex){
        if (owner==null) return;
        if (farmerIndex < 1 || farmerIndex > 3) return;
        UUID pet = resolvePetId(owner, farmerIndex);
        if (pet!=null) clearHopperByPet(pet);
        // Clear global binding as well (single hopper behaviour)
        clearGlobalHopper(owner);
        clearHopperByOwnerIndex(owner, farmerIndex);
        save();
    }
    public Record getHopper(UUID owner, int farmerIndex){
        if (owner==null) return null;
        if (farmerIndex < 1 || farmerIndex > 3) return null;
        // Prefer global hopper if present
        Record global = getGlobalHopper(owner);
        if (global != null) return global;
        UUID pet = resolvePetId(owner, farmerIndex);
        Record byPet = pet==null? null : getHopperByPet(pet);
        if (byPet != null) return byPet;
        // Fallback to persisted owner/index record (works even if farmer is currently unsummoned)
        return getHopperByOwnerIndex(owner, farmerIndex);
    }
    public void setBeacon(UUID owner, int farmerIndex, UUID id, Location loc){
        if (owner==null || loc==null || loc.getWorld()==null) return;
        if (farmerIndex < 1 || farmerIndex > 3) return;
        setBeaconByOwnerIndex(owner, farmerIndex, id, loc);
        UUID pet = resolvePetId(owner, farmerIndex);
        if (pet!=null) setBeaconByPet(pet, id, loc);
        save();
    }
    public void clearBeacon(UUID owner, int farmerIndex){
        if (owner==null) return;
        if (farmerIndex < 1 || farmerIndex > 3) return;
        UUID pet = resolvePetId(owner, farmerIndex);
        if (pet!=null) clearBeaconByPet(pet);
        clearBeaconByOwnerIndex(owner, farmerIndex);
        save();
    }
    public Record getBeacon(UUID owner, int farmerIndex){
        if (owner==null) return null;
        if (farmerIndex < 1 || farmerIndex > 3) return null;
        UUID pet = resolvePetId(owner, farmerIndex);
        Record byPet = pet==null? null : getBeaconByPet(pet);
        if (byPet != null) return byPet;
        return getBeaconByOwnerIndex(owner, farmerIndex);
    }

    // ------------- migration from legacy (owner/index) -------------
    // We detect old nodes like "<owner>.farmer_1.hopper" etc and move them to "<petId>.*"

    public void migrateIfNeeded(){
        // If file already has direct petId keys, assume migrated
        for (String key : cfg.getKeys(false)){
            if (key.contains("-")) { return; } // looks like UUID
        }
        // build owner -> pets order from players.yml
        FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
        PlayersStore ps = new PlayersStore(playersCfg);

        // Copy keys to avoid ConcurrentModification
        java.util.Set<String> owners = new java.util.HashSet<>(cfg.getKeys(false));
        for (String ownerStr : owners){
            UUID owner;
            try { owner = UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }
            java.util.List<java.util.UUID> pets = ps.getPets(owner);
            if (pets == null || pets.isEmpty()) continue;

            for (int idx=1; idx<=pets.size(); idx++){
                UUID pet = pets.get(idx-1);
                String oldBase = ownerStr + ".farmer_" + idx;
                // fuel
                if (cfg.contains(oldBase + ".fuel")){
                    cfg.set(pet.toString()+".fuel", cfg.getInt(oldBase+".fuel", 0));
                }
                // hopper
                if (cfg.isConfigurationSection(oldBase + ".hopper")){
                    cfg.set(pet.toString()+".hopper", cfg.getConfigurationSection(oldBase+".hopper").getValues(true));
                }
                // beacon
                if (cfg.isConfigurationSection(oldBase + ".beacon")){
                    cfg.set(pet.toString()+".beacon", cfg.getConfigurationSection(oldBase+".beacon").getValues(true));
                }
                // clear old
                cfg.set(oldBase, null);
            }
            // clear owner root if empty
            if (cfg.getConfigurationSection(ownerStr)==null || cfg.getConfigurationSection(ownerStr).getKeys(true).isEmpty()){
                cfg.set(ownerStr, null);
            }
        }
        save();
    }

    // legacy no-op: indices are irrelevant in petId-centric storage
    public void compactAfterRemoval(UUID owner, int removedIndex, int totalBefore){
        // nothing to do in new schema; kept for compatibility
        save();
    }

    public void remove(java.util.UUID petId){
        cfg.set(root(petId), null);
        save();
    }

}
