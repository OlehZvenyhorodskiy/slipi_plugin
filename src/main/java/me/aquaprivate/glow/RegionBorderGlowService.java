package me.aquaprivate.glow;

import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws particles along WorldGuard region borders for a private.
 *
 * Important performance note:
 * We intentionally DO NOT schedule one BukkitTask per region.
 * When you have many privates this becomes thousands of repeating tasks.
 * Instead we run a single global task and batch through records.
 */
public final class RegionBorderGlowService {

    private final AquaPrivatePlugin plugin;

    // Enabled region ids (border glow toggled in menu)
    private final Set<String> enabled = new HashSet<>();

    // Single global renderer task
    private BukkitTask task;
    private int cursor = 0;

    public RegionBorderGlowService(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    public void shutdown() {
        if (task != null) {
            try { task.cancel(); } catch (Throwable ignored) {}
            task = null;
        }
        enabled.clear();
        cursor = 0;
    }

    public void respawnAll() {
        enabled.clear();
        cursor = 0;
        for (PrivateRecord r : plugin.store().all()) {
            if (r == null || r.regionId == null) continue;
            if (!r.borderGlow) continue;
            enabled.add(r.regionId);
        }
        ensureTask();
    }

    public void enable(PrivateRecord r) {
        if (r == null || r.regionId == null) return;
        enabled.add(r.regionId);
        ensureTask();
    }

    public void disable(PrivateRecord r) {
        if (r == null || r.regionId == null) return;
        enabled.remove(r.regionId);
        if (enabled.isEmpty() && task != null) {
            try { task.cancel(); } catch (Throwable ignored) {}
            task = null;
        }
    }

    private void ensureTask() {
        if (task != null) return;
        // Render once per second (20t). Start slightly delayed so other plugins can initialize.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 20L);
    }

    private void tick() {
        try {
            // Purely visual. When nobody is online, do nothing.
            if (Bukkit.getOnlinePlayers().isEmpty()) return;
            if (enabled.isEmpty()) return;

            List<PrivateRecord> all = new ArrayList<>(plugin.store().all());
            if (all.isEmpty()) return;

            int perRun = Math.max(1, plugin.getConfig().getInt("settings.borderGlow.per-run", 40));
            if (cursor >= all.size()) cursor = 0;

            for (int i = 0; i < perRun; i++) {
                if (cursor >= all.size()) cursor = 0;
                PrivateRecord r = all.get(cursor++);
                if (r == null || r.regionId == null) continue;
                if (!r.borderGlow) continue;
                if (!enabled.contains(r.regionId)) continue;
                drawOnce(r);
            }
        } catch (Throwable ignored) {
        }
    }

    private void drawOnce(PrivateRecord r) {
        Location marker = r.toLocation();
        if (marker == null || marker.getWorld() == null) return;

        World world = marker.getWorld();
        RegionManager rm = plugin.wg().regionManager(world);
        if (rm == null) return;
        ProtectedRegion pr = rm.getRegion(r.regionId);
        if (pr == null) return;

        // Only render when a player is near this private.
        boolean hasNear = false;
        for (Player p : world.getPlayers()) {
            if (p == null) continue;
            if (p.getLocation().distanceSquared(marker) <= 80 * 80) {
                hasNear = true;
                break;
            }
        }
        if (!hasNear) return;

        int minX = pr.getMinimumPoint().getBlockX();
        int maxX = pr.getMaximumPoint().getBlockX();
        int minY = pr.getMinimumPoint().getBlockY();
        int maxY = pr.getMaximumPoint().getBlockY();
        int minZ = pr.getMinimumPoint().getBlockZ();
        int maxZ = pr.getMaximumPoint().getBlockZ();

        Particle particle = Particle.FLAME;
        int step = 2;
        int stepY = 3;

        double bottomY = Math.max(minY, world.getMinHeight()) + 1.2;
        double topY = Math.min(maxY, world.getMaxHeight() - 1) + 1.2;
        drawPerimeter(world, particle, minX, maxX, minZ, maxZ, bottomY, step);
        if (Math.abs(topY - bottomY) >= 2.0) {
            drawPerimeter(world, particle, minX, maxX, minZ, maxZ, topY, step);
        }

        int fromY = Math.max(minY, world.getMinHeight());
        int toY = Math.min(maxY, world.getMaxHeight() - 1);
        for (int y = fromY; y <= toY; y += stepY) {
            double py = y + 0.8;
            world.spawnParticle(particle, minX + 0.5, py, minZ + 0.5, 1, 0, 0, 0, 0);
            world.spawnParticle(particle, minX + 0.5, py, maxZ + 0.5, 1, 0, 0, 0, 0);
            world.spawnParticle(particle, maxX + 0.5, py, minZ + 0.5, 1, 0, 0, 0, 0);
            world.spawnParticle(particle, maxX + 0.5, py, maxZ + 0.5, 1, 0, 0, 0, 0);
        }
    }

    private void drawPerimeter(World world, Particle particle, int minX, int maxX, int minZ, int maxZ, double y, int step) {
        for (int x = minX; x <= maxX; x += step) {
            world.spawnParticle(particle, x + 0.5, y, minZ + 0.5, 1, 0, 0, 0, 0);
            world.spawnParticle(particle, x + 0.5, y, maxZ + 0.5, 1, 0, 0, 0, 0);
        }
        for (int z = minZ; z <= maxZ; z += step) {
            world.spawnParticle(particle, minX + 0.5, y, z + 0.5, 1, 0, 0, 0, 0);
            world.spawnParticle(particle, maxX + 0.5, y, z + 0.5, 1, 0, 0, 0, 0);
        }
    }
}
