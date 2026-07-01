package com.example.guardianparrot.listeners;

import com.example.guardianparrot.ParrotManager;
import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Optional;
import java.util.UUID;

/**
 * Prevent guardians from damaging the private owner or trusted players in the same private region.
 */
public class GuardianFriendlyFireListener implements Listener {

    private final AquaPrivatePlugin aqua;
    private final ParrotManager manager;

    public GuardianFriendlyFireListener(AquaPrivatePlugin aqua, ParrotManager manager) {
        this.aqua = aqua;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        Entity damager = e.getDamager();
        if (damager instanceof Projectile proj) {
            ProjectileSource shooter = proj.getShooter();
            if (shooter instanceof Entity ent) damager = ent;
        }

        if (!isGuardian(damager)) return;

        UUID ownerId = getGuardianOwner(damager);
        String regionId = getGuardianRegionId(damager);

        // Always protect the owner.
        if (ownerId != null && ownerId.equals(victim.getUniqueId())) {
            e.setCancelled(true);
            return;
        }

        // Protect trusted players in the same region.
        if (regionId != null && isTrustedInRegion(regionId, victim.getUniqueId(), victim.getLocation())) {
            e.setCancelled(true);
        }
    }

    private boolean isGuardian(Entity ent) {
        if (ent == null) return false;
        try {
            return ent.getPersistentDataContainer().has(manager.guardKey(), PersistentDataType.BYTE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private UUID getGuardianOwner(Entity ent) {
        if (ent == null) return null;
        try {
            String s = ent.getPersistentDataContainer().get(manager.ownerKey(), PersistentDataType.STRING);
            if (s == null || s.isEmpty()) return null;
            return UUID.fromString(s);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String getGuardianRegionId(Entity ent) {
        if (ent == null) return null;
        try {
            String s = ent.getPersistentDataContainer().get(manager.regionIdKey, PersistentDataType.STRING);
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isTrustedInRegion(String regionId, UUID playerId, Location worldHint) {
        if (regionId == null || playerId == null) return false;
        try {
            if (aqua != null) {
                Optional<PrivateRecord> rec = aqua.store().byRegionId(regionId);
                if (rec.isPresent() && playerId.equals(rec.get().owner)) return true;
            }

            if (worldHint == null || worldHint.getWorld() == null) return false;
            var container = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
            var rm = container.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(worldHint.getWorld()));
            if (rm == null) return false;
            var pr = rm.getRegion(regionId);
            if (pr == null) return false;
            return pr.getOwners().contains(playerId) || pr.getMembers().contains(playerId);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
