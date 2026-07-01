package com.fermerpets;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.CaveVines;
import org.bukkit.block.data.type.CaveVinesPlant;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HarvestAI {

    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, com.fermerpets.motion.MotionController> motionCtl = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> deliveryReturnAtTick = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Boolean> waterEscapeOn = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> waterBreachAt = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean fp_isFrontOneBlockStep(LivingEntity pet){
        Location loc = pet.getLocation();
        Vector dir = loc.getDirection().setY(0).normalize();
        Location front = loc.clone().add(dir.multiply(0.7));
        Block b = front.getBlock();
        Block bUp = b.getRelative(0, 1, 0);
        boolean isSolid = b.getType().isSolid() && !b.isLiquid();
        boolean spaceAbove = !bUp.getType().isSolid() && !bUp.isLiquid();
        boolean onGround = false;
        try { onGround = pet.isOnGround(); } catch (Throwable ignored) {}
        return isSolid && spaceAbove && onGround;
    }
    private static void fp_doStepUpJump(LivingEntity pet){
        Vector v = pet.getVelocity();
        if (v.getY() < 0.42) { v.setY(0.42); pet.setVelocity(v); }
        Debug.logPet(pet, "STEP-UP jump (v5)");
    }
    private static void fp_fixLookTo(Location from, Location to, LivingEntity pet){
        try{
            Vector d = to.clone().add(0.5, 0, 0.5).toVector().subtract(from.toVector());
            if (d.lengthSquared() > 0.0001){
                float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
                pet.setRotation(yaw, from.getPitch());
            }
        } catch (Throwable ignored){}
    }
    private static void fp_waterAssist(LivingEntity pet){
        try{
            if (pet.isInWater()){
                boolean headUnder = pet.getEyeLocation().getBlock().isLiquid();
                if (headUnder){
                    Vector v = pet.getVelocity();
                    if (v.getY() < 0.28){ v.setY(0.28); pet.setVelocity(v); }
                }
            }
        } catch (Throwable ignored){}
    }

    private static final java.util.Map<java.util.UUID, Long> flyStart = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<java.util.UUID> flyLanding = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<java.util.UUID, Boolean>());
    private static final int MAX_FLY_TICKS = 12;

    private HarvestAI(){}

    private static final Map<UUID, Long> lastScanAtMs = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastFeedAtMs = new ConcurrentHashMap<>();
    private static final Map<UUID, Location> lastHarvestLocation = new ConcurrentHashMap<>();
    private static final Map<UUID, java.util.List<ItemStack>> lootBuffer = new ConcurrentHashMap<>();
    private static final Set<String> CROPS = new HashSet<>(Arrays.asList(
            "WHEAT","CARROTS","POTATOES","BEETROOTS","NETHER_WART","SWEET_BERRY_BUSH",
            "PUMPKIN","MELON","SUGAR_CANE","CACTUS","COCOA","RED_MUSHROOM","BROWN_MUSHROOM",
            "KELP","KELP_PLANT","BAMBOO","CAVE_VINES","CAVE_VINES_PLANT"
    ));

    private static boolean matchesFilter(String filter, org.bukkit.Material mat){
        if (filter == null || filter.isEmpty() || mat == null) return true;

        String f = filter.toUpperCase(java.util.Locale.ROOT);
        String m = mat.name().toUpperCase(java.util.Locale.ROOT);

        // Прямое совпадение
        if (f.equals(m)) return true;

        // Совпадения предмет <-> блок
        if (f.equals("SWEET_BERRIES")) return m.equals("SWEET_BERRY_BUSH");
        if (f.equals("CARROT") || f.equals("CARROTS")) return m.equals("CARROTS");
        if (f.equals("POTATO") || f.equals("POTATOES")) return m.equals("POTATOES");
        if (f.equals("BEETROOT") || f.equals("BEETROOTS")) return m.equals("BEETROOTS");
        if (f.equals("NETHER_WART")) return m.equals("NETHER_WART");
        if (f.equals("SUGAR_CANE")) return m.equals("SUGAR_CANE");
        if (f.equals("COCOA_BEANS")) return m.equals("COCOA");
        if (f.equals("MELON") || f.equals("MELON_SLICE")) return m.equals("MELON");
        if (f.equals("PUMPKIN")) return m.equals("PUMPKIN");
        if (f.equals("CACTUS")) return m.equals("CACTUS");
        if (f.equals("RED_MUSHROOM")) return m.equals("RED_MUSHROOM");
        if (f.equals("BROWN_MUSHROOM")) return m.equals("BROWN_MUSHROOM");
        if (f.equals("BAMBOO")) return m.equals("BAMBOO");
        if (f.equals("KELP")) return m.equals("KELP") || m.equals("KELP_PLANT");
        if (f.equals("GLOW_BERRIES")) return m.equals("CAVE_VINES") || m.equals("CAVE_VINES_PLANT");
        if (f.equals("WHEAT")) return m.equals("WHEAT");

        // Обратные совпадения блок -> предмет
        if (m.equals("SWEET_BERRY_BUSH")) return f.equals("SWEET_BERRIES");
        if (m.equals("CARROTS")) return f.equals("CARROT") || f.equals("CARROTS");
        if (m.equals("POTATOES")) return f.equals("POTATO") || f.equals("POTATOES");
        if (m.equals("BEETROOTS")) return f.equals("BEETROOT") || f.equals("BEETROOTS");
        if (m.equals("COCOA")) return f.equals("COCOA_BEANS");
        if (m.equals("MELON")) return f.equals("MELON") || f.equals("MELON_SLICE");
        if (m.equals("CAVE_VINES") || m.equals("CAVE_VINES_PLANT")) return f.equals("GLOW_BERRIES");
        if (m.equals("KELP") || m.equals("KELP_PLANT")) return f.equals("KELP");

        return false;
    }

    public static void clear(UUID petId){
        lastScanAtMs.remove(petId);
        lastFeedAtMs.remove(petId);
        lastHarvestLocation.remove(petId);
        lootBuffer.remove(petId);
    }

    public static boolean tick(FermerPetsModule plugin, LivingEntity pet, Player owner){
        String currentFilter = null; // filter removed

        // Центр поиска (по умолчанию — от позиции питомца)
        Location searchCenter = pet.getLocation();
        int searchRange = plugin.getConfig().getInt("pet.target-distance", plugin.getConfig().getInt("unifiedAI.range", 30));

        // Если regionId привата отсутствует, но есть private_marker — привяжем поиск к маркеру.
        // Это помогает при призыве из меню привата/после reload, когда regionId может не успеть подтянуться.
        try {
            org.bukkit.persistence.PersistentDataContainer pdc0 = pet.getPersistentDataContainer();
            org.bukkit.NamespacedKey kRegion0 = new org.bukkit.NamespacedKey(plugin.plugin(), "private_region");
            String regionId0 = pdc0.get(kRegion0, org.bukkit.persistence.PersistentDataType.STRING);
            if (regionId0 == null || regionId0.isBlank()){
                org.bukkit.NamespacedKey kMarker0 = new org.bukkit.NamespacedKey(plugin.plugin(), "private_marker");
                String marker0 = pdc0.get(kMarker0, org.bukkit.persistence.PersistentDataType.STRING);
                if (marker0 != null && !marker0.isBlank()){
                    String[] parts = marker0.split(":");
                    if (parts.length == 4){
                        java.util.UUID wid = java.util.UUID.fromString(parts[0]);
                        org.bukkit.World w = org.bukkit.Bukkit.getWorld(wid);
                        if (w != null){
                            int mx = Integer.parseInt(parts[1]);
                            int my = Integer.parseInt(parts[2]);
                            int mz = Integer.parseInt(parts[3]);
                            searchCenter = new org.bukkit.Location(w, mx + 0.5, my + 0.5, mz + 0.5);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Привязка зоны сбора к региону привата (без END_ROD)
        // Берём regionId из PDC питомца; границы берём из WorldGuard каждый тик (если регион меняется — зона тоже меняется).
        try {
            org.bukkit.persistence.PersistentDataContainer pdc = pet.getPersistentDataContainer();
            org.bukkit.NamespacedKey kRegion = new org.bukkit.NamespacedKey(plugin.plugin(), "private_region");
            String regionId = pdc.get(kRegion, org.bukkit.persistence.PersistentDataType.STRING);
            if (regionId != null && !regionId.isBlank()){
                com.sk89q.worldguard.protection.managers.RegionManager rm = com.sk89q.worldguard.WorldGuard.getInstance()
                        .getPlatform().getRegionContainer().get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(pet.getWorld()));
                if (rm != null){
                    com.sk89q.worldguard.protection.regions.ProtectedRegion pr = rm.getRegion(regionId);
                    if (pr != null){
                        com.sk89q.worldedit.math.BlockVector3 min = pr.getMinimumPoint();
                        com.sk89q.worldedit.math.BlockVector3 max = pr.getMaximumPoint();

                        // центр и радиус (по X/Z), чтобы сканирование перекрывало весь регион
                        int cx = (min.getBlockX() + max.getBlockX()) / 2;
                        int cz = (min.getBlockZ() + max.getBlockZ()) / 2;
                        int rx = Math.max(Math.abs(max.getBlockX() - cx), Math.abs(min.getBlockX() - cx));
                        int rz = Math.max(Math.abs(max.getBlockZ() - cz), Math.abs(min.getBlockZ() - cz));
                        // IMPORTANT: do NOT keep Y from pet position. Use private marker Y when available
                        // so the scan covers crops even after long idle/drift.
                        double cy = pet.getLocation().getY();
                        try {
                            org.bukkit.NamespacedKey kMarker = new org.bukkit.NamespacedKey(plugin.plugin(), "private_marker");
                            String marker = pdc.get(kMarker, org.bukkit.persistence.PersistentDataType.STRING);
                            if (marker != null && !marker.isBlank()){
                                String[] parts = marker.split(":");
                                if (parts.length == 4){
                                    cy = Double.parseDouble(parts[2]) + 0.5;
                                }
                            } else {
                                cy = Math.max(min.getBlockY()+1, Math.min(max.getBlockY(), pet.getLocation().getY()));
                            }
                        } catch (Throwable ignored2) {}
                        searchCenter = new Location(pet.getWorld(), cx + 0.5, cy, cz + 0.5);
                        searchRange = Math.max(2, Math.max(rx, rz));

                        // если фермер вышел за границы — телепортируем внутрь
                        int px = pet.getLocation().getBlockX();
                        int py = pet.getLocation().getBlockY();
                        int pz = pet.getLocation().getBlockZ();
                        if (px < min.getBlockX() || px > max.getBlockX() || pz < min.getBlockZ() || pz > max.getBlockZ() || py < min.getBlockY() || py > max.getBlockY()){
                            // IMPORTANT: always return to marker Y (center), not current pet Y.
                            double safeY = cy;
                            safeY = Math.max(min.getBlockY() + 1, Math.min(max.getBlockY(), safeY));
                            Location safe = new Location(pet.getWorld(), cx + 0.5, safeY, cz + 0.5);
                            org.bukkit.NamespacedKey k = new org.bukkit.NamespacedKey(plugin.plugin(), "tp_whitelist");
                            pdc.set(k, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);
                            pet.teleport(safe);
                            pdc.remove(k);
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        searchRange = Math.max(2, searchRange);

        boolean cfgEscape = plugin.getConfig().getBoolean("water.escape_enabled", false);
        final int cooldownTicks = Math.max(0, plugin.getConfig().getInt("water.breach_cooldown_ticks", 10));
        final double upVy = Math.max(0.35, plugin.getConfig().getDouble("water.escape_up_velocity", 0.5));
        final double clearance = Math.max(0.8, plugin.getConfig().getDouble("water.surface_clearance", 1.2));
        boolean eyeLiquid = false;
        try { eyeLiquid = pet.getEyeLocation().getBlock().isLiquid(); } catch (Throwable ignored){}
        long wtick = 0; try { wtick = pet.getWorld().getFullTime(); } catch (Throwable ignored){}
        final java.util.UUID wi = pet.getUniqueId();

        try {
            boolean smoothEnabled = plugin.getConfig().getBoolean("movement.smoothing.enabled", true);
            if (smoothEnabled){
                com.fermerpets.motion.MotionController mc = motionCtl.computeIfAbsent(wi, k -> new com.fermerpets.motion.MotionController(pet));
                org.bukkit.Location tloc_mc = com.fermerpets.TargetTracker.get(wi);
                if (tloc_mc != null){
                    org.bukkit.util.Vector to = tloc_mc.toVector().subtract(pet.getLocation().toVector());
                    boolean onGround = false; try { onGround = pet.isOnGround(); } catch (Throwable ignored){}
                    boolean inLiquid = false; try { inLiquid = pet.getEyeLocation().getBlock().isLiquid(); } catch (Throwable ignored){}
                    boolean nearObstacle = false;
                    com.fermerpets.motion.ModePlanner.Plan plan = com.fermerpets.motion.ModePlanner.plan(to.lengthSquared(), nearObstacle, inLiquid);
                    org.bukkit.util.Vector vel = mc.stepTowards(to, plan.baseSpeed, onGround, inLiquid, nearObstacle, 1.0);
                    pet.setVelocity(vel);
                }
            }
        } catch (Throwable ignored){}

        if (cfgEscape && eyeLiquid){
            Location cur = pet.getLocation();
            int surfY = findWaterSurfaceY(cur, 10);
            Location tloc = com.fermerpets.TargetTracker.get(pet.getUniqueId());
            double wantY = (tloc != null ? tloc.getY() : cur.getY()+2);
            if (wantY > surfY + 0.5){
                pet.setGravity(false);
                Vector v = pet.getVelocity();
                if (v.getY() < upVy) v.setY(upVy);
                pet.setVelocity(v);
                if (cur.getY() >= surfY + clearance && !cur.getBlock().isLiquid()){
                    waterBreachAt.put(wi, wtick);
                }
            }
        }
        if (cfgEscape){
            if (eyeLiquid){
                waterEscapeOn.put(wi, Boolean.TRUE);
            } else {
                Long br = waterBreachAt.get(wi);
                if (br != null && wtick - br < cooldownTicks) waterEscapeOn.put(wi, Boolean.TRUE);
                else waterEscapeOn.remove(wi);
            }
        } else {
            waterEscapeOn.remove(wi);
        }

        Debug.logPet(pet, "HarvestAI.java: tick start");
        if (pet == null) return false;
        if (!plugin.getConfig().getBoolean("harvest.enabled", true)) return false;

        // Farmers are bound to the private-block region, NOT to the player location.
        // Owner may be offline; in that case we still work using stored PDC owner+index.
        final UUID ownerId = readOwnerId(plugin, pet);
        final int farmerIndex = readFarmerIndex(plugin, pet, ownerId);

        // ---- Amethyst upkeep: every 30 minutes take 5 amethysts from owner's menu fuel.
        // If there are not enough amethysts, the farmer is unsummoned.
        try {
            int minutes = 30;
            int upkeep = 5;
            try {
                // Optional overrides in fermermenu.yml: cost.upkeep_minutes / cost.upkeep_amount
                java.io.File fm = new java.io.File(plugin.getDataFolder(), "fermermenu.yml");
                if (fm.exists()){
                    org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(fm);
                    minutes = Math.max(1, y.getInt("cost.upkeep_minutes", minutes));
                    upkeep = Math.max(0, y.getInt("cost.upkeep_amount", upkeep));
                }
            } catch (Throwable ignored) {}
            long periodMs = minutes * 60L * 1000L;
            long now = System.currentTimeMillis();
            long last = lastFeedAtMs.getOrDefault(pet.getUniqueId(), 0L);
            if (upkeep > 0 && ownerId != null && (last == 0L || now - last >= periodMs)){
                com.fermerpets.MenuFuelStore fuel = new com.fermerpets.MenuFuelStore(plugin);
                if (fuel.take(ownerId, upkeep)){
                    lastFeedAtMs.put(pet.getUniqueId(), now);
                } else {
                    // No food: despawn & cleanup mapping
                    try { plugin.getManager().unsummonAndCleanup(ownerId, farmerIndex); } catch (Throwable ignored2) {
                        try { pet.remove(); } catch (Throwable ignored3) {}
                    }
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        final UUID pid = pet.getUniqueId();
        int harvestDelayTicks = plugin.getConfig().getInt("harvest.harvest-delay-ticks", 10);
        long delayMs = harvestDelayTicks * 50L + 0;
        long nowMs = System.currentTimeMillis();
        long lastMs = lastScanAtMs.getOrDefault(pid, 0L);
            // Bypass throttle if we already have a nearby active target
            org.bukkit.Location prev = com.fermerpets.TargetTracker.get(pid);
            if (prev != null && prev.getWorld() == pet.getWorld() && prev.distanceSquared(pet.getLocation()) <= 64) {
                // don't early-return; proceed to process the target immediately
            } else if (nowMs - lastMs < delayMs){
            com.fermerpets.FlightLanding.manage(pet, com.fermerpets.TargetTracker.get(pet.getUniqueId()));
            Debug.logPet(pet, "HarvestAI.java: skip due to throttle");
            return true;
        }
        lastScanAtMs.put(pid, nowMs);

        Target target = findTarget(pet, searchCenter, searchRange, currentFilter);
        
        // --- Idle orbit logic: when no harvest target, orbit around the REGION CENTER (not the player) ---
        if (target == null) {
            try {
                org.bukkit.Location center = searchCenter.clone();
                long phaseSeed = pet.getUniqueId().getLeastSignificantBits() ^ pet.getUniqueId().getMostSignificantBits();
                double phase = (phaseSeed % 360) * Math.PI / 180.0;
                long tickNow = pet.getWorld().getFullTime();
                double t = (tickNow % 120) / 120.0 * (Math.PI * 2.0) + phase; // full circle every ~6s
                double baseR = 1.7; // desired standoff distance from owner
                double distToCenter = pet.getLocation().distance(center);
                double r = distToCenter < 1.2 ? baseR * 1.4 : baseR;
                double ox = Math.cos(t) * r;
                double oz = Math.sin(t) * r;
                org.bukkit.Location idle = center.clone().add(ox, 0.0, oz);
                // keep pets around the private marker Y (center), so they don't "inherit" a wrong height after drift.
                idle.setY(center.getY());
                com.fermerpets.TargetTracker.setActive(pet.getUniqueId(), idle);
            } catch (Throwable ignored) {}
        }
if (target != null) { com.fermerpets.TargetTracker.setActive(pet.getUniqueId(), target.location); } else { /* keep idle/orbit target set above */ }
        if (target != null) activeTargets.put(pid,target); else activeTargets.remove(pid);
        if (target == null){
            Debug.logPet(pet, "HarvestAI.java: no target in range; idle move");
            org.bukkit.Location idle = com.fermerpets.TargetTracker.get(pet.getUniqueId());
            if (idle != null){
                fp_waterAssist(pet);
                fp_fixLookTo(pet.getLocation(), idle, pet);
                moveTowards(pet, idle, false);
                com.fermerpets.FlightLanding.manage(pet, idle);
            }
            return true;
        } else { Debug.logPet(pet, "HarvestAI.java: target="+target.type+" at "+locStr(target.location)); }

        boolean flightParticles = plugin.getConfig().getBoolean("harvest.flight-explosion-particles", true);
        boolean useEnderFx = plugin.getConfig().getBoolean("harvest.use-enderman-effect", true);

        fp_waterAssist(pet);
        if (fp_isFrontOneBlockStep(pet)) { fp_doStepUpJump(pet); }
        fp_fixLookTo(pet.getLocation(), target.location, pet);
        moveTowards(pet, target.location, flightParticles);
        com.fermerpets.FlightLanding.manage(pet, target.location);

        if (pet.getLocation().distanceSquared(target.location) > 2.25) {
            long nowTicks = pet.getWorld().getFullTime();
            Long started = flyStart.get(pet.getUniqueId());
            boolean timeExceeded = started != null && (nowTicks - started) > MAX_FLY_TICKS;
            double dx = (target!=null? target.location.getX() : pet.getLocation().getX()) - pet.getLocation().getX();
            double dz = (target!=null? target.location.getZ() : pet.getLocation().getZ()) - pet.getLocation().getZ();
            double horiz = Math.hypot(dx, dz);
            if (!flyLanding.contains(pet.getUniqueId()) && (horiz < 0.9 || timeExceeded)) {
                flyLanding.add(pet.getUniqueId());
                pet.setGravity(true);
                org.bukkit.util.Vector v = pet.getVelocity();
                if (v.getY() > -0.9) v.setY(-0.9);
                pet.setVelocity(v);
                com.fermerpets.Debug.logPet(pet, "LANDING: start " + (timeExceeded?"timeout":"overpad") + " vY=" + v.getY());
            }
            org.bukkit.Location below = pet.getLocation().clone().add(0, -0.6, 0);
            boolean solidBelow = below.getBlock().getType().isSolid();
            if (flyLanding.contains(pet.getUniqueId()) && solidBelow) {
                pet.setGravity(true);
                flyStart.remove(pet.getUniqueId());
                flyLanding.remove(pet.getUniqueId());
                com.fermerpets.Debug.logPet(pet, "LANDED on block " + below.getBlock().getType());
            }

            com.fermerpets.Debug.logPet(pet, "HarvestAI.java: approach phase; dist2="+pet.getLocation().distanceSquared(target.location));
            return true;
        }

        boolean harvested = false;
        if (target.type == TargetType.ITEM) {
            Item it = target.itemRef;
            if (it != null && it.isValid()) {
                Debug.logPet(pet, "HarvestAI.java: pickup item "+it.getItemStack()); pushLoot(pet.getUniqueId(), it.getItemStack().clone());
                rememberHarvestSpot(pet, it.getLocation());
                it.remove();
                harvested = true;
            }
        } else if (target.type == TargetType.BLOCK) {
            Block b = target.blockRef;
            if (b != null) {
                Debug.logPet(pet, "HarvestAI.java: harvest block "+b.getType()); harvested = harvestBlock(pet, b);
            }
        }

        if (!harvested) return true;

        if (plugin.getConfig().getBoolean("harvest.teleport-deliver", true)) {
            // If a hopper is set – deliver there even if owner is offline.
            // If no hopper – deliver to online owner inventory; if owner offline, drop near the private beacon.
            Player onlineOwner = owner;
            if (onlineOwner == null && ownerId != null) {
                try { onlineOwner = org.bukkit.Bukkit.getPlayer(ownerId); } catch (Throwable ignored) {}
            }
            Debug.logPet(pet, "HarvestAI.java: performDelivery idx="+farmerIndex+" owner="+(ownerId==null?"null":ownerId));
            performDeliveryBound(pet, ownerId, farmerIndex, onlineOwner, useEnderFx);
        }
        return true;
    }

    private static UUID readOwnerId(FermerPetsModule plugin, LivingEntity pet){
        try {
            org.bukkit.persistence.PersistentDataContainer pdc = pet.getPersistentDataContainer();
            org.bukkit.NamespacedKey ok = new org.bukkit.NamespacedKey(plugin.plugin(), "mps_owner");
            String s = pdc.get(ok, org.bukkit.persistence.PersistentDataType.STRING);
            if (s == null || s.isBlank()) return null;
            return java.util.UUID.fromString(s);
        } catch (Throwable ignored){
            return null;
        }
    }

    private static int readFarmerIndex(FermerPetsModule plugin, LivingEntity pet, UUID ownerId){
        try {
            org.bukkit.persistence.PersistentDataContainer pdc = pet.getPersistentDataContainer();
            org.bukkit.NamespacedKey ik = new org.bukkit.NamespacedKey(plugin.plugin(), "farmer_index");
            Integer idx = pdc.get(ik, org.bukkit.persistence.PersistentDataType.INTEGER);
            if (idx != null && idx >= 1 && idx <= 3) return idx;
        } catch (Throwable ignored) {}
        if (ownerId != null) {
            try { return resolveFarmerIndex(plugin, ownerId, pet); } catch (Throwable ignored) {}
        }
        return 1;
    }

    private static void rememberHarvestSpot(LivingEntity pet, Location loc){
        if (loc == null) loc = pet.getLocation();
        lastHarvestLocation.put(pet.getUniqueId(), loc.clone()); Debug.logPet(pet, "HarvestAI.java: rememberHarvestSpot "+locStr(loc));
    }

    private enum TargetType { ITEM, BLOCK }
    private static String locStr(Location l){ return (l==null||l.getWorld()==null)?"null":(l.getWorld().getName()+String.format("(%.2f,%.2f,%.2f)", l.getX(), l.getY(), l.getZ())); }
    private record Target(TargetType type, Location location, Item itemRef, Block blockRef){}

    private static final java.util.Map<java.util.UUID, Target> activeTargets = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean hasActiveTarget(java.util.UUID pid){
        return activeTargets.containsKey(pid);
    }

    private static Target findTarget(LivingEntity pet, Location searchCenter, int range, String currentFilter){
        World w = pet.getWorld();
        double bestDist = Double.MAX_VALUE;
        Target best = null;

        // Если у пета есть привязка к региону — ограничиваем поиск границами региона
        int minX = Integer.MIN_VALUE, maxX = Integer.MAX_VALUE;
        int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;
        int minZ = Integer.MIN_VALUE, maxZ = Integer.MAX_VALUE;
        try {
            org.bukkit.persistence.PersistentDataContainer pdc = pet.getPersistentDataContainer();
            org.bukkit.NamespacedKey kRegion = new org.bukkit.NamespacedKey(FermerPetsModule.get().plugin(), "private_region");
            String regionId = pdc.get(kRegion, org.bukkit.persistence.PersistentDataType.STRING);
            if (regionId != null && !regionId.isBlank()){
                com.sk89q.worldguard.protection.managers.RegionManager rm = com.sk89q.worldguard.WorldGuard.getInstance()
                        .getPlatform().getRegionContainer().get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(w));
                if (rm != null){
                    com.sk89q.worldguard.protection.regions.ProtectedRegion pr = rm.getRegion(regionId);
                    if (pr != null){
                        com.sk89q.worldedit.math.BlockVector3 min = pr.getMinimumPoint();
                        com.sk89q.worldedit.math.BlockVector3 max = pr.getMaximumPoint();
                        minX = min.getBlockX(); maxX = max.getBlockX();
                        minY = min.getBlockY(); maxY = max.getBlockY();
                        minZ = min.getBlockZ(); maxZ = max.getBlockZ();
                    }
                }
            }
        } catch (Throwable ignored) {}

        // ВАЖНО:
        // Раньше фермеры предпочитали цель-БЛОК, и только когда "блоков нет" начинали подбирать предметы.
        // Это приводило к накоплению лута на земле (особенно когда предметы дропаются рядом с приватным камнем
        // или воронкой). Поэтому предметы теперь имеют приоритет.

        // 1) Сначала ищем предметы от центра поиска
        for (Item it : w.getEntitiesByClass(Item.class)){
            if (!it.isValid()) continue;
            if (!isPlantLoot(it.getItemStack())) continue;
            if (!withinRange(searchCenter, it.getLocation(), range)) continue;

            int xx = it.getLocation().getBlockX();
            int yy = it.getLocation().getBlockY();
            int zz = it.getLocation().getBlockZ();
            if (xx < minX || xx > maxX || yy < minY || yy > maxY || zz < minZ || zz > maxZ) continue;

            double d2 = it.getLocation().distanceSquared(pet.getLocation());
            if (d2 < bestDist){ bestDist = d2; best = new Target(TargetType.ITEM, it.getLocation().clone(), it, null);}
        }
        // Если предмет найден — подбираем его сразу, не продолжая сбор блоков в этот тик.
        if (best != null && best.type == TargetType.ITEM) return best;

        // 2) Если предметов нет — ищем блоки культур
        int bx = searchCenter.getBlockX(), by = searchCenter.getBlockY(), bz = searchCenter.getBlockZ();
        for (int dx=-range; dx<=range; dx++){
            for (int dy=-range; dy<=range; dy++){
                for (int dz=-range; dz<=range; dz++){
                    Block b = w.getBlockAt(bx+dx,by+dy,bz+dz);
                    // границы региона привата
                    int xx = b.getX(), yy = b.getY(), zz = b.getZ();
                    if (xx < minX || xx > maxX || yy < minY || yy > maxY || zz < minZ || zz > maxZ) continue;
                    if (!CROPS.contains(b.getType().name())) continue;
                    if (!isMatureOrHarvestable(b)) continue;

                    Location l=b.getLocation().add(0.5,0,0.5);
                    double d2 = l.distanceSquared(pet.getLocation());
                    if (d2 < bestDist){ bestDist = d2; best = new Target(TargetType.BLOCK, l, null, b);}
                }
            }
        }

        return best;
    }

    private static boolean withinRange(Location a, Location b, int r){
        if (a.getWorld() != b.getWorld()) return false;
        return Math.abs(a.getBlockX()-b.getBlockX())<=r &&
                Math.abs(a.getBlockY()-b.getBlockY())<=r &&
                Math.abs(a.getBlockZ()-b.getBlockZ())<=r;
    }

    private static boolean isPlantLoot(ItemStack st){
        if (st == null) return false;
        String n = st.getType().name();
        return n.contains("SEEDS") || n.contains("WHEAT") || n.contains("CARROT") || n.contains("POTATO") ||
                n.contains("BEETROOT") || n.contains("NETHER_WART") || n.contains("BERRY") ||
                n.contains("PUMPKIN") || n.contains("MELON") || n.equals("SUGAR_CANE") ||
                n.contains("CACTUS") || n.contains("COCOA") || n.contains("MUSHROOM") ||
                n.contains("KELP") || n.contains("BAMBOO") || n.contains("GLOW_BERRIES") ||
                n.contains("PROPAGULE");
    }

    private static boolean isMatureOrHarvestable(Block b){
        Material m = b.getType();
        String name = m.name();
        try {
            switch (name){
                case "WHEAT","CARROTS","POTATOES","BEETROOTS","NETHER_WART","COCOA","SWEET_BERRY_BUSH" -> {
                    if (b.getBlockData() instanceof Ageable ag) {
                        return ag.getAge() >= ag.getMaximumAge();
                    }
                    return false;
                }
                case "SUGAR_CANE","CACTUS","BAMBOO","KELP","KELP_PLANT" -> {
                    return b.getRelative(BlockFace.UP).getType() == m || name.equals("KELP_PLANT");
                }
                case "CAVE_VINES","CAVE_VINES_PLANT" -> {
                    BlockData bd = b.getBlockData();
                    if (bd instanceof CaveVines cv) return cv.isBerries();
                    if (bd instanceof CaveVinesPlant cvp) return cvp.isBerries();
                    return false;
                }
                case "PUMPKIN","MELON","RED_MUSHROOM","BROWN_MUSHROOM" -> {
                    return true;
                }
                default -> { return false; }
            }
        } catch (Throwable t){ return false; }
    }

    private static void moveTowards(LivingEntity pet, Location target, boolean flightParticles){
        Location from = pet.getLocation();
        World w = pet.getWorld();
        Vector dir = target.toVector().subtract(from.toVector());
        double dist = dir.length();
        if (dist < 0.5) return;
        dir.normalize();

        Vector walkVel = dir.clone().multiply(Math.min(0.35, dist * 0.25)); Debug.logPet(pet, "HarvestAI.java: move WALK towards "+locStr(target));
        pet.setVelocity(new Vector(walkVel.getX(), pet.isOnGround()? pet.getVelocity().getY() : pet.getVelocity().getY(), walkVel.getZ()));

        try {
            Location eye = pet.getLocation().clone();
            Vector forward = eye.getDirection().setY(0).normalize();
            org.bukkit.block.Block front = eye.add(forward.multiply(0.6)).getBlock();
            if (front.getType().isSolid() && pet.isOnGround()) { Debug.logPet(pet, "HarvestAI.java: move SPIDER-HOP obstacle="+front.getType());
                pet.setVelocity(pet.getVelocity().setY(0.42));
            }
        } catch (Throwable ignored){}

        boolean needFlight = false;
        try {
            double dy = target.getY() - from.getY();
            if (Math.abs(dy) > 1.5) needFlight = true;
            Location mid = from.clone().add(dir.clone().multiply(Math.min(3, dist)));
            org.bukkit.block.Block below = mid.clone().add(0, -1, 0).getBlock();
            if (!below.getType().isSolid()) needFlight = true;
        } catch (Throwable ignored){}

        if (needFlight) { Debug.logPet(pet, "HarvestAI.java: move ALLAY-FLY (gap/vertical)");
            flyStart.put(pet.getUniqueId(), pet.getWorld().getFullTime());
            flyLanding.remove(pet.getUniqueId());
            try { pet.setGravity(false); } catch (Throwable ignored){}
            Vector fly = dir.clone().multiply(0.45);
            // Fly up OR down depending on target height.
            double dy = target.getY() - from.getY();
            double vy = dy * 0.20;
            if (vy > 0.35) vy = 0.35;
            if (vy < -0.35) vy = -0.35;
            // if target is roughly on same level, keep slight lift to avoid ground clipping
            if (Math.abs(dy) < 0.75) vy = 0.15;
            fly.setY(vy);
            pet.setVelocity(fly);
            if (flightParticles) {
                w.spawnParticle(Particle.EXPLOSION, pet.getLocation(), 6, 0.25, 0.25, 0.25, 0.02);
            }
            Bukkit.getScheduler().runTaskLater(FermerPetsModule.get().plugin(), () -> {
                try { pet.setGravity(true); } catch (Throwable ignored){}
            }, 10L);
        }
    }

    private static boolean harvestBlock(LivingEntity pet, Block b){
        String name = b.getType().name();
        World w = b.getWorld();
        Location spot = b.getLocation().add(0.5, 0, 0.5);
        boolean replanted = false;
        java.util.List<ItemStack> drops = new ArrayList<>();

        switch (name){
            case "WHEAT","CARROTS","POTATOES","BEETROOTS","NETHER_WART","COCOA" -> {
                if (!(b.getBlockData() instanceof Ageable ag) || ag.getAge() < ag.getMaximumAge()) return false;
                drops.addAll(b.getDrops());
                Ageable reset = (Ageable) Bukkit.createBlockData(b.getType());
                reset.setAge(0);
                b.setBlockData(reset, false);
                replanted = true;
                consumeOneSeedForReplant(name, drops);
            }
            case "SWEET_BERRY_BUSH" -> {
                int amount = 2 + new Random().nextInt(2);
                drops.add(new ItemStack(Material.SWEET_BERRIES, amount));
                if (b.getBlockData() instanceof Ageable ag2){
                    ag2.setAge(Math.min(1, ag2.getMaximumAge()));
                    b.setBlockData(ag2, false);
                }
            }
            case "CAVE_VINES","CAVE_VINES_PLANT" -> {
                int amount = 1 + new Random().nextInt(3);
                drops.add(new ItemStack(Material.GLOW_BERRIES, amount));
                BlockData bd = b.getBlockData();
                if (bd instanceof CaveVines cv){ cv.setBerries(false); b.setBlockData(cv, false); }
                if (bd instanceof CaveVinesPlant cvp){ cvp.setBerries(false); b.setBlockData(cvp, false); }
            }
            case "SUGAR_CANE","CACTUS","BAMBOO" -> {
                int broken = 0;
                Block cur = b.getRelative(BlockFace.UP);
                while (cur.getType().name().equals(name)) {
                    drops.addAll(cur.getDrops());
                    cur.setType(Material.AIR, false);
                    cur = cur.getRelative(BlockFace.UP);
                    broken++; if (broken > 64) break;
                }
                if (broken == 0) return false;
            }
            case "KELP","KELP_PLANT" -> {
                int broken = 0;
                Block cur = name.equals("KELP_PLANT") ? b : b.getRelative(BlockFace.UP);
                while (cur.getType().name().startsWith("KELP")) {
                    drops.addAll(cur.getDrops());
                    cur.setType(Material.AIR, false);
                    cur = cur.getRelative(BlockFace.UP);
                    broken++; if (broken > 64) break;
                }
                if (broken == 0) return false;
            }
            case "PUMPKIN","MELON","RED_MUSHROOM","BROWN_MUSHROOM" -> {
                drops.addAll(b.getDrops());
                b.setType(Material.AIR, false);
            }
            default -> {}
        }

        rememberHarvestSpot(pet, spot);
        for (ItemStack st : drops) pushLoot(pet.getUniqueId(), st);
        w.spawnParticle(Particle.HAPPY_VILLAGER, spot, 12, 0.4, 0.4, 0.4, 0.02);
        w.playSound(spot, Sound.BLOCK_CROP_BREAK, 0.7f, 1.2f);
        return true;
    }

    private static void consumeOneSeedForReplant(String name, java.util.List<ItemStack> drops){
        Material need = switch (name){
            case "WHEAT" -> Material.WHEAT_SEEDS;
            case "CARROTS" -> Material.CARROT;
            case "POTATOES" -> Material.POTATO;
            case "BEETROOTS" -> Material.BEETROOT_SEEDS;
            case "NETHER_WART" -> Material.NETHER_WART;
            case "COCOA" -> Material.COCOA_BEANS;
            default -> null;
        };
        if (need == null) return;
        for (ItemStack st : drops){
            if (st.getType() == need && st.getAmount() > 0){
                st.setAmount(st.getAmount()-1);
                break;
            }
        }
    }

    private static void pushLoot(UUID petId, ItemStack st){
        if (st == null || st.getType() == Material.AIR || st.getAmount() <= 0) return;
        lootBuffer.computeIfAbsent(petId, k -> new ArrayList<>()).add(st); Debug.logPet(Bukkit.getEntity(petId), "HarvestAI.java: pushLoot "+st);
    }

    private static void performDeliveryBound(LivingEntity pet, UUID ownerId, int farmerIndex, Player ownerOnline, boolean useEnderFx){
        org.bukkit.NamespacedKey k = new org.bukkit.NamespacedKey(FermerPetsModule.get().plugin(), "tp_whitelist");
        org.bukkit.persistence.PersistentDataContainer pdc = pet.getPersistentDataContainer();
        pdc.set(k, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);

        UUID petId = pet.getUniqueId();
        Location returnPoint = lastHarvestLocation.getOrDefault(petId, pet.getLocation().clone());

        Location deliveryTarget = null;
        try {
            FermerMenuService menuService = FermerMenus.get();
            if (menuService != null) {
                if (ownerId != null) deliveryTarget = menuService.getEffectiveHopperLocation(ownerId, farmerIndex);
            }
        } catch (Throwable ignored) {}

        // Extra safety: if farmerIndex mapping is wrong/missing (common after reloads),
        // but the hopper was bound to this exact petId, still deliver to it.
        if (deliveryTarget == null) {
            try {
                PlayersHoppersStore store = new PlayersHoppersStore(FermerPetsModule.get());
                PlayersHoppersStore.Record r = store.getHopperByPet(petId);
                if (r != null) deliveryTarget = r.loc;
            } catch (Throwable ignored) {}
        }

        if (deliveryTarget != null) {
            Debug.logPet(pet, "HarvestAI.java: DELIVER to HOPPER (no teleport) at "+locStr(deliveryTarget));

            java.util.List<ItemStack> buffer = lootBuffer.get(petId);
            if (buffer != null && !buffer.isEmpty()){
                org.bukkit.block.Block hopperBlock = deliveryTarget.getBlock();
                if (hopperBlock.getType() == org.bukkit.Material.HOPPER) {
                    org.bukkit.block.Hopper hopperInv = (org.bukkit.block.Hopper) hopperBlock.getState();
                    final Location dt = deliveryTarget;
                    for (ItemStack item : buffer) {
                        java.util.Map<Integer, ItemStack> leftover = hopperInv.getInventory().addItem(item);
                        if (!leftover.isEmpty()) {
                            // Toast only when owner is online
                            if (ownerOnline != null) {
                                try {
                                    org.bukkit.Bukkit.dispatchCommand(
                                            org.bukkit.Bukkit.getConsoleSender(),
                                            "cmi toast " + ownerOnline.getName() + " &6Воронка заполнена " + farmerIndex
                                    );
                                } catch (Throwable ignored) {}
                            }

            leftover.values().forEach(leftItem -> dt.getWorld().dropItemNaturally(dt, leftItem));
                        }
                    }
                }
                buffer.clear();
            }
        } else {
            
// No hopper: drop loot at the PRIVATE BLOCK marker if present.
// If marker missing, fall back to configured beacon (END_ROD), otherwise to returnPoint.
Location dropLoc = returnPoint;
try {
    // 1) private marker from pet PDC
    String marker = pdc.get(new org.bukkit.NamespacedKey(FermerPetsModule.get().plugin(), "private_marker"), org.bukkit.persistence.PersistentDataType.STRING);
    if (marker != null && !marker.isBlank()){
        String[] parts = marker.split(":");
        if (parts.length == 4){
            java.util.UUID wid = java.util.UUID.fromString(parts[0]);
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(wid);
            if (w != null){
                int mx = Integer.parseInt(parts[1]);
                int my = Integer.parseInt(parts[2]);
                int mz = Integer.parseInt(parts[3]);
                dropLoc = new Location(w, mx + 0.5, my + 1.0, mz + 0.5);
            }
        }
    }
} catch (Throwable ignored) {}

try {
    // 2) fallback to beacon if marker not found
    if (dropLoc == returnPoint){
        FermerMenuService menuService = FermerMenus.get();
        if (menuService != null && ownerId != null){
            Location beaconLoc = menuService.getEffectiveBeaconLocation(ownerId, farmerIndex);
            if (beaconLoc != null) dropLoc = beaconLoc.clone().add(0.5, 1.0, 0.5);
        }
    }
} catch (Throwable ignored) {}

java.util.List<ItemStack> buffer = lootBuffer.get(petId);
if (buffer != null && !buffer.isEmpty()){
    final Location dl = dropLoc.clone();
    final org.bukkit.World w = dl.getWorld();
    if (w != null) buffer.forEach(item -> w.dropItemNaturally(dl, item));
    buffer.clear();
}
        }

        // Choose where to return: prefer beacon/staff if configured
        Location backPoint = returnPoint;
        try {
            com.fermerpets.FermerMenuService menuService = com.fermerpets.FermerMenus.get();
            if (menuService != null) {
                Location beaconLoc = (ownerId != null ? menuService.getEffectiveBeaconLocation(ownerId, farmerIndex) : null);
                if (beaconLoc != null) backPoint = beaconLoc.clone().add(0, 1, 0);
            }
        } catch (Throwable ignored) {}
        pdc.remove(k);
    }

    private static int resolveFarmerIndex(FermerPetsModule plugin, UUID owner, LivingEntity pet){
        try {
            org.bukkit.configuration.file.FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
            PlayersStore ps = new PlayersStore(playersCfg);
            java.util.List<java.util.UUID> pets = ps.getPets(owner);
            if (pets != null){
                for (int i = 0; i < pets.size(); i++){
                    if (pets.get(i).equals(pet.getUniqueId())){
                        return i + 1;
                    }
                }
            }
        } catch(Throwable ignored){}
        return 1;
    }

    private static void playEnderFx(Location loc){
        World w = loc.getWorld();
        if (w == null) return;
        w.spawnParticle(Particle.PORTAL, loc, 60, 0.5, 0.8, 0.5, 0.1);
        w.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    private static int findWaterSurfaceY(Location from, int maxUp){
        try{
            World w = from.getWorld();
            if (w == null) return from.getBlockY();
            int y = from.getBlockY();
            int top = Math.min(w.getMaxHeight()-2, y + Math.max(6, maxUp));
            for (int yy = y; yy <= top; yy++){
                Block b = w.getBlockAt(from.getBlockX(), yy, from.getBlockZ());
                if (!b.isLiquid()) return yy - 1;
            }
            return y;
        } catch(Throwable ignored){ return from.getBlockY(); }
    }

    public static boolean isWaterEscapeOn(java.util.UUID id){
        return Boolean.TRUE.equals(waterEscapeOn.get(id));
    }


    public static void clearAll(){
        try { lastScanAtMs.clear(); } catch(Throwable ignored){}
        try { lastHarvestLocation.clear(); } catch(Throwable ignored){}
        try { lootBuffer.clear(); } catch(Throwable ignored){}
        try { waterEscapeOn.clear(); } catch(Throwable ignored){}
        try { waterBreachAt.clear(); } catch(Throwable ignored){}
        try { flyLanding.clear(); } catch(Throwable ignored){}
        try { flyStart.clear(); } catch(Throwable ignored){}
        try { activeTargets.clear(); } catch(Throwable ignored){}
    }
    
}