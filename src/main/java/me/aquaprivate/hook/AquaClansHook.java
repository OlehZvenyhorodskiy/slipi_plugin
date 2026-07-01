package me.aquaprivate.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.lang.reflect.Method;

/**
 * Soft integration with AquaClans (separate plugin).
 * We only need to know if a player is in a clan (and the clan name is provided via PlaceholderAPI).
 */
public final class AquaClansHook {

    private AquaClansHook() {}

    /** Returns true if AquaClans is installed and player has a clan. */
    public static boolean hasClan(Player p) {
        if (p == null) return false;
        return hasClan(p.getUniqueId());
    }

    /** Returns true if AquaClans is installed and UUID is a clan member. */
    public static boolean hasClan(UUID playerId) {
        if (playerId == null) return false;
        try {
            Plugin pl = Bukkit.getPluginManager().getPlugin("AquaClans");
            if (pl == null || !pl.isEnabled()) return false;

            // AquaClans has method getClansConfig() returning FileConfiguration.
            Method m = pl.getClass().getMethod("getClansConfig");
            Object cfg = m.invoke(pl);
            if (cfg == null) return false;

            // FileConfiguration#getString(String, String)
            Method getString = cfg.getClass().getMethod("getString", String.class, String.class);
            String clan = (String) getString.invoke(cfg, "members." + playerId, null);
            return clan != null && !clan.isBlank();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns clan tag/name for the given player UUID, or null if none / AquaClans missing. */
    public static String getClan(UUID playerId) {
        if (playerId == null) return null;
        try {
            Plugin pl = Bukkit.getPluginManager().getPlugin("AquaClans");
            if (pl == null || !pl.isEnabled()) return null;

            Method m = pl.getClass().getMethod("getClansConfig");
            Object cfg = m.invoke(pl);
            if (cfg == null) return null;

            Method getString = cfg.getClass().getMethod("getString", String.class, String.class);
            String clan = (String) getString.invoke(cfg, "members." + playerId, null);
            if (clan == null || clan.isBlank()) return null;
            return clan;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Returns clan level for the given player UUID (defaults to 1). */
    public static int getClanLevel(UUID playerId) {
        try {
            String clan = getClan(playerId);
            if (clan == null) return 1;

            Plugin pl = Bukkit.getPluginManager().getPlugin("AquaClans");
            if (pl == null || !pl.isEnabled()) return 1;

            Method m = pl.getClass().getMethod("getClansConfig");
            Object cfg = m.invoke(pl);
            if (cfg == null) return 1;

            Method getInt = cfg.getClass().getMethod("getInt", String.class, int.class);
            Object v = getInt.invoke(cfg, "clans." + clan + ".level", 1);
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof Number) return ((Number) v).intValue();
            return 1;
        } catch (Throwable ignored) {
            return 1;
        }
    }
}
