package me.aquaprivate.tnt;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.Locale;

/**
 * Transfers unique TNT type from the crafted TNT minecart item to the spawned TNT minecart entity,
 * and applies the custom name.
 */
public final class UniqueTntMinecartListener implements Listener {

    private final AquaPrivatePlugin plugin;
    private final UniqueTntService service;

    public UniqueTntMinecartListener(AquaPrivatePlugin plugin, UniqueTntService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private String getTypeId(ItemStack item) {
        return service.getItemTypeId(item);
    }

    private void tagNearbyMinecart(Location around, String typeId) {
        if (around == null || around.getWorld() == null || typeId == null) return;

        // Search a small area for a just-spawned TNT minecart.
        Collection<Entity> nearby = around.getWorld().getNearbyEntities(around, 2.0, 2.0, 2.0);
        for (Entity ent : nearby) {
            if (ent.getType() != EntityType.TNT_MINECART) continue;

            PersistentDataContainer pdc = ent.getPersistentDataContainer();
            if (pdc.has(service.pdcKey(), PersistentDataType.STRING)) {
                // Already tagged.
                continue;
            }

            pdc.set(service.pdcKey(), PersistentDataType.STRING, typeId);

            UniqueTntType type = service.getType(typeId);
            if (type != null && type.name() != null && !type.name().isEmpty()) {
                try {
                    ent.setCustomName(ColorUtil.color(type.name()));
                    ent.setCustomNameVisible(true);
                } catch (Throwable ignored) {}
            }
            break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent e) {
        // Prefer only main-hand to avoid double-firing.
        if (e.getHand() != null && e.getHand() != EquipmentSlot.HAND) return;

        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.TNT_MINECART) return;
        String typeId = getTypeId(item);
        if (typeId == null) return;

        Block clicked = e.getClickedBlock();
        if (clicked == null) return;

        Location around = clicked.getLocation().clone().add(0.5, 0.5, 0.5);

        // Tag on next tick (entity spawns after interaction).
        Bukkit.getScheduler().runTask(plugin, () -> tagNearbyMinecart(around, typeId));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.TNT_MINECART) return;
        String typeId = getTypeId(item);
        if (typeId == null) return;

        Location around = e.getBlock().getLocation().clone().add(0.5, 0.5, 0.5);
        Bukkit.getScheduler().runTask(plugin, () -> tagNearbyMinecart(around, typeId));
    }
}
