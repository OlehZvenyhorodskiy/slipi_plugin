package com.example.guardianparrot;

import org.bukkit.Material;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class GuardianEggHandler implements Listener {
    private final ParrotManager manager;
    public GuardianEggHandler(ParrotManager manager){ this.manager = manager; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseEgg(PlayerInteractEvent e){
        Player p = e.getPlayer();
        EquipmentSlot hand = e.getHand() == null ? EquipmentSlot.HAND : e.getHand();
        handleEggUse(p, hand, e);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseEggOnEntity(PlayerInteractEntityEvent e){
        Player p = e.getPlayer();
        EquipmentSlot hand = e.getHand();
        handleEggUse(p, hand, e);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseEggAtEntity(PlayerInteractAtEntityEvent e){
        Player p = e.getPlayer();
        EquipmentSlot hand = e.getHand();
        handleEggUse(p, hand, e);
    }

    private void handleEggUse(Player player, EquipmentSlot hand, Event ev){
        if (hand == null) hand = EquipmentSlot.HAND;
        PlayerInventory inv = player.getInventory();
        ItemStack item = (hand == EquipmentSlot.OFF_HAND) ? inv.getItemInOffHand() : inv.getItemInMainHand();
        if (item == null || item.getType() != Material.PARROT_SPAWN_EGG) return;
        ItemMeta meta = item.getItemMeta(); if (meta == null) return;
// validate unique egg_id
String eggId = meta.getPersistentDataContainer().get(manager.eggIdKey, PersistentDataType.STRING);
if (eggId == null || eggId.isEmpty() || !manager.eggExists(eggId)){
    // cancel vanilla usage and notify
    if (ev instanceof org.bukkit.event.player.PlayerInteractEvent pie){
        pie.setCancelled(true);
        try { pie.setUseItemInHand(org.bukkit.event.Event.Result.DENY); } catch (Throwable ignored){}
        try { pie.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY); } catch (Throwable ignored){}
    } else if (ev instanceof org.bukkit.event.player.PlayerInteractEntityEvent pee){
        pee.setCancelled(true);
    } else if (ev instanceof org.bukkit.event.player.PlayerInteractAtEntityEvent paee){
        paee.setCancelled(true);
    }
    // удалить предмет-яйцо из руки, т.к. оно недействительно
    int amtInvalid = item.getAmount();
    if (amtInvalid > 1){ item.setAmount(amtInvalid - 1); }
    else {
        if (hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND) inv.setItemInOffHand(null);
        else inv.setItemInMainHand(null);
    }
    try { player.updateInventory(); } catch (Throwable ignored) {}
    player.sendMessage("§cЭто яйцо недействительно (ID не найден).");
    return;
}
        Byte mark = meta.getPersistentDataContainer().get(manager.eggKey, PersistentDataType.BYTE);
        if (mark == null || mark != (byte)1) return; // не наше яйцо

        // лимит
        int maxPets = manager.getPlugin().getConfig().getInt("max-pets", 4);
        int currentPets = manager.getPlayersYaml().getStringList("players."+player.getUniqueId()+".pets").size();
        if (currentPets >= maxPets){
            player.sendMessage("§cВы достигли лимита хранителей ("+maxPets+")!");
            // Отменяем ванилу/расход
            if (ev instanceof PlayerInteractEvent pie){
                pie.setCancelled(true);
                try { pie.setUseItemInHand(Event.Result.DENY); } catch (Throwable ignored) {}
                try { pie.setUseInteractedBlock(Event.Result.DENY); } catch (Throwable ignored) {}
            } else if (ev instanceof PlayerInteractEntityEvent pee){
                pee.setCancelled(true);
            } else if (ev instanceof PlayerInteractAtEntityEvent paee){
                paee.setCancelled(true);
            }
            // Снимок и восстановление через тик (если вдруг «съело»)
            final ItemStack snapshot = item.clone();
            try { player.updateInventory(); } catch (Throwable ignored) {}
            final EquipmentSlot fHand = hand;
            final PlayerInventory fInv = inv;
            final ItemStack fSnap = (snapshot == null ? null : snapshot.clone());
            final Player fPlayer = player;
            manager.getPlugin().getServer().getScheduler().runTaskLater(manager.getPlugin().host(), () -> {
                if (!fPlayer.isOnline()) return;
                ItemStack cur = (fHand == EquipmentSlot.OFF_HAND) ? fInv.getItemInOffHand() : fInv.getItemInMainHand();
                boolean needRestore = false;
                if (fSnap != null) {
                    if (cur == null || cur.getAmount() != fSnap.getAmount() || !cur.isSimilar(fSnap)) needRestore = true;
                } else {
                    if (cur != null) needRestore = true;
                }
                if (needRestore){
                    if (fHand == EquipmentSlot.OFF_HAND) fInv.setItemInOffHand(fSnap);
                    else fInv.setItemInMainHand(fSnap);
                    try { fPlayer.updateInventory(); } catch (Throwable ignored) {}
                }
            }, 1L);
            return;
        }

        // успешный призыв (как /summon)
        if (ev instanceof PlayerInteractEvent) ((PlayerInteractEvent)ev).setCancelled(true);
        int forcedIndex = -1;
        Parrot par = manager.summonParrotWithIndex(player, player.getLocation(), false, forcedIndex);
        if (par != null){
            // remove this egg from registry to prevent reuse
            try {
                String eggIdUsed = meta.getPersistentDataContainer().get(manager.eggIdKey, PersistentDataType.STRING);
                if (eggIdUsed != null) manager.removeEggRecord(eggIdUsed);
            } catch (Throwable ignored){}
            // потратить 1 яйцо из соответствующей руки
            int amt = item.getAmount();
            if (amt > 1){ item.setAmount(amt - 1); }
            else {
                if (hand == EquipmentSlot.OFF_HAND) inv.setItemInOffHand(null);
                else inv.setItemInMainHand(null);
            }
            try { player.updateInventory(); } catch (Throwable ignored) {}
        }
    }
}