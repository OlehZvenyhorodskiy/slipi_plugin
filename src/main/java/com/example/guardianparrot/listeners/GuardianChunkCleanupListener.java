package com.example.guardianparrot.listeners;

import com.example.guardianparrot.ParrotManager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Parrot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Prevents "orphan" armor stands (head + armor) from remaining in the world.
 * We only remove stands marked as guardian that are NOT mounted to a parrot.
 * This keeps active visuals intact while ensuring leftovers are cleaned up even when chunks unload.
 */
public final class GuardianChunkCleanupListener implements Listener {

    private final ParrotManager manager;

    public GuardianChunkCleanupListener(ParrotManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent e) {
        try {
            for (Entity ent : e.getChunk().getEntities()) {
                if (!(ent instanceof ArmorStand as)) continue;
                boolean isGuardian;
                try {
                    isGuardian = as.hasMetadata("guardianparrot")
                            || as.getPersistentDataContainer().has(manager.guardKey(), PersistentDataType.BYTE);
                } catch (Throwable t) {
                    continue;
                }
                if (!isGuardian) continue;

                // If the stand is mounted to a parrot, keep it (it will unload together).
                Entity veh = as.getVehicle();
                if (veh instanceof Parrot) continue;

                // Otherwise it's an orphan visual — remove it.
                // Also clear stale holo map entry if this stand belongs to a specific parrot.
                try {
                    String pid = as.getPersistentDataContainer().get(manager.avatarParrotIdKey, PersistentDataType.STRING);
                    if (pid != null && !pid.isEmpty()) {
                        try { manager.cleanupOrphanHolo(java.util.UUID.fromString(pid)); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                try { as.remove(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}
