package me.aquaprivate.listener;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.util.ItemFactory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;

/**
 * Prevent renaming/modifying private blocks in an anvil.
 */
public final class AnvilListener implements Listener {

    private final AquaPrivatePlugin plugin;

    public AnvilListener(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isPrivateItem(ItemStack stack) {
        return ItemFactory.readBlockKey(plugin, stack) != null;
    }

    private boolean isUniqueTnt(ItemStack stack) {
        try {
            if (stack == null) return false;
            if (stack.getItemMeta() == null) return false;
            return stack.getItemMeta().getPersistentDataContainer().has(plugin.uniqueTnt().pdcKey(), org.bukkit.persistence.PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isUniqueTntMinecart(ItemStack stack) {
        try {
            if (stack == null) return false;
            if (stack.getType() != org.bukkit.Material.TNT_MINECART) return false;
            if (stack.getItemMeta() == null) return false;
            return stack.getItemMeta().getPersistentDataContainer().has(plugin.uniqueTnt().pdcKey(), org.bukkit.persistence.PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareAnvilEvent e) {
        AnvilInventory inv = e.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        if (isPrivateItem(left) || isPrivateItem(right)
                || isUniqueTnt(left) || isUniqueTnt(right)
                || isUniqueTntMinecart(left) || isUniqueTntMinecart(right)) {
            e.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof AnvilInventory)) return;
        // Slot 2 is the output.
        if (e.getRawSlot() != 2) return;

        AnvilInventory inv = (AnvilInventory) e.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        if (isPrivateItem(left) || isPrivateItem(right)
                || isUniqueTnt(left) || isUniqueTnt(right)
                || isUniqueTntMinecart(left) || isUniqueTntMinecart(right)) {
            e.setCancelled(true);
            inv.setItem(2, null);
        }
    }
}
