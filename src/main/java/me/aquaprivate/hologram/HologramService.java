package me.aquaprivate.hologram;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.HologramSettings;
import me.aquaprivate.model.PrivateBlockType;
import me.aquaprivate.model.PrivateRecord;
import me.aquaprivate.util.PlaceholderUtil;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class HologramService {
    private static final String TAG_BASE = "aquaprivate_holo";
    private static final String TAG_PREFIX = "ap:"; // ap:<regionId>

    private final AquaPrivatePlugin plugin;

    public HologramService(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

        private static boolean isChunkLoaded(Location loc) {
        if (loc == null) return false;
        World w = loc.getWorld();
        if (w == null) return false;
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        return w.isChunkLoaded(cx, cz);
    }

public void spawnFor(PrivateRecord r) {
        Location loc = r.toLocation();
        if (loc == null) return;
        if (!isChunkLoaded(loc)) return;

        PrivateBlockType type = plugin.resolveBlockType(r);
        if (type == null) return;
        HologramSettings hs = type.holograms;
        if (hs == null || !hs.enable || hs.lines.isEmpty()) return;

        removeFor(r); // avoid duplicates

        World w = loc.getWorld();
        if (w == null) return;
        // Never force-load chunks just to show holograms
        if (!isChunkLoaded(loc)) return;

        String ownerName = Bukkit.getOfflinePlayer(r.owner).getName();
        if (ownerName == null) ownerName = "Unknown";
        var ownerOffline = Bukkit.getOfflinePlayer(r.owner);

        // Raise hologram by one "text line" so it doesn't overlap visually.
        // One line step in our hologram stack is 0.25.
        double lineStep = 0.25;
        Location base = loc.clone().add(0.5, hs.height + lineStep, 0.5);
        List<UUID> spawned = new ArrayList<>();

        for (int i = 0; i < hs.lines.size(); i++) {
            String line = plugin.cfg().colorize(PlaceholderUtil.apply(plugin, r, hs.lines.get(i), ownerName, ownerOffline));

            Location l = base.clone().add(0.0, -(lineStep * i), 0.0);
            ArmorStand as = (ArmorStand) w.spawnEntity(l, EntityType.ARMOR_STAND);
            as.setVisible(false);
            as.setMarker(true);
            as.setGravity(false);
            as.setCustomName(line);
            as.setCustomNameVisible(true);
            as.setSmall(true);
            as.setInvulnerable(true);
            as.addScoreboardTag(TAG_BASE);
            as.addScoreboardTag(TAG_PREFIX + r.regionId.toLowerCase(Locale.ROOT));
            spawned.add(as.getUniqueId());
        }

        r.holograms.clear();
        r.holograms.addAll(spawned);
        plugin.store().save();
    }

    /**
     * Refresh hologram lines in-place (without re-spawning) when possible.
     * If armorstands are missing or line count changed, falls back to respawn.
     */
    public void refreshFor(PrivateRecord r) {
        if (r == null) return;
        Location loc = r.toLocation();
        if (loc == null) return;
        if (!isChunkLoaded(loc)) return;

        PrivateBlockType type = plugin.resolveBlockType(r);
        if (type == null) return;
        HologramSettings hs = type.holograms;
        if (hs == null || !hs.enable || hs.lines.isEmpty()) return;

        String ownerName = Bukkit.getOfflinePlayer(r.owner).getName();
        if (ownerName == null) ownerName = "Unknown";
        var ownerOffline = Bukkit.getOfflinePlayer(r.owner);

        // If stored holograms mismatch, just respawn.
        if (r.holograms == null || r.holograms.size() != hs.lines.size()) {
            spawnFor(r);
            return;
        }

        for (int i = 0; i < hs.lines.size(); i++) {
            UUID id = r.holograms.get(i);
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof ArmorStand as)) {
                spawnFor(r);
                return;
            }
            String line = plugin.cfg().colorize(PlaceholderUtil.apply(plugin, r, hs.lines.get(i), ownerName, ownerOffline));
            as.setCustomName(line);
            as.setCustomNameVisible(true);
        }
    }

    public void removeFor(PrivateRecord r) {
        Location loc = r.toLocation();
        World w = loc != null ? loc.getWorld() : Bukkit.getWorld(r.world);

        // Remove by stored UUIDs
        if (w != null) {
            for (UUID id : List.copyOf(r.holograms)) {
                Entity e = Bukkit.getEntity(id);
                if (e != null) e.remove();
            }
        }

        // Fallback: remove nearby tagged armorstands (works even if UUID list is stale)
        if (loc != null && w != null && isChunkLoaded(loc)) {
            String tag = TAG_PREFIX + r.regionId.toLowerCase(Locale.ROOT);
            for (Entity e : w.getNearbyEntities(loc.clone().add(0.5, 1.5, 0.5), 1.0, 5.0, 1.0)) {
                if (e.getType() != EntityType.ARMOR_STAND) continue;
                if (!e.getScoreboardTags().contains(TAG_BASE)) continue;
                if (!e.getScoreboardTags().contains(tag)) continue;
                e.remove();
            }
        }

        if (!r.holograms.isEmpty()) {
            r.holograms.clear();
            plugin.store().save();
        }
    }

    /** Re-spawn holograms for all stored privates (useful on server start/reload). */
    public void respawnAll() {
        for (PrivateRecord r : plugin.store().all()) {
            Location loc = r.toLocation();
            if (loc == null) continue;
            if (!isChunkLoaded(loc)) continue;
            spawnFor(r);
        }
    }

    /** Remove all holograms (useful on disable to prevent duplicates on reload). */
    public void removeAll() {
        for (PrivateRecord r : plugin.store().all()) {
            removeFor(r);
        }
    }

    /**
     * Hard cleanup: remove *all currently-loaded* hologram armorstands in every world.
     *
     * This is required to avoid "ghost" holograms after /reload, because chunks may be
     * unloaded during disable and the armorstands get saved in the world.
     */
    public int purgeAllLoaded() {
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            try {
                for (ArmorStand as : w.getEntitiesByClass(ArmorStand.class)) {
                    if (as.getScoreboardTags().contains(TAG_BASE)) {
                        as.remove();
                        removed++;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return removed;
    }

    /** Remove all hologram armorstands in a just-loaded chunk (fixes ghosts on chunk load). */
    public int purgeChunk(Chunk chunk) {
        if (chunk == null) return 0;
        int removed = 0;
        try {
            for (Entity e : chunk.getEntities()) {
                if (e.getType() != EntityType.ARMOR_STAND) continue;
                if (!e.getScoreboardTags().contains(TAG_BASE)) continue;
                e.remove();
                removed++;
            }
        } catch (Throwable ignored) {
        }
        return removed;
    }

    public static String holoTagBase() {
        return TAG_BASE;
    }
}
