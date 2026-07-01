package com.fermerpets;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FlightLanding {
    private FlightLanding(){}
    private static final ConcurrentHashMap<UUID, Long> flyStart = new ConcurrentHashMap<>();
    private static final Set<UUID> flyLanding = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int MAX_FLY_TICKS = 12;
    private static final double WATER_UP_VY = 0.40;

    public static void manage(LivingEntity pet, Location target){
        
        
        // RIM-CLIMB: if standing in water but just at surface, nudge upward and toward nearest solid rim
        try {
            org.bukkit.Location here = pet.getLocation();
            org.bukkit.block.Block feet = here.getBlock();
            if (feet.isLiquid()){
                // look for solid neighbor at same XZ and y+1 clearance to climb onto
                org.bukkit.World w = here.getWorld();
                int x = here.getBlockX(), y = here.getBlockY(), z = here.getBlockZ();
                org.bukkit.Location best = null; double bestd2 = Double.MAX_VALUE;
                int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : dirs){
                    int nx = x + d[0], nz = z + d[1];
                    org.bukkit.block.Block side = w.getBlockAt(nx, y, nz);
                    org.bukkit.block.Block head = w.getBlockAt(nx, y+1, nz);
                    if (!side.isLiquid() && side.getType().isSolid() && head.getType().isAir()){
                        org.bukkit.Location cand = new org.bukkit.Location(w, nx + 0.5, y + 1.2, nz + 0.5);
                        double d2 = cand.distanceSquared(here);
                        if (d2 < bestd2){ bestd2 = d2; best = cand; }
                    }
                }
                if (best != null){
                    org.bukkit.util.Vector dir = best.toVector().subtract(here.toVector());
                    dir.setY(0.42); // step-up boost
                    pet.setGravity(false);
                    pet.setVelocity(dir.multiply(0.35));
                    return;
                }
            }
        } catch (Throwable ignored) {}
        // SUPPRESS landing during balanced water-escape
        try {
            if (pet.getEyeLocation().getBlock().isLiquid()) return;
            if (com.fermerpets.HarvestAI.isWaterEscapeOn(pet.getUniqueId())) return;
        } catch (Throwable ignored) {}
        try {
            if (pet == null || !pet.isValid()) return;
            UUID id = pet.getUniqueId();
            boolean hasGrav;
            try { hasGrav = pet.hasGravity(); } catch (Throwable t){ hasGrav = true; }
            if (!hasGrav){
                long nowTicks = pet.getWorld().getFullTime();
                flyStart.putIfAbsent(id, nowTicks);
                Long started = flyStart.get(id);
                boolean inWater = false; try { inWater = pet.isInWater(); } catch (Throwable ignored) {}
                int timeout = inWater ? 6 : MAX_FLY_TICKS;
                boolean timeExceeded = (started != null) && (nowTicks - started > timeout);
                Location pl = pet.getLocation();
                double tx = (target!=null? target.getX() : pl.getX());
                double tz = (target!=null? target.getZ() : pl.getZ());
                double horiz = Math.hypot(tx - pl.getX(), tz - pl.getZ());
                // assist in water: if head under, give small upward velocity
                try {
                    if (inWater && pl.clone().add(0, pet.getEyeHeight(), 0).getBlock().isLiquid()){
                        org.bukkit.util.Vector vv = pet.getVelocity();
                        if (vv.getY() < WATER_UP_VY) { vv.setY(WATER_UP_VY); pet.setVelocity(vv); }
                    }
                } catch (Throwable ignored) {}
                if (!flyLanding.contains(id) && (horiz < 0.9 || timeExceeded)){
                    flyLanding.add(id);
                    try { pet.setGravity(true); } catch (Throwable ignored){}
                    Vector v = pet.getVelocity();
                    if (v.getY() > -0.9) v.setY(-0.9);
                    pet.setVelocity(v);
                    com.fermerpets.Debug.logPet(pet, "LANDING: start " + (timeExceeded? "timeout":"overpad") + " vY=" + v.getY());
                } else if (!flyLanding.contains(id)){
                    double ty = (target!=null? target.getY() : pl.getY());
                    double dy = pl.getY() - (ty + 1.0);
                    if (dy > 1.5){
                        Vector v = pet.getVelocity();
                        if (v.getY() > -0.12){ v.setY(-0.12); pet.setVelocity(v); }
                    }
                }
            } else {
                if (flyLanding.contains(id)){
                    Location below = pet.getLocation().clone().add(0, -0.6, 0);
                    boolean solidBelow = false;
                    try { solidBelow = below.getBlock().getType().isSolid(); } catch (Throwable ignored){}
                    if (solidBelow){
                        org.bukkit.util.Vector v = pet.getVelocity();
                        if (v.getY() > -0.9) { v.setY(-0.9); pet.setVelocity(v); }
                        flyStart.remove(id); flyLanding.remove(id);
                        com.fermerpets.Debug.logPet(pet, "LANDING: complete");
                    }
                } else {
                    flyStart.remove(id); flyLanding.remove(id);
                }
            }
        } catch (Throwable ignored){}
    }
}
