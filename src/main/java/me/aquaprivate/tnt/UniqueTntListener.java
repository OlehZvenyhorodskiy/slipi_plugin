package me.aquaprivate.tnt;

import me.aquaprivate.AquaPrivatePlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.bukkit.entity.EntityType;
import org.bukkit.metadata.MetadataValue;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import me.aquaprivate.util.ColorUtil;

/**
 * Custom TNT placement tagging and spawner-drop behaviour.
 */
public final class UniqueTntListener implements Listener {

    private final AquaPrivatePlugin plugin;
    private final UniqueTntService service;
    private final NamespacedKey key;

    // Persisted placed TNT blocks: world;x;y;z -> typeId
    private final Map<String, String> placed = new HashMap<>();
    private final File placedFile;

    public UniqueTntListener(AquaPrivatePlugin plugin, UniqueTntService service) {
        this.plugin = plugin;
        this.service = service;
        this.key = service.pdcKey();
        this.placedFile = new File(plugin.getDataFolder(), "placed_unique_tnt.yml");
        loadPlaced();
    }

    private static String locKey(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    private void loadPlaced() {
        placed.clear();
        if (!placedFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(placedFile);
        for (String k : y.getKeys(false)) {
            placed.put(k, y.getString(k));
        }
    }

    private void savePlaced() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : placed.entrySet()) {
            y.set(e.getKey(), e.getValue());
        }
        try {
            y.save(placedFile);
        } catch (IOException ignored) {
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (b.getType() != Material.TNT) return;

        String typeId = service.getItemTypeId(e.getItemInHand());
        if (typeId == null) return;

        placed.put(locKey(b.getLocation()), typeId);
        savePlaced();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTntSpawn(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof TNTPrimed tnt)) return;

        Location blockLoc = tnt.getLocation().getBlock().getLocation();
        String k = locKey(blockLoc);
        String typeId = placed.remove(k);
        if (typeId == null) {
            // Sometimes spawn location is slightly offset; try block below.
            String k2 = locKey(blockLoc.clone().add(0, -1, 0));
            typeId = placed.remove(k2);
        }
        if (typeId != null) {
            savePlaced();
            tnt.getPersistentDataContainer().set(key, PersistentDataType.STRING, typeId);
        }
    }

    public String getUniqueTntType(Entity entity) {
        if (entity == null) return null;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.get(key, PersistentDataType.STRING);
    }

    // IMPORTANT: we must run before other plugins potentially mutate the block list.
    // HIGHEST ensures our custom TNT block filtering is applied reliably.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onExplode(EntityExplodeEvent e) {
        final Entity exploding = e.getEntity();
        if (exploding == null) return;

        // Guardian protection: while guardians are ACTIVE in the private region (or very close to it),
        // block vanilla TNT / vanilla TNT minecarts / EHoBoom explosions.
        // Custom unique TNT types are handled below.
        if (shouldBlockVanillaExplosionDueToGuardians(e)) {
            return;
        }

        // Handle both primed TNT and TNT minecarts that were crafted from our custom TNT items.
        if (!(exploding instanceof TNTPrimed) && exploding.getType() != EntityType.TNT_MINECART) return;
        String typeId = getUniqueTntType(exploding);
        if (typeId == null) return;
        if (service.getType(typeId) == null) return;

        // Special behaviour: obsidian TNT
        if ("obsidian".equalsIgnoreCase(typeId)) {
            applyObsidianTnt(e);
            return;
        }

        // Special behaviour: razrivnoe TNT
        if ("razrivnoe".equalsIgnoreCase(typeId)) {
            applyRazrivnoeTnt(e);
            return;
        }

        // Guardian protection for our UNIQUE TNT type "ehoboom" (given via /aquaprivate givetnt ehoboom).
        // This TNT should behave like vanilla TNT when allowed, BUT must be blocked the same way as obsidian/vanilla TNT
        // while active guardians exist in the private region at/near the explosion.
        if ("ehoboom".equalsIgnoreCase(typeId)) {
            Location c = e.getLocation();
            if (c != null && c.getWorld() != null && shouldBlockObsidianTntDueToGuardians(c)) {
                try { e.setCancelled(true); } catch (Throwable ignored) {}
                try { e.blockList().clear(); } catch (Throwable ignored) {}
                try { exploding.remove(); } catch (Throwable ignored) {}
                try {
                    var type = service.getType("ehoboom");
                    ItemStack drop = (type != null ? service.createItem(type) : new ItemStack(Material.TNT));
                    c.getWorld().dropItemNaturally(c.clone().add(0.5, 0.5, 0.5), drop);
                } catch (Throwable ignored) {}
                return;
            }
        }

        // Drop spawners (preserving mob) instead of destroying them.
        // Works even if WorldGuard prevents block damage (fallback scan).
        Set<String> processed = new HashSet<>();

        var it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (tryDropSpawner(b, processed)) {
                it.remove();
            }
        }

        Location c = e.getLocation();
        if (c.getWorld() == null) return;
        int r = 6;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = c.getWorld().getBlockAt(c.getBlockX() + dx, c.getBlockY() + dy, c.getBlockZ() + dz);
                    if (b.getLocation().distanceSquared(c) > 25.0) continue; // ~5 blocks
                    tryDropSpawner(b, processed);
                }
            }
        }
    }

    /**
     * Blocks vanilla TNT / TNT minecart / EHoBoom explosions if active guardians exist in the private region.
     * When blocked: explosion is cancelled, the explosive drops back as an item, and a warning is broadcast
     * near the private anchor block (radius 100).
     */
    private boolean shouldBlockVanillaExplosionDueToGuardians(EntityExplodeEvent e) {
        final Entity exploding = e.getEntity();
        if (exploding == null) return false;
        final Location loc = e.getLocation();
        if (loc == null || loc.getWorld() == null) return false;

        // Determine if this is a "vanilla" explosive we need to guard.
        boolean isTntPrimed = exploding.getType() == EntityType.TNT;
        boolean isTntMinecart = exploding.getType() == EntityType.TNT_MINECART;

        // EHoBoom is an external plugin; try to detect by common metadata/tag/name.
        boolean isEhoBoom = hasAnyMetadata(exploding, "ehoboom", "EHoBoom", "EHOBOOM")
                || hasAnyScoreboardTag(exploding, "ehoboom", "EHoBoom", "EHOBOOM")
                || (exploding.getCustomName() != null && exploding.getCustomName().toLowerCase(java.util.Locale.ROOT).contains("ehoboom"));

        // For primed TNT, exclude our custom Unique TNT (it is handled separately).
        if (isTntPrimed && getUniqueTntType(exploding) != null) {
            return false;
        }

        // For TNT minecarts, exclude our custom Unique TNT minecart (it is handled separately).
        if (isTntMinecart && getUniqueTntType(exploding) != null) {
            return false;
        }

        if (!(isTntPrimed || isTntMinecart || isEhoBoom)) {
            return false;
        }

        // If guardians are active in the private region at/near this explosion -> block it.
        if (!shouldBlockObsidianTntDueToGuardians(loc)) {
            return false;
        }

        try { e.setCancelled(true); } catch (Throwable ignored) {}
        try { e.blockList().clear(); } catch (Throwable ignored) {}

        // Remove the exploding entity so it doesn't re-trigger.
        try { exploding.remove(); } catch (Throwable ignored) {}

        // Drop back the explosive as an item.
        try {
            ItemStack drop;
            if (isTntMinecart) {
                drop = new ItemStack(Material.TNT_MINECART);
            } else if (isEhoBoom) {
                // Try to restore the original EHoBoom explosive item (if the external plugin stored it as metadata).
                drop = getItemFromMetadata(exploding,
                        "ehoboom_item", "EHoBoomItem", "EHOBOOM_ITEM",
                        "dynamiteItem", "DynamiteItem",
                        "bombItem", "BombItem");
                if (drop == null) {
                    // Fallback if we can't determine the original item.
                    drop = new ItemStack(Material.TNT);
                }
            } else {
                // Vanilla TNT
                drop = new ItemStack(Material.TNT);
            }
            loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), drop);
        } catch (Throwable ignored) {}

        return true;
    }

    private static boolean hasAnyScoreboardTag(Entity e, String... tags) {
        if (e == null || tags == null || tags.length == 0) return false;
        try {
            var set = e.getScoreboardTags();
            if (set == null || set.isEmpty()) return false;
            for (String t : tags) {
                if (t == null) continue;
                for (String st : set) {
                    if (st != null && st.equalsIgnoreCase(t)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean hasAnyMetadata(Entity e, String... keys) {
        if (e == null || keys == null || keys.length == 0) return false;
        try {
            for (String k : keys) {
                if (k == null) continue;
                if (!e.hasMetadata(k)) continue;
                List<MetadataValue> vals = e.getMetadata(k);
                if (vals != null && !vals.isEmpty()) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Best-effort attempt to extract an ItemStack stored by some external plugin as entity metadata.
     * Used to restore EHoBoom explosives back to the player when we cancel the explosion.
     */
    private static ItemStack getItemFromMetadata(Entity e, String... keys) {
        if (e == null || keys == null || keys.length == 0) return null;
        try {
            for (String k : keys) {
                if (k == null) continue;
                if (!e.hasMetadata(k)) continue;
                List<MetadataValue> vals = e.getMetadata(k);
                if (vals == null || vals.isEmpty()) continue;
                for (MetadataValue mv : vals) {
                    if (mv == null) continue;
                    Object v;
                    try { v = mv.value(); } catch (Throwable t) { continue; }
                    if (v instanceof ItemStack) {
                        ItemStack it = ((ItemStack) v).clone();
                        if (it.getAmount() <= 0) it.setAmount(1);
                        return it;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void applyObsidianTnt(EntityExplodeEvent e) {
        Location c = e.getLocation();
        if (c == null || c.getWorld() == null) return;

        // This TNT always uses custom handling. Prevent vanilla block breaking/drops first.
        try { e.blockList().clear(); } catch (Throwable ignored) {}

        // If there are active guardians in the private region where this TNT explodes (or very close to it),
        // then the TNT must NOT explode: no damage, no block breaking, and it drops back as an item.
        // A warning is broadcast to players near the private block (anchor).
        if (shouldBlockObsidianTntDueToGuardians(c)) {
            try {
                e.setCancelled(true);
            } catch (Throwable ignored) {}
            try {
                var type = service.getType("obsidian");
                ItemStack drop = (type != null ? service.createItem(type) : new ItemStack(Material.TNT));
                c.getWorld().dropItemNaturally(c.clone().add(0.5, 0.5, 0.5), drop);
            } catch (Throwable ignored) {}
            return;
        }

        // Custom obsidian TNT behaviour:
        //  ANCIENT_DEBRIS   -> CRYING_OBSIDIAN   (replace in place, no drops)
        //  CRYING_OBSIDIAN  -> OBSIDIAN          (replace in place, no drops)
        //  OBSIDIAN         -> drop OBSIDIAN     (block disappears and item drops)
        // Search in a small cube around the explosion so adjacent blocks are always found reliably.
        final int r = 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = c.getWorld().getBlockAt(
                            c.getBlockX() + dx,
                            c.getBlockY() + dy,
                            c.getBlockZ() + dz
                    );
                    if (b == null) continue;

                    Material m = b.getType();
                    try {
                        if (m == Material.ANCIENT_DEBRIS) {
                            b.setType(Material.CRYING_OBSIDIAN, false);
                        } else if (m == Material.CRYING_OBSIDIAN) {
                            b.setType(Material.OBSIDIAN, false);
                        } else if (m == Material.OBSIDIAN) {
                            b.setType(Material.AIR, false);
                            c.getWorld().dropItemNaturally(b.getLocation().clone().add(0.5, 0.5, 0.5), new ItemStack(Material.OBSIDIAN));
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void applyRazrivnoeTnt(EntityExplodeEvent e) {
        final Entity exploding = e.getEntity();
        Location c = e.getLocation();
        if (c == null || c.getWorld() == null) return;

        // If guardians are active in the region where this TNT explodes, block block-damage,
        // but still deal damage to the guardians of THIS region.
        // IMPORTANT: no TNT item drop and no chat message.
        try {
            final AquaPrivatePlugin app = plugin;
            if (app.guardianParrot() != null && app.store() != null && app.wg().isReady()) {
                java.util.Set<String> regionIds = findPrivateRegionIdsNear(c, 0);
                if (!regionIds.isEmpty()) {
                    for (String regionId : regionIds) {
                        var opt = app.store().byRegionId(regionId);
                        if (opt.isEmpty()) continue;
                        var pr = opt.get();
                        if (pr.owner == null) continue;

                        boolean active = false;
                        try {
                            active = app.guardianParrot().hasActiveGuardians(pr.owner, regionId);
                        } catch (Throwable ignored) {}

                        if (active) {
                            try {
                                Location center = null;
                                try { center = pr.toLocation(); } catch (Throwable ignored2) {}
                                if (center == null) center = c;
                                app.guardianParrot().damageActiveGuardiansRandomWithBroadcast(pr.owner, regionId, center, 100, 10, 45);
                            } catch (Throwable ignored) {}

                            try { e.setCancelled(true); } catch (Throwable ignored) {}
                            try { e.blockList().clear(); } catch (Throwable ignored) {}
                            try { if (exploding != null) exploding.remove(); } catch (Throwable ignored) {}
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // No active guardians -> perform custom destruction.
        try { e.blockList().clear(); } catch (Throwable ignored) {}

        final int r = 4; // 9x9x9 area

        // Blocks that must NEVER be broken by razrivnoe TNT.
        final java.util.Set<Material> forbidden = new java.util.HashSet<>();
        forbidden.add(Material.OBSIDIAN);
        forbidden.add(Material.CRYING_OBSIDIAN);
        forbidden.add(Material.ANCIENT_DEBRIS);
        forbidden.add(Material.SPAWNER);
        // Backward-compat (some server forks / older mappings)
        try {
            Material legacySpawner = Material.matchMaterial("MOB_SPAWNER");
            if (legacySpawner != null) forbidden.add(legacySpawner);
        } catch (Throwable ignored) {}
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = c.getWorld().getBlockAt(c.getBlockX() + dx, c.getBlockY() + dy, c.getBlockZ() + dz);
                    if (b == null) continue;

                    Material m = b.getType();
                    if (m == Material.AIR) continue;
                    if (forbidden.contains(m)) continue;

                    // Break waterlogged chests/leaves only if waterlogged.
                    boolean mustBeWaterlogged = (m.name().endsWith("CHEST") || m.name().endsWith("LEAVES"));
                    if (mustBeWaterlogged) {
                        try {
                            org.bukkit.block.data.BlockData bd = b.getBlockData();
                            if (bd instanceof org.bukkit.block.data.Waterlogged wl) {
                                if (!wl.isWaterlogged()) continue;
                            } else {
                                continue;
                            }
                        } catch (Throwable ignored) {
                            continue;
                        }
                    }

                    try {
                        b.breakNaturally();
                    } catch (Throwable ignored) {
                        try { b.setType(Material.AIR, false); } catch (Throwable ignored2) {}
                    }
                }
            }
        }
    }

    /**
     * Returns true if an obsidian TNT explosion should be blocked because active guardians exist
     * in the private region at/near the explosion.
     */
    private boolean shouldBlockObsidianTntDueToGuardians(Location explosion) {
        try {
            final AquaPrivatePlugin app = plugin;
            if (app.guardianParrot() == null) return false;
            if (app.store() == null) return false;
            if (!app.wg().isReady()) return false;

            // Collect candidate private region ids at the explosion point (ONLY if inside a private region).
            java.util.Set<String> regionIds = findPrivateRegionIdsNear(explosion, 0);
            if (regionIds.isEmpty()) return false;

            for (String regionId : regionIds) {
                var opt = app.store().byRegionId(regionId);
                if (opt.isEmpty()) continue;
                var pr = opt.get();
                if (pr.owner == null) continue;

                boolean active = false;
                try {
                    active = app.guardianParrot().hasActiveGuardians(pr.owner, regionId);
                } catch (Throwable ignored) {}

                if (active) {
                    // Broadcast warning near the private anchor block (not the TNT itself).
                    Location anchor = pr.toLocation();
                    if (anchor != null && anchor.getWorld() != null) {
                        broadcastNearAnchor(anchor, 100.0,
                                ColorUtil.color("&cХранители Активны &7- &cвзрыв невозможен."));
                    }
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void broadcastNearAnchor(Location anchor, double radius, String msg) {
        if (anchor == null || anchor.getWorld() == null || msg == null) return;
        double r2 = radius * radius;
        for (var p : anchor.getWorld().getPlayers()) {
            try {
                if (p.getLocation().distanceSquared(anchor) <= r2) {
                    p.sendMessage(msg);
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Uses WorldGuard to find private region ids (that exist in our store) around a location.
     */
    private java.util.Set<String> findPrivateRegionIdsNear(Location loc, int sampleRadiusBlocks) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (loc == null || loc.getWorld() == null) return out;
        // NOTE: keep Java 17 compatibility (avoid pattern matching for instanceof with bindings)
        if (!(plugin instanceof me.aquaprivate.AquaPrivatePlugin)) return out;
        me.aquaprivate.AquaPrivatePlugin app = (me.aquaprivate.AquaPrivatePlugin) plugin;
        if (!app.wg().isReady()) return out;

        RegionManager rm;
        try {
            rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(loc.getWorld()));
        } catch (Throwable t) {
            return out;
        }
        if (rm == null) return out;

        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();

        int r = Math.max(0, sampleRadiusBlocks);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockVector3 pt = BlockVector3.at(cx + dx, cy, cz + dz);
                ApplicableRegionSet set;
                try {
                    set = rm.getApplicableRegions(pt);
                } catch (Throwable ignored) {
                    continue;
                }
                if (set == null || set.size() == 0) continue;
                for (ProtectedRegion reg : set) {
                    if (reg == null) continue;
                    String id = reg.getId();
                    if (id == null || id.isBlank()) continue;
                    // only consider regions we actually manage as privates
                    if (app.store().byRegionId(id).isPresent()) {
                        out.add(id);
                    }
                }
            }
        }
        return out;
    }


    private boolean tryDropSpawner(Block b, Set<String> processed) {
        if (b == null || b.getType() != Material.SPAWNER) return false;
        String k = locKey(b.getLocation());
        if (processed.contains(k)) return true;
        if (!(b.getState() instanceof CreatureSpawner spawner)) return false;

        // Create a normalized spawner item so identical mob-spawners stack properly.
        // IMPORTANT: drop exactly ONE spawner item (preserving the mob type).
        // Do NOT also drop a raw spawner item, otherwise it duplicates.
        ItemStack drop = createSpawnerItem(spawner.getSpawnedType());
        processed.add(k);

        // Remove the block first to avoid any other mechanics also dropping it.
        try {
            b.setType(Material.AIR, false);
        } catch (Throwable ignored) {}

        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), drop);
        return true;
    }

    private static ItemStack createSpawnerItem(org.bukkit.entity.EntityType type) {
        ItemStack item = new ItemStack(Material.SPAWNER);
        ItemMeta baseMeta = item.getItemMeta();
        if (!(baseMeta instanceof BlockStateMeta meta)) {
            return item;
        }

        var state = meta.getBlockState();
        if (state instanceof CreatureSpawner cs) {
            // Keep the mob type inside the dropped spawner (if available).
            // Some implementations may return null; don't force it.
            if (type != null && type != org.bukkit.entity.EntityType.UNKNOWN) {
                cs.setSpawnedType(type);
            }

            // Vanilla defaults (normalize to avoid non-stacking due to different internal values)
            try { cs.setDelay(20); } catch (Throwable ignored) {}
            try { cs.setMinSpawnDelay(200); } catch (Throwable ignored) {}
            try { cs.setMaxSpawnDelay(800); } catch (Throwable ignored) {}
            try { cs.setSpawnCount(4); } catch (Throwable ignored) {}
            try { cs.setMaxNearbyEntities(6); } catch (Throwable ignored) {}
            try { cs.setRequiredPlayerRange(16); } catch (Throwable ignored) {}
            try { cs.setSpawnRange(4); } catch (Throwable ignored) {}

            meta.setBlockState(cs);
        }

        // IMPORTANT:
        // Do NOT set a custom display name/lore. We want the vanilla item name
        // ("Рассадник монстров") and we also avoid confusing tooltips like
        // "Spawner: Unknown" when the mob type can't be resolved for display.
        item.setItemMeta(meta);
        return item;
    }

    private static String prettyEntityName(org.bukkit.entity.EntityType type) {
        if (type == null) return "Unknown";
        // Small RU mapping for the most common mobs. Others fallback to Title Case.
        return switch (type) {
            case ZOMBIE -> "Зомби";
            case SKELETON -> "Скелет";
            case CREEPER -> "Крипер";
            case SPIDER -> "Паук";
            case CAVE_SPIDER -> "Пещерный паук";
            case ENDERMAN -> "Эндермен";
            case BLAZE -> "Ифрит";
            case SLIME -> "Слизень";
            case MAGMA_CUBE -> "Магмовый куб";
            case WITCH -> "Ведьма";
            case PIGLIN -> "Пиглин";
            case PIGLIN_BRUTE -> "Пиглин-варвар";
            case GHAST -> "Гаст";
            case WITHER_SKELETON -> "Скелет-иссушитель";
            default -> title(type.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        };
    }

    private static String title(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean up = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ' ') {
                out.append(ch);
                up = true;
                continue;
            }
            out.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return out.toString();
    }
}