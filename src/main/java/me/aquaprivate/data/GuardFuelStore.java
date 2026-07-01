package me.aquaprivate.data;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Stores amethyst "fuel" balance used by Guardian (Хранитель) menus.
 *
 * File: plugins/AquaPrivate/guardfuel.yml
 *
 * Region-aware: each private/region has its own amethyst bank.
 */
public final class GuardFuelStore {

    private final File file;
    private FileConfiguration cfg;

    public GuardFuelStore(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "guardfuel.yml");
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) {
            try { file.createNewFile(); } catch (IOException ignored) {}
        }
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    private void reload() {
        try { cfg.load(file); } catch (Throwable ignored) {}
    }

    private void save() {
        try { ((YamlConfiguration) cfg).save(file); } catch (Throwable ignored) {}
    }

    private static String sanitize(String s) {
        if (s == null || s.isBlank()) return "__global__";
        return s.replace('.', '_');
    }

    // ===== Region-aware API =====

    public int get(UUID owner, String regionId) {
        if (owner == null) return 0;
        reload();
        String key = "players." + owner + ".regions." + sanitize(regionId) + ".amethysts";
        return Math.max(0, cfg.getInt(key, 0));
    }

    public void set(UUID owner, String regionId, int amount) {
        if (owner == null) return;
        if (amount < 0) amount = 0;
        String key = "players." + owner + ".regions." + sanitize(regionId) + ".amethysts";
        cfg.set(key, amount);
        // remove legacy global value to avoid confusion
        if (regionId == null || regionId.isBlank() || "__global__".equals(sanitize(regionId))) {
            cfg.set("players." + owner + ".amethysts", null);
        }
        save();
    }

    public int add(UUID owner, String regionId, int delta) {
        if (owner == null) return 0;
        reload();
        String key = "players." + owner + ".regions." + sanitize(regionId) + ".amethysts";
        int cur = Math.max(0, cfg.getInt(key, 0));
        int next = Math.max(0, cur + delta);
        cfg.set(key, next);
        save();
        return next;
    }

    public boolean take(UUID owner, String regionId, int amount) {
        if (owner == null) return false;
        if (amount <= 0) return true;
        reload();
        String key = "players." + owner + ".regions." + sanitize(regionId) + ".amethysts";
        int cur = Math.max(0, cfg.getInt(key, 0));
        if (cur < amount) return false;
        cfg.set(key, Math.max(0, cur - amount));
        save();
        return true;
    }

    // ===== Backwards compatible wrappers (global) =====

    public int get(UUID owner) { return get(owner, "__global__"); }
    public void set(UUID owner, int amount) { set(owner, "__global__", amount); }
    public int add(UUID owner, int delta) { return add(owner, "__global__", delta); }
    public boolean take(UUID owner, int amount) { return take(owner, "__global__", amount); }
}
