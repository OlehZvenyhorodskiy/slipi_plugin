package com.fermerpets;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/**
 * players.yml storage helper.
 *
 * Supported schemas:
 *  A) players.<uuid>.farmers.<1..3>: <uuid>            (preferred, fixed slots)
 *     players.<uuid>.farmer_paid.<1..3>: true/false    (one-time payment flag)
 *  B) players.<uuid>.pets: [<uuid>, ...]               (legacy list)
 *  C) owners.<uuid>.pets:  [<uuid>, ...]               (legacy-1)
 *  D) players.<uuid>: [<uuid>, ...]                    (legacy-2 flat list)
 *  E) players.<uuid>.entities: [<uuid>, ...]           (legacy-3)
 *  F) guardians.<uuid>.list: [<uuid>, ...]             (legacy-4 guess)
 */
public final class PlayersStore {
    private final FileConfiguration cfg;

    public PlayersStore(FileConfiguration cfg){ this.cfg = cfg; }

    /* ===================== New fixed-slot API ===================== */

    public UUID getFarmer(UUID owner, int index){
        if (owner == null) return null;
        if (index < 1 || index > 3) return null;
        String s = cfg.getString("players."+owner+".farmers."+index, null);
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex){ return null; }
    }

    public void setFarmer(UUID owner, int index, UUID petId){
        if (owner == null) return;
        if (index < 1 || index > 3) return;
        String path = "players."+owner+".farmers."+index;
        if (petId == null) cfg.set(path, null);
        else cfg.set(path, petId.toString());
        // keep legacy list in sync for old code paths
        cfg.set("players."+owner+".pets", toLegacyList(owner));
    }

    public Map<Integer, UUID> getFarmers(UUID owner){
        Map<Integer, UUID> out = new LinkedHashMap<>();
        for (int i=1;i<=3;i++){
            UUID id = getFarmer(owner,i);
            if (id != null) out.put(i,id);
        }
        return out;
    }

    public int countFarmers(UUID owner){
        int n = 0;
        for (int i=1;i<=3;i++) if (getFarmer(owner,i) != null) n++;
        if (n > 0) return n;
        // fallback legacy
        return getPets(owner).size();
    }

    public int firstFreeIndex(UUID owner){
        for (int i=1;i<=3;i++){
            if (getFarmer(owner,i) == null) return i;
        }
        return -1;
    }

    public boolean isPaid(UUID owner, int index){
        if (owner == null) return false;
        if (index < 1 || index > 3) return false;
        return cfg.getBoolean("players."+owner+".farmer_paid."+index, false);
    }

    public void setPaid(UUID owner, int index, boolean value){
        if (owner == null) return;
        if (index < 1 || index > 3) return;
        cfg.set("players."+owner+".farmer_paid."+index, value ? Boolean.TRUE : null);
    }

    /* ===================== Private binding (marker/region) ===================== */

    public void setBinding(UUID owner, int index, String regionId, org.bukkit.Location marker){
        if (owner == null) return;
        if (index < 1 || index > 3) return;
        String base = "players."+owner+".bindings."+index;
        if (regionId != null && !regionId.isBlank()) cfg.set(base+".regionId", regionId);
        if (marker != null && marker.getWorld() != null){
            cfg.set(base+".world", marker.getWorld().getUID().toString());
            cfg.set(base+".x", marker.getBlockX());
            cfg.set(base+".y", marker.getBlockY());
            cfg.set(base+".z", marker.getBlockZ());
        }
    }

    public String getBindingRegionId(UUID owner, int index){
        if (owner == null) return null;
        if (index < 1 || index > 3) return null;
        return cfg.getString("players."+owner+".bindings."+index+".regionId", null);
    }

    public org.bukkit.Location getBindingMarker(UUID owner, int index){
        if (owner == null) return null;
        if (index < 1 || index > 3) return null;
        String base = "players."+owner+".bindings."+index;
        if (cfg.getString(base+".world", null) == null) return null;
        try {
            java.util.UUID wid = java.util.UUID.fromString(cfg.getString(base+".world"));
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(wid);
            if (w == null) return null;
            int x = cfg.getInt(base+".x"), y = cfg.getInt(base+".y"), z = cfg.getInt(base+".z");
            return new org.bukkit.Location(w, x, y, z);
        } catch (Throwable t){
            return null;
        }
    }

    /**
     * One-time migration: if new schema missing but legacy list exists, map list[0..2] -> farmers.1..3.
     */
    public void migrateIfNeeded(UUID owner){
        if (owner == null) return;
        boolean hasNew = false;
        for (int i=1;i<=3;i++){
            if (cfg.getString("players."+owner+".farmers."+i, null) != null){
                hasNew = true; break;
            }
        }
        if (hasNew) return;

        List<UUID> legacy = getPets(owner);
        if (legacy.isEmpty()) return;

        for (int i=1;i<=3;i++){
            if (i-1 < legacy.size()){
                cfg.set("players."+owner+".farmers."+i, legacy.get(i-1).toString());
            }
        }
        // keep legacy list normalized to at most 3
        cfg.set("players."+owner+".pets", toLegacyList(owner));
    }

    private List<String> toLegacyList(UUID owner){
        List<String> raw = new ArrayList<>();
        for (int i=1;i<=3;i++){
            UUID id = getFarmer(owner,i);
            if (id != null) raw.add(id.toString());
        }
        return raw;
    }

    /* ===================== Legacy list API (read-only preferred) ===================== */

    // legacy getPets remains for compatibility, but prefers new farmers.<i> if present
    public List<UUID> getPets(UUID owner){
        // Prefer new schema if present
        if (owner != null){
            List<UUID> slot = new ArrayList<>();
            for (int i=1;i<=3;i++){
                UUID id = getFarmer(owner,i);
                if (id != null) slot.add(id);
            }
            if (!slot.isEmpty()) return slot;
        }

        List<UUID> out = new ArrayList<>();
        // current legacy
        List<String> a = cfg.getStringList("players."+owner+".pets");
        if (!a.isEmpty()) { parse(out, a); return out; }
        // legacy-1
        List<String> b = cfg.getStringList("owners."+owner+".pets");
        if (!b.isEmpty()) { parse(out, b); return out; }
        // legacy-2
        List<String> c = cfg.getStringList("players."+owner);
        if (!c.isEmpty()) { parse(out, c); return out; }
        // legacy-3
        List<String> d = cfg.getStringList("players."+owner+".entities");
        if (!d.isEmpty()) { parse(out, d); return out; }
        // legacy-4
        List<String> e = cfg.getStringList("guardians."+owner+".list");
        if (!e.isEmpty()) { parse(out, e); return out; }
        return out;
    }

    public void setPets(UUID owner, List<UUID> ids){
        if (owner == null){
            return;
        }
        if (ids == null || ids.isEmpty()){
            cfg.set("players."+owner, null);
            return;
        }
        // write legacy list (for compatibility)
        List<String> raw = new ArrayList<>();
        for (UUID u : ids) raw.add(u.toString());
        cfg.set("players."+owner+".pets", raw);
        // also map into farmers slots if empty
        for (int i=1;i<=3;i++){
            if (cfg.getString("players."+owner+".farmers."+i, null) == null && i-1 < ids.size()){
                cfg.set("players."+owner+".farmers."+i, ids.get(i-1).toString());
            }
        }
    }

    public boolean hasPets(UUID owner){
        if (owner == null) return false;
        for (int i=1;i<=3;i++){
            if (cfg.getString("players."+owner+".farmers."+i, null) != null) return true;
        }
        if (!cfg.getStringList("players."+owner+".pets").isEmpty()) return true;
        if (!cfg.getStringList("owners."+owner+".pets").isEmpty()) return true;
        if (!cfg.getStringList("players."+owner).isEmpty()) return true;
        if (!cfg.getStringList("players."+owner+".entities").isEmpty()) return true;
        if (!cfg.getStringList("guardians."+owner+".list").isEmpty()) return true;
        return false;
    }

    private static void parse(List<UUID> out, List<String> in){
        for (String s : in){ try { out.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored){} }
    }
}
