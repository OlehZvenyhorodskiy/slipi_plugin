package com.fermerpets;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class PetAIGuard implements Listener {
    private final FermerPetsModule plugin;

    public PetAIGuard(FermerPetsModule plugin){
        this.plugin = plugin;
    }

    private boolean isOurPet(Entity e){
        if (!(e instanceof org.bukkit.entity.LivingEntity le)) return false;
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        Byte flag = pdc.get(plugin.getManager().getPetKey(), PersistentDataType.BYTE);
        return flag != null && flag == (byte)1;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e){
        Entity ent = e.getEntity();
        if (!isOurPet(ent)) return;
        if (ent instanceof Mob m){
            try { m.setTarget(null); } catch (Throwable ignored){}
        }
        e.setCancelled(true);
    }

    // Запрещаем вашим питомцам (особенно эндерменам) менять блоки
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e){
        if (!isOurPet(e.getEntity())) return;
        e.setCancelled(true);
    }

    // Запрещаем трейд с нашим "жителем-питомцем"
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e){
        if (!(e.getRightClicked() instanceof Villager)) return;
        if (!isOurPet(e.getRightClicked())) return;
        e.setCancelled(true);
    }
}
