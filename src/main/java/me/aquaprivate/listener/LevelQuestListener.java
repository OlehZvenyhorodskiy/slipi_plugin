package me.aquaprivate.listener;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import me.aquaprivate.upgrade.PrivateUpgradeService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Level-up quest tracking (toggle from menu slot).
 */
public final class LevelQuestListener implements Listener {

    private final AquaPrivatePlugin plugin;
    private final PrivateUpgradeService upgrade;

    public LevelQuestListener(AquaPrivatePlugin plugin, PrivateUpgradeService upgrade) {
        this.plugin = plugin;
        this.upgrade = upgrade;
    }

    /**
     * Requirements are configurable per private level:
     * settings.level-quest.levels.<LEVEL>.*
     *
     * The requirements define what the player must do to upgrade
     * from the CURRENT private level to the NEXT level.
     *
     * If a level section is missing, we fall back to
     * settings.level-quest.requirements.* (legacy config).
     */
    private int reqEntity(int privateLevel) {
        String key = "settings.level-quest.levels." + privateLevel + ".entity-kills";
        if (plugin.getConfig().contains(key)) {
            return Math.max(0, plugin.getConfig().getInt(key, 0));
        }
        return Math.max(0, plugin.getConfig().getInt("settings.level-quest.requirements.entity-kills", 0));
    }

    private int reqPlayers(int privateLevel) {
        String key = "settings.level-quest.levels." + privateLevel + ".player-kills";
        if (plugin.getConfig().contains(key)) {
            return Math.max(0, plugin.getConfig().getInt(key, 15));
        }
        return Math.max(0, plugin.getConfig().getInt("settings.level-quest.requirements.player-kills", 15));
    }

    private int reqMineAmount(int privateLevel) {
        String key = "settings.level-quest.levels." + privateLevel + ".mine.amount";
        if (plugin.getConfig().contains(key)) {
            return Math.max(0, plugin.getConfig().getInt(key, 25));
        }
        return Math.max(0, plugin.getConfig().getInt("settings.level-quest.requirements.mine.amount", 25));
    }

    private Material reqMineMaterial(int privateLevel) {
        String key = "settings.level-quest.levels." + privateLevel + ".mine.material";
        String m = plugin.getConfig().contains(key)
                ? plugin.getConfig().getString(key, "DIAMOND")
                : plugin.getConfig().getString("settings.level-quest.requirements.mine.material", "DIAMOND");
        try {
            return Material.valueOf(m.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return Material.DIAMOND;
        }
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("settings.level-quest.enabled", true);
    }

    /**
     * Find the AquaPrivate record for a WorldGuard region at a specific location.
     * We bind quest progress to the private where the action happens,
     * not to "all privates owned by player".
     */
    private PrivateRecord privateAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        RegionManager rm = plugin.wg().regionManager(loc.getWorld());
        if (rm == null) return null;

        var set = rm.getApplicableRegions(BlockVector3.at(
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ()
        ));
        for (ProtectedRegion pr : set) {
            if (pr == null) continue;
            var opt = plugin.store().byRegionId(pr.getId());
            if (opt.isPresent()) return opt.get();
        }
        return null;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (!enabled()) return;
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        if (e.getEntity() instanceof Player) return;

        // Count progress only for the private where the kill happened.
        PrivateRecord r = privateAt(e.getEntity().getLocation());
        if (r == null) return;
        if (!r.levelQuestEnabled) return;
        if (!killer.getUniqueId().equals(r.owner)) return;
        if (r.level >= 20) return;

        int needEnt = reqEntity(r.level);
        if (needEnt <= 0) return;
        if (r.levelQuestEntityKills >= needEnt) return;

        r.levelQuestEntityKills++;
        plugin.store().save();
        killer.sendMessage(plugin.cfg().msg("quest-entity-kill")
                .replace("%entytyamound%", String.valueOf(r.levelQuestEntityKills)));
        tryComplete(killer, r);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (!enabled()) return;
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;

        // Count progress only for the private where the kill happened.
        PrivateRecord r = privateAt(e.getEntity().getLocation());
        if (r == null) return;
        if (!r.levelQuestEnabled) return;
        if (!killer.getUniqueId().equals(r.owner)) return;
        if (r.level >= 20) return;

        int needPk = reqPlayers(r.level);
        if (needPk <= 0) return;
        if (r.levelQuestPlayerKills >= needPk) return;

        r.levelQuestPlayerKills++;
        plugin.store().save();
        killer.sendMessage(plugin.cfg().msg("quest-player-kill")
                .replace("%killamound%", String.valueOf(r.levelQuestPlayerKills)));
        tryComplete(killer, r);
    }

    @EventHandler
    public void onMine(BlockBreakEvent e) {
        if (!enabled()) return;
        if (e.isCancelled()) return;
        Player p = e.getPlayer();

        // Bind progress to the private where the block is broken.
        PrivateRecord r = privateAt(e.getBlock().getLocation());
        if (r == null) return;
        if (!r.levelQuestEnabled) return;
        if (!p.getUniqueId().equals(r.owner)) return;
        if (r.level >= 20) return;

        Material targetItem = reqMineMaterial(r.level);

        // Count the amount of target items that would drop.
        // This fixes a common misconfiguration where config uses DIAMOND (item),
        // while the broken block is DIAMOND_ORE / DEEPSLATE_DIAMOND_ORE.
        int gained = 0;
        try {
            ItemStack tool = p.getInventory().getItemInMainHand();
            for (ItemStack drop : e.getBlock().getDrops(tool, p)) {
                if (drop == null) continue;
                if (drop.getType() != targetItem) continue;
                gained += Math.max(0, drop.getAmount());
            }
        } catch (Throwable ignored) {
            // Older/modified server implementations can throw here; fail safely.
        }

        if (gained <= 0) return;

        int needMine = reqMineAmount(r.level);
        int remaining = Math.max(0, needMine - r.levelQuestMined);
        if (remaining <= 0) return;
        int used = Math.min(gained, remaining);

        r.levelQuestMined += used;
        plugin.store().save();
        p.sendMessage(plugin.cfg().msg("quest-mine")
                .replace("%minersamound%", String.valueOf(r.levelQuestMined)));
        tryComplete(p, r);
    }

    private void tryComplete(Player p, PrivateRecord r) {
        int needEnt = reqEntity(r.level);
        int needMine = reqMineAmount(r.level);
        int needPk = reqPlayers(r.level);

        if (needEnt > 0 && r.levelQuestEntityKills < needEnt) return;
        if (needMine > 0 && r.levelQuestMined < needMine) return;
        if (needPk > 0 && r.levelQuestPlayerKills < needPk) return;

        int newLevel = Math.min(20, r.level + 1);
        int newSize = me.aquaprivate.util.PrivateLevelUtil.sizeForLevel(newLevel);

        if (!upgrade.upgradeRegion(r, newLevel)) return;
        r.level = newLevel;

        // Reaching max level turns the private into "clan" and blocks further leveling.
        if (newLevel >= 20) {
            r.privateType = "clan";
        }

        // reset quest state
        r.levelQuestEnabled = false;
        r.levelQuestEntityKills = 0;
        r.levelQuestMined = 0;
        r.levelQuestPlayerKills = 0;
        plugin.store().save();

        // Refresh hologram/glow so placeholders like %priv_size% update after upgrade.
        plugin.holograms().spawnFor(r);
        plugin.glints().spawnFor(r);

        p.sendMessage(plugin.cfg().msg("quest-complete")
                .replace("%levlprivata%", String.valueOf(newLevel)));

        String actor = p.getName();
        upgrade.playUpgradeEffects(r, newSize, newLevel, actor);
    }
}
