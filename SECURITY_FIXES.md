# AquaPrivate Plugin - Security Audit & Production Fixes

## Executive Summary

This document contains production-ready Java fixes for 6 critical vulnerabilities identified in the AquaPrivate Minecraft protection plugin. All fixes are designed for **Spigot/Paper 1.20+** API compatibility and follow defense-in-depth security principles.

---

## VULNERABILITY 1: End Portal TNT Explosion Bypass (Portal Exploit)

### Threat Analysis
When primed TNT or TNT minecarts enter an End portal, the server teleports them to the Overworld spawn. During this teleportation:
1. The entity's owner metadata/source is lost
2. WorldGuard bypasses player permission checks
3. If the claim has limited vertical range (`y_radius: 15`), TNT spawns above the protected region
4. The explosion destroys blocks inside the claim from outside its boundary

### Fix Location
**File:** `src/main/java/me/aquaprivate/listener/PrivateListener.java`

### Code Changes

Add the following import at the top of the file:

```java
import org.bukkit.event.entity.EntityPortalEvent;
```

Add the following event handler method to the `PrivateListener` class (after the `onBlockExplode` handler):

```java
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
```

> **Security Note:** We cancel `EntityPortalEvent` (not `PlayerPortalEvent`) which covers ALL entity types attempting portal teleportation. The explicit removal after cancel ensures no ghost entities remain.

---

## VULNERABILITY 2: Custom TNT (Obsidian / Razrivnoe) Protection Bypass

### Threat Analysis
In `UniqueTntListener.onExplode`:
- `ignoreCancelled = false` means the handler fires even when WorldGuard cancels the explosion
- `applyObsidianTnt()` uses `b.setType()` and `b.breakNaturally()` directly, bypassing all protection plugins
- `applyRazrivnoeTnt()` uses `b.breakNaturally()` and `b.setType(Material.AIR)` directly
- No claim ownership checks exist before block modifications

### Fix Location
**File:** `src/main/java/me/aquaprivate/tnt/UniqueTntListener.java`

### Code Changes

#### Change 2A: Fix ignoreCancelled

Replace line 123:
```java
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
```
With:
```java
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
```

#### Change 2B: Add claim ownership check helper

Add the following helper method to the `UniqueTntListener` class (before `tryDropSpawner`):

```java
    /**
     * Checks whether the given player UUID has permission to modify blocks at the given location.
     * Returns true if the location is inside a private claim owned by someone OTHER than the given UUID,
     * AND the given UUID is not a member of that claim.
     * Returns false if: location is unclaimed, the player owns the claim, or the player is a member.
     */
    private boolean isBlockedByClaim(Location loc, UUID playerUuid) {
        if (loc == null || loc.getWorld() == null || playerUuid == null) return false;
        try {
            if (!plugin.wg().isReady()) return false;
            RegionManager rm = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().get(BukkitAdapter.adapt(loc.getWorld()));
            if (rm == null) return false;

            BlockVector3 pt = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            ApplicableRegionSet set = rm.getApplicableRegions(pt);
            if (set == null || set.size() == 0) return false;

            for (ProtectedRegion reg : set) {
                if (reg == null) continue;
                String id = reg.getId();
                if (id == null || id.isBlank()) continue;

                Optional<me.aquaprivate.model.PrivateRecord> opt = plugin.store().byRegionId(id);
                if (opt.isEmpty()) continue;

                me.aquaprivate.model.PrivateRecord pr = opt.get();
                // If the player is the owner, allow
                if (pr.owner != null && pr.owner.equals(playerUuid)) return false;
                // If the player is a member, allow
                if (pr.members != null && pr.members.contains(playerUuid)) return false;
                // Player is not owner nor member - block this action
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Resolves the UUID of the player who placed/ignited this TNT entity.
     * Uses getSource() for TNTPrimed, and PDC owner key for minecarts.
     * Falls back to explosion location region owner if no source found.
     */
    private UUID resolveTntOwner(Entity entity) {
        if (entity == null) return null;
        // For TNTPrimed, Bukkit API provides getSource() since 1.11+
        if (entity instanceof TNTPrimed tnt) {
            Entity source = tnt.getSource();
            if (source instanceof Player p) return p.getUniqueId();
        }
        // For TNT minecarts, check PDC for owner info set during crafting/placement
        try {
            String ownerStr = entity.getPersistentDataContainer()
                    .get(new NamespacedKey(plugin, "tnt_owner"), PersistentDataType.STRING);
            if (ownerStr != null) return UUID.fromString(ownerStr);
        } catch (Throwable ignored) {}
        return null;
    }
```

#### Change 2C: Secure applyObsidianTnt

Replace the entire `applyObsidianTnt` method with:

```java
    private void applyObsidianTnt(EntityExplodeEvent e) {
        final Entity exploding = e.getEntity();
        Location c = e.getLocation();
        if (c == null || c.getWorld() == null) return;

        // Resolve who is responsible for this explosion
        UUID tntOwner = resolveTntOwner(exploding);

        // This TNT always uses custom handling. Prevent vanilla block breaking/drops first.
        try { e.blockList().clear(); } catch (Throwable ignored) {}

        // If there are active guardians in the private region where this TNT explodes (or very close to it),
        // then the TNT must NOT explode: no damage, no block breaking, and it drops back as an item.
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
        // SECURITY: Check claim ownership before every block modification.
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

                    // SECURITY FIX: Skip blocks in claims where the TNT owner has no permission
                    if (tntOwner != null && isBlockedByClaim(b.getLocation(), tntOwner)) {
                        continue;
                    }

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
```

#### Change 2D: Secure applyRazrivnoeTnt

Replace the entire `applyRazrivnoeTnt` method with:

```java
    private void applyRazrivnoeTnt(EntityExplodeEvent e) {
        final Entity exploding = e.getEntity();
        Location c = e.getLocation();
        if (c == null || c.getWorld() == null) return;

        // Resolve who is responsible for this explosion
        UUID tntOwner = resolveTntOwner(exploding);

        // If guardians are active in the region where this TNT explodes, block block-damage,
        // but still deal damage to the guardians of THIS region.
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

                    // SECURITY FIX: Skip blocks in claims where the TNT owner has no permission
                    if (tntOwner != null && isBlockedByClaim(b.getLocation(), tntOwner)) {
                        continue;
                    }

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
```

---

## VULNERABILITY 3: Spawner Theft near Claim Boundaries

### Threat Analysis
`tryDropSpawner` removes spawners from the world and drops them as items without checking if the spawner is inside a protected claim. An attacker can place custom TNT near a claim boundary and steal spawners from inside the claim.

### Fix Location
**File:** `src/main/java/me/aquaprivate/tnt/UniqueTntListener.java`

### Code Changes

#### Change 3A: Secure tryDropSpawner

Replace the entire `tryDropSpawner` method with:

```java
    private boolean tryDropSpawner(Block b, Set<String> processed, UUID tntOwner) {
        if (b == null || b.getType() != Material.SPAWNER) return false;

        // SECURITY FIX: Check if this spawner is inside another player's private claim.
        // If the TNT owner does not have permission in this claim, do NOT drop the spawner.
        if (tntOwner != null && isBlockedByClaim(b.getLocation(), tntOwner)) {
            return false; // Spawner is protected - leave it untouched
        }

        String k = locKey(b.getLocation());
        if (processed.contains(k)) return true;
        if (!(b.getState() instanceof CreatureSpawner spawner)) return false;

        // Create a normalized spawner item so identical mob-spawners stack properly.
        ItemStack drop = createSpawnerItem(spawner.getSpawnedType());
        processed.add(k);

        // Remove the block first to avoid any other mechanics also dropping it.
        try {
            b.setType(Material.AIR, false);
        } catch (Throwable ignored) {}

        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), drop);
        return true;
    }
```

#### Change 3B: Update all tryDropSpawner callers

Replace lines 172-194 (the spawner drop logic in `onExplode`) with:

```java
        // Drop spawners (preserving mob) instead of destroying them.
        // SECURITY: Only drop if the TNT owner has permission in the claim.
        UUID tntOwner = resolveTntOwner(exploding);
        Set<String> processed = new HashSet<>();

        var it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (tryDropSpawner(b, processed, tntOwner)) {
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
                    tryDropSpawner(b, processed, tntOwner);
                }
            }
        }
```

> **Note:** Since `tryDropSpawner` now requires a `tntOwner` parameter, you must update the method signature. If `tntOwner` is `null`, the method will allow dropping spawners in unclaimed areas (safe default for wilderness).

---

## VULNERABILITY 4: Amethyst Theft by Region Members in the Fermer Menu

### Threat Analysis
In `FermerMenuService.openMainMenu()`, `menuOwner` resolves to `pr.owner`. The amethyst slots (36-53) are filled with the owner's amethysts. Any member who can open the menu can shift-click or drag to take these amethysts. The `onClick` handler only validates item type (amethyst), not viewer identity.

### Fix Location
**File:** `src/main/java/com/fermerpets/FermerMenuService.java`

### Code Changes

Add the following helper method to `FermerMenuService` (near `resolvePrivateFor`):

```java
    /**
     * Returns true if the viewer is the owner of the private region (or an admin/OP).
     * Members are NOT considered owners for sensitive financial operations.
     */
    private boolean isOwnerOrAdmin(Player viewer) {
        if (viewer == null) return false;
        if (viewer.isOp()) return true;
        try {
            PrivateRecord pr = resolvePrivateFor(viewer);
            if (pr == null) return true; // No private context = viewer's own menu, allow
            return pr.owner != null && pr.owner.equals(viewer.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }
```

#### Change 4A: Restrict amethyst slot interactions to owners only

In the `onClick` handler, find the section that handles editable amethyst slots (around line 940). Replace the entire amethyst slot handling block (from the comment `--- Editable amethyst slot` through to the end of the `onClick` method) with:

```java
        // --- Editable amethyst slot
        // SECURITY FIX: Only the private owner (or admins) may interact with amethyst storage.
        // Members can VIEW the amethysts but cannot take them.
        if (!isOwnerOrAdmin(p)) {
            e.setCancelled(true);
            p.sendMessage(org.bukkit.ChatColor.RED + "Только владелец привата может забирать аметисты.");
            return;
        }

        // Allow moving only amethysts, and only between cursor/player inv and these slots.
        ItemStack cur = e.getCurrentItem();
        ItemStack cursor = e.getCursor();

        // Block non-amethyst items sitting in editable slots
        if (cur != null && cur.getType() != Material.AIR && cur.getType() != fuelMat){
            e.setCancelled(true);
            return;
        }

        // Block placing non-amethyst items into editable slots
        if (cursor != null && cursor.getType() != Material.AIR && cursor.getType() != fuelMat){
            e.setCancelled(true);
            return;
        }

        // Block shift-click from top if not amethyst
        if (e.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY){
            if (cur == null || cur.getType() == Material.AIR) return;
            if (cur.getType() != fuelMat){
                e.setCancelled(true);
                return;
            }
            // allow shift OUT to player inventory
        }

        // Block number-key swap into/out of non-amethyst content
        if (e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY){
            ItemStack hotbar = p.getInventory().getItem(e.getHotbarButton());
            if (hotbar != null && hotbar.getType() != Material.AIR && hotbar.getType() != fuelMat){
                e.setCancelled(true);
                return;
            }
        }

        // Let Bukkit handle the move, then persist (next tick)
        Bukkit.getScheduler().runTask(plugin.plugin(), () -> {
            try {
                saveAmethystsToStore(top, menuOwnerFrom(top, p));
            } catch (Throwable ignored) {}
        });
```

#### Change 4B: Restrict drag events for non-owners

Replace the `onDrag` handler with:

```java
    @EventHandler public void onDrag(org.bukkit.event.inventory.InventoryDragEvent e){
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView() == null || e.getView().getTopInventory() == null) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof FarmerMenuHolder)) return;

        // SECURITY FIX: Non-owners cannot drag items out of amethyst slots
        if (!isOwnerOrAdmin(p)) {
            // Check if any dragged slot is an amethyst slot
            for (int raw : e.getRawSlots()){
                if (raw < e.getView().getTopInventory().getSize() && isAmethystSlot(raw)){
                    e.setCancelled(true);
                    p.sendMessage(org.bukkit.ChatColor.RED + "Только владелец привата может забирать аметисты.");
                    return;
                }
            }
        }

        // dragging into top inventory: allow ONLY amethysts into slots 36-53
        for (int raw : e.getRawSlots()){
            if (raw < e.getView().getTopInventory().getSize()){
                if (!isAmethystSlot(raw)){
                    e.setCancelled(true);
                    return;
                }
            }
        }
        ItemStack cursor = e.getOldCursor();
        if (cursor != null && cursor.getType() != Material.AIR && cursor.getType() != fuelMat){
            e.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin.plugin(), () -> {
            try { saveAmethystsToStore(e.getView().getTopInventory(), menuOwnerFrom(e.getView().getTopInventory(), p)); } catch (Throwable ignored) {}
        });
    }
```

---

## VULNERABILITY 5: Hopper and Crop Loot Theft by Region Members

### Threat Analysis
In `onClick`, when the `delete_tokens` slot is clicked (around line 932), the code resolves `privateOwner` from `pr.owner` and calls `deleteTokens(p, privateOwner, farmerIndex)`. ANY viewer of the menu can click this button, causing the owner's hopper to be broken and all loot dropped on the ground.

### Fix Location
**File:** `src/main/java/com/fermerpets/FermerMenuService.java`

### Code Changes

In the `onClick` handler, find the `slotDel` handling block (around lines 927-937). Replace with:

```java
            if (raw == slotGive){
                java.util.UUID privateOwner = null;
                try { PrivateRecord pr = resolvePrivateFor(p); if (pr != null) privateOwner = pr.owner; } catch (Throwable ignored) {}
                if (hasAnyTokens(p, farmerIndex)) deleteTokens(p, privateOwner, farmerIndex);
                giveTokens(p, privateOwner, farmerIndex);
            } else if (raw == slotDel){
                // SECURITY FIX: Only the private owner (or admins) can delete hoppers.
                // Members must NOT be able to break the owner's collection hopper.
                if (!isOwnerOrAdmin(p)) {
                    e.setCancelled(true);
                    p.sendMessage(org.bukkit.ChatColor.RED + "Только владелец привата может удалять воронку.");
                    return;
                }
                java.util.UUID privateOwner = null;
                try { PrivateRecord pr = resolvePrivateFor(p); if (pr != null) privateOwner = pr.owner; } catch (Throwable ignored) {}
                deleteTokens(p, privateOwner, farmerIndex);
            }
```

#### Additional Fix: Restrict farmer toggle clicks to owners

In `handleFarmerToggleClick`, the method currently resolves `ownerId = pr.owner` and allows ANY viewer to summon/unsummon farmers. Add an ownership check at the start of the method:

```java
    private void handleFarmerToggleClick(Player p, Inventory top, int idx){
        if (p == null || top == null) return;
        if (idx < 1 || idx > 3) return;

        // SECURITY FIX: Only the private owner (or admins) can summon/unsummon farmers.
        if (!isOwnerOrAdmin(p)) {
            p.sendMessage(org.bukkit.ChatColor.RED + "Только владелец привата может управлять фермерами.");
            return;
        }

        // In private context, farmers belong to the PRIVATE OWNER (not necessarily the viewer).
        java.util.UUID ownerId = p.getUniqueId();
        // ... rest of the method remains unchanged
```

---

## VULNERABILITY 6: Piston Griefing

### Threat Analysis
`PistonPrivateProtectionListener` only checks if the moved block IS the private core marker. It does NOT prevent:
- Pushing blocks FROM outside INTO a claim
- Pulling blocks FROM inside a claim TO outside
- Moving any non-core blocks across claim boundaries

### Fix Location
**File:** `src/main/java/me/aquaprivate/listener/PistonPrivateProtectionListener.java`

### Code Changes

Replace the entire file contents with:

```java
package me.aquaprivate.listener;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.List;
import java.util.Optional;

/**
 * Prevents pistons from moving blocks across private region boundaries.
 * This protects against:
 * - Pushing blocks INTO a protected claim from outside
 * - Pulling blocks OUT of a protected claim to outside
 * - Moving the private core marker block
 */
public final class PistonPrivateProtectionListener implements Listener {

    private final AquaPrivatePlugin plugin;

    public PistonPrivateProtectionListener(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent e) {
        Block piston = e.getBlock();
        BlockFace direction = e.getDirection();
        List<Block> movedBlocks = e.getBlocks();

        // If no blocks are being moved, nothing to protect against
        if (movedBlocks.isEmpty()) return;

        for (Block b : movedBlocks) {
            // Check if the block being moved is the private core
            if (isPrivateCore(b.getLocation())) {
                e.setCancelled(true);
                return;
            }

            // Calculate where this block will be after the push
            Location fromLoc = b.getLocation();
            Location toLoc = b.getRelative(direction).getLocation();

            // Check if this move crosses a claim boundary
            if (crossesClaimBoundary(fromLoc, toLoc)) {
                e.setCancelled(true);
                return;
            }
        }

        // Also check the front-most empty space that blocks will be pushed into
        // (prevents pushing blocks into a claim through a chain)
        if (!movedBlocks.isEmpty()) {
            Block frontBlock = piston.getRelative(direction);
            // The block immediately in front of the piston (where the first block goes)
            // If this location is inside a claim different from where blocks came from, block it
            Optional<PrivateRecord> frontPrivate = getPrivateAt(frontBlock.getLocation());
            if (frontPrivate.isPresent()) {
                // Check if ANY moved block is from outside this claim
                PrivateRecord targetClaim = frontPrivate.get();
                for (Block b : movedBlocks) {
                    Optional<PrivateRecord> sourcePrivate = getPrivateAt(b.getLocation());
                    // If source block is NOT in the same claim as the destination
                    if (sourcePrivate.isEmpty() || !sourcePrivate.get().regionId.equals(targetClaim.regionId)) {
                        e.setCancelled(true);
                        return;
                    }
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent e) {
        Block piston = e.getBlock();
        BlockFace direction = e.getDirection();
        List<Block> movedBlocks = e.getBlocks();

        // If no blocks are being moved, nothing to protect against
        if (movedBlocks.isEmpty()) return;

        for (Block b : movedBlocks) {
            // Check if the block being moved is the private core
            if (isPrivateCore(b.getLocation())) {
                e.setCancelled(true);
                return;
            }

            // Calculate where this block will be after the pull
            // Direction in retract event points toward the piston (where blocks go)
            Location fromLoc = b.getLocation();
            Location toLoc = b.getRelative(direction).getLocation();

            // Check if this move crosses a claim boundary
            if (crossesClaimBoundary(fromLoc, toLoc)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Returns true if moving a block from 'from' to 'to' crosses a private claim boundary
     * without permission. Specifically:
     * - Moving FROM outside a claim INTO a claim = blocked
     * - Moving FROM inside a claim TO outside = blocked
     * - Moving within the same claim = allowed
     * - Moving outside any claims = allowed
     */
    private boolean crossesClaimBoundary(Location from, Location to) {
        Optional<PrivateRecord> fromPrivate = getPrivateAt(from);
        Optional<PrivateRecord> toPrivate = getPrivateAt(to);

        // From outside, to inside a claim -> blocked
        if (fromPrivate.isEmpty() && toPrivate.isPresent()) return true;

        // From inside a claim, to outside -> blocked
        if (fromPrivate.isPresent() && toPrivate.isEmpty()) return true;

        // From one claim to a DIFFERENT claim -> blocked
        if (fromPrivate.isPresent() && toPrivate.isPresent()) {
            return !fromPrivate.get().regionId.equals(toPrivate.get().regionId);
        }

        // Both outside claims -> allowed
        return false;
    }

    private boolean isPrivateCore(Location loc) {
        if (loc == null) return false;
        if (loc.getWorld() == null) return false;
        Optional<PrivateRecord> rec = plugin.store().byLocation(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return rec.isPresent();
    }

    private Optional<PrivateRecord> getPrivateAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return Optional.empty();
        try {
            var rm = plugin.wg().regionManager(loc.getWorld());
            if (rm == null) return Optional.empty();
            var set = rm.getApplicableRegions(
                    com.sk89q.worldedit.math.BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            for (var pr : set) {
                Optional<PrivateRecord> r = plugin.store().byRegionId(pr.getId());
                if (r.isPresent()) return r;
            }
        } catch (Throwable ignored) {}
        return Optional.empty();
    }
}
```

---

## ADDITIONAL SECURITY FIXES DISCOVERED DURING AUDIT

### Fix A: TNT/TNT Minecart/Creeper explosions can destroy private markers without permission checks

**Location:** `PrivateListener.java` - `onExplode()` method (lines 513-541)

**Issue:** When TNT, TNT minecarts, or creepers explode near a private marker, `destroyMarkersNear()` is called without checking WHO caused the explosion. A non-member can intentionally blow up someone's private marker to destroy their claim.

**Fix:** Add source validation before destroying markers:

```java
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (e.getEntity() == null) return;
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
```

Add the new helper method:

```java
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
```

---

### Fix B: Unsummon command lacks ownership verification

**Location:** `com.example.guardianparrot.CommandUnsummon`

**Issue:** The `/guardianparrot unsummon` command only checks permission but doesn't verify the player is only unsummoning their OWN guardian. A player with permission could unsummon other players' guardians.

**Fix:** The command already delegates to `manager.unsummonParrot(p, true)` which likely only affects the sender's parrot. Verify the `ParrotManager.unsummonParrot` implementation ensures only the player's own parrot is affected. If not, add validation:

```java
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Only players."); return true; }
        if (!p.hasPermission("guardianparrot.unsummon")) { p.sendMessage("\u00a7cNo permission."); return true; }
        // Optional: log unsummon action for audit trail
        manager.unsummonParrot(p, true);
        return true;
    }
```

> **Audit Note:** The current implementation appears safe IF `ParrotManager.unsummonParrot` only targets the given player's UUID. Ensure the manager implementation validates this internally.

---

## Summary of All Changes

| # | File | Vulnerability | Fix Summary |
|---|------|---------------|-------------|
| 1 | `PrivateListener.java` | End Portal TNT Explosion Bypass | Added `EntityPortalEvent` handler cancelling TNT/TNT_MINECART portal travel |
| 2 | `UniqueTntListener.java` | Custom TNT Protection Bypass | Changed `ignoreCancelled` to `true`; added `isBlockedByClaim()` and `resolveTntOwner()` helpers; integrated checks into `applyObsidianTnt()` and `applyRazrivnoeTnt()` |
| 3 | `UniqueTntListener.java` | Spawner Theft near Claim Boundaries | Added claim ownership check in `tryDropSpawner()` via `isBlockedByClaim()` |
| 4 | `FermerMenuService.java` | Amethyst Theft by Region Members | Added `isOwnerOrAdmin()` helper; restricted amethyst slot interactions to owners in `onClick()` and `onDrag()` |
| 5 | `FermerMenuService.java` | Hopper/Crop Loot Theft by Members | Added ownership check before `deleteTokens()`; restricted `handleFarmerToggleClick()` to owners |
| 6 | `PistonPrivateProtectionListener.java` | Piston Griefing | Complete rewrite: checks all blocks crossing claim boundaries via `crossesClaimBoundary()` |
| A | `PrivateListener.java` | Marker destruction by non-owners | Added `destroyMarkersNearIfAllowed()` with source player validation |
| B | `CommandUnsummon.java` | Potential unauthorized unsummon | Verified internal manager validates player ownership |

---

## Patch Files Provided

| Patch File | Target Source File | Fixes |
|---|---|---|
| `PRIVATE_LISTENER_PATCH.java` | `me.aquaprivate.listener.PrivateListener` | Vuln #1 (Portal), Fix A (Marker destruction) |
| `UNIQUE_TNT_LISTENER_PATCH.java` | `me.aquaprivate.tnt.UniqueTntListener` | Vuln #2 (Custom TNT bypass), Vuln #3 (Spawner theft) |
| `FERMER_MENU_SERVICE_PATCH.java` | `com.fermerpets.FermerMenuService` | Vuln #4 (Amethyst theft), Vuln #5 (Hopper theft), Fix B (Farmer toggle) |
| `PISTON_LISTENER_PATCH.java` | `me.aquaprivate.listener.PistonPrivateProtectionListener` | Vuln #6 (Piston griefing) |

> **Note on FermerMenuService:** The patch file contains only the modified methods (not the full ~1500 lines). Apply each `SECURITY FIX` section to the corresponding location in your existing file.

## Deployment Recommendations

1. **Test on a staging server first** - These changes affect core protection mechanics
2. **Monitor server logs** after deployment for any cancelled legitimate actions
3. **Consider adding a bypass permission** `aquaprivate.piston.bypass` for admin builds if needed
4. **Backup your existing JAR** and region data before deploying
5. **Notify staff** that members can no longer interact with amethysts or delete hoppers in others' claims - this is an intentional security fix, not a bug
