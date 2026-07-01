package me.aquaprivate.listener;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Fixes "ghost" holograms that can remain after /reload and later appear when a chunk is loaded.
 *
 * Strategy:
 * 1) On every chunk load, remove any ArmorStand with our hologram tag from that chunk.
 * 2) Then re-spawn holograms for privates that belong to this chunk (if holograms are enabled).
 */
public final class HologramChunkListener implements Listener {
    private final AquaPrivatePlugin plugin;

    public HologramChunkListener(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        final Chunk chunk = event.getChunk();
        if (chunk == null) return;

        // 1) Purge any old holo armorstands saved in this chunk
        plugin.holograms().purgeChunk(chunk);

        // 2) Re-spawn for privates that are inside this chunk (next tick)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            World w = chunk.getWorld();
            int cx = chunk.getX();
            int cz = chunk.getZ();

            for (PrivateRecord r : plugin.store().all()) {
                Location loc = r.toLocation();
                if (loc == null) continue;
                if (loc.getWorld() == null) continue;
                if (!loc.getWorld().getUID().equals(w.getUID())) continue;
                if ((loc.getBlockX() >> 4) != cx) continue;
                if ((loc.getBlockZ() >> 4) != cz) continue;
                plugin.holograms().spawnFor(r);
            }
        });
    }
}
