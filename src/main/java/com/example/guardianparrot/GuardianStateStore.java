package com.example.guardianparrot;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Stores which guardian (parrot) entity is bound to which owner + region slot (1..3).
 *
 * IMPORTANT: old versions stored UUID directly at "players.<uuid>.slotX" and then wrote
 * "players.<uuid>.slotX.lastCharge", which overwrote the UUID. This class supports reading
 * that legacy format and will migrate it on first write.
 */
public final class GuardianStateStore {

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration cfg;

    public GuardianStateStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "guardian_state.yml");
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    // ===== Region-aware API =====

    public UUID getPet(UUID owner, String regionId, int slot) {
        if (regionId == null || regionId.isBlank()) regionId = "__global__";
        // New path
        String pNew = "players." + owner + ".regions." + sanitize(regionId) + ".slot" + slot + ".pet";
        String sNew = cfg.getString(pNew);
        if (sNew != null && !sNew.isBlank()) {
            try { return UUID.fromString(sNew); } catch (Exception ignored) {}
        }

        // Legacy path (global only): "players.<uuid>.slotX" as a plain string
        if ("__global__".equals(regionId)) {
            String pOld = "players." + owner + ".slot" + slot;
            String sOld = cfg.getString(pOld);
            if (sOld != null && !sOld.isBlank()) {
                try { return UUID.fromString(sOld); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public void setPet(UUID owner, String regionId, int slot, UUID petId) {
        if (regionId == null || regionId.isBlank()) regionId = "__global__";
        String base = "players." + owner + ".regions." + sanitize(regionId) + ".slot" + slot;
        cfg.set(base + ".pet", petId == null ? null : petId.toString());

        // If we are writing global, remove legacy conflicting value to avoid overwrites.
        if ("__global__".equals(regionId)) {
            cfg.set("players." + owner + ".slot" + slot, null);
        }
        save();
    }

    public void setLastCharge(UUID owner, String regionId, int slot, long millis) {
        if (regionId == null || regionId.isBlank()) regionId = "__global__";
        String base = "players." + owner + ".regions." + sanitize(regionId) + ".slot" + slot;
        cfg.set(base + ".lastCharge", millis);

        // If legacy slot string exists, remove it to avoid type conflicts
        if ("__global__".equals(regionId)) {
            cfg.set("players." + owner + ".slot" + slot, null);
        }
        save();
    }

    public long getLastCharge(UUID owner, String regionId, int slot) {
        if (regionId == null || regionId.isBlank()) regionId = "__global__";
        String base = "players." + owner + ".regions." + sanitize(regionId) + ".slot" + slot;
        return cfg.getLong(base + ".lastCharge", 0L);
    }

    // ===== Guardian respawn cooldown (death) =====
    public void setDeadUntil(UUID owner, String regionId, int slot, long millis) {
        if (regionId == null || regionId.isBlank()) regionId = "__global__";
        String base = "players." + owner + ".regions." + sanitize(regionId) + ".slot" + slot;
        cfg.set(base + ".deadUntil", millis);

        // If legacy slot string exists, remove it to avoid type conflicts
        if ("__global__".equals(regionId)) {
            cfg.set("players." + owner + ".slot" + slot, null);
        }
        save();
    }

    public long getDeadUntil(UUID owner, String regionId, int slot) {
        if (regionId == null || regionId.isBlank()) regionId = "__global__";
        String base = "players." + owner + ".regions." + sanitize(regionId) + ".slot" + slot;
        return cfg.getLong(base + ".deadUntil", 0L);
    }


    // ===== Anchor (private-block) location per region-slot =====
    /**
     * Stores the private block (anchor) coordinates for this region slot.
     * Guardians must respawn at this anchor after reload.
     */
    public void setAnchor(UUID owner, String regionId, int slot, org.bukkit.Location anchor) {
        if (owner == null) return;
        if (regionId == null || regionId.isBlank()) regionId = "__global__";
        String base = "players." + owner + ".regions." + sanitize(regionId) + ".slot" + slot + ".anchor";
        if (anchor == null || anchor.getWorld() == null) {
            cfg.set(base, null);
            save();
            return;
        }
        cfg.set(base + ".world", anchor.getWorld().getName());
        cfg.set(base + ".x", anchor.getBlockX());
        cfg.set(base + ".y", anchor.getBlockY());
        cfg.set(base + ".z", anchor.getBlockZ());
        save();
    }

    /**
     * Returns stored private-block anchor for this region slot, or null.
     */
    public org.bukkit.Location getAnchor(UUID owner, String regionId, int slot) {
        if (owner == null) return null;
        if (regionId == null || regionId.isBlank()) regionId = "__global__";
        String base = "players." + owner + ".regions." + sanitize(regionId) + ".slot" + slot + ".anchor";
        String wName = cfg.getString(base + ".world");
        if (wName == null || wName.isBlank()) return null;
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(wName);
        if (w == null) return null;
        int x = cfg.getInt(base + ".x");
        int y = cfg.getInt(base + ".y");
        int z = cfg.getInt(base + ".z");
        return new org.bukkit.Location(w, x, y, z);
    }

    /**
     * Возвращает все слоты (1..3) и UUID призванных хранителей для владельца в конкретном регионе.
     */
    public java.util.Map<Integer, UUID> getAllSlots(UUID owner, String regionId) {
        if (regionId == null || regionId.isBlank()) regionId = "__global__";
        java.util.Map<Integer, UUID> out = new java.util.HashMap<>();
        for (int slot = 1; slot <= 3; slot++) {
            UUID pet = getPet(owner, regionId, slot);
            if (pet != null) out.put(slot, pet);
        }
        return out;
    }

    

    /**
     * Возвращает список regionId (как они сохранены в YAML) для владельца.
     */
    public java.util.Set<String> getRegions(UUID owner) {
        if (owner == null) return java.util.Collections.emptySet();
        ConfigurationSection sec = cfg.getConfigurationSection("players." + owner + ".regions");
        if (sec == null) return java.util.Collections.emptySet();
        return new java.util.HashSet<>(sec.getKeys(false));
    }

    /**
     * Возвращает карту regionId -> (slot -> petUuid) для владельца.
     */
    public java.util.Map<String, java.util.Map<Integer, UUID>> getAllRegionSlots(UUID owner) {
        java.util.Map<String, java.util.Map<Integer, UUID>> out = new java.util.HashMap<>();
        for (String regionId : getRegions(owner)) {
            java.util.Map<Integer, UUID> slots = getAllSlots(owner, regionId);
            if (!slots.isEmpty()) out.put(regionId, slots);
        }
        return out;
    }
// ===== Backwards compatible wrappers (global) =====

    public UUID getPet(UUID owner, int slot) {
        return getPet(owner, "__global__", slot);
    }

    public void setPet(UUID owner, int slot, UUID petId) {
        setPet(owner, "__global__", slot, petId);
    }

    public void setLastCharge(UUID owner, int slot, long millis) {
        setLastCharge(owner, "__global__", slot, millis);
    }

    public long getLastCharge(UUID owner, int slot) {
        return getLastCharge(owner, "__global__", slot);
    }

    public void setDeadUntil(UUID owner, int slot, long millis) {
        setDeadUntil(owner, "__global__", slot, millis);
    }

    public long getDeadUntil(UUID owner, int slot) {
        return getDeadUntil(owner, "__global__", slot);
    }


    public java.util.Map<Integer, UUID> getAllSlots(UUID owner) {
        return getAllSlots(owner, "__global__");
    }

    private void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save guardian_state.yml: " + e.getMessage());
        }
    }

    private static String sanitize(String s) {
        // Region ids may contain characters that YAML path treats specially.
        // Keep it simple: replace dots with underscores.
        return s.replace('.', '_');
    }
}
