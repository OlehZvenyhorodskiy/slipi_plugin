package com.example.guardianparrot.menu;

import me.aquaprivate.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Handles clicks/drags for guardian menu.
 */
public final class GuardianMenuListener implements Listener {

    private final GuardianMenuService service;

    public GuardianMenuListener(GuardianMenuService service) {
        this.service = service;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof org.bukkit.entity.Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuardianMenuHolder holder)) return;

        // cancel by default; we selectively allow interactions
        // IMPORTANT: do NOT block the player's own inventory (bottom). Farmers allow it.
        // We only protect the menu (top) except for allowed amethyst bank slots.
        e.setCancelled(true);

        int raw = e.getRawSlot();
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        boolean shift = e.isShiftClick();

        // --- Allow normal interactions in the PLAYER inventory (bottom)
        // (dragging/moving items while the menu is open should work).
        if (raw >= top.getSize() && !shift) {
            e.setCancelled(false);
            return;
        }

        // --- SHIFT-CLICK from player inventory into the amethyst bank
        // raw >= top.getSize() means the click happened in the bottom (player) inventory.
        if (shift && raw >= top.getSize()) {
            if (current != null && current.getType() == Material.AMETHYST_SHARD && current.getAmount() > 0) {
                int move = current.getAmount();
                // merge into existing stacks first
                for (int slot : holder.amethystSlots) {
                    ItemStack dst = top.getItem(slot);
                    if (dst == null || dst.getType() == Material.AIR) continue;
                    if (dst.getType() != Material.AMETHYST_SHARD) continue;
                    int space = 64 - dst.getAmount();
                    if (space <= 0) continue;
                    int add = Math.min(space, move);
                    dst.setAmount(dst.getAmount() + add);
                    move -= add;
                    if (move <= 0) break;
                }
                // fill empty slots
                if (move > 0) {
                    for (int slot : holder.amethystSlots) {
                        ItemStack dst = top.getItem(slot);
                        if (dst != null && dst.getType() != Material.AIR) continue;
                        int add = Math.min(64, move);
                        top.setItem(slot, new ItemStack(Material.AMETHYST_SHARD, add));
                        move -= add;
                        if (move <= 0) break;
                    }
                }

                // update source stack
                if (move <= 0) {
                    e.setCurrentItem(null);
                } else {
                    current.setAmount(move);
                    e.setCurrentItem(current);
                }

                // persist to store next tick
                org.bukkit.Bukkit.getScheduler().runTask(service.module.host(), () -> {
                    int count = 0;
                    for (int slot : holder.amethystSlots) {
                        ItemStack it = top.getItem(slot);
                        if (it == null || it.getType() == Material.AIR) continue;
                        if (it.getType() == Material.AMETHYST_SHARD) count += it.getAmount();
                    }
                    service.fuel.set(p.getUniqueId(), holder.regionId, count);
                });
            } else if (current != null && current.getType() != Material.AIR) {
                p.sendMessage(ColorUtil.color(service.module.host().getConfig().getString("messages.guard-bank-only-amethyst", "&cСюда можно класть только аметисты!")));
            }
            return;
        }

        // --- Clicks inside the TOP inventory
        if (raw < top.getSize() && holder.amethystSlots.contains(raw)) {
            // Allow ONLY amethyst shards to be placed into bank slots.
            if (shift) {
                // shift-clicking from TOP bank into player inventory is fine (vanilla)
                e.setCancelled(false);
            } else {
                if (cursor != null && cursor.getType() != Material.AIR && cursor.getType() != Material.AMETHYST_SHARD) {
                    p.sendMessage(ColorUtil.color(service.module.host().getConfig().getString("messages.guard-bank-only-amethyst", "&cСюда можно класть только аметисты!")));
                    e.setCancelled(true);
                } else {
                    e.setCancelled(false);
                }
            }

            // persist to store next tick
            org.bukkit.Bukkit.getScheduler().runTask(service.module.host(), () -> {
                int count = 0;
                for (int slot : holder.amethystSlots) {
                    ItemStack it = top.getItem(slot);
                    if (it == null || it.getType() == Material.AIR) continue;
                    if (it.getType() == Material.AMETHYST_SHARD) count += it.getAmount();
                }
                service.fuel.set(p.getUniqueId(), holder.regionId, count);
            });
            return;
        }

        // --- Other buttons / actions
        service.handleClick(p, top, raw, cursor, current, shift);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof org.bukkit.entity.Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuardianMenuHolder holder)) return;

        for (int slot : e.getRawSlots()) {
            if (slot < top.getSize() && !holder.amethystSlots.contains(slot)) {
                e.setCancelled(true);
                return;
            }
        }

        ItemStack it = e.getOldCursor();
        if (it != null && it.getType() != Material.AIR && it.getType() != Material.AMETHYST_SHARD) {
            e.setCancelled(true);
            p.sendMessage(ColorUtil.color(service.module.host().getConfig().getString("messages.guard-bank-only-amethyst", "&cСюда можно класть только аметисты!")));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof org.bukkit.entity.Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuardianMenuHolder)) return;
        service.onClose(p, top);
    }
}
