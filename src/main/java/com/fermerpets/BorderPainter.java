package com.fermerpets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BorderPainter {

    private BorderPainter(){}

    // Tasks and holograms tracked per-beacon key
    private static final Map<String, BukkitTask> tasksByBeacon = new ConcurrentHashMap<>();
    private static final Map<String, UUID> holoByBeacon = new ConcurrentHashMap<>();

    private static String beaconKey(Location loc){
        if (loc == null || loc.getWorld() == null) return "null";
        UUID w = loc.getWorld().getUID();
        return w.toString()+":"+loc.getBlockX()+":"+loc.getBlockY()+":"+loc.getBlockZ();
    }

    private static int resolveFarmerIndex(FermerPetsModule plugin, UUID owner, Location beaconLoc){
        try {
            org.bukkit.configuration.file.FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
            com.fermerpets.PlayersStore ps = new com.fermerpets.PlayersStore(playersCfg);
            java.util.List<java.util.UUID> pets = ps.getPets(owner);
            com.fermerpets.PlayersHoppersStore store = new com.fermerpets.PlayersHoppersStore(plugin);
            if (pets != null){
                for (int i=1;i<=pets.size();i++){
                    com.fermerpets.PlayersHoppersStore.Record r = store.getBeacon(owner, i);
                    if (r!=null && r.loc!=null && r.loc.getWorld()!=null && beaconLoc.getWorld()!=null
                            && r.loc.getWorld().equals(beaconLoc.getWorld())
                            && r.loc.getBlockX()==beaconLoc.getBlockX()
                            && r.loc.getBlockY()==beaconLoc.getBlockY()
                            && r.loc.getBlockZ()==beaconLoc.getBlockZ()){
                        return i;
                    }
                }
            }
        } catch(Throwable ignored){}
        return 1;
    }

    private static int radiusForFuel(Plugin plugin, int fuel){
        // default tiers based on config visual.outline.radii
        java.util.List<Integer> radii = plugin.getConfig().getIntegerList("fuel.radii");
        if (radii==null || radii.size()<5){
            radii = java.util.Arrays.asList(20,25,30,35,40);
        }
        if (fuel <= 0) return radii.get(0);
        if (fuel <= 15) return radii.get(1);
        if (fuel <= 30) return radii.get(2);
        if (fuel <= 45) return radii.get(3);
        return radii.get(4);
    }

    private static void drawSquare(World world, int cx, double y, int cz, int r, int step, Particle particle) {
        int minX = cx - r, maxX = cx + r;
        int minZ = cz - r, maxZ = cz + r;

        for (int x = minX; x <= maxX; x += step) {
            world.spawnParticle(particle, x + 0.5, y, minZ + 0.5, 1, 0, 0, 0, 0);
            world.spawnParticle(particle, x + 0.5, y, maxZ + 0.5, 1, 0, 0, 0, 0);
        }
        for (int z = minZ; z <= maxZ; z += step) {
            world.spawnParticle(particle, minX + 0.5, y, z + 0.5, 1, 0, 0, 0, 0);
            world.spawnParticle(particle, maxX + 0.5, y, z + 0.5, 1, 0, 0, 0, 0);
        }
    }
    private static void drawCube(World world, int cx, int cy, int cz, int r, int step, Particle particle, int height) {
        if (height < 0) height = 0;
        int dz = Math.max(1, step);
        for (int dy = -height; dy <= height; dy += dz) {
            double y = cy + dy + 0.2;
            drawSquare(world, cx, y, cz, r, step, particle);
        }
    }

    public static ArmorStand getOrCreateHolo(Location loc){
        String key = beaconKey(loc);
        UUID id = holoByBeacon.get(key);
        if (id != null){
            var ent = Bukkit.getEntity(id);
            if (ent instanceof ArmorStand && ent.isValid()){
                return (ArmorStand) ent;
            } else {
                holoByBeacon.remove(key);
            }
        }
        World w = loc.getWorld();
        if (w == null) return null;
        ArmorStand as = (ArmorStand) w.spawnEntity(loc.clone().add(0.5, 1.2, 0.5), EntityType.ARMOR_STAND);
        as.setInvisible(true);
        as.setMarker(true);
        as.setCollidable(false);
        as.setGravity(false);
        try { as.setSmall(true); } catch (Throwable ignored) {}
        as.setCustomNameVisible(true);
        holoByBeacon.put(key, as.getUniqueId());
        return as;
    }
    public static void removeHolo(Location loc){
        String key = beaconKey(loc);
        UUID id = holoByBeacon.remove(key);
        if (id != null){
            var e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    public static void scheduleBeaconOutline(FermerPetsModule plugin, UUID owner, Location beaconLoc) {
        if (beaconLoc == null || beaconLoc.getWorld() == null) return;
        if (!plugin.getConfig().getBoolean("visual.outline.enabled", true)) return;

        // cancel previous for this beacon
        BukkitTask prev = tasksByBeacon.remove(beaconKey(beaconLoc));
        if (prev != null) prev.cancel();

        // play activation sound
        try { beaconLoc.getWorld().playSound(beaconLoc, org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f); } catch (Throwable ignored) {}

        World world = beaconLoc.getWorld();
        final int yBase = beaconLoc.getBlockY();
        final int cx = beaconLoc.getBlockX();
        final int cz = beaconLoc.getBlockZ();

        int period = Math.max(5, plugin.getConfig().getInt("visual.outline.tickPeriod", 40));
        int step   = Math.max(1, plugin.getConfig().getInt("visual.outline.step", 4));
        final ConfigurationSection particlesSec = plugin.getConfig().getConfigurationSection("visual.outline.particles");

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin.plugin(), () -> {
            // purely visual; don't waste TPS when nobody is online
            if (org.bukkit.Bukkit.getOnlinePlayers().isEmpty()) return;
            // chunk loaded check
            if (world != null) {
                int _cx = beaconLoc.getBlockX() >> 4;
                int _cz = beaconLoc.getBlockZ() >> 4;
                if (!world.isChunkLoaded(_cx, _cz)) return;
            }
            // check block type still valid (END_ROD or BEACON)
            Material t = beaconLoc.getBlock().getType();
            if (t != Material.END_ROD && t != Material.BEACON) {
                stopForBeacon(beaconLoc);
                return;
            }

            int farmerIndex = resolveFarmerIndex(plugin, owner, beaconLoc);
            int amethysts = new com.fermerpets.PlayersHoppersStore(plugin).getFuel(owner, farmerIndex);
            int r = radiusForFuel(plugin.plugin(), amethysts);

            Particle particle = Particle.FLAME;
            if (particlesSec != null) {
                String name = particlesSec.getString(String.valueOf(r));
                if (name != null) {
                    try { particle = Particle.valueOf(name.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException ignored) {}
                }
            }
            int height = plugin.getConfig().getInt("visual.outline.height", r);

            // Hologram + local 4 particles
            try {
                ArmorStand holo = getOrCreateHolo(beaconLoc);
                if (holo != null){
                    String text = "§dАметисты: " + amethysts + " §7Радиус: §6" + r;
                    holo.teleport(beaconLoc.clone().add(0.5, 1.2, 0.5));
                    holo.setCustomName(text);
                }
                double d = 0.1;
                double py = yBase + 0.8;
                world.spawnParticle(particle, cx + 0.5 + d, py, cz + 0.5, 4, 0,0,0,0);
                world.spawnParticle(particle, cx + 0.5 - d, py, cz + 0.5, 4, 0,0,0,0);
                world.spawnParticle(particle, cx + 0.5, py, cz + 0.5 + d, 4, 0,0,0,0);
                world.spawnParticle(particle, cx + 0.5, py, cz + 0.5 - d, 4, 0,0,0,0);
            } catch (Throwable ignored) {}

            drawCube(world, cx, yBase, cz, r, step, particle, height);
        }, 20L, period);

        tasksByBeacon.put(beaconKey(beaconLoc), task);
    }

    public static void stopForOwner(java.util.UUID owner) {
        java.util.List<String> keys = new java.util.ArrayList<>(tasksByBeacon.keySet());
        for (String k : keys){
            BukkitTask t = tasksByBeacon.remove(k);
            if (t!=null) t.cancel();
            // remove holo if we can parse key
            try {
                String[] parts = k.split(":");
                if (parts.length == 4) {
                    java.util.UUID worldId = java.util.UUID.fromString(parts[0]);
                    org.bukkit.World w = org.bukkit.Bukkit.getWorld(worldId);
                    if (w != null){
                        int bx = Integer.parseInt(parts[1]);
                        int by = Integer.parseInt(parts[2]);
                        int bz = Integer.parseInt(parts[3]);
                        org.bukkit.Location l = new org.bukkit.Location(w, bx, by, bz);
                        removeHolo(l);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void stopForBeacon(Location beaconLoc){
        BukkitTask prev = tasksByBeacon.remove(beaconKey(beaconLoc));
        if (prev != null) prev.cancel();
        try { removeHolo(beaconLoc); } catch (Throwable ignored) {}
    }






public static void shutdownAll(){
    try {
        java.util.List<String> keys = new java.util.ArrayList<>(holoByBeacon.keySet());
        for (String k : keys){
            try {
                String[] parts = k.split(":");
                if (parts.length == 4) {
                    java.util.UUID worldId = java.util.UUID.fromString(parts[0]);
                    org.bukkit.World w = org.bukkit.Bukkit.getWorld(worldId);
                    if (w != null){
                        int bx = Integer.parseInt(parts[1]);
                        int by = Integer.parseInt(parts[2]);
                        int bz = Integer.parseInt(parts[3]);
                        org.bukkit.Location l = new org.bukkit.Location(w, bx, by, bz);
                        removeHolo(l);
                    }
                }
            } catch (Throwable ignored){}
        }
        for (org.bukkit.scheduler.BukkitTask t : tasksByBeacon.values()){
            try { t.cancel(); } catch (Throwable ignored){}
        }
        tasksByBeacon.clear();
        holoByBeacon.clear();
    } catch (Throwable ignored){}
}
}
