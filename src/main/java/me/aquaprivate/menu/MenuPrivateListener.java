package me.aquaprivate.menu;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.entity.Player;

public final class MenuPrivateListener implements Listener {

    private final MenuPrivateService menu;

    public MenuPrivateListener(MenuPrivateService menu) {
        this.menu = menu;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!menu.isMenu(e.getInventory())) return;
        e.setCancelled(true);

        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof PrivateMenuHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getRawSlot();
        String action = holder.clickActions != null ? holder.clickActions.get(slot) : null;
        if (action == null) return;

        // Click feedback for interactive slots
        menu.playClickSound(p);

        // Only our actions here.
        if (action.equalsIgnoreCase("toggle_leveling")) {
            menu.toggleLevelQuest(p, holder.regionId);
            return;
        }

        if (action.equalsIgnoreCase("toggle_glow")) {
            menu.toggleBorderGlow(p, holder.regionId);
            return;
        }

        if (action.equalsIgnoreCase("open_fermer_menu")) {
            menu.openFermerMenu(p, holder.regionId);
            return;
        }

        if (action.equalsIgnoreCase("fermer_button")) {
            menu.handleFermerButton(p, holder.regionId);
            return;
        }

        if (action.equalsIgnoreCase("guard_button")) {
            menu.handleGuardButton(p, holder.regionId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!menu.isMenu(e.getInventory())) return;
        e.setCancelled(true);
    }
}
