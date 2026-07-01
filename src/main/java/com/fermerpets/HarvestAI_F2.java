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
import com.fermerpets.Debug;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HarvestAI_F2 {

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
    private static final java.util.Set<java.util.UUID> flyLanding = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<UUID, Boolean>());
    private static final int MAX_FLY_TICKS = 12;
    private HarvestAI_F2(){}

    private static final Map<UUID, Long> lastScanAtMs = new ConcurrentHashMap<>();
    // Upkeep timer per pet: used to take amethysts every 30 minutes.
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
        if (f.equals(m)) return true;
        if (f.equals("SWEET_BERRIES") && m.equals("SWEET_BERRY_BUSH")) return true;
        if ((f.equals("CARROT") || f.equals("CARROTS")) && m.equals("CARROTS")) return true;
        if ((f.equals("POTATO") || f.equals("POTATOES")) && m.equals("POTATOES")) return true;
        if ((f.equals("BEETROOT") || f.equals("BEETROOTS")) && m.equals("BEETROOTS")) return true;
        if (f.equals("NETHER_WART") && m.equals("NETHER_WART")) return true;
        if (f.equals("SUGAR_CANE") && m.equals("SUGAR_CANE")) return true;
        if (f.equals("COCOA_BEANS") && m.equals("COCOA")) return true;
        if ((f.equals("MELON") || f.equals("MELON_SLICE")) && m.equals("MELON")) return true;
        if (f.equals("PUMPKIN") && m.equals("PUMPKIN")) return true;
        if (f.equals("CACTUS") && m.equals("CACTUS")) return true;
        if (f.equals("RED_MUSHROOM") && m.equals("RED_MUSHROOM")) return true;
        if (f.equals("BROWN_MUSHROOM") && m.equals("BROWN_MUSHROOM")) return true;
        if (f.equals("BAMBOO") && m.equals("BAMBOO")) return true;
        if (f.equals("KELP") && (m.equals("KELP") || m.equals("KELP_PLANT"))) return true;
        if (f.equals("GLOW_BERRIES") && (m.equals("CAVE_VINES") || m.equals("CAVE_VINES_PLANT"))) return true;
        if (f.equals("WHEAT") && m.equals("WHEAT")) return true;
        return false;
    }

    public static void clear(UUID petId){
        lastScanAtMs.remove(petId);
        lastHarvestLocation.remove(petId);
        lootBuffer.remove(petId);
        lastFeedAtMs.remove(petId);
    }

    public static boolean tick(FermerPetsModule plugin, LivingEntity pet, Player owner){
        String currentFilter = null;
        try { currentFilter = new com.fermerpets.PlayersHoppersStore(plugin).getFilterByPet(pet.getUniqueId()); } catch (Throwable ignored) {}

        Debug.logPet(pet, "HarvestAI_F2.java: tick start");
        if (pet == null || owner == null) return false;
        if (!plugin.getConfig().getBoolean("harvest.enabled", true)) return false;
        if (pet.getWorld() != owner.getWorld()) return false;

        // ---- Amethyst upkeep: every 30 minutes take 5 amethysts from owner's menu fuel.
        // Each farmer runs independently (so 3 farmers = 3x upkeep).
        try {
            int minutes = 30;
            int upkeep = 5;
            try {
                java.io.File fm = new java.io.File(plugin.getDataFolder(), "fermermenu.yml");
                if (fm.exists()){
                    org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(fm);
                    minutes = Math.max(1, y.getInt("cost.upkeep_minutes", minutes));
                    upkeep = Math.max(0, y.getInt("cost.upkeep_amount", upkeep));
                }
            } catch (Throwable ignored) {}
            if (upkeep > 0){
                long periodMs = minutes * 60L * 1000L;
                long now = System.currentTimeMillis();
                long last = lastFeedAtMs.getOrDefault(pet.getUniqueId(), 0L);
                // First tick after summon: initialize timer without charging immediately.
                if (last == 0L){
                    lastFeedAtMs.put(pet.getUniqueId(), now);
                } else if (now - last >= periodMs){
                    com.fermerpets.MenuFuelStore fuel = new com.fermerpets.MenuFuelStore(plugin);
                    if (fuel.take(owner.getUniqueId(), upkeep)){
                        lastFeedAtMs.put(pet.getUniqueId(), now);
                    } else {
                        // No fuel: despawn this farmer.
                        try { plugin.getManager().unsummonAndCleanup(owner.getUniqueId(), 2); } catch (Throwable ignored2) {
                            try { pet.remove(); } catch (Throwable ignored3) {}
                        }
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        final UUID pid = pet.getUniqueId();
        int harvestDelayTicks = plugin.getConfig().getInt("harvest.harvest-delay-ticks", 10);
        long delayMs = harvestDelayTicks * 50L + 10;
        long nowMs = System.currentTimeMillis();
        long lastMs = lastScanAtMs.getOrDefault(pid, 0L);
        if (nowMs - lastMs < delayMs){ com.fermerpets.FlightLanding.manage(pet, com.fermerpets.TargetTracker.get(pet.getUniqueId()));
            Debug.logPet(pet, "HarvestAI_F2.java: skip due to throttle"); return true; }
        lastScanAtMs.put(pid, nowMs);

        int range = plugin.getConfig().getInt("pet.target-distance",
                plugin.getConfig().getInt("unifiedAI.range", 30));
        range = Math.max(2, range);

        Target target = findTarget(pet, range, currentFilter);
        
        // --- Idle follow/orbit logic: when no harvest target, orbit around the owner at a small radius ---
        if (target == null && owner != null) {
            try {
                org.bukkit.Location ownerLoc = owner.getLocation();
                long phaseSeed = pet.getUniqueId().getLeastSignificantBits() ^ pet.getUniqueId().getMostSignificantBits();
                double phase = (phaseSeed % 360) * Math.PI / 180.0;
                long tickNow = pet.getWorld().getFullTime();
                double t = (tickNow % 120) / 120.0 * (Math.PI * 2.0) + phase; // full circle every ~6s
                double baseR = 1.7; // desired standoff distance from owner
                double distToOwner = pet.getLocation().distance(ownerLoc);
                double r = distToOwner < 1.2 ? baseR * 1.4 : baseR;
                double ox = Math.cos(t) * r;
                double oz = Math.sin(t) * r;
                org.bukkit.Location idle = ownerLoc.clone().add(ox, 0.0, oz);
                // keep stable height near the owner/marker instead of inheriting a drifted Y
                idle.setY(ownerLoc.getY());
                com.fermerpets.TargetTracker.setActive(pet.getUniqueId(), idle);
            } catch (Throwable ignored) {}
        }
if (target != null) { com.fermerpets.TargetTracker.setActive(pet.getUniqueId(), target.location); } else { com.fermerpets.TargetTracker.clear(pet.getUniqueId()); }
        if (target != null) activeTargets.put(pid,target); else activeTargets.remove(pid);
        if (target == null){ Debug.logPet(pet, "HarvestAI_F2.java: no target in range"); return false; } else { Debug.logPet(pet, "HarvestAI_F2.java: target="+target.type+" at "+locStr(target.location)); }

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

            com.fermerpets.Debug.logPet(pet, "HarvestAI_F2.java: approach phase; dist2="+pet.getLocation().distanceSquared(target.location));
            return true;
        }

        boolean harvested = false;
        if (target.type == TargetType.ITEM) {
            Item it = target.itemRef;
            if (it != null && it.isValid()) {
                Debug.logPet(pet, "HarvestAI_F2.java: pickup item "+it.getItemStack()); pushLoot(pet.getUniqueId(), it.getItemStack().clone());
                rememberHarvestSpot(pet, it.getLocation());
                it.remove();
                harvested = true;
            }
        } else if (target.type == TargetType.BLOCK) {
            Block b = target.blockRef;
            if (b != null) {
                Debug.logPet(pet, "HarvestAI_F2.java: harvest block "+b.getType()); harvested = harvestBlock(pet, b);
            }
        }

        if (!harvested) return true;

        if (plugin.getConfig().getBoolean("harvest.teleport-deliver", true)) {
            Debug.logPet(pet, "HarvestAI_F2.java: performDelivery to owner "+owner.getName()); performDelivery(pet, owner, useEnderFx);
        }
        return true;
    }

    private static void rememberHarvestSpot(LivingEntity pet, Location loc){
        if (loc == null) loc = pet.getLocation();
        lastHarvestLocation.put(pet.getUniqueId(), loc.clone()); Debug.logPet(pet, "HarvestAI_F2.java: rememberHarvestSpot "+locStr(loc));
    }

    private enum TargetType { ITEM, BLOCK }
    private static String locStr(Location l){ return (l==null||l.getWorld()==null)?"null":(l.getWorld().getName()+String.format("(%.2f,%.2f,%.2f)", l.getX(), l.getY(), l.getZ())); }
    private record Target(TargetType type, Location location, Item itemRef, Block blockRef){}


    private static final java.util.Map<java.util.UUID, Target> activeTargets = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean hasActiveTarget(java.util.UUID pid){
        return activeTargets.containsKey(pid);
    }

    private static Target findTarget(LivingEntity pet, int range, String currentFilter){
        World w = pet.getWorld();
        Location base = pet.getLocation();
        double bestDist = Double.MAX_VALUE;
        Target best = null;

        for (Item it : w.getEntitiesByClass(Item.class)){
            if (!it.isValid()) continue;
            if (!isPlantLoot(it.getItemStack())) continue;
            if (!withinRange(base, it.getLocation(), range)) continue;
            double d2 = it.getLocation().distanceSquared(base);
            if (d2 < bestDist){ bestDist = d2; best = new Target(TargetType.ITEM, it.getLocation().clone(), it, null);}
        }

        // Если нашли предмет — подбираем его сразу, чтобы лут не скапливался на земле.
        if (best != null && best.type == TargetType.ITEM) {
            return best;
        }

        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        for (int dx=-range; dx<=range; dx++){
            for (int dy=-range; dy<=range; dy++){
                for (int dz=-range; dz<=range; dz++){
                    Block b = w.getBlockAt(bx+dx,by+dy,bz+dz);
                    if (!CROPS.contains(b.getType().name())) continue;
                    if (!isMatureOrHarvestable(b)) continue;
                    if (currentFilter != null && !matchesFilter(currentFilter, b.getType())) continue;
                    Location l=b.getLocation().add(0.5,0,0.5);
                    double d2 = l.distanceSquared(base);
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

        Vector walkVel = dir.clone().multiply(Math.min(0.35, dist * 0.25)); Debug.logPet(pet, "HarvestAI_F2.java: move WALK towards "+locStr(target));
        pet.setVelocity(new Vector(walkVel.getX(), pet.isOnGround()? pet.getVelocity().getY() : pet.getVelocity().getY(), walkVel.getZ()));

        try {
            Location eye = pet.getLocation().clone();
            Vector forward = eye.getDirection().setY(0).normalize();
            org.bukkit.block.Block front = eye.add(forward.multiply(0.6)).getBlock();
            if (front.getType().isSolid() && pet.isOnGround()) { Debug.logPet(pet, "HarvestAI_F2.java: move SPIDER-HOP obstacle="+front.getType());
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

        if (needFlight) { Debug.logPet(pet, "HarvestAI_F2.java: move ALLAY-FLY (gap/vertical)");
            try { pet.setGravity(false); } catch (Throwable ignored){}
            Vector fly = dir.clone().multiply(0.45);
            double dy = target.getY() - from.getY();
            double vy = dy * 0.20;
            if (vy > 0.35) vy = 0.35;
            if (vy < -0.35) vy = -0.35;
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
                    broken++; if (broken > 32) break;
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
        lootBuffer.computeIfAbsent(petId, k -> new ArrayList<>()).add(st); Debug.logPet(Bukkit.getEntity(petId), "HarvestAI_F2.java: pushLoot "+st);
    }

    private static void performDelivery(LivingEntity pet, Player owner, boolean useEnderFx){
        org.bukkit.NamespacedKey k = new org.bukkit.NamespacedKey(FermerPetsModule.get().plugin(), "tp_whitelist");
        org.bukkit.persistence.PersistentDataContainer pdc = pet.getPersistentDataContainer();
        pdc.set(k, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);

        UUID petId = pet.getUniqueId();
        Location returnPoint = lastHarvestLocation.getOrDefault(petId, pet.getLocation().clone());

        if (useEnderFx) playEnderFx(pet.getLocation());
        Location ownerLoc = owner.getLocation().clone();
        Debug.logPet(pet, "HarvestAI_F2.java: TELEPORT to OWNER "+owner.getName()+" at "+locStr(ownerLoc)); pet.teleport(ownerLoc);
        if (useEnderFx) playEnderFx(ownerLoc);

        java.util.List<ItemStack> buffer = lootBuffer.get(petId);
        if (buffer != null && !buffer.isEmpty()){
            Map<Integer, ItemStack> leftovers = owner.getInventory().addItem(buffer.toArray(new ItemStack[0]));
            if (!leftovers.isEmpty()) leftovers.values().forEach(item -> owner.getWorld().dropItemNaturally(ownerLoc, item));
            buffer.clear();
        }

        if (useEnderFx) playEnderFx(pet.getLocation());
        Debug.logPet(pet, "HarvestAI_F2.java: TELEPORT back to LAST spot "+locStr(returnPoint)); pet.teleport(returnPoint);
        if (useEnderFx) playEnderFx(returnPoint);
        pdc.remove(k);
    }

    private static void playEnderFx(Location loc){
        World w = loc.getWorld();
        if (w == null) return;
        w.spawnParticle(Particle.PORTAL, loc, 60, 0.5, 0.8, 0.5, 0.1);
        w.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }
}