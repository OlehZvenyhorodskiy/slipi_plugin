
package com.example.guardianparrot;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
// IMPORTANT: Avoid per-pet scheduled tasks here. They destroy TPS when many guardians exist.
import com.example.guardianparrot.GPPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Hologram that sticks to the pet with zero visual lag. */
public class HoloLabelService {
    private final GPPlugin plugin;
    private final ParrotManager manager;
    private final Map<UUID, ArmorStand> holos = new ConcurrentHashMap<>();
    private static final double OFFSET_Y = 1.1;

    public HoloLabelService(GPPlugin plugin, ParrotManager manager){
        this.plugin = plugin;
        this.manager = manager;
    }

    // Kept for API compatibility but not used in the name anymore.
    public static class PetStats {
        public final int witherDamage;
        public final int wardenDamage;
        public final int tpRange;
        public PetStats(int witherDamage, int wardenDamage, int tpRange){
            this.witherDamage = witherDamage;
            this.wardenDamage = wardenDamage;
            this.tpRange = tpRange;
        }
    }

    public void attach(Parrot parrot, Player owner, PetStats stats){
        if (parrot == null || parrot.isDead()) return;
        ArmorStand as = holos.get(parrot.getUniqueId());
        if (as == null || as.isDead()) {
            Location at = parrot.getLocation().clone().add(0, OFFSET_Y, 0);
            as = parrot.getWorld().spawn(at, ArmorStand.class, stand -> {
                stand.setVisible(false);
                stand.setSmall(true);
                stand.setMarker(true);
                stand.setGravity(false);
                stand.setCustomNameVisible(true);

                // Mark as guardian entity so chunk/session cleanup can remove it.
                try {
                    stand.setMetadata("guardianparrot", new org.bukkit.metadata.FixedMetadataValue(plugin.host(), true));
                } catch (Throwable ignored) {}
                try {
                    var pdc = stand.getPersistentDataContainer();
                    pdc.set(manager.guardKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                    // Store parrot UUID so we can delete ONLY the matching holo.
                    pdc.set(manager.avatarParrotIdKey, org.bukkit.persistence.PersistentDataType.STRING, parrot.getUniqueId().toString());
                    if (owner != null) {
                        pdc.set(manager.ownerKey(), org.bukkit.persistence.PersistentDataType.STRING, owner.getUniqueId().toString());
                    }
                } catch (Throwable ignored) {}
            });
            holos.put(parrot.getUniqueId(), as);
        }
        as.setCustomName(renderName(parrot, owner)); // no [x/y] suffix anymore
        // IMPORTANT:
        // We do NOT mount the hologram as a passenger.
        // Guardians already use an ArmorStand passenger as a "visual avatar".
        // Passenger stacking order is not stable across versions and can place the label *below* the parrot.
        // Instead we sync the holo position here (this method is called from the guardian task).
        try {
            Location at = parrot.getLocation().clone().add(0, OFFSET_Y, 0);
            if (as.isValid()) as.teleport(at);
        } catch (Throwable ignored) {}
    }

    public void refresh(Parrot parrot, PetStats stats){
        ArmorStand as = holos.get(parrot.getUniqueId());
        if (as == null || as.isDead()) return;
        try { as.setCustomName(renderName(parrot, null)); } catch (Throwable ignored) {}
        try {
            Location at = parrot.getLocation().clone().add(0, OFFSET_Y, 0);
            if (as.isValid()) as.teleport(at);
        } catch (Throwable ignored) {}
    }

    public void detach(Entity parrot){
        if (parrot == null) return;
        UUID id = parrot.getUniqueId();
        ArmorStand as = holos.remove(id);
        if (as != null) { try { as.remove(); } catch (Throwable ignored) {} }
    }

    /**
     * Detach by parrot UUID even if the parrot entity is already gone/unloaded.
     * This is important when chunks unload and Bukkit.getEntity(uuid) returns null.
     */
    public void detachById(UUID parrotId){
        if (parrotId == null) return;
        ArmorStand as = holos.remove(parrotId);
        if (as != null) {
            try { as.remove(); } catch (Throwable ignored) {}
        }
    }

    public void detachAll(){
        for (ArmorStand as : holos.values()){ try { as.remove(); } catch (Throwable ignored) {} }
        holos.clear();
    }

    private String renderName(Parrot parrot, Player owner){
        String base = parrot.getCustomName();
        if (base == null || base.isEmpty()) {
            String ownerName = (owner != null ? owner.getName() : "owner");
            base = "§aхранитель (" + ownerName + ")";
        }
        return base;
    }
}
