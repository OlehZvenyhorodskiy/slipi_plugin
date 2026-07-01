
package com.fermerpets;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class DebugTeleportListener implements Listener {
    private final FermerPetsModule plugin;
    public DebugTeleportListener(FermerPetsModule plugin){ this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityTeleport(EntityTeleportEvent e){
        if (!Debug.enabled()) return;
        try{
            PersistentDataContainer pdc = e.getEntity().getPersistentDataContainer();
            Byte flag = pdc.get(plugin.getManager().getPetKey(), PersistentDataType.BYTE);
            if (flag != null && flag == (byte)1){
                Debug.logPet(e.getEntity(), "EntityTeleportEvent from=" +
                        loc(e.getFrom()) + " to=" + loc(e.getTo()));
            }
        } catch (Throwable ignored){}
    }

    private static String loc(org.bukkit.Location l){
        if (l == null || l.getWorld() == null) return "null";
        return String.format("%s(%.2f,%.2f,%.2f)", l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }
}
