package com.example.guardianparrot;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class GuardianListener implements Listener {

    private final ParrotManager manager;

    public GuardianListener(ParrotManager manager){
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e){
        Player p = e.getPlayer();
        manager.resummonAllForDebounced(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e){
        Player p = e.getPlayer();
        manager.resummonAllForDebounced(p);
    }

    // Блок естественного спавна попугаев, чтобы не появлялись чужие без ИИ
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onParrotNaturalSpawn(CreatureSpawnEvent e){
        if (e.getEntityType() != EntityType.PARROT) return;
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL){
            e.setCancelled(true);
        }
    }

    // Страховка: при уроне по нашему питомцу убеждаемся, что ИИ и голограмма на месте
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatTouch(EntityDamageEvent e){
        if (!(e.getEntity() instanceof Parrot p)) return;
        if (!p.getPersistentDataContainer().has(manager.guardKey(), PersistentDataType.BYTE)) return;

        UUID parrotId = p.getUniqueId();
        // Восстановить ИИ при необходимости
        if (!manager.hasTask(parrotId)){
            String ownerStr = p.getPersistentDataContainer().get(manager.ownerKey(), PersistentDataType.STRING);
            if (ownerStr != null){
                try {
                    UUID owner = UUID.fromString(ownerStr);
                    manager.restartFor(owner, parrotId, p);
                } catch (IllegalArgumentException ignored){}
            }
        }
        // Перевесить голограмму (если кто-то удалил)
        try { manager.reattachHolo(p); } catch (Throwable ignored){}
    }
}
