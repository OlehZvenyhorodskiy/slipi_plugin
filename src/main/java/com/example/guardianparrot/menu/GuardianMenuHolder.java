package com.example.guardianparrot.menu;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.*;

/**
 * InventoryHolder for guardian menu.
 */
public final class GuardianMenuHolder implements InventoryHolder {

    public final UUID owner;
    public final String regionId;
    public final Location privateMarker;

    public final Map<Integer, String> actions = new HashMap<>();
    public final List<Integer> amethystSlots = new ArrayList<>();

    /**
     * Map: GUI button slot -> guardian slot index (1..3).
     * Many configs use the same click_action (e.g. toggle_guard) for all three eggs,
     * so we need an explicit mapping to keep the logic slot-specific.
     */
    public final Map<Integer, Integer> guardButtons = new HashMap<>();

    public GuardianMenuHolder(UUID owner, String regionId, Location privateMarker) {
        this.owner = owner;
        this.regionId = regionId;
        this.privateMarker = privateMarker;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
