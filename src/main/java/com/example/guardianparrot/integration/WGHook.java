package com.example.guardianparrot.integration;


import org.bukkit.persistence.PersistentDataType;
import com.example.guardianparrot.GuardianParrotRuntime;
import com.sk89q.worldedit.util.Location;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class WGHook {
    public static final String FLAG_KEY = "guardian-parrot-allow";
    public static StateFlag GUARDIAN_PARROT_ALLOW = new StateFlag(FLAG_KEY, true);

    private static boolean available = false;

    public static void init() {
        try {
            if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
                GuardianParrotRuntime.get().getLogger().info("[WGHook] WorldGuard not found, skipping.");
                return;
            }
            // Register custom flag if not already present
            var registry = WorldGuard.getInstance().getFlagRegistry();
            try {
                registry.register(GUARDIAN_PARROT_ALLOW);
            } catch (IllegalStateException ignored) {
                // Already registered by reload or another plugin
                var existing = registry.get(FLAG_KEY);
                if (existing instanceof StateFlag ef) {
                    GUARDIAN_PARROT_ALLOW = ef;
                }
            }
            available = true;
            GuardianParrotRuntime.get().getLogger().info("[WGHook] WorldGuard hook initialized.");
        } catch (Throwable t) {
            GuardianParrotRuntime.get().getLogger().warning("[WGHook] Failed to init: " + t.getMessage());
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    public static boolean canParrotAct(Player player) {
        if (!available) return true;
        var loc = BukkitAdapter.adapt(player.getLocation());
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();
        ApplicableRegionSet set = query.getApplicableRegions(loc);
        var lp = WorldGuardPlugin.inst().wrapPlayer(player);
        // Allow if region flag explicitly ALLOW; otherwise fallback to BUILD permission
        com.sk89q.worldguard.protection.flags.StateFlag.State flag = set.queryState(lp, GUARDIAN_PARROT_ALLOW);
        if (flag != null) {
            return flag == com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW;
        }
        // Fallback: respect default build flag
        com.sk89q.worldguard.protection.flags.StateFlag.State build = set.queryState(lp, Flags.BLOCK_PLACE);
        return build == null || build == com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW;
    }
}
