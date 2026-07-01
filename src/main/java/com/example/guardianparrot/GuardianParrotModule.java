package com.example.guardianparrot;

import com.example.guardianparrot.listeners.RegionGateListener;
import com.example.guardianparrot.listeners.SessionCleanupListener;
import com.example.guardianparrot.listeners.GuardianRestoreOnJoinListener;
import com.example.guardianparrot.util.GuardMarkers;
import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.data.GuardFuelStore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class GuardianParrotModule implements GPPlugin {

    private final JavaPlugin host;
    private FileConfiguration config;
    private File configFile;

    private ParrotProjectileCosmetics cosmetics;
    private ParrotManager manager;
    private WorldsDenyService denyService;
    private com.example.guardianparrot.menu.GuardianMenuService menu;

    private GuardianStateStore stateStore;
    private GuardFuelStore fuelStore;
    private int upkeepTaskId = -1;

    public GuardianParrotModule(JavaPlugin host) {
        this.host = host;
    }

    @Override
    public JavaPlugin host() {
        return host;
    }

    @Override
    public FileConfiguration getConfig() {
        return config;
    }

    @Override
    public void saveResource(String resourcePath, boolean replace) {
        host.saveResource(resourcePath, replace);
    }

    public ParrotManager getManager() {
        return manager;
    }

    public com.example.guardianparrot.menu.GuardianMenuService getMenuService() {
        return menu;
    }

    public void enable() {
        GuardianParrotRuntime.set(this);

        configFile = new File(host.getDataFolder(), "guardianparrot.yml");
        if (!configFile.exists()) {
            host.getDataFolder().mkdirs();
            host.saveResource("guardianparrot.yml", false);
        }
        // Be resilient to broken user YAML. If guardianparrot.yml is invalid, we backup it
        // and restore bundled defaults so the plugin can still start.
        config = new YamlConfiguration();
        try {
            String raw = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            config.loadFromString(sanitizeGuardianYaml(raw));
        } catch (Exception ex) {
            host.getLogger().severe("Cannot load " + configFile.getPath());
            ex.printStackTrace();
            try {
                File broken = new File(host.getDataFolder(), "guardianparrot.yml.broken-" + System.currentTimeMillis());
                //noinspection ResultOfMethodCallIgnored
                configFile.renameTo(broken);
            } catch (Exception ignore) {
                // ignore
            }
            // Restore defaults
            host.saveResource("guardianparrot.yml", true);
            try {
                String raw = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
                config.loadFromString(sanitizeGuardianYaml(raw));
            } catch (Exception ex2) {
                host.getLogger().severe("Failed to restore default guardianparrot.yml; GuardianParrot module may not work.");
                ex2.printStackTrace();
            }
        }

		fuelStore = new GuardFuelStore(host);

        GuardMarkers.init(this);
        com.example.guardianparrot.integration.HDBHook.init();
        OptionalHooks.initOptionalHooks(this);

		manager = new ParrotManager(this);
		stateStore = new GuardianStateStore(host);

		menu = new com.example.guardianparrot.menu.GuardianMenuService(this, manager, stateStore, fuelStore);
        menu.reload();
        host.getServer().getPluginManager().registerEvents(new com.example.guardianparrot.menu.GuardianMenuListener(menu), host);

        startUpkeepTask();

        host.getServer().getPluginManager().registerEvents(new SessionCleanupListener(manager), host);
        // Important: guardians are despawned on quit/kick/world change.
        // When the player rejoins later, we must restore (re-summon) them.
        host.getServer().getPluginManager().registerEvents(new GuardianRestoreOnJoinListener(this), host);
        host.getServer().getPluginManager().registerEvents(new com.example.guardianparrot.listeners.GuardianChunkCleanupListener(manager), host);
        host.getServer().getScheduler().runTask(host, () -> manager.cleanupOrphansOnStartup());

        host.getServer().getPluginManager().registerEvents(new GuardianListener(manager), host);
        host.getServer().getPluginManager().registerEvents(new GuardianBreedBlocker(manager), host);
        host.getServer().getPluginManager().registerEvents(new GuardianSightAnnounce(manager), host);

        manager.cleanupWorldOrphans();
        // Restore guardians from per-private region state.
        // NOTE: on some servers /reload keeps players connected, but the "online players" list
        // and/or world chunks may not be fully ready for a short time after enable.
        // If we restore too early, guardians may not spawn. To make it robust we run restore twice.
        for (Player p : Bukkit.getOnlinePlayers()) {
            Bukkit.getScheduler().runTaskLater(host, () -> restoreGuardiansFor(p), 100L);
            Bukkit.getScheduler().runTaskLater(host, () -> restoreGuardiansFor(p), 200L);
        }

        denyService = new WorldsDenyService(this);
        denyService.load();
        host.getServer().getPluginManager().registerEvents(new RegionGateListener(this, manager, denyService), host);

        // LootPickupListener expects a Bukkit Plugin instance (for NamespacedKey + scheduler)
        host.getServer().getPluginManager().registerEvents(new LootPickupListener(host, manager), host);
        try {
            if (host instanceof AquaPrivatePlugin ap) {
                host.getServer().getPluginManager().registerEvents(new com.example.guardianparrot.listeners.RegionDefenseListener(ap, manager), host);
                host.getServer().getPluginManager().registerEvents(new com.example.guardianparrot.listeners.GuardianFriendlyFireListener(ap, manager), host);
                host.getServer().getPluginManager().registerEvents(new com.example.guardianparrot.listeners.GuardianHpListener(ap, this, manager, stateStore), host);
            }
        } catch (Throwable ignored) {}
        host.getServer().getPluginManager().registerEvents(new OwnerAttackListener(manager), host);

        cosmetics = new ParrotProjectileCosmetics(this, manager);
        host.getServer().getPluginManager().registerEvents(cosmetics, host);

        host.getLogger().info("[GuardianParrot] embedded module enabled.");
    }

    public void disable() {
        if (upkeepTaskId != -1) {
            Bukkit.getScheduler().cancelTask(upkeepTaskId);
            upkeepTaskId = -1;
        }
        try { if (cosmetics != null) cosmetics.detachAll(); } catch (Throwable ignored) {}
        if (manager != null) {
            manager.shutdownAll();
        }
        host.getLogger().info("[GuardianParrot] embedded module disabled.");
    }

    private void startUpkeepTask() {
        if (upkeepTaskId != -1) {
            Bukkit.getScheduler().cancelTask(upkeepTaskId);
        }
        long periodTicks = 20L * 60L; // every minute
        upkeepTaskId = host.getServer().getScheduler().runTaskTimer(host, () -> {
            int upkeepMinutes = menu.getUpkeepMinutes();
            int upkeepAmount = menu.getUpkeepAmount();
            if (upkeepMinutes <= 0 || upkeepAmount <= 0) return;

            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                var regionSlots = stateStore.getAllRegionSlots(p.getUniqueId());
                if (regionSlots.isEmpty()) continue;

                long needMillis = upkeepMinutes * 60_000L;

                for (var re : regionSlots.entrySet()) {
                    String regionId = re.getKey();
                    var slots = re.getValue();
                    for (var e : slots.entrySet()) {
                        int slot = e.getKey();

                        long last = stateStore.getLastCharge(p.getUniqueId(), regionId, slot);
                        if (last == 0L) {
                            stateStore.setLastCharge(p.getUniqueId(), regionId, slot, now);
                            continue;
                        }
                        if (now - last < needMillis) continue;

                        int curFuel = fuelStore.get(p.getUniqueId(), regionId);
                        if (curFuel < upkeepAmount) {
                            menu.forceUnsummon(p.getUniqueId(), regionId, slot, true);
                            continue;
                        }

                        fuelStore.set(p.getUniqueId(), regionId, curFuel - upkeepAmount);
                        stateStore.setLastCharge(p.getUniqueId(), regionId, slot, now);

                        // If the player has the guardian menu open, refresh to update placeholders/fuel.
                        menu.refreshOpenMenus(p.getUniqueId(), false);
                    }
                }
            }
        }, periodTicks, periodTicks).getTaskId();
    }

    public void openMenu(Player player, String regionId, Location privateMarker) {
        if (menu != null) {
            menu.openMenu(player, regionId, privateMarker);
        }
    }

    /**
     * Returns true if there is at least one summoned (alive) guardian in the given private region.
     *
     * "Summoned" means: stateStore contains pet UUID AND the entity exists & is valid (not dead).
     * If the UUID exists in state but the entity is missing/dead, we treat it as "respawn cooldown"
     * and return false.
     */
    public boolean hasActiveGuardians(java.util.UUID owner, String regionId) {
        if (owner == null || regionId == null || regionId.isBlank()) return false;
        if (stateStore == null) return false;
        try {
            for (int slot = 1; slot <= 3; slot++) {
                java.util.UUID petId = stateStore.getPet(owner, regionId, slot);
                if (petId == null) continue;
                org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(petId);
                if (e instanceof org.bukkit.entity.Parrot p && p.isValid() && !p.isDead()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Deals random damage to all currently active guardians in the given private region.
     * Used by unique TNT logic (e.g. razrivnoe TNT).
     */
    public void damageActiveGuardiansRandom(java.util.UUID owner, String regionId, int minDamage, int maxDamage) {
        if (owner == null) return;
        if (regionId == null || regionId.isBlank()) return;
        if (stateStore == null) return;
        int min = Math.min(minDamage, maxDamage);
        int max = Math.max(minDamage, maxDamage);
        if (min <= 0) min = 1;

        try {
            java.util.Map<Integer, java.util.UUID> slots = stateStore.getAllSlots(owner, regionId);
            if (slots == null || slots.isEmpty()) return;
            java.util.Random rnd = new java.util.Random();

            for (java.util.UUID petId : slots.values()) {
                if (petId == null) continue;
                org.bukkit.entity.Entity ent = org.bukkit.Bukkit.getEntity(petId);
                if (!(ent instanceof org.bukkit.entity.Parrot p)) continue;
                if (!p.isValid() || p.isDead()) continue;

                int dmg = (min == max) ? min : (min + rnd.nextInt((max - min) + 1));
                try {
                    p.damage((double) dmg);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * Deals random damage to active guardians AND announces it in chat to players
     * within {@code announceRadius} blocks from the private-block center.
     *
     * Required announcements:
     *  - On hit: "Хранитель получил X урона"
     *  - On death/disappear (cooldown): "Хранитель сражен."
     */
    public void damageActiveGuardiansRandomWithBroadcast(
            java.util.UUID owner,
            String regionId,
            org.bukkit.Location privateCenter,
            int announceRadius,
            int minDamage,
            int maxDamage
    ) {
        if (owner == null) return;
        if (regionId == null || regionId.isBlank()) return;
        if (stateStore == null) return;

        int min = Math.min(minDamage, maxDamage);
        int max = Math.max(minDamage, maxDamage);
        if (min <= 0) min = 1;

        try {
            java.util.Map<Integer, java.util.UUID> slots = stateStore.getAllSlots(owner, regionId);
            if (slots == null || slots.isEmpty()) return;

            java.util.Random rnd = new java.util.Random();
            for (var se : slots.entrySet()) {
                try {
                    int slot = se.getKey();
                    java.util.UUID petId = se.getValue();
                    if (petId == null) continue;
                    org.bukkit.entity.Entity ent = org.bukkit.Bukkit.getEntity(petId);
                    if (!(ent instanceof org.bukkit.entity.Parrot p)) continue;
                    if (!p.isValid() || p.isDead()) continue;

                    int dmg = (min == max) ? min : (min + rnd.nextInt((max - min) + 1));
                    org.bukkit.Location center = (privateCenter != null ? privateCenter : p.getLocation());

                    // applyCustomGuardianDamage() will broadcast both the hit and the "slain" message
                    // and will also handle cooldown + respawn scheduling.
                    applyCustomGuardianDamage(p, dmg, owner, regionId, slot, center, announceRadius);
                } catch (Throwable ignored) {
                    // ignore per-guardian failures
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * Deals exact damage to ONE guardian entity using custom HP system and broadcasts messages.
     */
    public void damageGuardianEntityWithBroadcast(org.bukkit.entity.Parrot guardian,
                                                 int damage,
                                                 org.bukkit.Location privateCenter,
                                                 int announceRadius) {
        if (guardian == null) return;
        try {
            java.util.UUID owner = null;
            String ownerStr = guardian.getPersistentDataContainer().get(manager.ownerKey(), org.bukkit.persistence.PersistentDataType.STRING);
            if (ownerStr != null && !ownerStr.isEmpty()) owner = java.util.UUID.fromString(ownerStr);
            String regionId = guardian.getPersistentDataContainer().get(manager.regionIdKey, org.bukkit.persistence.PersistentDataType.STRING);
            Integer slot = guardian.getPersistentDataContainer().get(manager.slotKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            if (owner == null || regionId == null || regionId.isBlank() || slot == null || slot <= 0) return;

            applyCustomGuardianDamage(guardian, damage, owner, regionId, slot, privateCenter, announceRadius);
        } catch (Throwable ignored) {}
    }


    /**
     * Applies our custom guardian HP system:
     * - Each guardian has 50 HP stored in PDC (manager.hpKey)
     * - When HP reaches 0, guardian is dismissed and respawns after 3 minutes (cooldown stored in stateStore).
     * Returns true if the guardian was slain by this hit.
     */
    private boolean applyCustomGuardianDamage(
            org.bukkit.entity.Parrot guardian,
            int damage,
            java.util.UUID owner,
            String regionId,
            int slot,
            org.bukkit.Location privateCenter,
            int announceRadius
    ) {
        if (guardian == null) return false;
        if (damage <= 0) damage = 1;

        int hp = 50;
        try {
            Integer cur = guardian.getPersistentDataContainer().get(manager.hpKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            if (cur != null) hp = cur;
        } catch (Throwable ignored) {}

        int newHp = hp - damage;
        try { guardian.getPersistentDataContainer().set(manager.hpKey, org.bukkit.persistence.PersistentDataType.INTEGER, Math.max(0, newHp)); } catch (Throwable ignored) {}

        org.bukkit.Location center = (privateCenter != null ? privateCenter : guardian.getLocation());

        // Announce damage
        try {
            broadcastInRadius(center, announceRadius,
                    org.bukkit.ChatColor.RED + "Хранитель получил " + org.bukkit.ChatColor.YELLOW + damage +
                            org.bukkit.ChatColor.RED + " урона.");
        } catch (Throwable ignored) {}

        if (newHp > 0) {
            // Keep vanilla health synced (optional) so it visually matches if needed
            try {
                var maxHealthAttr = getMaxHealthAttribute();
                if (maxHealthAttr != null) {
                    var attr = guardian.getAttribute(maxHealthAttr);
                    if (attr != null && attr.getBaseValue() < 50.0) attr.setBaseValue(50.0);
                }
                guardian.setHealth(Math.min(50.0, Math.max(1.0, (double) newHp)));
            } catch (Throwable ignored) {}
            return false;
        }

        // Slain -> dismiss + cooldown + schedule respawn
        try {
            broadcastInRadius(center, announceRadius,
                    org.bukkit.ChatColor.DARK_RED + "Хранитель сражен.");
        } catch (Throwable ignored) {}

        long deadUntil = System.currentTimeMillis() + 180_000L; // 3 minutes
        try { if (stateStore != null) stateStore.setDeadUntil(owner, regionId, slot, deadUntil); } catch (Throwable ignored) {}
        try { if (stateStore != null) stateStore.setPet(owner, regionId, slot, null); } catch (Throwable ignored) {}

        // Remove entity & its task/visuals
        try { manager.dismissPet(guardian.getUniqueId()); } catch (Throwable ignored) {}

        // Schedule respawn (best-effort). If owner is offline at that time, it will respawn on next join.
        try {
            final java.util.UUID fOwner = owner;
            final String fRegion = regionId;
            final int fSlot = slot;
            final org.bukkit.Location fCenter = center;
            org.bukkit.Bukkit.getScheduler().runTaskLater(host, () -> {
                try {
                    if (stateStore == null) return;
                    long du = stateStore.getDeadUntil(fOwner, fRegion, fSlot);
                    if (du > 0L && du > System.currentTimeMillis()) return;
                    if (stateStore.getPet(fOwner, fRegion, fSlot) != null) return;

                    org.bukkit.entity.Player op = org.bukkit.Bukkit.getPlayer(fOwner);
                    if (op == null || !op.isOnline()) return;

                    org.bukkit.Location marker = null;
                    try { marker = stateStore.getAnchor(fOwner, fRegion, fSlot); } catch (Throwable ignored2) {}
                    if (marker == null) {
                        try {
                            if (host instanceof me.aquaprivate.AquaPrivatePlugin app) {
                                var opt = app.store().byRegionId(fRegion);
                                if (opt.isPresent()) marker = opt.get().toLocation();
                            }
                        } catch (Throwable ignored2) {}
                    }
                    if (marker == null || marker.getWorld() == null) return;

                    org.bukkit.Location spawnAt = marker.clone().add(0.5, 1.0, 0.5);
                    java.util.UUID newPet = manager.summonGuardian(op, spawnAt, fRegion, marker, fSlot);
                    if (newPet != null) {
                        stateStore.setPet(fOwner, fRegion, fSlot, newPet);
                        stateStore.setAnchor(fOwner, fRegion, fSlot, marker);
                        stateStore.setDeadUntil(fOwner, fRegion, fSlot, 0L);
                    }
                } catch (Throwable ignored2) {}
            }, 20L * 60L * 3L);
        } catch (Throwable ignored) {}

        return true;
    }

    /**
     * Compatibility helper: on some server APIs MAX_HEALTH is exposed instead of GENERIC_MAX_HEALTH.
     * We resolve it via reflection so the project compiles against both.
     */
    private static org.bukkit.attribute.Attribute getMaxHealthAttribute() {
        try {
            java.lang.reflect.Field f = org.bukkit.attribute.Attribute.class.getField("GENERIC_MAX_HEALTH");
            return (org.bukkit.attribute.Attribute) f.get(null);
        } catch (NoSuchFieldException ignored) {
            try {
                java.lang.reflect.Field f = org.bukkit.attribute.Attribute.class.getField("MAX_HEALTH");
                return (org.bukkit.attribute.Attribute) f.get(null);
            } catch (Throwable t) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private void broadcastInRadius(org.bukkit.Location center, int radiusBlocks, String message) {
        if (center == null || center.getWorld() == null) return;
        int r = Math.max(0, radiusBlocks);
        double r2 = (double) r * (double) r;
        for (org.bukkit.entity.Player pl : center.getWorld().getPlayers()) {
            if (pl == null || !pl.isOnline()) continue;
            try {
                if (pl.getLocation().distanceSquared(center) <= r2) {
                    pl.sendMessage(message);
                }
            } catch (Throwable ignored) {}
        }
    }

    public void reload() {
        if (configFile != null) {
        // Be resilient to broken user YAML. If guardianparrot.yml is invalid, we backup it
        // and restore bundled defaults so the plugin can still start.
        config = new YamlConfiguration();
        try {
            String raw = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            config.loadFromString(sanitizeGuardianYaml(raw));
        } catch (Exception ex) {
            host.getLogger().severe("Cannot load " + configFile.getPath());
            ex.printStackTrace();
            try {
                File broken = new File(host.getDataFolder(), "guardianparrot.yml.broken-" + System.currentTimeMillis());
                //noinspection ResultOfMethodCallIgnored
                configFile.renameTo(broken);
            } catch (Exception ignore) {
                // ignore
            }
            // Restore defaults
            host.saveResource("guardianparrot.yml", true);
            try {
                String raw = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
                config.loadFromString(sanitizeGuardianYaml(raw));
            } catch (Exception ex2) {
                host.getLogger().severe("Failed to restore default guardianparrot.yml; GuardianParrot module may not work.");
                ex2.printStackTrace();
            }
        }
        }
        if (denyService != null) {
            denyService.load();
        }
        if (menu != null) {
            menu.reload();
        }
    }

    /**
     * Restores all guardians for this online player at their private-block anchors.
     * Unlike the old logic, this does NOT despawn other slot guardians.
     */
    private void restoreGuardiansFor(Player owner) {
        if (owner == null || !owner.isOnline()) return;
        if (stateStore == null || manager == null) return;

        var all = stateStore.getAllRegionSlots(owner.getUniqueId());
        if (all.isEmpty()) return;

        for (var re : all.entrySet()) {
            String regionId = re.getKey();
            var slots = re.getValue();
            for (var se : slots.entrySet()) {
                int slot = se.getKey();
                java.util.UUID petId = se.getValue();

                // If guardian was recently slain, respect 3-minute respawn cooldown
                try {
                    long du = stateStore.getDeadUntil(owner.getUniqueId(), regionId, slot);
                    if (du > 0L && du > System.currentTimeMillis()) {
                        continue;
                    }
                } catch (Throwable ignored) {}

                // Determine anchor: prefer stored anchor, fallback to AquaPrivate private-block location.
                Location anchor = null;
                try { anchor = stateStore.getAnchor(owner.getUniqueId(), regionId, slot); } catch (Throwable ignored) {}
                Location marker = anchor;
                if (marker == null) {
                    try {
                        if (host instanceof AquaPrivatePlugin app && regionId != null) {
                            var opt = app.store().byRegionId(regionId);
                            if (opt.isPresent()) marker = opt.get().toLocation();
                        }
                    } catch (Throwable ignored) {}
                }
                if (marker == null || marker.getWorld() == null) {
                    // Don't spawn "somewhere" if we don't know the private block.
                    continue;
                }

                org.bukkit.entity.Entity ent = null;
                try { ent = org.bukkit.Bukkit.getEntity(petId); } catch (Throwable ignored) {}

                if (ent instanceof org.bukkit.entity.Parrot parrot && parrot.isValid()) {
                    // Ensure task/holo are running after reload and teleport back to anchor.
                    try {
                        manager.restartFor(owner.getUniqueId(), parrot.getUniqueId(), parrot);
                    } catch (Throwable ignored) {}
                    try {
                        Location tp = marker.clone().add(0.5, 1.0, 0.5);
                        if (tp.getWorld() != null) parrot.teleport(tp);
                    } catch (Throwable ignored) {}
                    continue;
                }

                // Missing entity -> re-summon at anchor and update state.
                Location spawnAt = marker.clone().add(0.5, 1.0, 0.5);
                java.util.UUID newPet = manager.summonGuardian(owner, spawnAt, regionId, marker, slot);
                if (newPet != null) {
                    stateStore.setPet(owner.getUniqueId(), regionId, slot, newPet);
                    stateStore.setAnchor(owner.getUniqueId(), regionId, slot, marker);
                    // keep lastCharge as-is (upkeep timer) if it exists; otherwise set now
                    long last = stateStore.getLastCharge(owner.getUniqueId(), regionId, slot);
                    if (last == 0L) stateStore.setLastCharge(owner.getUniqueId(), regionId, slot, System.currentTimeMillis());
                }
            }
        }
    }

    /**
     * Public-safe entry point used by join listener.
     * We keep the actual restore logic private to the module.
     */
    public void restoreForJoin(Player owner) {
        restoreGuardiansFor(owner);
    }

    /**
     * Accept a common (but invalid YAML) shorthand used in some configs.
     * Example (invalid):
     *   give: ... click_action: give_egg
     * We convert it to valid YAML:
     *   give:
     *     click_action: give_egg
     */
    private static String sanitizeGuardianYaml(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        StringBuilder out = new StringBuilder(raw.length() + 128);
        String[] lines = raw.split("\\r?\\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.append(line).append('\n');
                continue;
            }
            int idx = line.indexOf(": ... click_action:");
            if (idx > 0) {
                int firstNonWs = 0;
                while (firstNonWs < line.length() && Character.isWhitespace(line.charAt(firstNonWs))) firstNonWs++;
                String indent = line.substring(0, firstNonWs);
                String key = line.substring(firstNonWs, idx).trim();
                String action = line.substring(idx + ": ... click_action:".length()).trim();
                if (!key.isEmpty() && !action.isEmpty()) {
                    out.append(indent).append(key).append(":").append('\n');
                    out.append(indent).append("  ").append("click_action: ").append(action).append('\n');
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
