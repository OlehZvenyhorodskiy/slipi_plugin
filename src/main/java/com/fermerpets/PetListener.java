package com.fermerpets;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import org.bukkit.event.player.PlayerKickEvent;

import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class PetListener implements Listener {
    private final FermerPetsModule plugin;
    private final PetManager manager;

    public PetListener(FermerPetsModule plugin, PetManager manager){
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e){
        // Farmers are bound to private blocks and reconciled on plugin enable.
        // Do NOT re-summon near players on join (it causes duplicates and wrong spawn location).
        try {
            com.fermerpets.ChunkPinService.updatePinsForOwner(plugin.plugin(), e.getPlayer().getUniqueId(), true);
        } catch (Throwable ignored) {}
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e){
        // No auto-resummon on respawn.
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e){
        // Farmers must stay in the world even when the owner is offline.
        try {
            com.fermerpets.ChunkPinService.updatePinsForOwner(plugin.plugin(), e.getPlayer().getUniqueId(), false);
        } catch (Throwable ignored) {}
    }

    // Spawn eggs are no longer used.
    private void consumeOne(Player p, PlayerInteractEvent e){
        try{
            if (e.getHand() == EquipmentSlot.OFF_HAND){
                ItemStack off = p.getInventory().getItemInOffHand();
                if (off != null){
                    int amt = off.getAmount();
                    if (amt > 1){ off.setAmount(amt-1); } else { p.getInventory().setItemInOffHand(null); }
                }
            } else {
                ItemStack main = p.getInventory().getItemInMainHand();
                if (main != null){
                    int amt = main.getAmount();
                    if (amt > 1){ main.setAmount(amt-1); } else { p.getInventory().setItemInMainHand(null); }
                }
            }
        } catch (Throwable ignored){}
    }


// Разрешаем ванильные яйца в спавнеры и запрещаем яйца плагина (с egg_id)


// Разрешаем ванильные яйца в спавнеры и запрещаем яйца плагина (с egg_id)


// Разрешаем ванильные яйца в спавнеры и запрещаем яйца плагина (с egg_id)
    // Разрешаем ванильные яйца в спавнеры и запрещаем яйца плагина (с egg_id)

    @EventHandler
    public void onKick(PlayerKickEvent e){
        try { manager.forceDespawnAllByOwnerUUID(e.getPlayer().getUniqueId()); } catch (Throwable ignored){}
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e){
        try {
            java.util.Set<java.util.UUID> kos = manager.getKillOnSight();
            if (kos == null || kos.isEmpty()) return;
            for (org.bukkit.entity.Entity ent : e.getChunk().getEntities()){
                if (!(ent instanceof org.bukkit.entity.LivingEntity le)) continue;
                java.util.UUID id = le.getUniqueId();
                if (!kos.contains(id)) continue;
                org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();
                Byte flag = pdc.get(manager.getPetKey(), org.bukkit.persistence.PersistentDataType.BYTE);
                if (flag != null && flag == (byte)1){
                    try { manager.stopPetBrainPublic(id); } catch (Throwable ignored){}
                    try { le.remove(); } catch (Throwable ignored){}
                    kos.remove(id);
                }
            }
        

// === Deduplicate / enforce fixed-slot records on chunk load ===
// Keep this VERY lightweight: no disk IO, and avoid repeating work per entity.
// If a farmer entity exists but the slot in players.yml is inactive OR points to another UUID,
// this entity is considered stray/duplicate and must be removed.
try {
    com.fermerpets.PlayersStore ps = manager.getPlayersStore();
    java.util.HashSet<java.util.UUID> migrated = new java.util.HashSet<>();
    for (org.bukkit.entity.Entity ent : e.getChunk().getEntities()){
        if (!(ent instanceof org.bukkit.entity.LivingEntity le)) continue;
        org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();
        Byte flag = pdc.get(manager.getPetKey(), org.bukkit.persistence.PersistentDataType.BYTE);
        if (flag == null || flag != (byte)1) continue;
        String ownerStr = pdc.get(manager.getOwnerKey(), org.bukkit.persistence.PersistentDataType.STRING);
        if (ownerStr == null || ownerStr.isBlank()) continue;
        java.util.UUID oid;
        try { oid = java.util.UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }
        Integer idx = pdc.get(new org.bukkit.NamespacedKey(plugin.plugin(), "farmer_index"), org.bukkit.persistence.PersistentDataType.INTEGER);
        if (idx == null || idx < 1 || idx > 3) idx = 1;

        if (migrated.add(oid)) {
            try { ps.migrateIfNeeded(oid); } catch (Throwable ignored) {}
        }

        java.util.UUID expected = ps.getFarmer(oid, idx);
        if (expected == null || !expected.equals(le.getUniqueId())){
            try { manager.stopPetBrainPublic(le.getUniqueId()); } catch (Throwable ignored) {}
            try { le.remove(); } catch (Throwable ignored) {}
            try { kos.remove(le.getUniqueId()); } catch (Throwable ignored) {}
        }
    }
} catch (Throwable ignored) {}
} catch (Throwable ignored){}
    }

}
