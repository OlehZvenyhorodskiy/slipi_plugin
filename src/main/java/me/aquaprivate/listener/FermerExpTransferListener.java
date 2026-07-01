package me.aquaprivate.listener;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Siphons EXP LEVELS from a player into a private while farmer activation payment is running.
 */
public final class FermerExpTransferListener implements Listener {

    private final AquaPrivatePlugin plugin;

    /**
     * Guard against re-entrancy: when we remove levels ourselves, Bukkit fires PlayerLevelChangeEvent again.
     */
    private final Set<UUID> draining = ConcurrentHashMap.newKeySet();

    public FermerExpTransferListener(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        if (e.getAmount() <= 0) return;
        // Run after exp is applied
        Bukkit.getScheduler().runTask(plugin, () -> drainIfNeeded(p));
    }

    /**
     * Commands like /xp set/add levels may NOT fire PlayerExpChangeEvent. Track level changes too.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelChange(PlayerLevelChangeEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        if (draining.contains(p.getUniqueId())) return;
        // Only interesting when level goes up
        if (e.getNewLevel() <= e.getOldLevel()) return;
        Bukkit.getScheduler().runTask(plugin, () -> drainIfNeeded(p));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> drainIfNeeded(p));
    }

    private void drainIfNeeded(Player p) {
        if (p == null || !p.isOnline()) return;
        UUID owner = p.getUniqueId();
        List<PrivateRecord> privs = plugin.store().byOwner(owner);
        if (privs.isEmpty()) return;

        // We expect at most one active siphon per owner; if multiple exist, drain the first.
        for (PrivateRecord rec : privs) {
            // Guardian payment siphon has priority.
            if (rec.guardExpTransferEnabled) {
                final int needLevels = plugin.getConfig().getInt("settings.guard.required-levels", 100);
                if (rec.guardExpLevels >= needLevels) {
                    rec.guardExpTransferEnabled = false;
                    plugin.store().save();
                    return;
                }

                int remaining = needLevels - Math.max(0, rec.guardExpLevels);
                if (remaining <= 0) {
                    rec.guardExpLevels = needLevels;
                    rec.guardExpTransferEnabled = false;
                    plugin.store().save();
                    return;
                }

                int lvl = p.getLevel();
                if (lvl <= 0) return;
                int take = Math.min(lvl, remaining);
                if (take <= 0) return;

                try {
                    draining.add(p.getUniqueId());
                    p.giveExpLevels(-take);
                } catch (Throwable t) {
                    p.setLevel(Math.max(0, p.getLevel() - take));
                } finally {
                    Bukkit.getScheduler().runTask(plugin, () -> draining.remove(p.getUniqueId()));
                }

                rec.guardExpLevels = Math.min(needLevels, rec.guardExpLevels + take);
                if (rec.guardExpLevels >= needLevels) {
                    rec.guardExpLevels = needLevels;
                    rec.guardExpTransferEnabled = false;
                    p.sendMessage(plugin.cfg().msg("guard-exp-complete"));
                }

                plugin.store().save();
                try { plugin.holograms().refreshFor(rec); } catch (Throwable ignored) {}

                // Refresh private menu if open
                try {
                    if (p.getOpenInventory() != null && p.getOpenInventory().getTopInventory() != null
                            && plugin.menuPrivate().isMenu(p.getOpenInventory().getTopInventory())
                            && p.getOpenInventory().getTopInventory().getHolder() instanceof me.aquaprivate.menu.PrivateMenuHolder h
                            && h.regionId != null && h.regionId.equals(rec.regionId)) {
                        plugin.menuPrivate().open(p, rec);
                    }
                } catch (Throwable ignored) {}

                return;
            }

            if (!rec.farmerExpTransferEnabled) continue;
            if (rec.farmerExpLevels >= 100) {
                rec.farmerExpTransferEnabled = false;
                plugin.store().save();
                return;
            }

            int remaining = 100 - Math.max(0, rec.farmerExpLevels);
            if (remaining <= 0) {
                rec.farmerExpLevels = 100;
                rec.farmerExpTransferEnabled = false;
                plugin.store().save();
                return;
            }

            int lvl = p.getLevel();
            if (lvl <= 0) return;
            int take = Math.min(lvl, remaining);
            if (take <= 0) return;

            try {
                draining.add(p.getUniqueId());
                p.giveExpLevels(-take);
            } catch (Throwable t) {
                // Fallback (should not happen)
                p.setLevel(Math.max(0, p.getLevel() - take));
            } finally {
                // Remove guard next tick to avoid blocking normal future changes
                Bukkit.getScheduler().runTask(plugin, () -> draining.remove(p.getUniqueId()));
            }

            rec.farmerExpLevels = Math.min(100, rec.farmerExpLevels + take);

            if (rec.farmerExpLevels >= 100) {
                rec.farmerExpLevels = 100;
                rec.farmerExpTransferEnabled = false;
                p.sendMessage(plugin.cfg().msg("fermer-exp-complete"));
            }

            plugin.store().save();
            try { plugin.holograms().refreshFor(rec); } catch (Throwable ignored) {}

            // If player currently has the private menu open for this private, refresh it.
            try {
                if (p.getOpenInventory() != null && p.getOpenInventory().getTopInventory() != null
                        && plugin.menuPrivate().isMenu(p.getOpenInventory().getTopInventory())
                        && p.getOpenInventory().getTopInventory().getHolder() instanceof me.aquaprivate.menu.PrivateMenuHolder h
                        && h.regionId != null && h.regionId.equals(rec.regionId)) {
                    plugin.menuPrivate().open(p, rec);
                }
            } catch (Throwable ignored) {}

            return; // only one private drains per tick
        }
    }
}
