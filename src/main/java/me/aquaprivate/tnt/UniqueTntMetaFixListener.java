package me.aquaprivate.tnt;

import me.aquaprivate.AquaPrivatePlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Some servers/plugins may sanitize item meta (e.g. strip ItemFlags) after the item is given.
 * This listener re-applies the correct name/lore/glow for our unique TNT items whenever
 * they appear in a player's inventory.
 */
public final class UniqueTntMetaFixListener implements Listener {

    private final AquaPrivatePlugin plugin;
    private final UniqueTntService service;

    public UniqueTntMetaFixListener(AquaPrivatePlugin plugin, UniqueTntService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        plugin.getServer().getScheduler().runTask(plugin, () -> fixPlayer(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) {
            plugin.getServer().getScheduler().runTask(plugin, () -> fixPlayer(p));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) {
            plugin.getServer().getScheduler().runTask(plugin, () -> fixPlayer(p));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p) {
            plugin.getServer().getScheduler().runTask(plugin, () -> fixPlayer(p));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) {
            plugin.getServer().getScheduler().runTask(plugin, () -> fixPlayer(p));
        }
    }

    private void fixPlayer(Player p) {
        if (p == null) return;
        fixInventory(p.getInventory());
        // Also fix top inventory if open (crafting/anvil/etc.)
        if (p.getOpenInventory() != null) {
            fixInventory(p.getOpenInventory().getTopInventory());
        }
    }

    private void fixInventory(Inventory inv) {
        if (inv == null) return;
        ItemStack[] contents = inv.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null) continue;
            String typeId = service.getItemTypeId(it);
            if (typeId == null) continue;
            UniqueTntType type = service.getType(typeId);
            if (type == null) continue;

            int amount = it.getAmount();
            ItemStack fixed;
            // Preserve actual material: TNT minecart must stay a minecart item, not become TNT.
            if (it.getType() == Material.TNT_MINECART) {
                fixed = service.createMinecartItem(type);
            } else {
                fixed = service.createItem(type);
            }
            fixed.setAmount(amount);
            contents[i] = fixed;
            changed = true;
        }
        if (changed) {
            inv.setContents(contents);
        }
    }
}
