
package com.fermerpets;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.bukkit.Location;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards against vanilla mob spawns when using plugin spawn eggs.
 * Cancels right-click with our eggs (so Vanilla won't spawn a mob).
 * Also cancels dispenser usage of our eggs and guards CreatureSpawn as a fallback.
 */
public final class SpawnGuardListener implements Listener {

    private final FermerPetsModule plugin;
    private final NamespacedKey eggKey;

    // Recent egg-use marks to correlate with CreatureSpawn (fallback safety)
    private static final Map<Location, Long> recentEggUse = new ConcurrentHashMap<>();

    public SpawnGuardListener(FermerPetsModule plugin){
        this.plugin = plugin;
        this.eggKey = new NamespacedKey(plugin.plugin(), "egg_id");
    }

    private boolean isOurEgg(ItemStack item){
        if (item == null) return false;
        try{
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return false;
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            String eggId = pdc.get(eggKey, PersistentDataType.STRING);
            return eggId != null && !eggId.isEmpty();
        } catch (Throwable ignored){}
        return false;
    }

    private void markUse(Location loc){
        if (loc == null || loc.getWorld() == null) return;
        recentEggUse.put(loc.clone(), System.currentTimeMillis());
        // cleanup old
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Location, Long>> it = recentEggUse.entrySet().iterator();
        while (it.hasNext()){
            Map.Entry<Location, Long> e = it.next();
            if (now - e.getValue() > 1000L) it.remove(); // keep 1s window
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerUseEgg(PlayerInteractEvent e){
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (!isOurEgg(item)) return;

        // Cancel vanilla handling so no mob spawns
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (p != null){
            markUse(p.getLocation());
        }

        // Let the existing plugin logic handle summoning (PetListener/PetManager)
        // We don't summon here to avoid double-spawn; we only guard from vanilla.
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDispenser(BlockDispenseEvent e){
        ItemStack item = e.getItem();
        if (!isOurEgg(item)) return;
        // Disallow dispensing our eggs to avoid vanilla mobs
        e.setCancelled(true);
        if (e.getBlock() != null && e.getBlock().getWorld() != null){
            markUse(e.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreatureSpawn(CreatureSpawnEvent e){
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) return;

        // Fallback: if there was a recent use of our egg near this location, cancel vanilla spawn
        Location loc = e.getLocation();
        long now = System.currentTimeMillis();
        for (Map.Entry<Location, Long> en : recentEggUse.entrySet()){
            Location l = en.getKey();
            if (l.getWorld() != loc.getWorld()) continue;
            if (l.distanceSquared(loc) <= 9 && now - en.getValue() <= 1000L){
                e.setCancelled(true);
                return;
            }
        }
    }
}
