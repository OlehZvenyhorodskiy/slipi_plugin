package com.example.guardianparrot;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

public class OwnerAttackListener implements Listener {
    private final ParrotManager manager;
    public OwnerAttackListener(ParrotManager manager){ this.manager = manager; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerDamaged(EntityDamageByEntityEvent e){
        if (!(e.getEntity() instanceof Player owner)) return;
        if (manager.getPets(owner.getUniqueId()).isEmpty()) return;
        Entity attacker = e.getDamager();
        if (attacker instanceof Projectile proj){
            ProjectileSource shooter = proj.getShooter();
            if (shooter instanceof Entity shooterEntity){
                attacker = shooterEntity;
            }
        }
        manager.notifyOwnerUnderAttack(owner, attacker);
    }
}
