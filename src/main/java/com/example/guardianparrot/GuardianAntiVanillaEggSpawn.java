package com.example.guardianparrot;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.entity.Parrot;
import org.bukkit.persistence.PersistentDataType;

/**
 * Safety net: blocks vanilla PARROT spawns via spawn eggs.
 * If это наш хранитель, у него всегда стоит guardKey в PDC.
 * Любой попугай, появившийся через SPAWNER_EGG без guardKey, отменяется.
 */
public class GuardianAntiVanillaEggSpawn implements Listener {
    private final ParrotManager manager;
    public GuardianAntiVanillaEggSpawn(ParrotManager manager){ this.manager = manager; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e){
        if (!(e.getEntity() instanceof Parrot)) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) return;
        Parrot par = (Parrot) e.getEntity();
        // Наших не трогаем (есть служебная метка guardKey)
        boolean isGuardian = par.getPersistentDataContainer().has(manager.guardKey, PersistentDataType.BYTE);
        if (!isGuardian){
            e.setCancelled(true);
        }
    }
}
