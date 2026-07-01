package com.fermerpets.integration;

import org.bukkit.entity.Player;

public final class WEHook {
    public static org.bukkit.Location getSelectionCenter(Player p){
        try {
            Class<?> weBukkit = Class.forName("com.sk89q.worldedit.bukkit.WorldEditPlugin");
            org.bukkit.plugin.Plugin we = org.bukkit.Bukkit.getPluginManager().getPlugin("WorldEdit");
            if (we == null || !we.isEnabled() || !weBukkit.isInstance(we)) return null;
            Object sel = weBukkit.getMethod("getSelection", org.bukkit.entity.Player.class).invoke(we, p);
            if (sel == null) return null;
            org.bukkit.World w = (org.bukkit.World) sel.getClass().getMethod("getWorld").invoke(sel);
            com.sk89q.worldedit.math.BlockVector3 min = (com.sk89q.worldedit.math.BlockVector3) sel.getClass().getMethod("getMinimumPoint").invoke(sel);
            com.sk89q.worldedit.math.BlockVector3 max = (com.sk89q.worldedit.math.BlockVector3) sel.getClass().getMethod("getMaximumPoint").invoke(sel);
            double cx = (min.getX() + max.getX())/2.0;
            double cy = (min.getY() + max.getY())/2.0;
            double cz = (min.getZ() + max.getZ())/2.0;
            return new org.bukkit.Location(w, cx+0.5, cy, cz+0.5);
        } catch (Throwable ignored){
            return null;
        }
    }
}
