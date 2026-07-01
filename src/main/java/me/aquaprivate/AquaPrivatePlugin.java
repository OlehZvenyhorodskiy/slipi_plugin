package me.aquaprivate;

import me.aquaprivate.cfg.ConfigService;
import me.aquaprivate.cmd.APCommand;
import me.aquaprivate.cmd.AquaProtectionCommand;
import me.aquaprivate.data.PrivateStore;
import me.aquaprivate.data.GuardFuelStore;
import me.aquaprivate.glint.GlintEntityListener;
import me.aquaprivate.glint.GlintService;
import me.aquaprivate.glow.RegionBorderGlowService;
import me.aquaprivate.hologram.HologramService;
import me.aquaprivate.hook.WorldGuardHook;
import me.aquaprivate.home.HomeMenu;
import me.aquaprivate.home.HomeMenuListener;
import me.aquaprivate.home.TeleportService;
import me.aquaprivate.listener.AnvilListener;
import me.aquaprivate.listener.FermerExpTransferListener;
import me.aquaprivate.listener.HologramChunkListener;
import me.aquaprivate.listener.LevelQuestListener;
import me.aquaprivate.listener.PistonPrivateProtectionListener;
import me.aquaprivate.listener.PrivateListener;
import me.aquaprivate.menu.MenuPrivateListener;
import me.aquaprivate.menu.MenuPrivateService;
import me.aquaprivate.tnt.UniqueTntListener;
import me.aquaprivate.tnt.UniqueTntMinecartListener;
import me.aquaprivate.tnt.UniqueTntMetaFixListener;
import me.aquaprivate.tnt.UniqueTntService;
import me.aquaprivate.upgrade.PrivateUpgradeService;
import me.aquaprivate.model.PrivateBlockType;
import me.aquaprivate.model.PrivateRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

import com.fermerpets.FermerPetsModule;

public final class AquaPrivatePlugin extends JavaPlugin {

    private ConfigService configService;
    private PrivateStore privateStore;
    private WorldGuardHook worldGuardHook;
    private HologramService holograms;
    private GlintService glints;
    private RegionBorderGlowService borderGlow;
    private TeleportService teleportService;
    private HomeMenu homeMenu;
    private MenuPrivateService menuPrivate;
    private UniqueTntService uniqueTnt;
    private PrivateUpgradeService upgrade;
    private FermerPetsModule fermer;
    private com.example.guardianparrot.GuardianParrotModule guardianParrot;
    private GuardFuelStore guardFuel;
    private org.bukkit.scheduler.BukkitTask hologramAutoRefreshTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Ensure default resources exist
        saveResource("menuprivate.yml", false);
        saveResource("tntforprivare.yml", false);
        saveResource("guardianparrot.yml", false);
        saveResource("combat.yml", false);
        saveResource("worldsdenyspawn.yml", false);

        this.configService = new ConfigService(this);
        this.privateStore = new PrivateStore(this);
        this.guardFuel = new GuardFuelStore(this);
        this.worldGuardHook = new WorldGuardHook(this);
        this.holograms = new HologramService(this);
        this.glints = new GlintService(this);
        this.borderGlow = new RegionBorderGlowService(this);
        this.teleportService = new TeleportService(this);
        this.homeMenu = new HomeMenu(this);
        this.menuPrivate = new MenuPrivateService(this);
        this.uniqueTnt = new UniqueTntService(this);
        this.upgrade = new PrivateUpgradeService(this);

        if (!worldGuardHook.isReady()) {
            getLogger().severe("WorldGuard/WorldEdit не найдены. Плагин отключён.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Embedded FermerPets module (part of this plugin)
        this.fermer = new FermerPetsModule(this);
        this.fermer.enable();

        // Embedded GuardianParrot module (integrated)
        this.guardianParrot = new com.example.guardianparrot.GuardianParrotModule(this);
        this.guardianParrot.enable();

        // Важно: WorldGuard блокирует регистрацию новых флагов после старта.
        // Поэтому мы не регистрируем кастомные флаги (ps-*, farewell-action) через реестр,
        // а пишем их напрямую в regions.yml после сохранения региона (см. WorldGuardYamlInjector).

        privateStore.load();
        configService.reload();
        menuPrivate.reload();
        uniqueTnt.reload();

        // IMPORTANT: After /reload, old hologram armorstands can be saved inside chunks.
        // They will re-appear when the chunk loads unless we purge them.
        try {
            int removed = holograms.purgeAllLoaded();
            if (removed > 0) getLogger().info("Purged old loaded holograms: " + removed);
        } catch (Throwable ignored) {}

        // Re-spawn holograms for existing privates (important after restart/reload)
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                holograms.respawnAll();
            } catch (Exception ex) {
                getLogger().warning("Failed to respawn holograms: " + ex.getMessage());
            }
            try {
                glints.respawnAll();
            } catch (Exception ex) {
                getLogger().warning("Failed to respawn glints: " + ex.getMessage());
            }
            try {
                borderGlow.respawnAll();
            } catch (Exception ex) {
                getLogger().warning("Failed to respawn border glow: " + ex.getMessage());
            }

            // Periodically refresh hologram lines so placeholders (fuel, amethysts, etc.)
            // update even when players do not interact with the private block.
            try { if (hologramAutoRefreshTask != null) hologramAutoRefreshTask.cancel(); } catch (Throwable ignored) {}
            try {
                hologramAutoRefreshTask = new org.bukkit.scheduler.BukkitRunnable() {
                    int cursor = 0;
                    @Override public void run() {
                        try {
                            if (org.bukkit.Bukkit.getOnlinePlayers().isEmpty()) return;

                            java.util.List<me.aquaprivate.model.PrivateRecord> all =
                                    new java.util.ArrayList<>(privateStore.all());
                            if (all.isEmpty()) return;

                            int perRun = Math.max(1, AquaPrivatePlugin.this.getConfig()
                                    .getInt("settings.holograms.refresh-per-run", 50));

                            if (cursor >= all.size()) cursor = 0;

                            for (int i = 0; i < perRun; i++) {
                                if (all.isEmpty()) break;
                                if (cursor >= all.size()) cursor = 0;
                                me.aquaprivate.model.PrivateRecord r = all.get(cursor++);
                                try { holograms.refreshFor(r); } catch (Throwable ignored2) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                }.runTaskTimer(AquaPrivatePlugin.this, 100L, 100L);
            } catch (Throwable ignored) {}
        });

        Bukkit.getPluginManager().registerEvents(new PrivateListener(this), this);
        Bukkit.getPluginManager().registerEvents(new LevelQuestListener(this, upgrade), this);
        Bukkit.getPluginManager().registerEvents(new PistonPrivateProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AnvilListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GlintEntityListener(this), this);
        Bukkit.getPluginManager().registerEvents(new HologramChunkListener(this), this);
        Bukkit.getPluginManager().registerEvents(new HomeMenuListener(this, homeMenu, teleportService), this);
        Bukkit.getPluginManager().registerEvents(new MenuPrivateListener(menuPrivate), this);
        Bukkit.getPluginManager().registerEvents(new FermerExpTransferListener(this), this);
        Bukkit.getPluginManager().registerEvents(new UniqueTntListener(this, uniqueTnt), this);
        Bukkit.getPluginManager().registerEvents(new UniqueTntMetaFixListener(this, uniqueTnt), this);
        Bukkit.getPluginManager().registerEvents(new UniqueTntMinecartListener(this, uniqueTnt), this);

        var aquaProtection = new AquaProtectionCommand(this);
        Objects.requireNonNull(getCommand("aquaprotection")).setExecutor(aquaProtection);
        Objects.requireNonNull(getCommand("aquaprotection")).setTabCompleter(aquaProtection);

        var apCmd = new APCommand(this);
        Objects.requireNonNull(getCommand("ap")).setExecutor(apCmd);
        Objects.requireNonNull(getCommand("ap")).setTabCompleter(apCmd);

        getLogger().info("AquaPrivate enabled.");
    }

    @Override
    public void onDisable() {
        try { if (hologramAutoRefreshTask != null) hologramAutoRefreshTask.cancel(); } catch (Throwable ignored) {}

        // IMPORTANT: on disable (/reload) we must remove embedded entities so they do not remain flying.
        // This matches the original behaviour: despawn on disable, respawn on enable if active in menu.
        if (guardianParrot != null) {
            try { guardianParrot.disable(); } catch (Throwable ignored) {}
        }
        if (fermer != null) {
            try { fermer.disable(); } catch (Throwable ignored) {}
        }

        if (borderGlow != null) {
            try { borderGlow.shutdown(); } catch (Throwable ignored) {}
        }
        if (holograms != null) {
            try { holograms.removeAll(); } catch (Throwable ignored) {}
            try { holograms.purgeAllLoaded(); } catch (Throwable ignored) {}
        }
        if (glints != null) {
            try { glints.removeAll(); } catch (Throwable ignored) {}
        }
        if (privateStore != null) {
            try { privateStore.save(); } catch (Throwable ignored) {}
        }
        getLogger().info("AquaPrivate disabled.");
    }

    public ConfigService cfg() {
        return configService;
    }

    public PrivateStore store() {
        return privateStore;
    }

    public WorldGuardHook wg() {
        return worldGuardHook;
    }

    public HologramService holograms() {
        return holograms;
    }

    public GlintService glints() {
        return glints;
    }

    public HomeMenu homeMenu() {
        return homeMenu;
    }

    public TeleportService teleportService() {
        return teleportService;
    }

    public MenuPrivateService menuPrivate() {
        return menuPrivate;
    }

    public RegionBorderGlowService borderGlow() { return borderGlow; }

    public UniqueTntService uniqueTnt() {
        return uniqueTnt;
    }

    public PrivateUpgradeService upgrade() {
        return upgrade;
    }

    public FermerPetsModule fermer() {
        return fermer;
    }

    public com.example.guardianparrot.GuardianParrotModule guardianParrot() {
        return guardianParrot;
    }

    public GuardFuelStore guardFuel() {
        return guardFuel;
    }

    /**
     * Resolve block type for a stored private.
     *
     * Admins may rename keys inside config.yml (blocks.<key>). Old privates keep the old key,
     * which would break holograms/menus. We recover by matching the real marker block material.
     */
    public PrivateBlockType resolveBlockType(PrivateRecord r) {
        if (r == null) return null;
        PrivateBlockType byKey = cfg().block(r.blockKey);
        if (byKey != null) return byKey;

        try {
            Location loc = r.toLocation();
            if (loc == null || loc.getWorld() == null) return null;
            Material mat = loc.getBlock().getType();
            if (mat == null) return null;
            for (PrivateBlockType t : cfg().blocks().values()) {
                if (t.material == mat) {
                    // Heal stored key so next restart works.
                    r.blockKey = t.key;
                    try { store().save(); } catch (Exception ignored) {}
                    return t;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
