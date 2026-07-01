package com.example.guardianparrot.integration;


import org.bukkit.persistence.PersistentDataType;
import com.example.guardianparrot.GuardianParrotRuntime;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.Optional;

/** WorldEdit helper utilities (7.2.x). */
public final class WEHook {
    private static boolean available = false;

    public static void init() {
        try {
            if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) {
                GuardianParrotRuntime.get().getLogger().info("[WEHook] WorldEdit not found, skipping.");
                return;
            }
            available = true;
            GuardianParrotRuntime.get().getLogger().info("[WEHook] WorldEdit hook initialized.");
        } catch (Throwable t) {
            available = false;
            GuardianParrotRuntime.get().getLogger().warning("[WEHook] Failed to init: " + t.getMessage());
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    /** Get player's current WE selection as a Bukkit BoundingBox (if it's cuboid). */
    public static Optional<BoundingBox> getPlayerSelectionBox(Player player) {
        if (!available) return Optional.empty();
        try {
            WorldEditPlugin we = (WorldEditPlugin) Bukkit.getPluginManager().getPlugin("WorldEdit");
            var local = we.getSession(player);
            Region region = local.getSelection(BukkitAdapter.adapt(player.getWorld()));
            if (region == null) return Optional.empty();
            if (!(region instanceof CuboidRegion cr)) return Optional.empty();

            BlockVector3 min = cr.getMinimumPoint();
            BlockVector3 max = cr.getMaximumPoint();
            return Optional.of(new BoundingBox(min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Center of player's WE selection if present. */
    public static Optional<Location> getPlayerSelectionCenter(Player player) {
        return getPlayerSelectionBox(player).map(box -> box.getCenter().toLocation(player.getWorld()));
    }
}
