package me.aquaprivate.listener;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.Optional;

/**
 * Prevents moving the "private core" block with pistons.
 * Other blocks inside the region can still be pushed/pulled.
 */
public final class PistonPrivateProtectionListener implements Listener {

    private final AquaPrivatePlugin plugin;

    public PistonPrivateProtectionListener(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (isPrivateCore(b.getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (isPrivateCore(b.getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    private boolean isPrivateCore(Location loc) {
        if (loc == null) return false;
        if (loc.getWorld() == null) return false;
        Optional<PrivateRecord> rec = plugin.store().byLocation(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return rec.isPresent();
    }
}
