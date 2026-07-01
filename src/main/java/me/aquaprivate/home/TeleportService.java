package me.aquaprivate.home;

import me.aquaprivate.AquaPrivatePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TeleportService {

    private final AquaPrivatePlugin plugin;
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();

    public TeleportService(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Compatibility helper for command handlers.
     * Teleports to block coordinates with configured effects.
     */
    public void teleportWithEffects(Player p, String worldName, int x, int y, int z) {
        if (worldName == null || worldName.isEmpty()) return;
        org.bukkit.World w = Bukkit.getWorld(worldName);
        if (w == null) return;
        Location target = new Location(w, x + 0.5, y, z + 0.5);
        teleport(p, target);
    }

    public void teleport(Player p, Location target) {
        // cancel previous
        cancel(p);

        int warmup = plugin.getConfig().getInt("teleport.warmup-seconds", 0);
        boolean cancelOnMove = plugin.getConfig().getBoolean("teleport.cancel-on-move", true);
        String startMsg = plugin.cfg().msg("home-tp-start").replace("%seconds%", String.valueOf(warmup));
        String successMsg = plugin.cfg().msg("home-tp-success");
        String cancelMsg = plugin.cfg().msg("home-tp-cancel");

        Location startLoc = p.getLocation().clone();

        if (warmup <= 0) {
            doTeleport(p, target, successMsg);
            return;
        }

        p.sendMessage(startMsg);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int left = warmup;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel(p);
                    return;
                }
                if (cancelOnMove) {
                    Location now = p.getLocation();
                    if (now.getWorld() != startLoc.getWorld() ||
                            now.getBlockX() != startLoc.getBlockX() ||
                            now.getBlockY() != startLoc.getBlockY() ||
                            now.getBlockZ() != startLoc.getBlockZ()) {
                        p.sendMessage(cancelMsg);
                        cancel(p);
                        return;
                    }
                }
                spawnCountdownParticles(p);
                left--;
                if (left <= 0) {
                    cancel(p);
                    doTeleport(p, target, successMsg);
                } else {
                    // optional countdown message
                    String tickMsg = plugin.cfg().msg("home-tp-countdown").replace("%seconds%", String.valueOf(left));
                    if (!tickMsg.trim().isEmpty()) p.sendMessage(tickMsg);
                }
            }
        }, 20L, 20L);

        tasks.put(p.getUniqueId(), task);
    }

    private void doTeleport(Player p, Location target, String successMsg) {
        // Spigot API does not expose async chunk loading on all versions.
        // Keep it simple and reliable: ensure the chunk is loaded synchronously, then teleport on main thread.
        try {
            if (target.getWorld() != null) {
                try {
                    target.getWorld().getChunkAt(target).load(true);
                } catch (Throwable ignored) {
                    // Some implementations may not support load(true)
                    target.getWorld().getChunkAt(target);
                }
            }
        } catch (Throwable ignored) {
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            p.teleport(target);
            spawnArriveParticles(p);
            if (!successMsg.trim().isEmpty()) p.sendMessage(successMsg);
            String soundName = plugin.getConfig().getString("teleport.sound", "");
            if (soundName != null && !soundName.isEmpty()) {
                try {
                    p.playSound(p.getLocation(), Sound.valueOf(soundName.toUpperCase()), 1f, 1f);
                } catch (Exception ignored) {
                }
            }
        });
    }

    
private void spawnCountdownParticles(Player p) {
    if (!plugin.getConfig().getBoolean("teleport.particles.enabled", false)) return;
    if (!plugin.getConfig().getBoolean("teleport.particles.during-countdown", true)) return;
    spawnParticles(p);
}

private void spawnArriveParticles(Player p) {
    if (!plugin.getConfig().getBoolean("teleport.particles.enabled", false)) return;
    if (!plugin.getConfig().getBoolean("teleport.particles.on-arrive", true)) return;
    spawnParticles(p);
}

private void spawnParticles(Player p) {
    try {
        World w = p.getWorld();
        Particle particle;
        try {
            particle = Particle.valueOf(plugin.getConfig().getString("teleport.particles.type", "LAVA").toUpperCase());
        } catch (Exception ex) {
            particle = Particle.LAVA;
        }

        int count = plugin.getConfig().getInt("teleport.particles.count", 100);
        double ox = plugin.getConfig().getDouble("teleport.particles.offset-x", 0.0);
        double oy = plugin.getConfig().getDouble("teleport.particles.offset-y", 3.0);
        double oz = plugin.getConfig().getDouble("teleport.particles.offset-z", 0.0);

        double sx = plugin.getConfig().getDouble("teleport.particles.spread-x", 0.0);
        double sy = plugin.getConfig().getDouble("teleport.particles.spread-y", 3.0);
        double sz = plugin.getConfig().getDouble("teleport.particles.spread-z", 0.0);

        double extra = plugin.getConfig().getDouble("teleport.particles.extra", 10.0);
        boolean force = plugin.getConfig().getBoolean("teleport.particles.force", true);

        Location base = p.getLocation().clone().add(ox, oy, oz);

        // Try to use "force" overload if present (Paper/Spigot variants differ)
        try {
            var m = World.class.getMethod("spawnParticle", Particle.class, Location.class, int.class,
                    double.class, double.class, double.class, double.class, Object.class, boolean.class);
            m.invoke(w, particle, base, count, sx, sy, sz, extra, null, force);
            return;
        } catch (Throwable ignored) {
        }

        try {
            var m = World.class.getMethod("spawnParticle", Particle.class, Location.class, int.class,
                    double.class, double.class, double.class, double.class, boolean.class);
            m.invoke(w, particle, base, count, sx, sy, sz, extra, force);
            return;
        } catch (Throwable ignored) {
        }

        w.spawnParticle(particle, base, count, sx, sy, sz, extra);
    } catch (Throwable ignored) {
    }
}

public void cancel(Player p) {
        BukkitTask t = tasks.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }
}