package com.example.guardianparrot.listeners;


import org.bukkit.persistence.PersistentDataType;
import com.example.guardianparrot.GuardianParrotRuntime;
import com.example.guardianparrot.ParrotManager;
import com.example.guardianparrot.WorldsDenyService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Despawn/resummon guardians when entering/leaving denied regions. */
public final class RegionGateListener implements Listener {
    private final com.example.guardianparrot.GPPlugin plugin;
    private final ParrotManager manager;
    private final WorldsDenyService deny;
    private final Set<UUID> currentlyDenied = new HashSet<>();

    public RegionGateListener(com.example.guardianparrot.GPPlugin plugin, ParrotManager manager, WorldsDenyService deny){
        this.plugin = plugin;
        this.manager = manager;
        this.deny = deny;
    }

    private void tick(Player p){
        boolean denied = deny.isDeniedHere(p);
        boolean wasDenied = currentlyDenied.contains(p.getUniqueId());
        if (denied && !wasDenied){
            currentlyDenied.add(p.getUniqueId());
            // Despawn only, do NOT modify players.yml
            manager.despawnAllFor(p);
            p.sendMessage("§eВаши хранители скрылись в запретном регионе.");
        } else if (!denied && wasDenied){
            currentlyDenied.remove(p.getUniqueId());
            // Resummon based on players.yml
            manager.resummonAllForDebounced(p);
            p.sendMessage("§aЗапретная зона покинута. Хранители возвращены.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e){
        // Only react when block position changed
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()){
            return;
        }
        tick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e){
        tick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e){
        // slight delay to ensure world/regions are fully loaded
        Bukkit.getScheduler().runTask(plugin.host(), () -> tick(e.getPlayer()));
    }
}
