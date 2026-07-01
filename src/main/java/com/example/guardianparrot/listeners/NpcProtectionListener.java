package com.example.guardianparrot.listeners;

import com.example.guardianparrot.integration.CitizensHook;
import com.example.guardianparrot.util.GuardMarkers;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

public final class NpcProtectionListener implements Listener {

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() == null) return;
        if (GuardMarkers.isGuardianParrot(e.getEntity()) && CitizensHook.isNPC(e.getTarget())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (CitizensHook.isNPC(e.getEntity()) && GuardMarkers.isGuardianParrot(e.getDamager())) {
            e.setCancelled(true);
        }
    }
}
