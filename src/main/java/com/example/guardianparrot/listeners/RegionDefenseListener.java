package com.example.guardianparrot.listeners;

import com.example.guardianparrot.ParrotManager;
import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import me.aquaprivate.hook.AquaClansHook;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Optional;
import java.util.UUID;

/**
 * Region-based defense:
 * - If a hostile mob or an untrusted player attacks the private owner or a member of that private region,
 *   guardians bound to the same private region will attack the aggressor.
 */
public class RegionDefenseListener implements Listener {

    private final AquaPrivatePlugin plugin;
    private final ParrotManager manager;

    public RegionDefenseListener(AquaPrivatePlugin plugin, ParrotManager manager){
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e){
        if (!(e.getEntity() instanceof Player victim)) return;

        Entity attacker = e.getDamager();
        if (attacker instanceof Projectile proj){
            ProjectileSource shooter = proj.getShooter();
            if (shooter instanceof Entity ent) attacker = ent;
        }
        if (!(attacker instanceof LivingEntity)) return;

        // Determine private region at victim location
        String regionId = findPrivateRegionId(victim.getLocation());
        if (regionId == null) return;

        // Ensure victim is the private owner or added member
        if (!isTrustedInRegion(regionId, victim.getUniqueId())) return;

        // If attacker is player and trusted OR same clan -> ignore
        if (attacker instanceof Player ap){
            if (isTrustedInRegion(regionId, ap.getUniqueId())) return;

            String clanV = AquaClansHook.getClan(victim.getUniqueId());
            String clanA = AquaClansHook.getClan(ap.getUniqueId());
            if (clanV != null && clanA != null && clanV.equalsIgnoreCase(clanA)) return;
        }

        // Notify guardians for this region
        manager.notifyRegionUnderAttack(regionId, attacker);
    }

    private String findPrivateRegionId(Location loc){
        try {
            if (loc == null || loc.getWorld() == null) return null;
            var container = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
            var rm = container.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(loc.getWorld()));
            if (rm == null) return null;
            var bv = com.sk89q.worldedit.math.BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            var set = rm.getApplicableRegions(bv);
            if (set == null) return null;
            for (var pr : set){
                if (pr == null) continue;
                String id = pr.getId();
                if (id == null) continue;
                Optional<PrivateRecord> rec = plugin.store().byRegionId(id);
                if (rec.isPresent()) return id;
            }
            return null;
        } catch (Throwable ignored){
            return null;
        }
    }

    private boolean isTrustedInRegion(String regionId, UUID playerId){
        if (regionId == null || playerId == null) return false;
        try {
            // Prefer AquaPrivate store (owner)
            Optional<PrivateRecord> rec = plugin.store().byRegionId(regionId);
            if (rec.isPresent()){
                if (playerId.equals(rec.get().owner)) return true;
            }

            // Also accept WorldGuard members/owners
            PrivateRecord r = rec.orElse(null);
            // Need world: use stored record location if possible
            if (r == null || r.toLocation() == null || r.toLocation().getWorld() == null) return false;
            var container = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
            var rm = container.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(r.toLocation().getWorld()));
            if (rm == null) return false;
            var pr = rm.getRegion(regionId);
            if (pr == null) return false;

            try {
                var wgId = com.sk89q.worldguard.domains.PlayerDomain.class; // just to ensure class load
            } catch (Throwable ignored2) {}

            return pr.getOwners().contains(playerId) || pr.getMembers().contains(playerId);
        } catch (Throwable ignored){
            return false;
        }
    }
}
