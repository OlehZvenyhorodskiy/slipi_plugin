package me.aquaprivate.listener;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.hook.WorldGuardYamlInjector;
import me.aquaprivate.model.PrivateBlockType;
import me.aquaprivate.model.PrivateRecord;
import me.aquaprivate.util.ItemFactory;
import me.aquaprivate.util.PrivateLevelUtil;
import org.bukkit.*;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public final class PrivateListener implements Listener {

    private final AquaPrivatePlugin plugin;

    public PrivateListener(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isOwnerOrMember(Player p, PrivateRecord r) {
        if (r.owner != null && r.owner.equals(p.getUniqueId()))
            return true;
        return r.members.contains(p.getUniqueId());
    }

    private Optional<PrivateRecord> getPrivateAt(Location loc) {
        RegionManager rm = plugin.wg().regionManager(loc.getWorld());
        if (rm == null)
            return Optional.empty();
        var set = rm.getApplicableRegions(BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        for (ProtectedRegion pr : set) {
            Optional<PrivateRecord> r = plugin.store().byRegionId(pr.getId());
            if (r.isPresent())
                return r;
        }
        return Optional.empty();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlacePrivate(BlockPlaceEvent e) {
        if (!e.getPlayer().hasPermission("aquaprivate.place")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.cfg().msg("no-permission"));
            return;
        }

        ItemStack inHand = e.getItemInHand();
        String blockKey = ItemFactory.readBlockKey(plugin, inHand);
        if (blockKey == null)
            return; // not our item

        PrivateBlockType type = plugin.cfg().block(blockKey);
        if (type == null)
            return;

        int lvl = ItemFactory.readLevel(plugin, inHand);

        // Placement limit by permission aquaprivate.place.<N>
        if (!e.getPlayer().isOp()) {
            int limit = getPlaceLimit(e.getPlayer());
            if (limit <= 0) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(plugin.cfg().msg("no-permission"));
                return;
            }
            int current = plugin.wg().listPrivates(e.getPlayer().getUniqueId()).size();
            if (current >= limit) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(plugin.cfg().msg("limit-reached")
                        .replace("%limit%", String.valueOf(limit))
                        .replace("%current%", String.valueOf(current)));
                return;
            }
        }

        Block block = e.getBlockPlaced();
        World world = block.getWorld();

        if (plugin.cfg().isWorldDisabled(world.getName())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.cfg().msg("disabled-world"));
            return;
        }

        // Deny if inside any existing WG region
        if (type.settings.mergeRegions && plugin.wg().hasAnyRegionAt(block.getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.cfg().msg("overlapping"));
            return;
        }

        // Create region
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        int yr = type.yRadius;
        int minY = (yr == -1) ? world.getMinHeight() : y - yr;
        int maxY = (yr == -1) ? (world.getMaxHeight() - 1) : y + yr;

        int size = PrivateLevelUtil.sizeForLevel(lvl);
        int[] off = PrivateLevelUtil.offsetsForSize(size);
        int minOff = off[0];
        int maxOff = off[1];

        BlockVector3 min = BlockVector3.at(x + minOff, minY, z + minOff);
        BlockVector3 max = BlockVector3.at(x + maxOff, maxY, z + maxOff);

        // Format as requested: ap-1018x-5y1298z
        String regionId = "ap-" + x + "x" + y + "y" + z + "z";
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(regionId, min, max);

        // owner/member list in WG
        DefaultDomain owners = new DefaultDomain();
        owners.addPlayer(e.getPlayer().getUniqueId());
        region.setOwners(owners);

        // Apply WG flags (только стандартные, которые WG знает)
        applyRegionFlags(region, type, e.getPlayer());

        RegionManager rm = plugin.wg().regionManager(world);
        if (rm == null) {
            e.setCancelled(true);
            return;
        }

        if (type.settings.mergeRegions && plugin.wg().intersectsAnyOtherRegion(world, region)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.cfg().msg("overlapping"));
            return;
        }

        rm.addRegion(region);
        try {
            rm.save();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save WG regions: " + ex.getMessage());
        }

        // Пишем дополнительные ps-* и farewell-action напрямую в regions.yml (см.
        // комментарий в плагине)
        WorldGuardYamlInjector.writeCustomFlags(world, regionId,
                buildCustomYamlFlags(type, e.getPlayer(), block.getLocation()));

        PrivateRecord rec = new PrivateRecord();
        rec.blockKey = type.key;
        rec.world = world.getName();
        rec.x = x;
        rec.y = y;
        rec.z = z;
        rec.regionId = regionId;
        rec.owner = e.getPlayer().getUniqueId();
        rec.level = lvl;
        rec.privateType = "normal";
        rec.farmerActive = false;
        rec.farmerFuel = "";
        plugin.store().put(rec);
        plugin.store().save();

        // hologram
        plugin.holograms().spawnFor(rec);
        // glint visual (as placed block can't render glint)
        plugin.glints().spawnFor(rec);

        if (type.settings.createLightning) {
            // Visual + sound (Paper/Spigot behaviour differs for strikeLightningEffect
            // sound)
            Location l = block.getLocation();
            world.strikeLightningEffect(l);
            playLightningSound(l);
        }

        // Effects: nautilus particles from center to every border block on create
        try {
            me.aquaprivate.util.RegionEffects.playBorderExpand(plugin, world.getName(), x, y, z, size);
        } catch (Throwable ignored) {
        }
        e.getPlayer().sendMessage(plugin.cfg().msg("created").replace("%player%", e.getPlayer().getName()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block block = e.getBlock();

        // If breaking a private marker block
        Optional<PrivateRecord> marker = plugin.store().byLocation(block.getWorld().getName(), block.getX(),
                block.getY(), block.getZ());
        if (marker.isPresent()) {
            PrivateRecord r = marker.get();
            Player p = e.getPlayer();
            // OP should always be able to remove any private.
            if (!p.isOp() && !p.hasPermission("aquaprivate.delete")) {
                e.setCancelled(true);
                p.sendMessage(plugin.cfg().msg("no-permission"));
                return;
            }
            if (!r.owner.equals(p.getUniqueId()) && !p.isOp()) {
                e.setCancelled(true);
                p.sendMessage(plugin.cfg().msg("not-owner"));
                return;
            }

            // Если к привату привязаны фермеры — удаляем их вместе с приватным блоком
            try {
                plugin.fermer().getManager().removePetsBoundToPrivate(r.regionId);
            } catch (Throwable ignored) {
            }

            // Выбрасываем опыт (кол-во уровней, вложенных в приват для фермера)
            try {
                spawnExpForLevels(block.getLocation().add(0.5, 0.5, 0.5),
                        Math.max(0, Math.min(100, r.farmerExpLevels)));
            } catch (Throwable ignored) {
            }

            // Выбрасываем опыт, вложенный в Хранителя (guard), чтобы он не пропадал.
            try {
                int guardLvls = Math.max(0, Math.min(100, r.guardExpLevels));
                if (guardLvls > 0) {
                    spawnExpForLevels(block.getLocation().add(0.5, 0.5, 0.5), guardLvls);
                    r.guardExpLevels = 0;
                    r.guardExpTransferEnabled = false;
                }
            } catch (Throwable ignored) {
            }

            // Выбрасываем остатки аметиста из "инвентаря" меню фермера (slots 36-53), чтобы
            // они не пропадали.
            try {
                com.fermerpets.MenuFuelStore fuel = new com.fermerpets.MenuFuelStore(plugin.fermer());
                int left = fuel.get(r.owner);
                if (left > 0) {
                    dropAmethysts(block.getLocation().add(0.5, 0.5, 0.5), left);
                    fuel.set(r.owner, 0);
                }
            } catch (Throwable ignored) {
            }

            // Если у владельца установлена "воронка для фермера" — удаляем её из мира
            // вместе с приватом.
            // (и выбрасываем содержимое, чтобы лут не пропал)
            try {
                com.fermerpets.PlayersHoppersStore phs = new com.fermerpets.PlayersHoppersStore(plugin.fermer());
                com.fermerpets.PlayersHoppersStore.Record gh = phs.getGlobalHopperForOwner(r.owner);
                if (gh != null && gh.loc != null && gh.loc.getWorld() != null) {
                    org.bukkit.block.Block hb = gh.loc.getBlock();
                    if (hb.getType() == org.bukkit.Material.HOPPER) {
                        org.bukkit.block.BlockState st = hb.getState();
                        if (st instanceof org.bukkit.block.Hopper hopper) {
                            org.bukkit.inventory.Inventory inv = hopper.getInventory();
                            for (org.bukkit.inventory.ItemStack it : inv.getContents()) {
                                if (it != null && it.getType() != org.bukkit.Material.AIR) {
                                    hb.getWorld().dropItemNaturally(hb.getLocation().add(0.5, 0.5, 0.5), it);
                                }
                            }
                            inv.clear();
                        }
                    }

                    // Stop totem particles and unpin chunk
                    try {
                        if (plugin.fermer() != null && plugin.fermer().hopperBeacon() != null) {
                            plugin.fermer().hopperBeacon().cancelTotem(gh.loc);
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        com.fermerpets.ChunkPinService cps = new com.fermerpets.ChunkPinService(plugin);
                        cps.unpinChunkAt(gh.loc);
                    } catch (Throwable ignored) {
                    }

                    hb.setType(org.bukkit.Material.AIR);
                }
                phs.clearAllHoppersForOwner(r.owner);
            } catch (Throwable ignored) {
            }

            // remove WG region
            RegionManager rm = plugin.wg().regionManager(block.getWorld());
            if (rm != null) {
                rm.removeRegion(r.regionId);
                try {
                    rm.save();
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to save WG regions: " + ex.getMessage());
                }
            }

            // remove record
            plugin.holograms().removeFor(r);
            plugin.glints().removeFor(r);
            plugin.store().remove(r);
            plugin.store().save();

            // drop private item
            PrivateBlockType type = plugin.resolveBlockType(r);
            ItemStack drop = (type != null)
                    ? ItemFactory.createPrivateItem(plugin, type,
                            Bukkit.getOfflinePlayer(r.owner).getName() != null
                                    ? Bukkit.getOfflinePlayer(r.owner).getName()
                                    : p.getName(),
                            r.level)
                    : new ItemStack(block.getType());

            e.setDropItems(false);
            block.setType(Material.AIR);
            block.getWorld().dropItemNaturally(block.getLocation(), drop);

            if (type != null && type.settings.deleteLightning) {
                Location l = block.getLocation();
                block.getWorld().strikeLightningEffect(l);
                playLightningSound(l);
            }

            p.sendMessage(plugin.cfg().msg("removed"));
            return;
        }

        // normal break inside private region
        Optional<PrivateRecord> inPriv = getPrivateAt(block.getLocation());
        if (inPriv.isEmpty())
            return;
        PrivateRecord r = inPriv.get();
        PrivateBlockType type = plugin.resolveBlockType(r);
        if (type == null)
            return;

        if (plugin.cfg().isBlacklistedBreak(block.getType())) {
            e.setCancelled(true);
            return;
        }

        if (type.settings.denyBlockBreak
                && !isOwnerOrMember(e.getPlayer(), r)
                && !e.getPlayer().isOp()
                && !e.getPlayer().hasPermission("aquaprivate.bypass")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.cfg().msg("not-owner"));
        }
    }

    private void playLightningSound(Location loc) {
        try {
            World w = loc.getWorld();
            if (w == null)
                return;
            w.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
            w.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.8f, 1.0f);
        } catch (Throwable ignored) {
            // In case mappings change, don't fail placement/break.
        }
    }

    // ---- EXP particles/orbs for farmer levels invested into private (0..100) ----
    private static void spawnExpForLevels(Location loc, int levels) {
        if (loc == null || loc.getWorld() == null)
            return;
        int lvl = Math.max(0, Math.min(100, levels));
        if (lvl <= 0)
            return;
        int xp = xpForLevel(lvl);
        // Spawn several orbs to ensure visible "частицы опыта"
        int remaining = xp;
        while (remaining > 0) {
            int give = Math.min(remaining, 500); // orb cap-ish
            org.bukkit.entity.ExperienceOrb orb = loc.getWorld().spawn(loc, org.bukkit.entity.ExperienceOrb.class);
            orb.setExperience(give);
            remaining -= give;
        }
    }

    // Total XP points required to reach a given level from level 0 (vanilla
    // formula)
    private static int xpForLevel(int level) {
        int L = Math.max(0, level);
        if (L <= 16)
            return L * L + 6 * L;
        if (L <= 31)
            return (int) Math.round(2.5 * L * L - 40.5 * L + 360);
        return (int) Math.round(4.5 * L * L - 162.5 * L + 2220);
    }

    private static void dropAmethysts(Location loc, int amount) {
        if (loc == null || loc.getWorld() == null)
            return;
        int left = Math.max(0, amount);
        while (left > 0) {
            int give = Math.min(64, left);
            org.bukkit.inventory.ItemStack st = new org.bukkit.inventory.ItemStack(org.bukkit.Material.AMETHYST_SHARD,
                    give);
            loc.getWorld().dropItemNaturally(loc, st);
            left -= give;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceOther(BlockPlaceEvent e) {
        // ignore private placement (handled above)
        String blockKey = ItemFactory.readBlockKey(plugin, e.getItemInHand());
        if (blockKey != null)
            return;

        Optional<PrivateRecord> inPriv = getPrivateAt(e.getBlockPlaced().getLocation());
        if (inPriv.isEmpty())
            return;

        PrivateRecord r = inPriv.get();
        PrivateBlockType type = plugin.resolveBlockType(r);
        if (type == null)
            return;

        if (type.settings.denyBlockPlace
                && !isOwnerOrMember(e.getPlayer(), r)
                && !e.getPlayer().isOp()
                && !e.getPlayer().hasPermission("aquaprivate.bypass")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.cfg().msg("not-owner"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null)
            return;
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)
            return;

        Block b = e.getClickedBlock();

        // Right-click on the PRIVATE marker block itself opens private menu (like
        // before).
        Optional<PrivateRecord> clickedPrivate = plugin.store().byLocation(
                b.getWorld().getName(),
                b.getX(),
                b.getY(),
                b.getZ());
        if (clickedPrivate.isPresent()) {
            PrivateRecord rec = clickedPrivate.get();
            if (!isOwnerOrMember(e.getPlayer(), rec) && !e.getPlayer().hasPermission("aquaprivate.admin")) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(plugin.cfg().msg("not-owner"));
                return;
            }
            e.setCancelled(true);
            plugin.menuPrivate().open(e.getPlayer(), rec);
            return;
        }
        if (!(b.getState() instanceof Container))
            return;

        Optional<PrivateRecord> inPriv = getPrivateAt(b.getLocation());
        if (inPriv.isEmpty())
            return;
        PrivateRecord r = inPriv.get();
        PrivateBlockType type = plugin.resolveBlockType(r);
        if (type == null)
            return;

        if (type.settings.denyChestOpen
                && !isOwnerOrMember(e.getPlayer(), r)
                && !e.getPlayer().isOp()
                && !e.getPlayer().hasPermission("aquaprivate.bypass")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.cfg().msg("not-owner"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWitherChangeBlock(EntityChangeBlockEvent e) {
        Entity ent = e.getEntity();
        if (ent.getType() != EntityType.WITHER)
            return;

        Optional<PrivateRecord> inPriv = getPrivateAt(e.getBlock().getLocation());
        if (inPriv.isEmpty())
            return;
        PrivateRecord r = inPriv.get();
        PrivateBlockType type = plugin.resolveBlockType(r);
        if (type == null)
            return;

        // Never allow wither body to change/break blocks inside privates.
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (e.getEntity() == null)
            return;
        EntityType t = e.getEntityType();

        // 1) Never allow wither / wither skull explosions to destroy blocks inside privates.
        if (t == EntityType.WITHER || t == EntityType.WITHER_SKULL) {
            e.blockList().removeIf(b -> getPrivateAt(b.getLocation()).isPresent());
            return;
        }

        // 2) End crystal: do not deform privates (no block damage inside privates)
        if (t == EntityType.END_CRYSTAL) {
            e.blockList().removeIf(b -> getPrivateAt(b.getLocation()).isPresent());
            return;
        }

        // 3) TNT / TNT minecart / creeper: if marker block is within radius 1 of explosion,
        // remove region and drop private block item.
        // SECURITY FIX: Only destroy markers if the explosive source has permission.
        if (t == EntityType.TNT || t == EntityType.TNT_MINECART || t == EntityType.CREEPER) {
            Entity entity = e.getEntity();
            Player responsible = null;

            // Find who is responsible for this explosion
            if (entity instanceof org.bukkit.entity.TNTPrimed tnt) {
                Entity source = tnt.getSource();
                if (source instanceof Player p) responsible = p;
            } else if (t == EntityType.CREEPER) {
                // Check if creeper was ignited by a player (not naturally)
                try {
                    if (entity.hasMetadata("source_player")) {
                        List<org.bukkit.metadata.MetadataValue> vals = entity.getMetadata("source_player");
                        for (var mv : vals) {
                            if (mv.value() instanceof Player p) { responsible = p; break; }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // If we can identify a responsible player, only destroy markers they own or have rights to
            if (responsible != null) {
                final Player finalResponsible = responsible;
                destroyMarkersNearIfAllowed(e.getLocation(), 1, finalResponsible);
            } else {
                // Unattributed explosion (natural creeper, etc.) - destroy markers as before
                destroyMarkersNear(e.getLocation(), 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        // Covers rare cases of block-caused explosions.
        destroyMarkersNear(e.getBlock().getLocation(), 1);
    }

    /**
     * Prevents TNT and TNT minecarts from traveling through ANY dimension portals.
     * This closes the End Portal exploit where teleported explosives lose their
     * owner metadata and spawn at the Overworld destination above protected regions,
     * bypassing WorldGuard permission checks.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        Entity entity = e.getEntity();
        if (entity == null) return;

        EntityType type = entity.getType();
        if (type == EntityType.TNT || type == EntityType.TNT_MINECART) {
            e.setCancelled(true);
            // Remove the entity to prevent it from re-triggering or lingering
            entity.remove();
        }
    }

    private void destroyMarkersNearIfAllowed(Location center, int radius, Player responsible) {
        World w = center.getWorld();
        if (w == null) return;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Location l = new Location(w,
                            center.getBlockX() + dx,
                            center.getBlockY() + dy,
                            center.getBlockZ() + dz);
                    Optional<PrivateRecord> rec = plugin.store().byLocation(
                            w.getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                    if (rec.isPresent()) {
                        PrivateRecord r = rec.get();
                        // Only destroy if the responsible player is the owner, OP, or has bypass
                        if (r.owner != null && (r.owner.equals(responsible.getUniqueId())
                                || responsible.isOp()
                                || responsible.hasPermission("aquaprivate.bypass"))) {
                            destroyPrivateMarker(l, r);
                        }
                    }
                }
            }
        }
    }

    private void destroyMarkersNear(Location center, int radius) {
        World w = center.getWorld();
        if (w == null)
            return;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Location l = new Location(w,
                            center.getBlockX() + dx,
                            center.getBlockY() + dy,
                            center.getBlockZ() + dz);
                    Optional<PrivateRecord> rec = plugin.store().byLocation(
                            w.getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                    rec.ifPresent(r -> destroyPrivateMarker(l, r));
                }
            }
        }
    }

    private void destroyPrivateMarker(Location markerLoc, PrivateRecord r) {
        Block marker = markerLoc.getBlock();

        // фермеры, привязанные к этому привату
        try {
            plugin.fermer().getManager().removePetsBoundToPrivate(r.regionId);
        } catch (Throwable ignored) {
        }

        // опыт закачки (0..100 уровней)
        try {
            spawnExpForLevels(markerLoc.clone().add(0.5, 0.5, 0.5), Math.max(0, Math.min(100, r.farmerExpLevels)));
        } catch (Throwable ignored) {
        }

        // опыт хранителя (guard) — тоже выбрасываем
        try {
            int guardLvls = Math.max(0, Math.min(100, r.guardExpLevels));
            if (guardLvls > 0)
                spawnExpForLevels(markerLoc.clone().add(0.5, 0.5, 0.5), guardLvls);
        } catch (Throwable ignored) {
        }

        // Удаляем установленную "воронку для фермера" (и выбрасываем содержимое)
        try {
            com.fermerpets.PlayersHoppersStore phs = new com.fermerpets.PlayersHoppersStore(plugin.fermer());
            com.fermerpets.PlayersHoppersStore.Record gh = phs.getGlobalHopperForOwner(r.owner);
            if (gh != null && gh.loc != null && gh.loc.getWorld() != null) {
                org.bukkit.block.Block hb = gh.loc.getBlock();
                if (hb.getType() == org.bukkit.Material.HOPPER) {
                    org.bukkit.block.BlockState st = hb.getState();
                    if (st instanceof org.bukkit.block.Hopper hopper) {
                        org.bukkit.inventory.Inventory inv = hopper.getInventory();
                        for (org.bukkit.inventory.ItemStack it : inv.getContents()) {
                            if (it != null && it.getType() != org.bukkit.Material.AIR) {
                                hb.getWorld().dropItemNaturally(hb.getLocation().add(0.5, 0.5, 0.5), it);
                            }
                        }
                        inv.clear();
                    }
                }

                // Stop totem particles and unpin chunk
                try {
                    if (plugin.fermer() != null && plugin.fermer().hopperBeacon() != null) {
                        plugin.fermer().hopperBeacon().cancelTotem(gh.loc);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    com.fermerpets.ChunkPinService cps = new com.fermerpets.ChunkPinService(plugin);
                    cps.unpinChunkAt(gh.loc);
                } catch (Throwable ignored) {
                }

                hb.setType(org.bukkit.Material.AIR);
            }
            phs.clearAllHoppersForOwner(r.owner);
        } catch (Throwable ignored) {
        }

        // Remove WG region
        RegionManager rm = plugin.wg().regionManager(marker.getWorld());
        if (rm != null) {
            rm.removeRegion(r.regionId);
            try {
                rm.save();
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to save WG regions: " + ex.getMessage());
            }
        }

        // Remove record
        plugin.holograms().removeFor(r);
        plugin.glints().removeFor(r);
        plugin.store().remove(r);
        plugin.store().save();

        // Drop private item
        PrivateBlockType type = plugin.resolveBlockType(r);

        String ownerName = null;
        try {
            ownerName = Bukkit.getOfflinePlayer(r.owner).getName();
        } catch (Throwable ignored) {
        }
        if (ownerName == null)
            ownerName = "Unknown";

        ItemStack drop = (type != null)
                ? ItemFactory.createPrivateItem(plugin, type, ownerName, r.level)
                : new ItemStack(marker.getType());

        marker.setType(Material.AIR);
        marker.getWorld().dropItemNaturally(markerLoc, drop);

        if (type != null && type.settings.deleteLightning) {
            marker.getWorld().strikeLightningEffect(markerLoc);
            playLightningSound(markerLoc);
        }
    }

    private void applyRegionFlags(ProtectedRegion region, PrivateBlockType type, Player owner) {
        // greeting/farewell messages
        String greet = plugin.cfg().msgRaw("enter").replace("%owner%", owner.getName());
        String bye = plugin.cfg().msgRaw("leave").replace("%owner%", owner.getName());

        // WG will serialize as '§' in regions.yml
        region.setFlag(Flags.GREET_MESSAGE, plugin.cfg().color(greet));
        region.setFlag(Flags.FAREWELL_MESSAGE, plugin.cfg().color(bye));

        // Hard safety: withers must NOT damage blocks in privates (body or skull).
        try {
            region.setFlag(Flags.WITHER_DAMAGE, StateFlag.State.DENY);
        } catch (Throwable ignored) {
        }
        // Note: WorldGuard 7.0.14 does not expose a WITHER_EXPLOSION flag constant.
        // Wither skull/body block breaking is enforced via Bukkit events instead
        // (EntityChangeBlockEvent + EntityExplodeEvent) for compatibility.

        // Explosions logic from config (for other explosion types)
        boolean explosions = type.settings.explosions;
        Set<String> excl = new HashSet<>(type.settings.exclusionExplosionsTypes);

        // Map a few standard explosion flags
        // Exclusions are entity-type names (as used in config). Support both old and
        // new naming.
        boolean exclTnt = excl.contains("TNT") || excl.contains("PRIMED_TNT");
        boolean exclTntMinecart = excl.contains("TNT_MINECART") || excl.contains("MINECART_TNT");
        setExplosionFlag(region, Flags.TNT, explosions, exclTnt || exclTntMinecart);
        setExplosionFlag(region, Flags.CREEPER_EXPLOSION, explosions, excl.contains("CREEPER"));
        setExplosionFlag(region, Flags.GHAST_FIREBALL, explosions, excl.contains("FIREBALL"));

        // PvP flag is configurable
        region.setFlag(Flags.PVP, type.settings.allowPvp ? StateFlag.State.ALLOW : StateFlag.State.DENY);
    }

    private java.util.Map<String, String> buildCustomYamlFlags(PrivateBlockType type, Player owner,
            Location markerLoc) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();

        // farewell-action: в примере это отдельный строковый флаг
        String bye = plugin.cfg().msgRaw("leave").replace("%owner%", owner.getName());
        map.put("farewell-action", plugin.cfg().color(bye));

        // ps-home: x y z (y на +1 как в примере)
        String home = markerLoc.getBlockX() + ".0 " + (markerLoc.getBlockY() + 1) + ".0 " + markerLoc.getBlockZ()
                + ".0";
        map.put("ps-home", home);

        // ps-tax-autopayer: UUID владельца
        map.put("ps-tax-autopayer", owner.getUniqueId().toString());

        // ps-block-material: материал блока привата
        map.put("ps-block-material", type.material.name());

        return map;
    }

    private void setExplosionFlag(ProtectedRegion region, StateFlag flag, boolean explosionsEnabled,
            boolean excludedTypeShouldAllow) {
        // If explosions are disabled, allow only excluded types.
        // If explosions are enabled, deny excluded types.
        StateFlag.State state;
        if (!explosionsEnabled) {
            state = excludedTypeShouldAllow ? StateFlag.State.ALLOW : StateFlag.State.DENY;
        } else {
            state = excludedTypeShouldAllow ? StateFlag.State.DENY : StateFlag.State.ALLOW;
        }
        region.setFlag(flag, state);
    }

    /**
     * Placement limit based on permissions:
     * - aquaprivate.place.<N> where N is integer
     * - aquaprivate.place.* means unlimited
     * Returns 0 if player has no numbered permissions.
     */
    private int getPlaceLimit(Player player) {
        if (player.hasPermission("aquaprivate.place.*")) {
            return Integer.MAX_VALUE;
        }

        int max = 0;
        for (org.bukkit.permissions.PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            if (!pai.getValue())
                continue;
            String perm = pai.getPermission();
            if (!perm.startsWith("aquaprivate.place."))
                continue;
            String tail = perm.substring("aquaprivate.place.".length());
            if ("*".equals(tail)) {
                return Integer.MAX_VALUE;
            }
            try {
                int n = Integer.parseInt(tail);
                if (n > max)
                    max = n;
            } catch (NumberFormatException ignored) {
            }
        }
        return max;
    }
}