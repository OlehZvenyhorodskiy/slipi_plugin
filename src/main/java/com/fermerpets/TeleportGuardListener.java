
package com.fermerpets;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Cancels any teleport of our pets unless explicitly whitelisted. */
public final class TeleportGuardListener implements Listener {
    private final FermerPetsModule plugin;
    private final NamespacedKey petKey;
    private final NamespacedKey tpWhitelistKey;

    public TeleportGuardListener(FermerPetsModule plugin){
        this.plugin = plugin;
        this.petKey = plugin.getManager().getPetKey();
        this.tpWhitelistKey = new NamespacedKey(plugin.plugin(), "tp_whitelist");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityTeleport(EntityTeleportEvent e){
        Entity ent = e.getEntity();
        try{
            PersistentDataContainer pdc = ent.getPersistentDataContainer();
            Byte isPet = pdc.get(petKey, PersistentDataType.BYTE);
            if (isPet == null || isPet != (byte)1) return;

            // allowed only when whitelisted (set around delivery)
            Byte allow = pdc.get(tpWhitelistKey, PersistentDataType.BYTE);
            if (allow != null && allow == (byte)1) {
                Debug.logPet(ent, "TeleportGuard: allow whitelisted TP " + e.getFrom() + " -> " + e.getTo());
                return;
            }

            // else cancel
            e.setCancelled(true);
            Debug.logPet(ent, "TeleportGuard: CANCELLED unexpected TP " + e.getFrom() + " -> " + e.getTo());
        } catch (Throwable t){
            // fail open
        }
    }
}
