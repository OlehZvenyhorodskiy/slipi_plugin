package me.aquaprivate.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Marker holder to identify our private GUI. */
public final class PrivateMenuHolder implements InventoryHolder {

    public final String regionId;
    public final java.util.Map<Integer, String> clickActions;

    public PrivateMenuHolder(String regionId, java.util.Map<Integer, String> clickActions) {
        this.regionId = regionId;
        this.clickActions = clickActions;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
