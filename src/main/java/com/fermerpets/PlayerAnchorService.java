package com.fermerpets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerAnchorService {

    private final FermerPetsModule plugin;
    private final Map<String, Object> anchors = new HashMap<>();

    public PlayerAnchorService(FermerPetsModule plugin){
        this.plugin = plugin;
    }

    private static void log(String s){ try { Bukkit.getLogger().info("[Anchor] " + s); } catch (Throwable ignored) {} }

    private boolean citizensAvailable(){
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("Citizens");
            if (p == null || !p.isEnabled()){ log("Citizens plugin not found/enabled"); return false; }
            Class.forName("net.citizensnpcs.api.CitizensAPI");
            return true;
        } catch (Throwable t){
            log("Citizens API missing: "+t.getClass().getSimpleName()+": "+t.getMessage());
            return false;
        }
    }

    private String keyFor(Location loc){
        World w = loc.getWorld();
        return (w == null ? "null" : w.getName()) + ":" + (loc.getBlockX()>>4) + "," + (loc.getBlockZ()>>4);
    }

    public void ensureAnchorAt(Location loc){
        if (loc == null) return;
        if (!citizensAvailable()){
            log("Citizens not found; cannot spawn player-anchors. Chunks will stay loaded via force tickets, but entity activation may sleep.");
            return;
        }
        try {
            String key = keyFor(loc);
            Object existing = anchors.get(key);
            if (existing != null){
                try {
                    Method isSpawned = existing.getClass().getMethod("isSpawned");
                    boolean spawned = (boolean)isSpawned.invoke(existing);
                    if (!spawned){
                        Method spawn = existing.getClass().getMethod("spawn", Location.class);
                        spawn.invoke(existing, loc.clone().add(0.5, 0.01, 0.5));
                        log("Re-spawned anchor NPC at " + key + " " + loc);
                    }
                } catch (Throwable ignored){}
                return;
            }

            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Method getRegistry = api.getMethod("getNPCRegistry");
            Object registry = getRegistry.invoke(null);

            String name = "FermerAnchor";
            log("Creating NPC via Citizens at "+loc);
            Class<?> npcClass = Class.forName("net.citizensnpcs.api.npc.NPC");
            Method create = registry.getClass().getMethod("createNPC", EntityType.class, String.class);
            Object npc = create.invoke(registry, EntityType.PLAYER, name);

            try { npc.getClass().getMethod("setProtected", boolean.class).invoke(npc, true); } catch (Throwable ignored){}

            try {
                Method data = npc.getClass().getMethod("data");
                Object dataStore = data.invoke(npc);
                try {
                    Class<?> meta = Class.forName("net.citizensnpcs.api.npc.NPC$Metadata");
                    Object NAMEPLATE_VISIBLE = meta.getField("NAMEPLATE_VISIBLE").get(null);
                    Method set = dataStore.getClass().getMethod("set", Object.class, Object.class);
                    set.invoke(dataStore, NAMEPLATE_VISIBLE, Boolean.FALSE);
                } catch (Throwable ignored){}
                try {
                    Class<?> meta = Class.forName("net.citizensnpcs.api.npc.NPC$Metadata");
                    Object REMOVE_FROM_PLAYERLIST = meta.getField("REMOVE_FROM_PLAYERLIST").get(null);
                    Method set = dataStore.getClass().getMethod("set", Object.class, Object.class);
                    set.invoke(dataStore, REMOVE_FROM_PLAYERLIST, Boolean.TRUE);
                } catch (Throwable ignored){}
            } catch (Throwable ignored){}

            try {
                Method getNavigator = npc.getClass().getMethod("getNavigator");
                Object nav = getNavigator.invoke(npc);
                try { nav.getClass().getMethod("setTarget", Location.class).invoke(nav, (Object)null); } catch (Throwable ignored){}
            } catch (Throwable ignored){}

            Method spawn = npcClass.getMethod("spawn", Location.class);
            boolean ok = (boolean) spawn.invoke(npc, loc.clone().add(0.5, 0.01, 0.5));
            log("NPC.spawn returned="+ok);
            if (ok){
                anchors.put(key, npc);
                log("Spawned anchor NPC at " + key + " " + loc);
            } else {
                log("Failed to spawn anchor NPC at " + key + " " + loc);
            }
        } catch (Throwable t){
            log("Citizens anchor error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public void removeAnchorAt(Location loc){
        if (loc == null) return;
        String key = keyFor(loc);
        Object npc = anchors.remove(key);
        if (npc == null) return;
        try {
            Method despawn = npc.getClass().getMethod("despawn");
            despawn.invoke(npc);
            try { npc.getClass().getMethod("destroy").invoke(npc); } catch (Throwable ignored){}
            log("Despawned anchor NPC at " + key);
        } catch (Throwable ignored){}
    }

    public void ensureAnchorsForOwner(UUID ownerId){
        try {
            PlayersHoppersStore store = new PlayersHoppersStore(plugin);
            org.bukkit.configuration.file.FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
            PlayersStore ps = new PlayersStore(playersCfg);
            java.util.List<java.util.UUID> pets = ps.getPets(ownerId);
            if (pets != null){
                for (java.util.UUID petId : pets){
                    PlayersHoppersStore.Record b = store.getBeaconByPet(petId);
                    if (b != null && b.loc != null){
                        ensureAnchorAt(b.loc);
                    }
                }
            }
        } catch (Throwable ignored){}
    }

    public void ensureAllAnchors(){
        try {
            org.bukkit.configuration.file.FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
            java.util.Set<java.util.UUID> owners = new java.util.HashSet<>();
            if (playersCfg.getConfigurationSection("players") != null){
                for (String s : playersCfg.getConfigurationSection("players").getKeys(false)){
                    try { owners.add(java.util.UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
                }
            }
            if (playersCfg.getConfigurationSection("owners") != null){
                for (String s : playersCfg.getConfigurationSection("owners").getKeys(false)){
                    try { owners.add(java.util.UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
                }
            }
            for (java.util.UUID ownerId : owners){
                ensureAnchorsForOwner(ownerId);
            }
        } catch (Throwable ignored){}
    }
}
