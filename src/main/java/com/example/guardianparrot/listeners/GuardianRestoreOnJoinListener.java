package com.example.guardianparrot.listeners;

import com.example.guardianparrot.GuardianParrotModule;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * After a player rejoins the server, SessionCleanupListener despawns all guardians.
 * We must restore them back from {@link com.example.guardianparrot.GuardianStateStore}.
 */
public final class GuardianRestoreOnJoinListener implements Listener {

    private final GuardianParrotModule module;

    public GuardianRestoreOnJoinListener(GuardianParrotModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        // Delay a bit: WorldGuard regions + AquaPrivate store might not be ready instantly
        // on heavily loaded servers / after plugin reload.
        Bukkit.getScheduler().runTaskLater(module.host(), () -> {
            try {
                module.restoreForJoin(e.getPlayer());
            } catch (Throwable ignored) {
                // ignore
            }
        }, 40L);
    }
}
