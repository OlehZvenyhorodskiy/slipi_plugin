package com.fermerpets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.ArmorStand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Embedded version of FermerPets.
 * <p>
 * This module is not a standalone Bukkit plugin; it is hosted inside another plugin
 * (AquaPrivate) and delegates scheduling / event registration to the host plugin.
 */
public final class FermerPetsModule {

    private final JavaPlugin host;
    private final File dataFolder;

    private final UUID bootId = UUID.randomUUID();
    private static FermerPetsModule instance;

    private FileConfiguration config;
    private PetManager manager;
    private WorldsDenyService worlds;
    private FermerMenuService menuService;
    private HopperBeaconListener hopperBeaconListener;
    private BukkitTask fuelTask;

    public FermerPetsModule(JavaPlugin host) {
        this.host = Objects.requireNonNull(host, "host");
        this.dataFolder = new File(host.getDataFolder(), "fermerpets");
    }

    public static FermerPetsModule get() {
        return instance;
    }

    public Plugin plugin() {
        return host;
    }

    /**
     * Compatibility shim: some embedded FermerPets classes expect a reloadConfig() method
     * like a normal JavaPlugin.
     */
    public void reloadConfig() {
        try { host.reloadConfig(); } catch (Throwable ignored) {}
        // Reload embedded config from our subfolder
        try { loadEmbeddedConfig(); } catch (Throwable ignored) {}
    }

    private void loadEmbeddedConfig() {
        if (!dataFolder.exists()) dataFolder.mkdirs();
        File f = new File(dataFolder, "config.yml");
        if (!f.exists()) {
            try { saveResource("config.yml", false); } catch (Throwable ignored) {}
        }
        config = YamlConfiguration.loadConfiguration(f);
    }

    public UUID getBootId() {
        return bootId;
    }

    public File getDataFolder() {
        return dataFolder;
    }

    public java.util.logging.Logger getLogger() {
        return host.getLogger();
    }

    public org.bukkit.Server getServer() {
        return host.getServer();
    }

    public FileConfiguration getConfig() {
        return config;
    }

    /**
     * Access to hopper/particle manager.
     */
    public HopperBeaconListener hopperBeacon() {
        return hopperBeaconListener;
    }

    public void saveConfig() {
        if (config == null) return;
        try {
            if (!dataFolder.exists()) dataFolder.mkdirs();
            ((YamlConfiguration) config).save(new File(dataFolder, "config.yml"));
        } catch (Exception ignored) {
        }
    }

    public void saveResource(String resourcePath, boolean replace) {
        // resources are stored under /fermerpets/ inside the host jar
        host.saveResource("fermerpets/" + resourcePath, replace);
        // move it into subfolder root if needed
        File extracted = new File(host.getDataFolder(), "fermerpets/" + resourcePath);
        File target = new File(dataFolder, resourcePath);
        try {
            if (!dataFolder.exists()) dataFolder.mkdirs();
            if (!extracted.exists()) return;
            if (target.exists() && !replace) return;
            if (target.exists()) target.delete();
            // ensure parent
            if (target.getParentFile() != null) target.getParentFile().mkdirs();
            java.nio.file.Files.move(extracted.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // cleanup empty dir
            File maybeDir = new File(host.getDataFolder(), "fermerpets");
            if (maybeDir.isDirectory() && Objects.requireNonNull(maybeDir.list()).length == 0) {
                //noinspection ResultOfMethodCallIgnored
                maybeDir.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public PetManager getManager() {
        return manager;
    }

    public WorldsDenyService getWorldsService() {
        return worlds;
    }

    public FermerMenuService getMenuService() {
        return menuService;
    }

    public void enable() {
        instance = this;

        try { HarvestAI.clearAll(); } catch (Throwable ignored) {}
        try { TargetTracker.clearAll(); } catch (Throwable ignored) {}

        // Ensure data folder exists
        if (!dataFolder.exists()) dataFolder.mkdirs();

        // Extract default configs if missing
        if (!new File(dataFolder, "config.yml").exists()) saveResource("config.yml", false);
        if (!new File(dataFolder, "players.yml").exists()) saveResource("players.yml", false);
        if (!new File(dataFolder, "worldsdenyspawn.yml").exists()) saveResource("worldsdenyspawn.yml", false);
        if (!new File(dataFolder, "playershoppers.yml").exists()) saveResource("playershoppers.yml", false);
        if (!new File(dataFolder, "fermermenu.yml").exists()) saveResource("fermermenu.yml", false);

        // Load module config
        config = YamlConfiguration.loadConfiguration(new File(dataFolder, "config.yml"));

        // PlayersHoppers migration (safe)
        try {
            PlayersHoppersStore _phs = new PlayersHoppersStore(this);
            _phs.reload();
            _phs.migrateIfNeeded();
        } catch (Throwable ignored) {}

        try { com.fermerpets.integration.HDBHook.init(); } catch (Throwable ignored) {}

        manager = new PetManager(this);
        worlds = new WorldsDenyService(this);

        // Reconcile farmers after /reload or plugin restart.
        // IMPORTANT: during /reload some servers report worlds/entities as "not fully ready" for a short time.
        // If we reconcile too early, respawns may silently fail and pets won't appear.
        // We therefore run it twice with a bigger delay.
        // - DO NOT spawn near player
        // - attach to existing entities (prevents duplicates)
        // - if missing, respawn near the private marker (private block)
        Bukkit.getScheduler().runTaskLater(host, () -> {
            try { manager.reconcileFarmersAfterReload(); } catch (Throwable ignored) {}
        }, 100L);
        Bukkit.getScheduler().runTaskLater(host, () -> {
            try { manager.reconcileFarmersAfterReload(); } catch (Throwable ignored) {}
        }, 200L);

        Bukkit.getPluginManager().registerEvents(new PetListener(this, manager), host);
        Bukkit.getPluginManager().registerEvents(new PetProtectionListener(manager), host);
        Bukkit.getPluginManager().registerEvents(new SpawnGuardListener(this), host);
        // IMPORTANT: Farmers are bound to the private block (marker) and must NOT be auto-summoned near players.
        // The legacy RegionGateListener (auto resummon on move/world-change) caused duplicates and wrong spawn location
        // after /reload. We intentionally do not register it.
        // Bukkit.getPluginManager().registerEvents(new RegionGateListener(this, manager, worlds), host);
        Bukkit.getPluginManager().registerEvents(new DebugTeleportListener(this), host);
        Bukkit.getPluginManager().registerEvents(new TeleportGuardListener(this), host);
        menuService = new FermerMenuService(this);
        FermerMenus.init(menuService);

        hopperBeaconListener = new HopperBeaconListener(this, menuService);
        Bukkit.getPluginManager().registerEvents(hopperBeaconListener, host);
        Bukkit.getPluginManager().registerEvents(new FermerBlockProtectionListener(this), host);

        // Ensure beacon/hopper chunks stay loaded even when owner is in another world
        try {
            ChunkPinService cps = new ChunkPinService(host);
            cps.register();
            cps.scheduleRepinTask();
            Bukkit.getScheduler().runTaskLater(host, () -> {
                try {
                    org.bukkit.configuration.file.FileConfiguration playersCfg = getManager().getPlayersYaml();
                    Set<UUID> owners = new HashSet<>();
                    if (playersCfg.getConfigurationSection("players") != null) {
                        for (String s : playersCfg.getConfigurationSection("players").getKeys(false)) {
                            try { owners.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
                        }
                    }
                    if (playersCfg.getConfigurationSection("owners") != null) {
                        for (String s : playersCfg.getConfigurationSection("owners").getKeys(false)) {
                            try { owners.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
                        }
                    }
                    for (UUID ownerId : owners) {
                        ChunkPinService.updatePinsForOwner(host, ownerId, Bukkit.getPlayer(ownerId) != null);
                    }
                } catch (Throwable ignored) {}
            }, 40L);
        } catch (Throwable ignored) {}

        try { hopperBeaconListener.rescheduleAllTotems(); hopperBeaconListener.ensureAllBlocksPresent(); } catch (Throwable ignored) {}

        // Post-init after worlds and softdepends settle
        try {
            new BukkitRunnable() {
                @Override public void run() {
                    try { HarvestAI.clearAll(); } catch (Throwable ignored) {}
                    try { TargetTracker.clearAll(); } catch (Throwable ignored) {}
                }
            }.runTaskLater(host, 40L);
        } catch (Throwable ignored) {}

        try { com.fermerpets.guard.PermissionHardlock.install(host); } catch (Throwable ignored) {}
        // PlugmanSafeBootstrap expects a JavaPlugin instance, so we delegate to the host plugin.
        try { com.fermerpets.boot.PlugmanSafeBootstrap.wire(host, null, null); } catch (Throwable ignored) {}

        // Re-schedule outlines for existing beacons after reload
        new BukkitRunnable() {
            @Override public void run() {
                try {
                    PlayersHoppersStore store = new PlayersHoppersStore(FermerPetsModule.this);
                    org.bukkit.configuration.file.FileConfiguration playersCfg = getManager().getPlayersYaml();
                    PlayersStore ps = new PlayersStore(playersCfg);
                    Set<String> owners = new HashSet<>(playersCfg.getConfigurationSection("players") == null
                            ? Collections.emptySet()
                            : playersCfg.getConfigurationSection("players").getKeys(false));
                    if (playersCfg.getConfigurationSection("owners") != null) {
                        owners.addAll(playersCfg.getConfigurationSection("owners").getKeys(false));
                    }
                    for (String ownerStr : owners) {
                        UUID owner;
                        try { owner = UUID.fromString(ownerStr); } catch (IllegalArgumentException ex) { continue; }
                        List<UUID> pets = ps.getPets(owner);
                        if (pets == null) continue;
                        for (UUID petId : pets) {
                            PlayersHoppersStore.Record r = store.getBeaconByPet(petId);
                            if (r != null && r.loc != null) {
                                try { BorderPainter.scheduleBeaconOutline(FermerPetsModule.this, owner, r.loc); } catch (Throwable ignored) {}
                            }
                        }
                    }
                } catch (Throwable ex) {
                    getLogger().warning("[FermerPets] Reschedule outlines failed: " + ex.getMessage());
                }
            }
        }.runTaskLater(host, 40L);

        // Schedule periodic fuel consumption
        try {
            int minutes = Math.max(1, getConfig().getInt("fuel.period_minutes", 10));
            long periodTicks = minutes * 60L * 20L;
            if (fuelTask != null) fuelTask.cancel();
            fuelTask = new BukkitRunnable() {
                @Override public void run() {
                    try {
                        PlayersHoppersStore store = new PlayersHoppersStore(FermerPetsModule.this);
                        org.bukkit.configuration.file.FileConfiguration playersCfg = getManager().getPlayersYaml();
                        PlayersStore ps = new PlayersStore(playersCfg);
                        Set<String> owners = new HashSet<>(playersCfg.getConfigurationSection("players") == null
                                ? Collections.emptySet()
                                : playersCfg.getConfigurationSection("players").getKeys(false));
                        if (playersCfg.getConfigurationSection("owners") != null) {
                            owners.addAll(playersCfg.getConfigurationSection("owners").getKeys(false));
                        }
                        for (String ownerStr : owners) {
                            UUID owner;
                            try { owner = UUID.fromString(ownerStr); } catch (IllegalArgumentException ex) { continue; }
                            List<UUID> pets = ps.getPets(owner);
                            if (pets == null) continue;
                            for (UUID petId : pets) {
                                if (store.getBeaconByPet(petId) != null) {
                                    int fuel = store.getFuelByPet(petId);
                                    if (fuel > 0) {
                                        store.setFuelByPet(petId, fuel - 1);
                                        try { if (menuService != null) menuService.refreshOpenMenus(); } catch (Throwable ignored) {}
                                        try {
                                            if (store.getBeaconByPet(petId) != null) {
                                                Location loc = store.getBeaconByPet(petId).loc;
                                                int newFuel = store.getFuelByPet(petId);
                                                int r = new FermerMenuService(FermerPetsModule.this).resolveRadius(newFuel);
                                                if (newFuel <= 0) {
                                                    BorderPainter.removeHolo(loc);
                                                } else {
                                                    ArmorStand holo = BorderPainter.getOrCreateHolo(loc);
                                                    if (holo != null) {
                                                        String text = "§dАметисты: " + newFuel + " §7Радиус: §6" + r;
                                                        holo.setCustomName(text);
                                                    }
                                                }
                                            }
                                        } catch (Throwable ignore) {}
                                    }
                                }
                            }
                        }
                    } catch (Throwable ex) {
                        getLogger().warning("[FermerPets] Fuel consumption tick failed: " + ex.getMessage());
                    }
                }
            }.runTaskTimer(host, periodTicks, periodTicks);
        } catch (Throwable ignored) {}

        getLogger().info("FermerPets embedded module enabled.");
    }

    public void disable() {
        try { if (fuelTask != null) fuelTask.cancel(); } catch (Throwable ignored) {}
        try { if (hopperBeaconListener != null) hopperBeaconListener.shutdown(); } catch (Throwable ignored) {}
        try { if (menuService != null) menuService.shutdown(); } catch (Throwable ignored) {}
        try { FermerMenus.init(null); } catch (Throwable ignored) {}
        try { if (manager != null) manager.shutdown(); } catch (Throwable ignored) {}
        try { new com.fermerpets.ChunkPinService(host).clearAll(); } catch (Throwable ignored) {}
        try { HarvestAI.clearAll(); } catch (Throwable ignored) {}
        try { TargetTracker.clearAll(); } catch (Throwable ignored) {}
        try { BorderPainter.shutdownAll(); } catch (Throwable ignored) {}
        getLogger().info("FermerPets embedded module disabled.");
    }
}
