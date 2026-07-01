package com.example.guardianparrot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.persistence.PersistentDataType;

public class GuardianBreedBlocker implements Listener {
    private final ParrotManager manager;
    public GuardianBreedBlocker(ParrotManager manager){ this.manager = manager; }
    private boolean isOur(Entity e){
        return e != null && e.getType() == EntityType.PARROT
                && e.getPersistentDataContainer().has(manager.guardKey(), PersistentDataType.BYTE);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e){
        if (isOur(e.getFather()) || isOur(e.getMother()) || isOur(e.getEntity())){
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(GuardianParrotRuntime.get().host(), () -> {
                if (e.getEntity() != null && e.getEntity().isValid()) e.getEntity().remove();
            });
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e){
        if (e.getEntityType() == EntityType.PARROT && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING){
            e.setCancelled(true);
            e.getEntity().remove();
        }
    }
}
