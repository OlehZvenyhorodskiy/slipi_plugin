package com.example.guardianparrot;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class GuardianSightAnnounce implements Listener {
    private final ParrotManager manager;
    private final Map<UUID, Long> lastToast = new HashMap<>(); // per-owner cooldown

    public GuardianSightAnnounce(ParrotManager manager){
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(org.bukkit.event.entity.EntityDamageByEntityEvent e){
        if (!(e.getDamager() instanceof Parrot p)) return;
        if (!(e.getEntity() instanceof org.bukkit.entity.Monster target)) return;
        if (!p.getPersistentDataContainer().has(manager.guardKey, PersistentDataType.BYTE)) return;

        String ownerStr = p.getPersistentDataContainer().get(manager.ownerKey, PersistentDataType.STRING);
        if (ownerStr == null) return;
        java.util.UUID ownerId;
        try { ownerId = java.util.UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ return; }
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) return;

        long now = System.currentTimeMillis();
        Long last = lastToast.get(ownerId);
        if (last != null && now - last < 2000) return; // 2s cooldown
        lastToast.put(ownerId, now);

        String mob = target.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_',' ');
        String msg = "cmi toast " + owner.getName() + " &6Вижу " + mob;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), msg);
    }
}
