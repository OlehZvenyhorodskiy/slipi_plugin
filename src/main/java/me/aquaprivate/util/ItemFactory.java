package me.aquaprivate.util;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateBlockType;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class ItemFactory {

    public static final String PDC_BLOCK_KEY = "private_block_key";
    public static final String PDC_LEVEL_KEY = "private_block_level";

    private ItemFactory() {}

    public static ItemStack createPrivateItem(AquaPrivatePlugin plugin, PrivateBlockType type, String playerName) {
        return createPrivateItem(plugin, type, playerName, 1);
    }

    public static ItemStack createPrivateItem(AquaPrivatePlugin plugin, PrivateBlockType type, String playerName, int level) {
        ItemStack stack = new ItemStack(type.material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.color(type.name));

            int lvl = Math.max(1, Math.min(20, level));
            int size = PrivateLevelUtil.sizeForLevel(lvl);

            List<String> lore = new ArrayList<>();
            for (String line : type.lore) {
                String out = line;
                out = out.replace("%player%", playerName);
                out = out.replace("%levlprivata%", String.valueOf(lvl));
                out = out.replace("%priv_size%", String.valueOf(size));
                out = out.replace("%privatetype%", plugin.getConfig().getString("settings.privatetype.normal", "обычный"));
                out = out.replace("%fermerprivat%", plugin.getConfig().getString("settings.fermer.inactive", "не активный"));
                out = out.replace("%fermerprivattoplivo%", "");
                lore.add(ColorUtil.color(out));
            }
            meta.setLore(lore);

            // Glow WITHOUT showing any enchant text:
            // We do NOT add a real enchant (it shows as "Прочность I").
            // Instead we add an EMPTY enchantments list via NBT (same trick used by many plugins),
            // which makes the client render glint but without any enchant lines.

            // Hide everything related to enchants/attributes in tooltip.
            // Different Spigot API builds expose different ItemFlag constants.
            // Add the common ones directly and add the optional ones via Enum lookup.
            meta.addItemFlags(
                    ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_ATTRIBUTES,
                    ItemFlag.HIDE_UNBREAKABLE,
                    ItemFlag.HIDE_DESTROYS,
                    ItemFlag.HIDE_PLACED_ON
            );
            addFlagIfExists(meta, "HIDE_POTION_EFFECTS");
            addFlagIfExists(meta, "HIDE_DYE");
            addFlagIfExists(meta, "HIDE_ARMOR_TRIM");
            addFlagIfExists(meta, "HIDE_ADDITIONAL_TOOLTIP");

            NamespacedKey key = new NamespacedKey(plugin, PDC_BLOCK_KEY);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, type.key);
            NamespacedKey levelKey = new NamespacedKey(plugin, PDC_LEVEL_KEY);
            meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, lvl);

            stack.setItemMeta(meta);

            // Force raw NBT empty-enchants glint (ViaVersion safety).
            // (returns a new stack)
            stack = NbtUtil.withEmptyEnchantGlint(stack);
        }
        return stack;
    }

    private static void addFlagIfExists(ItemMeta meta, String flagName) {
        try {
            ItemFlag f = Enum.valueOf(ItemFlag.class, flagName);
            meta.addItemFlags(f);
        } catch (Throwable ignored) {
            // flag not present on this API build
        }
    }

    public static int readLevel(AquaPrivatePlugin plugin, ItemStack stack) {
        if (stack == null) return 1;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return 1;
        NamespacedKey key = new NamespacedKey(plugin, PDC_LEVEL_KEY);
        Integer lvl = meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        if (lvl == null) return 1;
        return Math.max(1, Math.min(20, lvl));
    }

    public static String readBlockKey(AquaPrivatePlugin plugin, ItemStack stack) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        NamespacedKey key = new NamespacedKey(plugin, PDC_BLOCK_KEY);
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }
}
