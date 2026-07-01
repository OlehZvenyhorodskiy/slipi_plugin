package me.aquaprivate.tnt;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import me.aquaprivate.util.ColorUtil;


import java.io.File;
import java.util.*;

/**
 * Loads tntforprivare.yml and provides access to custom TNT types.
 */
public final class UniqueTntService {

    public static final String FILE_NAME = "tntforprivare.yml";
    public static final String PDC_KEY = "ap_unique_tnt_type";

    private final JavaPlugin plugin;
    private final NamespacedKey pdcKey;
    private final Map<String, UniqueTntType> types = new LinkedHashMap<>();

    public UniqueTntService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.pdcKey = new NamespacedKey(plugin, PDC_KEY);
    }

    public void reload() {
        types.clear();

        File file = new File(plugin.getDataFolder(), FILE_NAME);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        // Auto-merge new bundled TNT types into existing user config (do not overwrite user values).
        // This ensures tab-complete (/aquaprivate givetnt) sees newly added types even on old servers.
        try {
            YamlConfiguration defCfg = new YamlConfiguration();
            try (java.io.InputStream is = plugin.getResource(FILE_NAME)) {
                if (is != null) {
                    String raw = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    defCfg.loadFromString(raw);
                }
            }
            boolean changed = false;
            for (String k : defCfg.getKeys(false)) {
                if (!cfg.contains(k)) {
                    cfg.set(k, defCfg.get(k));
                    changed = true;
                }
            }
            if (changed) {
                cfg.save(file);
            }
        } catch (Throwable ignored) {}

        for (String rootKey : cfg.getKeys(false)) {
            ConfigurationSection root = cfg.getConfigurationSection(rootKey);
            if (root == null) continue;

            ConfigurationSection ex = root.getConfigurationSection("explosions-allowed");
            Map<String, Boolean> allowed = new HashMap<>();
            if (ex != null) {
                for (String k : ex.getKeys(false)) {
                    allowed.put(k.toLowerCase(Locale.ROOT), ex.getBoolean(k, false));
                }
            }

            ConfigurationSection ut = root.getConfigurationSection("unique-tnt");
            if (ut == null) continue;
            ConfigurationSection itemSec = ut.getConfigurationSection("item");
            if (itemSec == null) continue;

            Material mat = Material.matchMaterial(itemSec.getString("material", "TNT"));
            if (mat == null) mat = Material.TNT;
            String name = itemSec.getString("name", rootKey);
            List<String> lore = itemSec.getStringList("lore");
            boolean glow = itemSec.getBoolean("glow", false);

            ConfigurationSection craftSec = ut.getConfigurationSection("craft");
            List<String> pattern = craftSec != null ? craftSec.getStringList("pattern") : List.of();
            ConfigurationSection ingSec = craftSec != null ? craftSec.getConfigurationSection("ingredients") : null;
            Map<Character, Material> ingredients = new HashMap<>();
            if (ingSec != null) {
                for (String k : ingSec.getKeys(false)) {
                    if (k.length() != 1) continue;
                    Material m = Material.matchMaterial(ingSec.getString(k, ""));
                    if (m != null) ingredients.put(k.charAt(0), m);
                }
            }

            UniqueTntType type = new UniqueTntType(rootKey, allowed, mat, name, lore, glow, pattern, ingredients);
            types.put(rootKey.toLowerCase(Locale.ROOT), type);
        }

        for (UniqueTntType type : types.values()) {
            if (type.pattern().size() != 3) continue;
            NamespacedKey key = new NamespacedKey(plugin, "unique_tnt_" + type.id().toLowerCase(Locale.ROOT));
            ShapedRecipe recipe = new ShapedRecipe(key, createItem(type));
            recipe.shape(type.pattern().get(0), type.pattern().get(1), type.pattern().get(2));
            for (var e : type.ingredients().entrySet()) {
                recipe.setIngredient(e.getKey(), e.getValue());
            }
            try {
                // Prevent duplicates after /reload.
                try { plugin.getServer().removeRecipe(key); } catch (Throwable ignored) {}
                plugin.getServer().addRecipe(recipe);
            } catch (Exception ignored) {
            }
        }

        // Shapeless: Minecart + Custom TNT -> Custom TNT Minecart (inherits TNT name/lore/type).
        // We use ExactChoice so only OUR TNT item (with PDC) can be used in crafting.
        for (UniqueTntType type : types.values()) {
            try {
                NamespacedKey key = new NamespacedKey(plugin, "unique_tnt_minecart_" + type.id().toLowerCase(Locale.ROOT));
                try { plugin.getServer().removeRecipe(key); } catch (Throwable ignored) {}

                ItemStack out = createMinecartItem(type);
                ShapelessRecipe r = new ShapelessRecipe(key, out);
                r.addIngredient(Material.MINECART);
                r.addIngredient(new RecipeChoice.ExactChoice(createItem(type)));
                plugin.getServer().addRecipe(r);
            } catch (Throwable ignored) {}
        }
    }

    public NamespacedKey pdcKey() {
        return pdcKey;
    }

    public Set<String> typeIds() {
        return new LinkedHashSet<>(types.keySet());
    }

    public UniqueTntType getType(String id) {
        if (id == null) return null;
        return types.get(id.toLowerCase(Locale.ROOT));
    }

    public ItemStack createItem(UniqueTntType type) {
        ItemStack item = new ItemStack(type.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Mark this item as "unique TNT" with its type id.
            meta.getPersistentDataContainer().set(pdcKey, PersistentDataType.STRING, type.id());

            // Apply legacy colors (&, &#RRGGBB, and keep existing §x§R§R...) using String meta.
            if (type.name() != null && !type.name().isEmpty()) {
                meta.setDisplayName(ColorUtil.color(type.name()));
            }

            if (type.lore() != null && !type.lore().isEmpty()) {
                List<String> loreStr = new ArrayList<>();
                for (String lineLore : type.lore()) {
                    loreStr.add(ColorUtil.color(lineLore));
                }
                meta.setLore(loreStr);
            }

            if (type.glow()) {
                // Glow intentionally removed (no enchant glint) to avoid showing enchantment lines in tooltip.
                // The config option is kept only for backwards compatibility.
            }

            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack createMinecartItem(UniqueTntType type) {
        ItemStack item = new ItemStack(Material.TNT_MINECART);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(pdcKey, PersistentDataType.STRING, type.id());

            // Same name/lore as the dynamite/TNT type.
            if (type.name() != null && !type.name().isEmpty()) {
                meta.setDisplayName(ColorUtil.color(type.name()));
            }
            if (type.lore() != null && !type.lore().isEmpty()) {
                List<String> loreStr = new ArrayList<>();
                for (String lineLore : type.lore()) {
                    loreStr.add(ColorUtil.color(lineLore));
                }
                meta.setLore(loreStr);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public String getItemTypeId(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(pdcKey, PersistentDataType.STRING);
    }

    // Glow removed.
}