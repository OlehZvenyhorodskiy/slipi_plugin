package me.aquaprivate.upgrade;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import me.aquaprivate.util.PrivateLevelUtil;
import me.aquaprivate.util.RegionEffects;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Shared upgrade logic used by /ap upgrade and by the leveling-quest system.
 */
public final class PrivateUpgradeService {

    private final AquaPrivatePlugin plugin;

    public PrivateUpgradeService(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean upgradeRegion(PrivateRecord rec, int newLevel) {
        World w = Bukkit.getWorld(rec.world);
        if (w == null) return false;

        RegionManager rm = plugin.wg().regionManager(w);
        if (rm == null) return false;

        ProtectedRegion existing = rm.getRegion(rec.regionId);
        if (existing == null) return false;

        int size = PrivateLevelUtil.sizeForLevel(newLevel);
        int halfDown = (size - 1) / 2;
        int halfUp = size / 2;

        BlockVector3 min = BlockVector3.at(rec.x - halfDown, rec.y - halfDown, rec.z - halfDown);
        BlockVector3 max = BlockVector3.at(rec.x + halfUp, rec.y + halfUp, rec.z + halfUp);

        ProtectedCuboidRegion upgraded = new ProtectedCuboidRegion(rec.regionId, min, max);

        try { upgraded.setOwners(existing.getOwners()); } catch (Throwable ignored) {}
        try { upgraded.setMembers(existing.getMembers()); } catch (Throwable ignored) {}

        try {
            for (var e : existing.getFlags().entrySet()) {
                try {
                    upgraded.setFlag((com.sk89q.worldguard.protection.flags.Flag<Object>) e.getKey(), e.getValue());
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}

        rm.removeRegion(rec.regionId);
        rm.addRegion(upgraded);

        try { rm.save(); } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save WG regions on upgrade: " + ex.getMessage());
        }
        return true;
    }

    public void playUpgradeEffects(PrivateRecord rec, int newSize, int newLevel, String actorName) {
        try {
            var cfg = plugin.getConfig();
            if (!cfg.getBoolean("settings.upgrade-effects.enabled", true)) return;

            int localRadius = Math.max(1, cfg.getInt("settings.upgrade-effects.local-chat-radius", 64));
            String soundName = cfg.getString("settings.upgrade-effects.sound", "BLOCK_BEACON_ACTIVATE");
            float soundVolume = (float) cfg.getDouble("settings.upgrade-effects.sound-volume", 1.0);
            float soundPitch = (float) cfg.getDouble("settings.upgrade-effects.sound-pitch", 1.0);

            World w = Bukkit.getWorld(rec.world);
            if (w == null) return;

            var center = new org.bukkit.Location(w, rec.x + 0.5, rec.y + 0.5, rec.z + 0.5);

            String msg = plugin.cfg().msg("upgrade-local-chat")
                    .replace("%player%", actorName)
                    .replace("%size%", String.valueOf(newSize))
                    .replace("%level%", String.valueOf(newLevel));
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(center) <= (double) localRadius * (double) localRadius) {
                    p.sendMessage(msg);
                }
            }

            try {
                org.bukkit.Sound s = org.bukkit.Sound.valueOf(soundName);
                w.playSound(center, s, soundVolume, soundPitch);
            } catch (Throwable ignored) {}

            RegionEffects.playBorderExpand(plugin, rec.world, rec.x, rec.y, rec.z, newSize);
        } catch (Throwable ignored) {}
    }
}
