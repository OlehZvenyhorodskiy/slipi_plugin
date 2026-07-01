package com.fermerpets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ChunkPinService {

    private final Plugin plugin;
    private BukkitTask repinTask;

    public ChunkPinService(Plugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        // reserved for future use
    }

    public void scheduleRepinTask() {
        if (repinTask != null) return;
        // "plugin" here is already the Bukkit Plugin instance (host).
        repinTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // no-op hook for future consistency checks
        }, 20L * 30, 20L * 30);
    }

    public void pinChunkAt(Location loc) {
        if (!isForcePinningEnabled()) return;
        if (loc == null) return;
        World w = loc.getWorld();
        if (w == null) return;
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        w.getChunkAt(cx, cz);
        try { w.addPluginChunkTicket(cx, cz, plugin); } catch (Throwable ignore) {}
        try { w.setChunkForceLoaded(cx, cz, true); } catch (Throwable ignore) {}
    }

    public void unpinChunkAt(Location loc) {
        if (loc == null) return;
        World w = loc.getWorld();
        if (w == null) return;
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        try { w.removePluginChunkTicket(cx, cz, plugin); } catch (Throwable ignore) {}
        try { w.setChunkForceLoaded(cx, cz, false); } catch (Throwable ignore) {}
    }

    public Set<Long> pinArea(Location center, int r) {
        Set<Long> pinned = new HashSet<>();
        if (!isForcePinningEnabled()) return pinned;
        if (center == null) return pinned;
        World w = center.getWorld();
        if (w == null) return pinned;
        int ccx = center.getBlockX() >> 4;
        int ccz = center.getBlockZ() >> 4;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = ccx + dx, z = ccz + dz;
                w.getChunkAt(x, z);
                try { w.addPluginChunkTicket(x, z, plugin); } catch (Throwable ignore) {}
                try { w.setChunkForceLoaded(x, z, true); } catch (Throwable ignore) {}
                long key = (((long)x) << 32) ^ (z & 0xffffffffL);
                pinned.add(key);
            }
        }
        return pinned;
    }

    public void unpinArea(Location center, int r) {
        if (center == null) return;
        World w = center.getWorld();
        if (w == null) return;
        int ccx = center.getBlockX() >> 4;
        int ccz = center.getBlockZ() >> 4;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = ccx + dx, z = ccz + dz;
                try { w.removePluginChunkTicket(x, z, plugin); } catch (Throwable ignore) {}
                try { w.setChunkForceLoaded(x, z, false); } catch (Throwable ignore) {}
            }
        }
    }

    public void clearAll() {
        for (World w : Bukkit.getWorlds()) {
            try { w.removePluginChunkTickets(plugin); } catch (Throwable ignore) {}
        }
    }

    /**
     * Whether FermerPets is allowed to FORCE-load chunks via plugin tickets.
     * Default is false (safer for performance).
     */
    public static boolean isForcePinningEnabled(){
        try {
            FermerPetsModule m = FermerPetsModule.get();
            if (m == null) return false;
            return m.getConfig().getBoolean("chunks.force_loaded", false);
        } catch (Throwable ignored){
            return false;
        }
    }

    // Legacy static shims
    @Deprecated
    public static void updatePinsForOwner(Plugin pluginParam, UUID ownerId, boolean active) {
        if (ownerId == null || pluginParam == null) return;
        try {
            FermerPetsModule m = FermerPetsModule.get();
            if (m == null) return;

            boolean force = isForcePinningEnabled();
            boolean keepOffline = m.getConfig().getBoolean("chunks.keep_loaded_offline", false);
            boolean shouldKeep = active || keepOffline;

            PlayersHoppersStore store = new PlayersHoppersStore(m);

            // Collect all known locations for this owner (global hopper + per-index beacons/hoppers)
            java.util.ArrayList<Location> locs = new java.util.ArrayList<>();
            try {
                PlayersHoppersStore.Record gh = store.getGlobalHopperForOwner(ownerId);
                if (gh != null && gh.loc != null) locs.add(gh.loc);
            } catch (Throwable ignored) {}
            for (int i = 1; i <= 3; i++){
                try {
                    PlayersHoppersStore.Record h = store.getHopper(ownerId, i);
                    if (h != null && h.loc != null) locs.add(h.loc);
                } catch (Throwable ignored) {}
                try {
                    PlayersHoppersStore.Record b = store.getBeacon(ownerId, i);
                    if (b != null && b.loc != null) locs.add(b.loc);
                } catch (Throwable ignored) {}
            }

            ChunkPinService cps = new ChunkPinService(pluginParam);

            // If force pinning is disabled, we only ever UNPIN to make sure nothing stays forced.
            if (!force || !shouldKeep){
                for (Location l : locs){
                    try { cps.unpinArea(l, 1); } catch (Throwable ignored) {}
                }
                return;
            }

            // Force pinning enabled and we should keep chunks loaded now.
            for (Location l : locs){
                try { cps.pinArea(l, 1); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @Deprecated
    public static void pinChunkAt(Plugin pluginParam, Location loc, boolean includeNeighbors) {
        ChunkPinService temp = new ChunkPinService(pluginParam);
        if (includeNeighbors) temp.pinArea(loc, 1); else temp.pinChunkAt(loc);
    }
}
