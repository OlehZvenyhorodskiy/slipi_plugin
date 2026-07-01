package me.aquaprivate.listener;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.List;
import java.util.Optional;

/**
 * Prevents pistons from moving blocks across private region boundaries.
 * This protects against:
 * - Pushing blocks INTO a protected claim from outside
 * - Pulling blocks OUT of a protected claim to outside
 * - Moving the private core marker block
 */
public final class PistonPrivateProtectionListener implements Listener {

    private final AquaPrivatePlugin plugin;

    public PistonPrivateProtectionListener(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent e) {
        Block piston = e.getBlock();
        BlockFace direction = e.getDirection();
        List<Block> movedBlocks = e.getBlocks();

        // If no blocks are being moved, nothing to protect against
        if (movedBlocks.isEmpty()) return;

        for (Block b : movedBlocks) {
            // Check if the block being moved is the private core
            if (isPrivateCore(b.getLocation())) {
                e.setCancelled(true);
                return;
            }

            // Calculate where this block will be after the push
            Location fromLoc = b.getLocation();
            Location toLoc = b.getRelative(direction).getLocation();

            // Check if this move crosses a claim boundary
            if (crossesClaimBoundary(fromLoc, toLoc)) {
                e.setCancelled(true);
                return;
            }
        }

        // Also check the front-most empty space that blocks will be pushed into
        // (prevents pushing blocks into a claim through a chain)
        if (!movedBlocks.isEmpty()) {
            Block frontBlock = piston.getRelative(direction);
            // The block immediately in front of the piston (where the first block goes)
            // If this location is inside a claim different from where blocks came from, block it
            Optional<PrivateRecord> frontPrivate = getPrivateAt(frontBlock.getLocation());
            if (frontPrivate.isPresent()) {
                // Check if ANY moved block is from outside this claim
                PrivateRecord targetClaim = frontPrivate.get();
                for (Block b : movedBlocks) {
                    Optional<PrivateRecord> sourcePrivate = getPrivateAt(b.getLocation());
                    // If source block is NOT in the same claim as the destination
                    if (sourcePrivate.isEmpty() || !sourcePrivate.get().regionId.equals(targetClaim.regionId)) {
                        e.setCancelled(true);
                        return;
                    }
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent e) {
        Block piston = e.getBlock();
        BlockFace direction = e.getDirection();
        List<Block> movedBlocks = e.getBlocks();

        // If no blocks are being moved, nothing to protect against
        if (movedBlocks.isEmpty()) return;

        for (Block b : movedBlocks) {
            // Check if the block being moved is the private core
            if (isPrivateCore(b.getLocation())) {
                e.setCancelled(true);
                return;
            }

            // Calculate where this block will be after the pull
            // Direction in retract event points toward the piston (where blocks go)
            Location fromLoc = b.getLocation();
            Location toLoc = b.getRelative(direction).getLocation();

            // Check if this move crosses a claim boundary
            if (crossesClaimBoundary(fromLoc, toLoc)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Returns true if moving a block from 'from' to 'to' crosses a private claim boundary
     * without permission. Specifically:
     * - Moving FROM outside a claim INTO a claim = blocked
     * - Moving FROM inside a claim TO outside = blocked
     * - Moving within the same claim = allowed
     * - Moving outside any claims = allowed
     */
    private boolean crossesClaimBoundary(Location from, Location to) {
        Optional<PrivateRecord> fromPrivate = getPrivateAt(from);
        Optional<PrivateRecord> toPrivate = getPrivateAt(to);

        // From outside, to inside a claim -> blocked
        if (fromPrivate.isEmpty() && toPrivate.isPresent()) return true;

        // From inside a claim, to outside -> blocked
        if (fromPrivate.isPresent() && toPrivate.isEmpty()) return true;

        // From one claim to a DIFFERENT claim -> blocked
        if (fromPrivate.isPresent() && toPrivate.isPresent()) {
            return !fromPrivate.get().regionId.equals(toPrivate.get().regionId);
        }

        // Both outside claims -> allowed
        return false;
    }

    private boolean isPrivateCore(Location loc) {
        if (loc == null) return false;
        if (loc.getWorld() == null) return false;
        Optional<PrivateRecord> rec = plugin.store().byLocation(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return rec.isPresent();
    }

    private Optional<PrivateRecord> getPrivateAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return Optional.empty();
        try {
            var rm = plugin.wg().regionManager(loc.getWorld());
            if (rm == null) return Optional.empty();
            var set = rm.getApplicableRegions(
                    com.sk89q.worldedit.math.BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            for (var pr : set) {
                Optional<PrivateRecord> r = plugin.store().byRegionId(pr.getId());
                if (r.isPresent()) return r;
            }
        } catch (Throwable ignored) {}
        return Optional.empty();
    }
}
