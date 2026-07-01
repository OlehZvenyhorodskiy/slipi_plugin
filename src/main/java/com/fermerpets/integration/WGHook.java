package com.fermerpets.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.entity.Player;

import java.util.Set;

public final class WGHook {
    private WGHook() {}

    /**
     * Returns true if player is inside a region whose id is whitelisted in allowedIds.
     * Special case: "__global__" means whole world is allowed (no region membership required).
     */
    public static boolean isAllowedForOwnerOrMember(Player player, Set<String> allowedIds){
        try {
            if (player == null) return true;
            if (allowedIds == null || allowedIds.isEmpty()) return true; // nothing configured -> allow
            // Normalize allow-list to lowercase and handle __global__
            java.util.Set<String> allows = new java.util.HashSet<>();
            for (String s : allowedIds){ if (s != null) allows.add(s.toLowerCase()); }
            if (allows.contains("__global__")) return true;
            var loc = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player.getLocation());
            var container = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
            var query = container.createQuery();
            var set = query.getApplicableRegions(loc);
            if (set == null) return false;
            for (var r : set){
                String id = r.getId();
                if (id != null && allows.contains(id.toLowerCase())) return true;
            }
            return false;
        } catch (Throwable t){
            // if WG missing/broken — do not block gameplay
            return true;
        }
    }
}
