package me.aquaprivate.cfg;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.HologramSettings;
import me.aquaprivate.model.PrivateBlockType;
import me.aquaprivate.model.PrivateSettings;
import me.aquaprivate.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

public final class ConfigService {
    private final AquaPrivatePlugin plugin;

    private Set<String> disabledWorlds = new HashSet<>();
    private Set<Material> blacklistBreak = new HashSet<>();

    private final Map<String, String> messages = new HashMap<>();

    private PrivateSettings globalSettings = PrivateSettings.defaults();
    private final Map<String, PrivateBlockType> blocks = new LinkedHashMap<>();

    public ConfigService(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.reloadConfig();

        disabledWorlds.clear();
        for (String w : plugin.getConfig().getStringList("disable-worlds")) {
            disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        }

        blacklistBreak.clear();
        for (String m : plugin.getConfig().getStringList("blacklist-break-block")) {
            Material mat = Material.matchMaterial(m);
            if (mat != null) blacklistBreak.add(mat);
        }

        messages.clear();
        // Backward compatibility: some older configs used "messages:" instead of "message:"
        ConfigurationSection msg = plugin.getConfig().getConfigurationSection("message");
        if (msg == null) {
            msg = plugin.getConfig().getConfigurationSection("messages");
        }
        if (msg != null) {
            for (String k : msg.getKeys(false)) {
                messages.put(k, msg.getString(k, ""));
            }
        }

        globalSettings = readSettings(plugin.getConfig().getConfigurationSection("settings"), PrivateSettings.defaults());

        blocks.clear();
        ConfigurationSection blocksSec = plugin.getConfig().getConfigurationSection("blocks");
        if (blocksSec != null) {
            for (String key : blocksSec.getKeys(false)) {
                ConfigurationSection bs = blocksSec.getConfigurationSection(key);
                if (bs == null) continue;

                ConfigurationSection item = bs.getConfigurationSection("item");
                String matName = item != null ? item.getString("material", key) : key;
                Material material = Material.matchMaterial(matName);
                if (material == null) {
                    plugin.getLogger().warning("Unknown material in config for block " + key + ": " + matName);
                    continue;
                }

                String name = item != null ? item.getString("name", "&bAquaPrivate") : "&bAquaPrivate";
                List<String> lore = item != null ? item.getStringList("lore") : Collections.emptyList();

                ConfigurationSection region = bs.getConfigurationSection("region");
                int xr = region != null ? region.getInt("x_radius", 15) : 15;
                int yr = region != null ? region.getInt("y_radius", 15) : 15;
                int zr = region != null ? region.getInt("z_radius", 15) : 15;

                PrivateSettings st = globalSettings.copy();
                st = readSettings(bs.getConfigurationSection("settings"), st);

                // holograms
                HologramSettings hs = HologramSettings.defaults();
                ConfigurationSection holo = bs.getConfigurationSection("holograms");
                if (holo != null) {
                    boolean en = holo.getBoolean("enable", false);
                    double height = holo.getDouble("height", 1.75);
                    List<String> lines = holo.getStringList("lines");
                    hs = new HologramSettings(en, height, lines);
                }

                blocks.put(key.toUpperCase(Locale.ROOT), new PrivateBlockType(
                        key.toUpperCase(Locale.ROOT), material, name, lore, xr, yr, zr, st, hs
                ));
            }
        }

        if (blocks.isEmpty()) {
            plugin.getLogger().warning("No blocks configured in config.yml (section blocks: is empty)");
        }
    }

    private PrivateSettings readSettings(ConfigurationSection sec, PrivateSettings base) {
        if (sec == null) return base;
        PrivateSettings s = base.copy();
        if (sec.contains("allow-pvp")) s.allowPvp = sec.getBoolean("allow-pvp");
        if (sec.contains("create-lightning-strike")) s.createLightning = sec.getBoolean("create-lightning-strike");
        if (sec.contains("delete-lightning-strike")) s.deleteLightning = sec.getBoolean("delete-lightning-strike");
        if (sec.contains("merge-regions")) s.mergeRegions = sec.getBoolean("merge-regions");

        if (sec.contains("deny-block-break")) s.denyBlockBreak = sec.getBoolean("deny-block-break");
        if (sec.contains("deny-block-place")) s.denyBlockPlace = sec.getBoolean("deny-block-place");
        if (sec.contains("deny-chest-open")) s.denyChestOpen = sec.getBoolean("deny-chest-open");
        if (sec.contains("deny-wither-block-damage")) s.denyWitherBlockDamage = sec.getBoolean("deny-wither-block-damage");

        if (sec.contains("explosions")) s.explosions = sec.getBoolean("explosions");
        if (sec.contains("exclusion-explosions-types")) s.exclusionExplosionsTypes = sec.getStringList("exclusion-explosions-types");
        if (sec.contains("block-change-wither")) s.blockChangeWither = sec.getBoolean("block-change-wither");
        return s;
    }

    public boolean isWorldDisabled(String worldName) {
        return disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    public boolean isBlacklistedBreak(Material material) {
        return blacklistBreak.contains(material);
    }

    public String msg(String key) {
        String prefix = messages.getOrDefault("prefix", "");
        if (prefix == null || prefix.isEmpty()) {
            // hard fallback so admins never see an empty line even if config section is missing
            prefix = "&8[&bAquaPrivate&8] ";
        }
        if ("prefix".equalsIgnoreCase(key)) return ColorUtil.color(prefix);
        String raw = messages.getOrDefault(key, "");
        return ColorUtil.color(prefix + raw);
    }

    /** Raw (without prefix). Useful for WG greeting/farewell flags. */
    public String msgRaw(String key) {
        return messages.getOrDefault(key, "");
    }

    public String color(String s) {
        return ColorUtil.color(s);
    }

    public Map<String, PrivateBlockType> blocks() {
        return Collections.unmodifiableMap(blocks);
    }

    public PrivateBlockType block(String key) {
        return blocks.get(key.toUpperCase(Locale.ROOT));
    }

    public String colorize(String s) { return ColorUtil.color(s); }

    public boolean getBoolean(String path, boolean def) {
        return plugin.getConfig().getBoolean(path, def);
    }


    /** List message from config: message.<key> as list of strings (colored later by caller) */
    public java.util.List<String> msgList(String key) {
        try {
            return plugin.getConfig().getStringList("message." + key);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

}
