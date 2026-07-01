package com.fermerpets;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.entity.LivingEntity;

/**
 * Prevent farmers (pets) from being killed or damaged. They must be removable only via the farmer menu.
 */
public class PetProtectionListener implements Listener {

    private final PetManager manager;

    public PetProtectionListener(PetManager manager) {
        this.manager = manager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        // Avoid pattern matching for instanceof to keep Java 17 compatibility in all toolchains.
        if (!(e.getEntity() instanceof LivingEntity)) return;
        LivingEntity le = (LivingEntity) e.getEntity();
        try {
            if (manager != null && manager.isOurPet(le)) {
                e.setCancelled(true);
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof LivingEntity)) return;
        LivingEntity le = (LivingEntity) e.getEntity();
        try {
            if (manager != null && manager.isOurPet(le)) {
                e.getDrops().clear();
                e.setDroppedExp(0);
            }
        } catch (Throwable ignored) {}
    }
}
