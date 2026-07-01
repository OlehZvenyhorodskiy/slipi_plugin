package com.fermerpets;

import com.fermerpets.integration.WGHook;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Set;

/** World/region transitions: do not modify players.yml on auto actions. */
public class RegionGateListener implements Listener {
    private final FermerPetsModule plugin;
    private final PetManager manager;
    private final WorldsDenyService worlds;

    public RegionGateListener(FermerPetsModule plugin, PetManager manager, WorldsDenyService worlds){
        this.plugin = plugin;
        this.manager = manager;
        this.worlds = worlds;
    }

    private void scheduleResummon(final Player p, final int attempt){
        if (attempt > 3) return;
        plugin.getServer().getScheduler().runTaskLater(plugin.plugin(), () -> {
            Set<String> allowed = worlds.getAllowedForWorld(p.getWorld().getName());
            boolean ok = (allowed == null) || WGHook.isAllowedForOwnerOrMember(p, allowed);
            if (ok){
                manager.resummonAllFromRecords(p);
                int target = Math.min(manager.countRecordedPets(p.getUniqueId()), manager.getMaxPets());
                if (manager.countActivePets(p.getUniqueId()) < target){
                    scheduleResummon(p, attempt+1);
                }
            } else {
                scheduleResummon(p, attempt+1);
            }
        }, attempt==1 ? 20L : (attempt==2 ? 60L : 100L));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e){
        scheduleResummon(e.getPlayer(), 1);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e){
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        Player p = e.getPlayer();
        Set<String> allowed = worlds.getAllowedForWorld(p.getWorld().getName());
        if (allowed == null){
            // world not configured -> allow everywhere
            plugin.getServer().getScheduler().runTaskLater(plugin.plugin(), () -> manager.ensureActiveFromRecords(p), 5L);
            return;
        }
        boolean ok = WGHook.isAllowedForOwnerOrMember(p, allowed);
        if (!ok){
            manager.unsummonAllEntitiesOnly(p);
            p.sendMessage(ChatColor.RED + "Питомец задеспавнен — вне разрешённой зоны.");
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin.plugin(), () -> manager.ensureActiveFromRecords(p), 5L);
        }
    }
}
