package com.example.guardianparrot.listeners;

import com.example.guardianparrot.GuardianParrotModule;
import com.example.guardianparrot.GuardianStateStore;
import com.example.guardianparrot.ParrotManager;
import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Parrot;
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
 * Custom HP + death/respawn logic for guardians.
 *
 * Requirements:
 * - Each guardian has 50 HP (independent per guardian).
 * - Guardians can be damaged by players NOT in region members/owners.
 * - When HP reaches 0: guardian disappears and can reappear only after 3 minutes.
 */
public class GuardianHpListener implements Listener {

    private final AquaPrivatePlugin aqua;
    private final GuardianParrotModule module;
    private final ParrotManager manager;
    private final GuardianStateStore state;

    public GuardianHpListener(AquaPrivatePlugin aqua, GuardianParrotModule module, ParrotManager manager, GuardianStateStore state) {
        this.aqua = aqua;
        this.module = module;
        this.manager = manager;
        this.state = state;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Parrot p)) return;
        if (!isGuardian(p)) return;

        Entity damager = e.getDamager();
        if (damager instanceof Projectile proj) {
            ProjectileSource shooter = proj.getShooter();
            if (shooter instanceof Entity ent) damager = ent;
        }

        if (!(damager instanceof Player attacker)) return;

        UUID ownerId = getOwner(p);
        String regionId = getRegionId(p);
        int slot = getSlot(p);

        if (ownerId == null || regionId == null || slot <= 0) return;

        // Trusted players (owner/members) are NOT allowed to damage their guardian.
        if (isTrustedInRegion(regionId, attacker.getUniqueId(), p.getLocation())) {
            e.setCancelled(true);
            return;
        }

        int dmg = (int) Math.ceil(Math.max(1.0, e.getFinalDamage()));
        e.setCancelled(true); // use our custom HP system only

        // Determine private center (anchor) for broadcast
        Location center = null;
        try { center = state.getAnchor(ownerId, regionId, slot); } catch (Throwable ignored) {}
        if (center == null) {
            try {
                Optional<PrivateRecord> rec = aqua.store().byRegionId(regionId);
                if (rec.isPresent()) center = rec.get().toLocation();
            } catch (Throwable ignored) {}
        }
        if (center == null) center = p.getLocation();

        // Delegate to module (it applies HP, death, cooldown and respawn)
        try {
            module.damageGuardianEntityWithBroadcast(p, dmg, center, 100);
        } catch (Throwable ignored) {}
    }

    private boolean isGuardian(Entity ent) {
        try {
            return ent.getPersistentDataContainer().has(manager.guardKey(), PersistentDataType.BYTE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private UUID getOwner(Entity ent) {
        try {
            String s = ent.getPersistentDataContainer().get(manager.ownerKey(), PersistentDataType.STRING);
            if (s == null || s.isEmpty()) return null;
            return UUID.fromString(s);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String getRegionId(Entity ent) {
        try {
            String s = ent.getPersistentDataContainer().get(manager.regionIdKey, PersistentDataType.STRING);
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int getSlot(Entity ent) {
        try {
            Integer s = ent.getPersistentDataContainer().get(manager.slotKey, PersistentDataType.INTEGER);
            return s == null ? -1 : s;
        } catch (Throwable ignored) {
            return -1;
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
