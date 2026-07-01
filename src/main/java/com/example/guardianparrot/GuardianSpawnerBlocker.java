package com.example.guardianparrot;


import org.bukkit.persistence.PersistentDataType;import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;

public class GuardianSpawnerBlocker implements Listener {

    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onApplyEggToSpawner(PlayerInteractEvent e){
        if (e.getClickedBlock() == null) return;
        if (e.getClickedBlock().getType() != Material.SPAWNER) return;
        if (e.getHand() != EquipmentSlot.HAND) return; // только правая рука
        ItemStack item = e.getItem();
        if (item == null) return;
        if (!(item.getItemMeta() instanceof SpawnEggMeta)) return; // не яйцо спавна

        // Разрешить ванильные яйца, запретить яйца плагина (по нашему eggIdKey)
        try {
            ParrotManager manager = GuardianParrotRuntime.get().getManager();
            if (manager != null && item.hasItemMeta()
                    && item.getItemMeta().getPersistentDataContainer().has(manager.eggIdKey, org.bukkit.persistence.PersistentDataType.STRING)) {
                // Это кастомное яйцо из плагина — запрещаем использовать на спавнере
                e.setCancelled(true);
                e.getPlayer().sendMessage("§cЯйца хранителя нельзя применять к спавнеру.");
            }
            // иначе: ванильное яйцо — ничего не отменяем
        } catch (Throwable t){
            // На всякий случай: если что-то пошло не так — не ломаем ваниллу
            // Ничего не отменяем
        }
    }
}
