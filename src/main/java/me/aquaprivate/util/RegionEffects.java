package me.aquaprivate.util;

import me.aquaprivate.AquaPrivatePlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Visual effects for private create/upgrade: Nautilus particles from center to every border block. */
public final class RegionEffects {

    private RegionEffects() {}

    public static void playBorderExpand(AquaPrivatePlugin plugin, String worldName, int x, int y, int z, int size) {
        try {
            var cfg = plugin.getConfig();
            boolean enabled = cfg.getBoolean("settings.upgrade-effects.enabled", true);
            if (!enabled) return;

            String particleName = cfg.getString("settings.upgrade-effects.particle", "NAUTILUS");
            int maxPerTick = Math.max(50, cfg.getInt("settings.upgrade-effects.particles-per-tick", 300));
            int duration = Math.max(1, cfg.getInt("settings.upgrade-effects.duration-ticks", 30));
            String soundName = cfg.getString("settings.upgrade-effects.sound", "BLOCK_BEACON_ACTIVATE");
            float soundVolume = (float) cfg.getDouble("settings.upgrade-effects.sound-volume", 1.0);
            float soundPitch = (float) cfg.getDouble("settings.upgrade-effects.sound-pitch", 1.0);

            World w = Bukkit.getWorld(worldName);
            if (w == null) return;

            var center = new org.bukkit.Location(w, x + 0.5, y + 0.5, z + 0.5);

            // Sound at center
            try {
                org.bukkit.Sound s = org.bukkit.Sound.valueOf(soundName);
                w.playSound(center, s, soundVolume, soundPitch);
            } catch (Throwable ignored) {}

            final org.bukkit.Particle particle = parseParticle(particleName);

            int[] off = PrivateLevelUtil.offsetsForSize(size);
            final int minOff = off[0];
            final int maxOff = off[1];

            // Build all border targets of the cube (every block on the surface)
            final List<Vector> targets = new ArrayList<>();
            for (int dx = minOff; dx <= maxOff; dx++) {
                for (int dy = minOff; dy <= maxOff; dy++) {
                    for (int dz = minOff; dz <= maxOff; dz++) {
                        boolean border = (dx == minOff || dx == maxOff || dy == minOff || dy == maxOff || dz == minOff || dz == maxOff);
                        if (!border) continue;
                        targets.add(new Vector(dx, dy, dz));
                    }
                }
            }
            // Shuffle to distribute evenly over time
            java.util.Collections.shuffle(targets, new Random());

            final int total = targets.size();
            final int perTick = Math.max(1, Math.min(maxPerTick, total));

            new BukkitRunnable() {
                int tick = 0;
                int cursor = 0;
                @Override public void run() {
                    tick++;
                    double t = (double) tick / (double) duration;
                    if (t > 1.0) { cancel(); return; }

                    // Emit particles moving from center towards border blocks
                    for (int i = 0; i < perTick; i++) {
                        Vector d = targets.get(cursor++);
                        if (cursor >= total) cursor = 0;

                        double len = d.length();
                        if (len <= 0.0) continue;
                        Vector unit = d.clone().multiply(1.0 / len);
                        double dist = len * t;

                        var loc = center.clone().add(unit.multiply(dist));
                        w.spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);

        } catch (Throwable ignored) {}
    }

    private static org.bukkit.Particle parseParticle(String name) {
        if (name == null || name.isEmpty()) return org.bukkit.Particle.NAUTILUS;
        try {
            return org.bukkit.Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return org.bukkit.Particle.NAUTILUS;
        }
    }
}
