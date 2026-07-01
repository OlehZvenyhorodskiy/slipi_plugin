package com.fermerpets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class NmsAnchorService {

    private final FermerPetsModule plugin;
    private final Map<String, Object> anchors = new HashMap<>();

    public NmsAnchorService(FermerPetsModule plugin){
        this.plugin = plugin;
    }

    private static void log(String s){ try { Bukkit.getLogger().info("[NMS-Anchor] " + s); } catch (Throwable ignored) {} }

    private String key(Location loc){
        World w = loc.getWorld();
        return (w==null?"null":w.getName())+":"+(loc.getBlockX()>>4)+","+(loc.getBlockZ()>>4);
    }

    public boolean ensureAnchorAt(Location loc){
        try {
            if (loc == null) return false;
            String key = key(loc);
            if (anchors.containsKey(key)) return true;

            // CraftServer
            Class<?> craftServerCls = Class.forName("org.bukkit.craftbukkit.CraftServer");
            Object craftServer = craftServerCls.cast(Bukkit.getServer());
            // Dedicated server / MinecraftServer
            Method getServer = craftServerCls.getMethod("getServer");
            Object mcServer = getServer.invoke(craftServer);

            // ServerLevel / WorldServer
            Class<?> craftWorldCls = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            Object craftWorld = craftWorldCls.cast(loc.getWorld());
            Method getHandleWorld = craftWorldCls.getMethod("getHandle");
            Object worldServer = getHandleWorld.invoke(craftWorld);

            // GameProfile
            Class<?> gameProfileCls = Class.forName("com.mojang.authlib.GameProfile");
            Constructor<?> gpCtor = gameProfileCls.getConstructor(java.util.UUID.class, String.class);
            Object gameProfile = gpCtor.newInstance(java.util.UUID.randomUUID(), "FermerAnchor");

            // ServerPlayer
            Class<?> serverPlayerCls;
            Constructor<?> spCtor;
            try {
                // 1.20.1..1.20.4 mapping
                serverPlayerCls = Class.forName("net.minecraft.server.level.ServerPlayer");
                Class<?> serverLevelCls = Class.forName("net.minecraft.server.level.ServerLevel");
                Class<?> minecraftServerCls = Class.forName("net.minecraft.server.MinecraftServer");
                spCtor = serverPlayerCls.getConstructor(minecraftServerCls, serverLevelCls, gameProfileCls);
            } catch (Throwable t){
                // Legacy
                serverPlayerCls = Class.forName("net.minecraft.server.level.EntityPlayer");
                Class<?> worldCls = Class.forName("net.minecraft.server.level.WorldServer");
                Class<?> minecraftServerCls = Class.forName("net.minecraft.server.MinecraftServer");
                spCtor = serverPlayerCls.getConstructor(minecraftServerCls, worldCls, gameProfileCls);
            }
            Object serverPlayer = spCtor.newInstance(mcServer, worldServer, gameProfile);

            // Set position
            try {
                Method setPos = serverPlayerCls.getMethod("setPos", double.class, double.class, double.class);
                setPos.invoke(serverPlayer, loc.getX()+0.5, loc.getY()+0.2, loc.getZ()+0.5);
            } catch (Throwable t){
                try {
                    Method setLocation = serverPlayerCls.getMethod("setLocation", double.class, double.class, double.class, float.class, float.class);
                    setLocation.invoke(serverPlayer, loc.getX()+0.5, loc.getY()+0.2, loc.getZ()+0.5, 0f, 0f);
                } catch (Throwable ignored){}
            }

            // Add to world
            boolean added = false;
            try {
                // modern addFreshEntity
                Method addEntity = worldServer.getClass().getMethod("addFreshEntity", Class.forName("net.minecraft.world.entity.Entity"));
                added = (boolean)addEntity.invoke(worldServer, serverPlayer);
            } catch (Throwable t){
                try {
                    Method addEntity = worldServer.getClass().getMethod("addEntity", serverPlayerCls);
                    Object res = addEntity.invoke(worldServer, serverPlayer);
                    added = (res == null); // older versions return void
                } catch (Throwable ignored){}
            }

            if (added){
                anchors.put(key, serverPlayer);
                log("Spawned fake ServerPlayer at "+key+" "+loc);
                return true;
            } else {
                log("Failed to add ServerPlayer to world at "+key);
                return false;
            }
        } catch (Throwable t){
            log("NMS anchor error: "+t.getClass().getSimpleName()+": "+t.getMessage());
            return false;
        }
    }

    public void removeAnchorAt(Location loc){
        try {
            String key = key(loc);
            Object sp = anchors.remove(key);
            if (sp == null) return;
            try {
                Method discard = sp.getClass().getMethod("discard");
                discard.invoke(sp);
            } catch (Throwable t){
                try {
                    Method kill = sp.getClass().getMethod("kill");
                    kill.invoke(sp);
                } catch (Throwable ignored){}
            }
            log("Removed fake ServerPlayer at "+key);
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
            PlayersHoppersStore store = new PlayersHoppersStore(plugin);
            for (java.util.UUID ownerId : owners){
                com.fermerpets.PlayersStore ps = new com.fermerpets.PlayersStore(playersCfg);
                java.util.List<java.util.UUID> pets = ps.getPets(ownerId);
                if (pets != null){
                    for (java.util.UUID petId : pets){
                        PlayersHoppersStore.Record b = store.getBeaconByPet(petId);
                        if (b != null && b.loc != null){
                            ensureAnchorAt(b.loc);
                        }
                    }
                }
            }
        } catch (Throwable ignored){}
    }
}
