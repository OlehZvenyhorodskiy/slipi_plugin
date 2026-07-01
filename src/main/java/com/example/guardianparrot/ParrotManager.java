package com.example.guardianparrot;

import com.example.guardianparrot.integration.CitizensHook;


import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Parrot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import com.example.guardianparrot.GPPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import com.example.guardianparrot.projectile.ParrotSkull;
import com.example.guardianparrot.HoloLabelService;
import com.example.guardianparrot.HoloLabelService.PetStats;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ParrotManager {
    private final GPPlugin plugin;
    public final NamespacedKey eggKey;
    public final NamespacedKey guardKey;
    public final NamespacedKey ownerKey;
    public final NamespacedKey petIndexKey;
    // playersEGG store
    private java.io.File playersEggFile;
    private org.bukkit.configuration.file.FileConfiguration playersEggCfg;
    public final org.bukkit.NamespacedKey eggIdKey;

    // AquaPrivate binding (private marker + region + slot)
    public final NamespacedKey privateWorldKey;
    public final NamespacedKey privateXKey;
    public final NamespacedKey privateYKey;
    public final NamespacedKey privateZKey;
    public final NamespacedKey regionIdKey;
    public final NamespacedKey slotKey;
    public final NamespacedKey hpKey;
    /**
     * Stored on the armor-stand avatar so we can delete ONLY the matching visual.
     * Without this, removing one guardian could wipe visuals of other guardians nearby.
     */
    public final NamespacedKey avatarParrotIdKey;

    /**
     * FermerPets has a TeleportGuardListener that cancels teleports for pet entities unless a whitelist flag is set.
     * Guardians are also treated as pets on many servers, so we must temporarily whitelist our forced teleports
     * (return-to-anchor / combat teleport) to prevent silent cancellation.
     */
    private final NamespacedKey tpWhitelistKey;

    public NamespacedKey ownerKey(){ return ownerKey; }
    public boolean hasTask(UUID id){ return tasks.containsKey(id); }
    public void restartFor(UUID owner, UUID parrotId, Parrot parrot){ startTask(owner, parrotId); try { holo.attach(parrot, Bukkit.getPlayer(owner), resolvePetStats(Bukkit.getPlayer(owner), parrot)); } catch (Throwable ignored){} }
    public void reattachHolo(Parrot p){ Player pl = null; try { String os = p.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING); if (os!=null) pl = Bukkit.getPlayer(java.util.UUID.fromString(os)); } catch (Throwable ignored){} if (pl!=null) try { holo.attach(p, pl, resolvePetStats(pl, p)); } catch (Throwable ignored){} }

    /**
     * Used by chunk cleanup to avoid leaving stale entries in the holo map when a hologram
     * armor stand is removed directly (e.g., chunk unload).
     */
    public void cleanupOrphanHolo(UUID parrotId) {
        try { holo.detachById(parrotId); } catch (Throwable ignored) {}
    }

    public org.bukkit.NamespacedKey guardKey(){ return guardKey; }
    private final HoloLabelService holo;
    private final Map<UUID, ArmorStand> avatars = new ConcurrentHashMap<>();
    // IMPORTANT: Do not run per-guardian avatar sync tasks. They were a major TPS killer.
    // We rely on passenger attachment and occasional re-attach in the main GuardianTask tick.

    private File playersFile;
    private FileConfiguration playersCfg;

    // combat
    private File combatFile;
    private FileConfiguration combatCfg;
    private int strikeInterval, shootInterval, tpDistance, scanInterval, aggroRadius;

    // runtime
    private final Map<UUID, GuardianTask> tasks = new ConcurrentHashMap<>();

    // Debounce guard to coalesce multiple resummon triggers (TP/world change/plugin reload)
    private final java.util.Set<java.util.UUID> resummonGuard =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Set<UUID> eggCooldown = ConcurrentHashMap.newKeySet();
    private org.bukkit.scheduler.BukkitTask reaperTask;
    private org.bukkit.scheduler.BukkitTask holoRefreshTask;

    public ParrotManager(GPPlugin plugin){
        this.plugin = plugin;
        this.eggKey   = new NamespacedKey(plugin.host(), "guardianparrot_egg");
        this.guardKey = new NamespacedKey(plugin.host(), "guardianparrot_guard");
        this.ownerKey = new NamespacedKey(plugin.host(), "guardianparrot_owner");
        this.petIndexKey = new NamespacedKey(plugin.host(), "guardianparrot_index");
        this.eggIdKey = new NamespacedKey(plugin.host(), "egg_id");
        this.privateWorldKey = new NamespacedKey(plugin.host(), "ap_priv_world");
        this.privateXKey = new NamespacedKey(plugin.host(), "ap_priv_x");
        this.privateYKey = new NamespacedKey(plugin.host(), "ap_priv_y");
        this.privateZKey = new NamespacedKey(plugin.host(), "ap_priv_z");
        this.regionIdKey = new NamespacedKey(plugin.host(), "ap_region_id");
        this.slotKey = new NamespacedKey(plugin.host(), "ap_guard_slot");
        this.hpKey = new NamespacedKey(plugin.host(), "ap_guard_hp");
        this.avatarParrotIdKey = new NamespacedKey(plugin.host(), "ap_avatar_parrot_id");
        this.tpWhitelistKey = new NamespacedKey(plugin.host(), "tp_whitelist");
        this.holo = new HoloLabelService(plugin, this);
        reload();
        startReaper();
        startHoloRefresh();
        startReaper();
    }

    public void reload(){
        // Embedded-module compatibility: ensure guardianparrot.yml exists
        plugin.saveDefaultConfig();
        if (playersFile == null) playersFile = new File(plugin.getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            try { playersFile.getParentFile().mkdirs(); playersFile.createNewFile(); } catch (IOException ignored){}
        }
        playersCfg = YamlConfiguration.loadConfiguration(playersFile);
        // playersEGG.yml (spawn eggs) are no longer used.
        // Keep an in-memory config for compatibility but DO NOT create any files on disk.
        playersEggFile = null;
        playersEggCfg = new org.bukkit.configuration.file.YamlConfiguration();

// combat.yml
        combatFile = new File(plugin.getDataFolder(), "combat.yml");
        if (!combatFile.exists()) plugin.saveResource("combat.yml", false);
        combatCfg = YamlConfiguration.loadConfiguration(combatFile);
        strikeInterval = combatCfg.getInt("warden-strike-interval", 40);
        shootInterval  = combatCfg.getInt("wither-shoot-interval", 60);
        tpDistance     = combatCfg.getInt("teleport-distance", 30);
        scanInterval   = combatCfg.getInt("scan-interval", 5);
        aggroRadius    = combatCfg.getInt("aggro-radius", 20);
    }

    // players.yml helpers
    private int maxPets(){ return plugin.getConfig().getInt("max-pets-per-player", 1); }
    public List<String> getPets(UUID owner){ return playersCfg.getStringList("players."+owner+".pets"); }
    private void setPets(UUID owner, List<String> list){ playersCfg.set("players."+owner+".pets", list); savePlayers(); }
    public boolean hasAnyPet(Player p){ return !getPets(p.getUniqueId()).isEmpty(); }
    public void addPet(Player p, UUID id){ List<String> l = getPets(p.getUniqueId()); l.add(id.toString()); setPets(p.getUniqueId(), l); }
    public UUID popFirstPet(Player p){
        List<String> l = getPets(p.getUniqueId());
        if (l.isEmpty()) return null;
        UUID id = UUID.fromString(l.remove(0));
        setPets(p.getUniqueId(), l);
        return id;
    }
    public void savePlayers(){ try { playersCfg.save(playersFile); } catch (IOException e){ e.printStackTrace(); } }

    

    /* ===================== playersEGG: GLOBAL store ===================== */
    private java.util.List<String> getAllEggs(){
        if (playersEggCfg == null) return new java.util.ArrayList<>();
        java.util.List<String> eggs = playersEggCfg.getStringList("eggs");
        return eggs == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(eggs);
    }
    private void setAllEggs(java.util.List<String> eggs){
        if (playersEggCfg == null) return;
        playersEggCfg.set("eggs", (eggs==null||eggs.isEmpty()) ? null : eggs);
        try { if (playersEggFile != null) playersEggCfg.save(playersEggFile); } catch (java.io.IOException ignored){}
    }
    public boolean eggExists(String eggId){
        if (eggId == null || eggId.isEmpty()) return false;
        return getAllEggs().contains(eggId);
    }
    public void addEggRecord(String eggId){
        if (eggId == null || eggId.isEmpty()) return;
        java.util.List<String> eggs = getAllEggs();
        if (!eggs.contains(eggId)) eggs.add(eggId);
        setAllEggs(eggs);
    }
    public void removeEggRecord(String eggId){
        if (eggId == null || eggId.isEmpty()) return;
        java.util.List<String> eggs = getAllEggs();
        if (eggs.remove(eggId)) setAllEggs(eggs);
    }
    private void migratePlayersEggsIfNeeded(){
        if (playersEggCfg == null) return;
        org.bukkit.configuration.ConfigurationSection sec = playersEggCfg.getConfigurationSection("players");
        if (sec == null) return;
        java.util.Set<String> owners = sec.getKeys(false);
        java.util.Set<String> merged = new java.util.HashSet<>();
        for (String o : owners){
            java.util.List<String> eggs = playersEggCfg.getStringList("players."+o+".eggs");
            if (eggs != null) merged.addAll(eggs);
        }
        playersEggCfg.set("players", null);
        playersEggCfg.set("eggs", new java.util.ArrayList<>(merged));
        try { if (playersEggFile != null) playersEggCfg.save(playersEggFile); } catch (java.io.IOException ignored){}
    }

    /** Give an egg to player and register its egg_id in playersEGG.yml */
    public void giveEgg(org.bukkit.entity.Player owner){
        org.bukkit.inventory.ItemStack egg = buildEgg();
        org.bukkit.inventory.meta.ItemMeta m = egg.getItemMeta();
        if (m != null){
            String id = m.getPersistentDataContainer().get(eggIdKey, org.bukkit.persistence.PersistentDataType.STRING);
            if (id != null && !id.isEmpty()){
                addEggRecord(id);
            }
        }
        java.util.Map<Integer, org.bukkit.inventory.ItemStack> leftover = owner.getInventory().addItem(egg);
        if (leftover != null && !leftover.isEmpty()){
            org.bukkit.World w = owner.getWorld();
            org.bukkit.Location loc = owner.getLocation();
            for (org.bukkit.inventory.ItemStack rest : leftover.values()){
                if (rest == null) continue;
                w.dropItemNaturally(loc, rest);
            }
        }
        try { owner.updateInventory(); } catch (Throwable ignored){}
    }
// egg
    public ItemStack buildEgg(){ return buildEgg(-1); }

    ItemStack buildEgg(int index){
        ItemStack it = new ItemStack(Material.PARROT_SPAWN_EGG, 1);
        ItemMeta m = it.getItemMeta();
        // IMPORTANT: keep this file UTF-8. Some previous edits introduced mojibake in Russian strings.
        m.setDisplayName(ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("egg-name","&bЯйцо Хранителя")));
        List<String> lore = plugin.getConfig().getStringList("egg-lore").stream().map(s -> ChatColor.translateAlternateColorCodes('&', s)).toList();
        m.setLore(lore);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        var pdc = m.getPersistentDataContainer();
        pdc.set(eggKey, PersistentDataType.BYTE, (byte)1);
        // Assign unique egg_id for anti-duplication registry
        String eggId = java.util.UUID.randomUUID().toString();
        pdc.set(eggIdKey, PersistentDataType.STRING, eggId);
        if (index > 0) pdc.set(petIndexKey, PersistentDataType.INTEGER, index);
        it.setItemMeta(m);
        return it;
    }
    public boolean isGuardianEgg(ItemStack it){
        if (it == null || it.getType() != Material.PARROT_SPAWN_EGG) return false;
        ItemMeta m = it.getItemMeta(); if (m == null) return false;
        Byte b = m.getPersistentDataContainer().get(eggKey, PersistentDataType.BYTE);
        if (b != null && b == (byte)1) return true;
        String n = m.getDisplayName();
        return n != null && ChatColor.stripColor(n).contains("Яйцо Хранителя");
    }

    // duplication guard for eggs
    public boolean tryEnterEggCooldown(UUID playerId){
        if (eggCooldown.contains(playerId)) return false;
        eggCooldown.add(playerId);
        new BukkitRunnable(){ @Override public void run(){ eggCooldown.remove(playerId); } }.runTaskLater(plugin.host(), 2L);
        return true;
    }

    // summon/unsummon
    public HoloLabelService getHolo(){ return holo; }

    public Parrot summonParrot(Player owner, Location at){ return summonParrot(owner, at, false); }

    public Parrot summonParrot(Player owner, Location at, boolean bypassLimit){ return summonParrotWithIndex(owner, at, bypassLimit, -1); }

    public Parrot summonParrotWithIndex(Player owner, Location at, boolean bypassLimit, int forcedIndex){
        List<String> current = getPets(owner.getUniqueId());
        if (!bypassLimit && current.size() >= maxPets()){
            owner.sendMessage(ChatColor.RED+"Достигнут лимит питомцев ("+maxPets()+")");
            return null;
        }
        Parrot parrot = (Parrot) at.getWorld().spawnEntity(at, EntityType.PARROT);
        try { parrot.setInvisible(true); parrot.setSilent(true); parrot.setCustomNameVisible(false);
              parrot.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false, false)); } catch (Throwable ignored) {}

        String vx = com.example.guardianparrot.VaultUtil.getPrefix(owner);
        String disp = (vx == null || vx.isEmpty() ? "" : vx + " ") + "Хранитель (" + owner.getName() + ")";
        parrot.setCustomName(disp);
        parrot.setCustomNameVisible(true);
        parrot.getPersistentDataContainer().set(ownerKey, org.bukkit.persistence.PersistentDataType.STRING, owner.getUniqueId().toString());
        parrot.setInvulnerable(true);
        parrot.setPersistent(true);
        // === avatar: invisible tiny armor stand with owner's head and dark-green leather chestplate ===
        ArmorStand avatar = (ArmorStand) at.getWorld().spawnEntity(at, EntityType.ARMOR_STAND);
        try {
            avatar.setSmall(true); // half size
            avatar.setInvisible(true); // hide body, keep equipment visible
            avatar.setBasePlate(false);
            avatar.setArms(false);
            avatar.setGravity(false);
            avatar.setMarker(false); // keep equipment visible and allow as passenger
            // equip player head with owner's skin
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            try { sm.setOwningPlayer(owner); } catch (Throwable ignored) {}
            head.setItemMeta(sm);
            // dark green leather chestplate
            ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE);
            LeatherArmorMeta lam = (LeatherArmorMeta) chest.getItemMeta();
            lam.setColor(org.bukkit.Color.fromRGB(8, 64, 16));
            lam.addItemFlags(ItemFlag.HIDE_DYE);
            chest.setItemMeta(lam);
            // put equipment
            if (avatar.getEquipment() != null) {
                avatar.getEquipment().setHelmet(head);
                avatar.getEquipment().setChestplate(chest);
            }
            // mark avatar as guardian for cleanup
            avatar.setMetadata("guardianparrot", new FixedMetadataValue(plugin.host(), true));
            avatar.getPersistentDataContainer().set(guardKey, PersistentDataType.BYTE, (byte)1);
            avatar.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
            // bind the avatar to its parrot id so cleanup never removes other guardians' visuals
            avatar.getPersistentDataContainer().set(avatarParrotIdKey, PersistentDataType.STRING, parrot.getUniqueId().toString());
            // Track avatar and attach as passenger so it moves with the parrot.
            avatars.put(parrot.getUniqueId(), avatar);
            try { if (!parrot.getPassengers().contains(avatar)) parrot.addPassenger(avatar); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    

        // mark
        ItemMeta dummy = Bukkit.getItemFactory().getItemMeta(Material.STONE);
        PersistentDataContainer pdc = parrot.getPersistentDataContainer();
        pdc.set(guardKey, PersistentDataType.BYTE, (byte)1);
        pdc.set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        parrot.setMetadata("guardianparrot", new FixedMetadataValue(plugin.host(), true));

        addPet(owner, parrot.getUniqueId());
        int idx = (forcedIndex>0? forcedIndex : getPets(owner.getUniqueId()).indexOf(parrot.getUniqueId().toString())+1);
        setPetIndex(parrot, idx);
        // attach holo labels
        try { holo.attach(parrot, owner, resolvePetStats(owner, parrot)); } catch (Throwable t){ t.printStackTrace(); }
        startTask(owner.getUniqueId(), parrot.getUniqueId());
        return parrot;
    }

    /**
     * Summon a guardian bound to a specific private marker/region and fixed slot (1..3).
     * Returns pet UUID, or null if failed.
     */
    public java.util.UUID summonGuardian(Player owner, Location at, String regionId, Location privateMarker, int slot) {
        Parrot p = summonParrotWithIndex(owner, at, true, slot);
        if (p == null) return null;
        var pdc = p.getPersistentDataContainer();
        if (regionId != null) {
            pdc.set(regionIdKey, PersistentDataType.STRING, regionId);
        }
        pdc.set(slotKey, PersistentDataType.INTEGER, slot);
        if (privateMarker != null && privateMarker.getWorld() != null) {
            pdc.set(privateWorldKey, PersistentDataType.STRING, privateMarker.getWorld().getName());
            pdc.set(privateXKey, PersistentDataType.INTEGER, privateMarker.getBlockX());
            pdc.set(privateYKey, PersistentDataType.INTEGER, privateMarker.getBlockY());
            pdc.set(privateZKey, PersistentDataType.INTEGER, privateMarker.getBlockZ());
        }
        // Initialize custom HP for this guardian (independent per guardian)
        try { pdc.set(hpKey, PersistentDataType.INTEGER, 50); } catch (Throwable ignored) {}
        try {
            var maxHealthAttr = getMaxHealthAttribute();
            if (maxHealthAttr != null) {
                var attr = p.getAttribute(maxHealthAttr);
                if (attr != null) attr.setBaseValue(50.0);
            }
            p.setHealth(50.0);
        } catch (Throwable ignored) {}

        return p.getUniqueId();
    }

    /** Despawn a specific pet by UUID. */
    public void dismissPet(java.util.UUID petId) {
        if (petId == null) return;
        cancelTask(petId);

        Entity ent = null;
        try { ent = Bukkit.getEntity(petId); } catch (Throwable ignored) {}

        if (ent != null) {
            try { holo.detach(ent); } catch (Throwable ignored) {}
            // Remove avatar reliably (can be detached from passengers under lag/teleports)
            try {
                String ownerStr = null;
                try { ownerStr = ent.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING); } catch (Throwable ignored) {}
                java.util.UUID ownerId = null;
                if (ownerStr != null && !ownerStr.isEmpty()) {
                    try { ownerId = java.util.UUID.fromString(ownerStr); } catch (Throwable ignored) {}
                }
                if (ownerId != null) {
                    removeGuardianAvatar(ent.getUniqueId(), ownerId, ent.getLocation());
                } else {
                    // At least remove tracked avatar by parrot UUID
                    removeGuardianAvatarTrackedOnly(ent.getUniqueId());
                }
            } catch (Throwable ignored) {}
            try {
                for (Entity pass : ent.getPassengers()) {
                    try { if (pass.hasMetadata("guardianparrot")) pass.remove(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            try { ent.remove(); } catch (Throwable ignored) {}
        } else {
            // Parrot entity already gone/unloaded, but holo may still exist.
            try { holo.detachById(petId); } catch (Throwable ignored) {}
        }

        // remove from stored lists (players.yml)
        try {
            if (playersCfg.getConfigurationSection("players") != null) {
                for (String key : playersCfg.getConfigurationSection("players").getKeys(false)) {
                    java.util.List<String> pets = new java.util.ArrayList<>(playersCfg.getStringList("players." + key + ".pets"));
                    if (pets.remove(petId.toString())) playersCfg.set("players." + key + ".pets", pets);
                }
                savePlayers();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Compatibility helper: on some server APIs MAX_HEALTH is exposed instead of GENERIC_MAX_HEALTH.
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

    public boolean unsummonParrot(Player owner, boolean giveEgg){
        UUID pet = popFirstPet(owner);
        if (pet == null){
            owner.sendMessage(ChatColor.GRAY + "У вас нет призванных хранителей.");
            return false;
        }
        // remove entity & task
        cancelTask(pet);
        Entity e = Bukkit.getEntity(pet);
        if (e != null){
            try { holo.detach(e); } catch (Throwable ignored){}
            // remove passenger visuals (if attached)
            for (Entity pass : e.getPassengers()) {
                try { if (pass.hasMetadata("guardianparrot")) pass.remove(); } catch (Throwable ignored) {}
            }
            // remove tracked avatar even if it is NOT currently a passenger (bug left orphan stands)
            try { removeGuardianAvatar(e.getUniqueId(), owner.getUniqueId(), e.getLocation()); } catch (Throwable ignored) {}
            e.remove();
        } else {
            // fallback: we still try to remove any orphan avatar by parrot UUID
            try { holo.detachById(pet); } catch (Throwable ignored) {}
            try { removeGuardianAvatar(pet, owner.getUniqueId(), owner.getLocation()); } catch (Throwable ignored) {}
        }

        //     players.yml ()
        java.util.List<String> petsList = new java.util.ArrayList<>(playersCfg.getStringList("players."+owner.getUniqueId()+".pets"));
        boolean removed = petsList.remove(pet.toString());
        if (removed){
            playersCfg.set("players."+owner.getUniqueId()+".pets", petsList);
            savePlayers();
        }

        //        
        if (giveEgg){
            giveEgg(owner); //    ;   ,    
        }

        owner.sendMessage(ChatColor.YELLOW + "Хранитель отозван.");
        return true;
    }

    public void despawnAllFor(Player owner){
        Set<UUID> set = getPets(owner.getUniqueId()).stream().map(UUID::fromString).collect(Collectors.toSet());
        for (UUID id : set) {
            cancelTask(id);
            Entity e = Bukkit.getEntity(id);
            if (e != null){
                try { holo.detach(e); } catch (Throwable ignored){}
                for (Entity pass : e.getPassengers()) { try { if (pass.hasMetadata("guardianparrot")) pass.remove(); } catch (Throwable ignored) {} }
                try { removeGuardianAvatar(e.getUniqueId(), owner.getUniqueId(), e.getLocation()); } catch (Throwable ignored) {}
                e.remove();
            } else {
                try { holo.detachById(id); } catch (Throwable ignored) {}
                try { removeGuardianAvatar(id, owner.getUniqueId(), owner.getLocation()); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Removes the guardian armorstand avatar reliably.
     * We first use the in-memory map, then fall back to a nearby search by PDC/metadata.
     */
    private void removeGuardianAvatar(UUID parrotId, UUID ownerId, org.bukkit.Location hint) {
        removeGuardianAvatarTrackedOnly(parrotId);

        // fallback: remove only the avatar that belongs to THIS parrotId.
        // IMPORTANT: do not remove by owner-only, otherwise deleting one guardian can
        // wipe the visuals of other guardians spawned on the same private block.
        if (hint == null || hint.getWorld() == null) return;
        try {
            for (ArmorStand as : hint.getWorld().getEntitiesByClass(ArmorStand.class)) {
                if (as == null || as.isDead() || !as.isValid()) continue;
                boolean isGuardian = as.hasMetadata("guardianparrot");
                if (!isGuardian) {
                    var pdc = as.getPersistentDataContainer();
                    isGuardian = pdc != null && pdc.has(guardKey, PersistentDataType.BYTE);
                }
                if (!isGuardian) continue;
                // match exact avatar -> parrot id
                String pid = null;
                try { pid = as.getPersistentDataContainer().get(avatarParrotIdKey, PersistentDataType.STRING); } catch (Throwable ignored) {}
                if (pid == null) {
                    // legacy avatars (created before we started storing parrot id):
                    // remove only if it is extremely close to the removed parrot location AND belongs to the owner.
                    // This avoids wiping other guardians' visuals.
                    String o = null;
                    try { o = as.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING); } catch (Throwable ignored) {}
                    if (o == null || !o.equals(ownerId.toString())) continue;
                    if (as.getLocation().distanceSquared(hint) > 1.0) continue; // ~1 block radius
                } else {
                    if (!pid.equals(parrotId.toString())) continue;
                }
                try { as.remove(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Remove tether task and tracked avatar entity (even if not mounted).
     */
    private void removeGuardianAvatarTrackedOnly(UUID parrotId) {
        // remove the tracked avatar
        ArmorStand tracked = avatars.remove(parrotId);
        if (tracked != null) {
            try { if (tracked.isValid()) tracked.remove(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Ensure the visual avatar (ArmorStand) stays mounted on the parrot.
     * This is a lightweight alternative to the old per-pet sync task.
     */
    private void ensureAvatarAttached(org.bukkit.entity.Parrot parrot) {
        if (parrot == null) return;
        ArmorStand avatar = avatars.get(parrot.getUniqueId());
        if (avatar == null) return;
        if (avatar.isDead() || !avatar.isValid()) {
            avatars.remove(parrot.getUniqueId());
            return;
        }
        try {
            if (!parrot.getPassengers().contains(avatar)) {
                parrot.addPassenger(avatar);
            }
        } catch (Throwable ignored) {}
    }
    /**
     * Resummon guardians for an owner after reconnect/reload.
     *
     * Old implementation despawned everything and re-summoned at the *player location*.
     * That caused guardians spawning "from the player" on join, duplicated spawns and lag spikes.
     *
     * New behavior:
     *  - does NOT despawn anything globally
     *  - restores per (regionId, slot) based on guardian_state.yml
     *  - only spawns if the private-block anchor is known AND its chunk is already loaded
     */
    /**
     * Resummon guardians for an owner after reconnect/reload.
     *
     * IMPORTANT: we do NOT spawn new guardians here.
     * The only safe operation on join/reload is to re-bind tasks/visuals to
     * already existing guardian entities that are currently loaded.
     *
     * Spawning here (especially at player location) can create duplication
     * loops and huge lag spikes during chunk load.
     */
    public void resummonAllFor(Player owner){
        if (owner == null) return;

        // Debounce: join/world-change/reload can fire multiple times in a short window.
        final UUID ownerId = owner.getUniqueId();
        if (!resummonGuard.add(ownerId)) return;

        Bukkit.getScheduler().runTaskLater(plugin.host(), () -> {
            try {
                resyncLoadedGuardians(ownerId);
            } finally {
                resummonGuard.remove(ownerId);
            }
        }, 5L);
    }

    /**
     * Re-bind tasks/labels/avatars for currently loaded guardian entities of this owner.
     * Also removes duplicates per (regionId, slot) to prevent multiplication after reloads.
     */
    private void resyncLoadedGuardians(UUID ownerId){
        if (ownerId == null) return;
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) return;

        final Map<String, UUID> seen = new HashMap<>(); // key -> parrotUuid
        int kept = 0;

        for (World w : Bukkit.getWorlds()){
            for (Parrot p : w.getEntitiesByClass(Parrot.class)){
                try {
                    PersistentDataContainer pdc = p.getPersistentDataContainer();
                    if (!pdc.has(guardKey, PersistentDataType.BYTE)) continue;

                    String ownerStr = pdc.get(ownerKey, PersistentDataType.STRING);
                    if (ownerStr == null) continue;

                    UUID pid;
                    try { pid = UUID.fromString(ownerStr); } catch (Throwable t) { continue; }
                    if (!ownerId.equals(pid)) continue;

                    String rid = pdc.get(regionIdKey, PersistentDataType.STRING);
                    if (rid == null || rid.isBlank()) rid = "__global__";
                    Integer slot = pdc.get(slotKey, PersistentDataType.INTEGER);
                    int s = (slot == null ? 0 : slot);
                    String key = rid.toLowerCase(Locale.ROOT) + ":" + s;

                    UUID existing = seen.putIfAbsent(key, p.getUniqueId());
                    if (existing != null && !existing.equals(p.getUniqueId())){
                        // duplicate for the same slot: remove extra entity + its visuals
                        try { holo.detach(p); } catch (Throwable ignored) {}
                        try { removeGuardianAvatar(p.getUniqueId(), ownerId, p.getLocation()); } catch (Throwable ignored) {}
                        try { p.remove(); } catch (Throwable ignored) {}
                        continue;
                    }

                    // ensure task exists
                    if (!tasks.containsKey(p.getUniqueId())){
                        startTask(ownerId, p.getUniqueId());
                    }

                    // ensure visuals are attached
                    try { ensureAvatarAttached(p); } catch (Throwable ignored) {}
                    try { holo.attach(p, owner, resolvePetStats(owner, p)); } catch (Throwable ignored) {}

                    kept++;
                } catch (Throwable ignored){}
            }
        }

        // Optional: if nothing loaded, do nothing (do NOT spawn).
        // Spawning is only allowed via menu-clicks tied to the private-block anchor.
    }
    // ---- AGGRO API ----
        public void notifyOwnerUnderAttack(Player owner, Entity attacker){
        if (owner == null || attacker == null) return;
        if (CitizensHook.isNPC(attacker)) return;

        // Все хранители владельца реагируют, но цель назначается только одному ближайшему хранителю.
        GuardianTask chosen = null;
        double best = Double.MAX_VALUE;

        for (GuardianTask t : tasks.values()){
            if (!t.owner.equals(owner.getUniqueId())) continue;

            org.bukkit.entity.Entity pe = Bukkit.getEntity(t.parrotId);
            if (!(pe instanceof org.bukkit.entity.Parrot pp) || pp.isDead()) continue;
            if (pp.getWorld() != attacker.getWorld()) continue;

            double d = pp.getLocation().distanceSquared(attacker.getLocation());
            if (d < best){ best = d; chosen = t; }
        }

        if (chosen != null){
            chosen.setTarget(attacker);

            // Не "таскаем" хранителей за игроком 2014 они привязаны к региону привата.
            // Но можно быстро телепортнуть ближайшего, если атакующий находится внутри региона этого хранителя.
            try {
                org.bukkit.entity.Entity pe = Bukkit.getEntity(chosen.parrotId);
                if (pe instanceof org.bukkit.entity.Parrot pp){
                    com.sk89q.worldguard.protection.regions.ProtectedRegion pr = null;
                    try {
                        String rid = pp.getPersistentDataContainer().get(regionIdKey, PersistentDataType.STRING);
                        if (rid != null && !rid.isBlank() && pp.getWorld() != null) {
                            var container = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
                            var rm = container.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(pp.getWorld()));
                            if (rm != null) pr = rm.getRegion(rid);
                        }
                    } catch (Throwable ignored) {}

                    if (pr == null){
                        // без привязки к региону 2014 оставляем старое поведениельное
                        chosen.teleportNear(attacker.getLocation());
                    } else {
                        var bvA = com.sk89q.worldedit.math.BlockVector3.at(
                                attacker.getLocation().getBlockX(),
                                attacker.getLocation().getBlockY(),
                                attacker.getLocation().getBlockZ()
                        );
                        if (pr.contains(bvA)) chosen.teleportNear(attacker.getLocation());
                    }
                }
            } catch (Throwable ignored) {}
        }

        // тост CMI (через консоль)
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "cmi toast " + owner.getName() + " &6Вижу моба: " + attacker.getType().name());
    }

    /**
     * Region-based alert: если на игрока (владельца/друга региона) напали внутри привата,
     * все хранители, привязанные к этому regionId, должны атаковать агрессора.
     */
    public void notifyRegionUnderAttack(String regionId, Entity attacker){
        if (regionId == null || regionId.isBlank()) return;
        if (attacker == null) return;
        if (!(attacker instanceof LivingEntity)) return;
        if (CitizensHook.isNPC(attacker)) return;

        for (GuardianTask t : tasks.values()){
            try {
                Entity pe = Bukkit.getEntity(t.parrotId);
                if (!(pe instanceof Parrot pp) || pp.isDead()) continue;

                String rid = null;
                try { rid = pp.getPersistentDataContainer().get(regionIdKey, PersistentDataType.STRING); } catch (Throwable ignored) {}
                if (rid == null || !rid.equalsIgnoreCase(regionId)) continue;

                if (pp.getWorld() != attacker.getWorld()) continue;

                // attacker должен быть внутри этого региона
                try {
                    var container = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
                    var rm = container.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(pp.getWorld()));
                    var pr = rm == null ? null : rm.getRegion(rid);
                    if (pr != null) {
                        var bvA = com.sk89q.worldedit.math.BlockVector3.at(attacker.getLocation().getBlockX(), attacker.getLocation().getBlockY(), attacker.getLocation().getBlockZ());
                        if (!pr.contains(bvA)) continue;
                    }
                } catch (Throwable ignored) {}

                t.setTarget(attacker);
            } catch (Throwable ignored) {}
        }
    }

    // ---- internal task ----
    private void startTask(UUID owner, UUID parrot){
        GuardianTask old = tasks.remove(parrot);
        if (old != null) old.cancel();
        GuardianTask t = new GuardianTask(owner, parrot);
        // Guardians must stay inside the private-block region.
        // Run every tick for reliable boundary enforcement; run heavier logic at scanInterval inside the task.
        t.runTaskTimer(plugin.host(), 10L, 1L);
        tasks.put(parrot, t);
    }
    private void cancelTask(UUID parrot){
        GuardianTask t = tasks.remove(parrot);
        if (t != null) t.cancel();
    }

    private class GuardianTask extends BukkitRunnable {
        // === safe vector helpers to avoid NaN/Infinity velocities ===
        private org.bukkit.util.Vector normOrFallback(org.bukkit.util.Vector v, org.bukkit.util.Vector fallback) {
            if (v == null) return (fallback != null ? fallback.clone() : new org.bukkit.util.Vector(0, 0.1, 0));
            double x=v.getX(), y=v.getY(), z=v.getZ();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return (fallback != null ? fallback.clone() : new org.bukkit.util.Vector(0, 0.1, 0));
            }
            if (v.lengthSquared() < 1.0E-6) {
                return (fallback != null && fallback.lengthSquared() > 1.0E-6) ? fallback.clone() : new org.bukkit.util.Vector(0, 0.1, 0);
            }
            try { return v.normalize(); } catch (Throwable t) { return new org.bukkit.util.Vector(0, 0.1, 0); }
        }
        private boolean finite(org.bukkit.util.Vector v){
            return v != null && Double.isFinite(v.getX()) && Double.isFinite(v.getY()) && Double.isFinite(v.getZ());
        }

        final UUID owner;
        final UUID parrotId;
        UUID targetId = null;
        int strikeCd = 0, shootCd = 0;

        // Tick counters
        int tick = 0;

        // Cache WG region lookup to avoid hitting RegionContainer every tick.
        private String cachedRegionId = null;
        private com.sk89q.worldguard.protection.regions.ProtectedRegion cachedRegion = null;
        private int regionRefreshCd = 0;

        GuardianTask(UUID owner, UUID parrotId){ this.owner=owner; this.parrotId=parrotId; }

        @Override public void run(){
            // Guardians must protect the private region even when the owner is offline.
            // Cancelling the task here disables boundary enforcement + combat.
            final Player p = Bukkit.getPlayer(owner); // may be null
            Entity e = Bukkit.getEntity(parrotId);
            if (!(e instanceof Parrot parrot) || parrot.isDead()){
                cancel(); return;
            }

            // Re-attach visuals occasionally (no per-pet sync tasks).
            // If owner is offline, just keep the avatar attached (no holo refresh).
            try { ensureAvatarAttached(parrot); } catch (Throwable ignored) {}
            if (p != null && p.isOnline()) {
                try { holo.attach(parrot, p, resolvePetStats(p, parrot)); } catch (Throwable ignored) {}
            }

            // ===== Region-bound logic (same idea as Farmers): guardians belong to the private-block region, NOT the player. =====
            // 1) Determine the bound region (WorldGuard regionId in PDC) if present.
            com.sk89q.worldguard.protection.regions.ProtectedRegion boundRegion = null;
            try {
                String rid = parrot.getPersistentDataContainer().get(regionIdKey, PersistentDataType.STRING);
                if (rid != null && !rid.isBlank() && parrot.getWorld() != null) {
                    // Refresh cache every ~2 seconds or when region id changes.
                    if (regionRefreshCd-- <= 0 || cachedRegion == null || cachedRegionId == null || !rid.equalsIgnoreCase(cachedRegionId)) {
                        cachedRegionId = rid;
                        var container = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
                        var rm = container.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(parrot.getWorld()));
                        cachedRegion = (rm != null ? rm.getRegion(rid) : null);
                        regionRefreshCd = 40;
                    }
                    boundRegion = cachedRegion;
                } else {
                    cachedRegionId = null;
                    cachedRegion = null;
                    regionRefreshCd = 40;
                }
            } catch (Throwable ignored) {}

            // 2) Determine anchor location: private marker (private block) if stored, else region center, else parrot current.
            Location anchor = getPrivateMarkerLocation(parrot);
            if (anchor == null && boundRegion != null) {
                try {
                    var min = boundRegion.getMinimumPoint();
                    var max = boundRegion.getMaximumPoint();
                    int cx = (min.getBlockX() + max.getBlockX()) / 2;
                    int cy = Math.max(min.getBlockY(), Math.min(max.getBlockY(), parrot.getLocation().getBlockY()));
                    int cz = (min.getBlockZ() + max.getBlockZ()) / 2;
                    anchor = new Location(parrot.getWorld(), cx + 0.5, cy + 0.5, cz + 0.5);
                } catch (Throwable ignored) {}
            }
            if (anchor == null) anchor = parrot.getLocation();

            // 3) Keep guardian strictly inside the private-block region.
            // If it ever leaves the region, immediately return it to the exact private block coordinates (anchor).
            try {
                boolean outside = false;
                if (boundRegion != null) {
                    var bv = com.sk89q.worldedit.math.BlockVector3.at(parrot.getLocation().getBlockX(), parrot.getLocation().getBlockY(), parrot.getLocation().getBlockZ());
                    outside = !boundRegion.contains(bv);
                }
                if (outside) {
                    teleportExact(clampToRegion(anchor, boundRegion));
                    targetId = null;
                    return;
                }
            } catch (Throwable ignored) {}

            // Cooldowns tick every server tick.
            if (strikeCd > 0) strikeCd -= 1;
            if (shootCd  > 0) shootCd  -= 1;

            // Heavy logic (scan/aggro/teleport/combat decisions) runs at scanInterval.
            tick++;
            if (scanInterval > 1 && (tick % scanInterval) != 0) {
                return;
            }

            LivingEntity target = findOrValidateTarget(p, parrot, boundRegion);
            if (target == null) return;

            //     
            if (parrot.getLocation().distanceSquared(target.getLocation()) > 100){ // >10 
                // Only teleport towards targets that are inside the same bound region; otherwise return to anchor.
                if (boundRegion != null) {
                    var bvT = com.sk89q.worldedit.math.BlockVector3.at(target.getLocation().getBlockX(), target.getLocation().getBlockY(), target.getLocation().getBlockZ());
                    if (!boundRegion.contains(bvT)) {
                        targetId = null;
                        teleportExact(clampToRegion(anchor, boundRegion));
                        return;
                    }
                }
                teleportNear(clampToRegion(target.getLocation(), boundRegion), boundRegion);
            }

            //    
            if (strikeCd <= 0 && parrot.getLocation().distanceSquared(target.getLocation()) < 4){ // <2 
                strikeCd = Math.max(1, strikeInterval);
                doMelee(parrot, target);
            }

            //  
            if (shootCd <= 0){
                shootCd = Math.max(1, shootInterval);
                doWitherShot(parrot, target);
            }
        }

        private LivingEntity findOrValidateTarget(Player ownerPlayer, Parrot parrot, com.sk89q.worldguard.protection.regions.ProtectedRegion boundRegion){
            // 1) keep current if still valid and in same world + within aggro radius to *parrot* (not owner)
            LivingEntity cur = targetId!=null && Bukkit.getEntity(targetId) instanceof LivingEntity le && !le.isDead() ? le : null;
            if (cur != null && CitizensHook.isNPC(cur)) { targetId = null; cur = null; }
            if (cur != null && cur.getWorld()==parrot.getWorld()
                    && parrot.getLocation().distanceSquared(cur.getLocation()) < (aggroRadius*aggroRadius)){
                // Must stay inside bound region
                if (boundRegion != null) {
                    try {
                        var bv = com.sk89q.worldedit.math.BlockVector3.at(cur.getLocation().getBlockX(), cur.getLocation().getBlockY(), cur.getLocation().getBlockZ());
                        if (!boundRegion.contains(bv)) { targetId = null; return null; }
                    } catch (Throwable ignored) {}
                }
                return cur;
            }
            // 2) build a set of targets already taken by other guardians of the same owner
            java.util.Set<java.util.UUID> taken = new java.util.HashSet<>();
            for (GuardianTask tOther : tasks.values()){
                if (!tOther.parrotId.equals(this.parrotId) && tOther.owner.equals(this.owner) && tOther.targetId != null){
                    taken.add(tOther.targetId);
                }
            }
            // 3) search nearest target around *this parrot*, skipping already taken targets
            LivingEntity best = null;
            double bestD = Double.MAX_VALUE;
            for (org.bukkit.entity.Entity ent : parrot.getNearbyEntities(aggroRadius, aggroRadius, aggroRadius)){
                if (ent instanceof org.bukkit.entity.Monster m && !taken.contains(m.getUniqueId())) {
                    if (CitizensHook.isNPC(m)) continue;
                    if (boundRegion != null) {
                        try {
                            var bv = com.sk89q.worldedit.math.BlockVector3.at(m.getLocation().getBlockX(), m.getLocation().getBlockY(), m.getLocation().getBlockZ());
                            if (!boundRegion.contains(bv)) continue;
                        } catch (Throwable ignored) {}
                    }
                    double d = m.getLocation().distanceSquared(parrot.getLocation());
                    if (d < bestD){ bestD = d; best = m; }
                }

                // PvP players (non-trusted) inside the same private region
                if (ent instanceof Player pl) {
                    if (ownerPlayer != null && pl.getUniqueId().equals(ownerPlayer.getUniqueId())) continue;
                    if (!pl.isOnline() || pl.isDead()) continue;
                    if (CitizensHook.isNPC(pl)) continue;
                    if (taken.contains(pl.getUniqueId())) continue;

                    if (boundRegion != null) {
                        try {
                            var bv = com.sk89q.worldedit.math.BlockVector3.at(pl.getLocation().getBlockX(), pl.getLocation().getBlockY(), pl.getLocation().getBlockZ());
                            if (!boundRegion.contains(bv)) continue;
                        } catch (Throwable ignored) {}

                        // trusted players (WG members/owners) are ignored
                        try {
                            if (boundRegion.getOwners().contains(pl.getUniqueId()) || boundRegion.getMembers().contains(pl.getUniqueId())) continue;
                        } catch (Throwable ignored) {}
                    }

                    // "PvP mode" heuristics: scoreboard tags / metadata often used by PvP toggle plugins
                    if (!isPlayerInPvpMode(pl)) continue;

                    double d = pl.getLocation().distanceSquared(parrot.getLocation());
                    if (d < bestD){ bestD = d; best = pl; }
                }
            }
            if (best != null) targetId = best.getUniqueId();
            else targetId = null;
            return best;
        }

        private boolean isPlayerInPvpMode(Player pl){
            try {
                for (String tag : pl.getScoreboardTags()) {
                    if (tag == null) continue;
                    String t = tag.toLowerCase(Locale.ROOT);
                    if (t.equals("pvp") || t.equals("pvpmode") || t.equals("in_pvp") || t.equals("combat") || t.equals("incombat")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}

            try {
                for (String key : java.util.List.of("pvp", "pvpmode", "cmi:pvp", "cmipvp", "combat", "incombat")) {
                    if (!pl.hasMetadata(key)) continue;
                    var list = pl.getMetadata(key);
                    if (list == null) continue;
                    for (var mv : list) {
                        if (mv == null) continue;
                        try {
                            if (mv.asBoolean()) return true;
                        } catch (Throwable ignored2) {}
                        try {
                            String s = mv.asString();
                            if (s != null && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("1") || s.equalsIgnoreCase("yes"))) return true;
                        } catch (Throwable ignored2) {}
                    }
                }
            } catch (Throwable ignored) {}

            return false;
        }

        private void doMelee(Parrot parrot, LivingEntity target){
            if (CitizensHook.isNPC(target)) return;
            Player __owner = Bukkit.getPlayer(owner);
            target.damage(8.0, __owner != null ? __owner : parrot);
            org.bukkit.util.Vector __kb = target.getLocation().toVector().subtract(parrot.getLocation().toVector());
            __kb = normOrFallback(__kb, parrot.getLocation().getDirection()).multiply(0.6);
            __kb.setY(0.4);
            org.bukkit.util.Vector __newVel = target.getVelocity().clone().add(__kb);
            if (!finite(__newVel)) __newVel = new org.bukkit.util.Vector(0,0.1,0);
            target.setVelocity(__newVel);
            parrot.getWorld().playSound(parrot.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 1f);
            parrot.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0,1,0), 10, 0.3, 0.4, 0.3, 0.01);
            //   
            // Paper/Spigot 1.21+: SLOW is deprecated/removed; use SLOWNESS
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2, true, false, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, false, true));
            parrot.getWorld().playSound(parrot.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1f);
            parrot.getWorld().spawnParticle(Particle.SONIC_BOOM, target.getLocation(), 1);
        }

        private void doWitherShot(Parrot parrot, LivingEntity target){
            if (CitizensHook.isNPC(target)) return;
            // Оригинальная механика из референс‑проекта: настоящий снаряд ParrotSkull (урон + WITHER внутри ParrotSkull).
            try {
                ParrotSkull.launch(plugin, parrot, target);
            } catch (Throwable ignored) {}
        }

        void teleportNear(Location to){
            teleportNear(to, null);
        }

        void teleportNear(Location to, com.sk89q.worldguard.protection.regions.ProtectedRegion pr){
            Entity e = Bukkit.getEntity(parrotId);
            if (e != null){
                Location dest = to.clone().add((Math.random()-0.5)*1.5, 0.3, (Math.random()-0.5)*1.5);
                // Ensure random offset cannot push the guardian outside the region.
                dest = clampToRegion(dest, pr);
                dest = findSafeTeleport(dest);
                whitelistTeleport(e);
                robustTeleport(e, dest);
                e.getWorld().playSound(e.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                e.getWorld().spawnParticle(Particle.PORTAL, e.getLocation(), 20, 0.4, 0.6, 0.4, 0.01);
            }
        }

        void teleportExact(Location to){
            Entity e = Bukkit.getEntity(parrotId);
            if (e != null){
                Location dest = findSafeTeleport(to.clone());
                whitelistTeleport(e);
                robustTeleport(e, dest);
                e.getWorld().playSound(e.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                e.getWorld().spawnParticle(Particle.PORTAL, e.getLocation(), 20, 0.4, 0.6, 0.4, 0.01);
            }
        }

        /**
         * Teleport helpers:
         * Some servers/plugins can silently cancel entity teleports, especially for entities with passengers.
         * Guardians use an avatar armor-stand as a passenger, so we temporarily detach passengers,
         * teleport with PLUGIN cause, then restore passengers. If the teleport is cancelled, retry a few times.
         */
        private void robustTeleport(Entity e, Location dest){
            if (e == null || dest == null) return;
            try { e.setVelocity(new org.bukkit.util.Vector(0, 0, 0)); } catch (Throwable ignored) {}

            java.util.List<Entity> passengers = new java.util.ArrayList<>();
            try { passengers.addAll(e.getPassengers()); } catch (Throwable ignored) {}
            try {
                for (Entity ps : passengers) {
                    try { e.removePassenger(ps); } catch (Throwable ignored2) {}
                }
            } catch (Throwable ignored) {}

            boolean ok = false;
            try {
                ok = e.teleport(dest, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            } catch (Throwable t) {
                try { ok = e.teleport(dest); } catch (Throwable ignored) {}
            }

            // Restore passengers
            try {
                for (Entity ps : passengers) {
                    if (ps == null || ps.isDead()) continue;
                    try { e.addPassenger(ps); } catch (Throwable ignored2) {}
                }
            } catch (Throwable ignored) {}

            if (ok) return;

            // Retry next ticks (up to 3 tries) - helps when a teleport is cancelled due to temporary conditions.
            Bukkit.getScheduler().runTaskLater(plugin.host(), () -> {
                try {
                    Entity ent = Bukkit.getEntity(parrotId);
                    if (ent == null || ent.isDead()) return;
                    try { ent.setVelocity(new org.bukkit.util.Vector(0, 0, 0)); } catch (Throwable ignored) {}
                    try { ent.teleport(dest, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN); }
                    catch (Throwable ignored) { try { ent.teleport(dest); } catch (Throwable ignored2) {} }
                } catch (Throwable ignored) {}
            }, 1L);
            Bukkit.getScheduler().runTaskLater(plugin.host(), () -> {
                try {
                    Entity ent = Bukkit.getEntity(parrotId);
                    if (ent == null || ent.isDead()) return;
                    if (ent.getLocation().distanceSquared(dest) < 0.25) return;
                    try { ent.setVelocity(new org.bukkit.util.Vector(0, 0, 0)); } catch (Throwable ignored) {}
                    try { ent.teleport(dest, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN); }
                    catch (Throwable ignored) { try { ent.teleport(dest); } catch (Throwable ignored2) {} }
                } catch (Throwable ignored) {}
            }, 3L);
            Bukkit.getScheduler().runTaskLater(plugin.host(), () -> {
                try {
                    Entity ent = Bukkit.getEntity(parrotId);
                    if (ent == null || ent.isDead()) return;
                    if (ent.getLocation().distanceSquared(dest) < 0.25) return;
                    try { ent.setVelocity(new org.bukkit.util.Vector(0, 0, 0)); } catch (Throwable ignored) {}
                    try { ent.teleport(dest, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN); }
                    catch (Throwable ignored) { try { ent.teleport(dest); } catch (Throwable ignored2) {} }
                } catch (Throwable ignored) {}
            }, 6L);
        }

        /**
         * Some servers use FermerPets TeleportGuardListener which cancels teleports for pet entities.
         * Temporarily whitelist this teleport in entity PDC.
         */
        private void whitelistTeleport(Entity e){
            try {
                PersistentDataContainer pdc = e.getPersistentDataContainer();
                pdc.set(tpWhitelistKey, PersistentDataType.BYTE, (byte)1);
                // remove the flag next tick (allow exactly one teleport)
                Bukkit.getScheduler().runTask(plugin.host(), () -> {
                    try {
                        Entity ent = Bukkit.getEntity(parrotId);
                        if (ent != null) ent.getPersistentDataContainer().remove(tpWhitelistKey);
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}
        }

        /**
         * Ensure teleport destination is not inside a solid block.
         */
        private Location findSafeTeleport(Location loc){
            if (loc == null || loc.getWorld() == null) return loc;
            try {
                World w = loc.getWorld();
                try { w.getChunkAt(loc).load(); } catch (Throwable ignored) {}

                int bx = loc.getBlockX();
                int bz = loc.getBlockZ();
                int by = loc.getBlockY();

                for (int dy = 0; dy <= 6; dy++){
                    int y = Math.min(w.getMaxHeight() - 2, by + dy);
                    var b1 = w.getBlockAt(bx, y, bz);
                    var b2 = w.getBlockAt(bx, y + 1, bz);
                    if (b1.isPassable() && b2.isPassable()){
                        return new Location(w, bx + 0.5, y, bz + 0.5);
                    }
                }

                int yTop = w.getHighestBlockYAt(bx, bz);
                return new Location(w, bx + 0.5, Math.min(w.getMaxHeight() - 2, yTop + 1), bz + 0.5);
            } catch (Throwable ignored){
                return loc;
            }
        }
        private Location getPrivateMarkerLocation(Parrot parrot){
            try {
                var pdc = parrot.getPersistentDataContainer();
                String wName = pdc.get(privateWorldKey, PersistentDataType.STRING);
                Integer x = pdc.get(privateXKey, PersistentDataType.INTEGER);
                Integer y = pdc.get(privateYKey, PersistentDataType.INTEGER);
                Integer z = pdc.get(privateZKey, PersistentDataType.INTEGER);
                if (wName == null || x == null || y == null || z == null) return null;
                var w = Bukkit.getWorld(wName);
                if (w == null) return null;
                return new Location(w, x + 0.5, y + 1.0, z + 0.5);
            } catch (Throwable ignored){
                return null;
            }
        }

        private Location clampToRegion(Location loc, com.sk89q.worldguard.protection.regions.ProtectedRegion pr){
            if (loc == null) return null;
            if (pr == null) return loc;
            try {
                var min = pr.getMinimumPoint();
                var max = pr.getMaximumPoint();
                double x = Math.max(min.getX() + 0.5, Math.min(max.getX() + 0.5, loc.getX()));
                double y = Math.max(min.getY() + 0.5, Math.min(max.getY() + 0.5, loc.getY()));
                double z = Math.max(min.getZ() + 0.5, Math.min(max.getZ() + 0.5, loc.getZ()));
                return new Location(loc.getWorld(), x, y, z);
            } catch (Throwable ignored){
                return loc;
            }
        }

        void setTarget(Entity ent){
            if (ent instanceof LivingEntity le){
                if (CitizensHook.isNPC(le)) return;
                // avoid assigning a target already claimed by another guardian of the same owner
                for (GuardianTask tOther : tasks.values()){
                    if (!tOther.parrotId.equals(this.parrotId) && tOther.owner.equals(this.owner) && le.getUniqueId().equals(tOther.targetId)){
                        return; // someone else of this owner is already on this target
                    }
                }
                this.targetId = le.getUniqueId();
            }
        }

    }



    public GPPlugin getPlugin(){ return this.plugin; }



    public org.bukkit.configuration.file.FileConfiguration getPlayersYaml(){ return this.playersCfg; }



    public void startReaper(){
        if (reaperTask != null){ try{ reaperTask.cancel(); }catch(Throwable ignored){} }
        reaperTask = new BukkitRunnable(){
            @Override public void run(){
                // remove tasks for missing/invalid parrots
                tasks.entrySet().removeIf(e -> {
                    Entity ent = Bukkit.getEntity(e.getKey());
                    return !(ent instanceof Parrot) || ent.isDead();
                });
            }
        }.runTaskTimer(plugin.host(), 200L, 200L);
    }



    public void stopReaper(){
        if (reaperTask != null){ try{ reaperTask.cancel(); }catch(Throwable ignored){} reaperTask = null; }
    }



    public void startHoloRefresh(){
        if (holoRefreshTask != null){ try{ holoRefreshTask.cancel(); }catch(Throwable ignored){} }
        holoRefreshTask = new BukkitRunnable(){
            @Override public void run(){
                try{
                    for (var entry : tasks.entrySet()){
                        Entity ent = Bukkit.getEntity(entry.getKey());
                        if (ent instanceof Parrot p){
                            Player owner = Bukkit.getPlayer(entry.getValue().owner);
                            if (owner != null){
                                try{ holo.refresh(p, resolvePetStats(owner, p)); } catch (Throwable ignored){}
                            }
                        }
                    }
                } catch (Throwable ignored){}
            }
        }.runTaskTimer(plugin.host(), 100L, 100L);
    }



    public void stopHoloRefresh(){
        if (holoRefreshTask != null){ try{ holoRefreshTask.cancel(); }catch(Throwable ignored){} holoRefreshTask = null; }
    }



    public void setPetIndex(Parrot parrot, int index){
        if (parrot == null) return;
        parrot.getPersistentDataContainer().set(petIndexKey, PersistentDataType.INTEGER, index);
    }



    public int getPetIndexFrom(Parrot parrot){
        if (parrot == null) return -1;
        Integer v = parrot.getPersistentDataContainer().get(petIndexKey, PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }



    private com.example.guardianparrot.HoloLabelService.PetStats resolvePetStats(Player owner, Parrot parrot){
        int defWither = combatCfg.getInt("wither-damage", 6);
        int defWarden = combatCfg.getInt("warden-damage", 8);
        int defTp     = combatCfg.getInt("teleport-distance", 30);
        int wither = defWither, warden = defWarden, tp = defTp;
        try{
            int idx = getPetIndexFrom(parrot);
            if (idx > 0 && playersCfg != null){
                String base = "players."+owner.getUniqueId()+"."+idx;
                wither = playersCfg.getInt(base+".wither", wither);
                warden = playersCfg.getInt(base+".warden", warden);
                tp     = playersCfg.getInt(base+".tpDistance", tp);
            }
        } catch (Throwable ignored){}
        return new com.example.guardianparrot.HoloLabelService.PetStats(wither, warden, tp);
    }




    public void resummonAllForDebounced(Player owner){
        if (owner == null) return;
        final java.util.UUID uid = owner.getUniqueId();
        // If we're already processing a resummon for this player, skip
        if (!resummonGuard.add(uid)) return;

        // Run slightly later to let cascaded events settle
        Bukkit.getScheduler().runTaskLater(plugin.host(), () -> {
            try {
                resummonAllFor(owner);
                // After resummon, purge any leftover duplicates in owner's world
                purgeDuplicates(owner);
            } catch (Throwable ignored) {
            } finally {
                // Release the guard a bit later to absorb follow-up events
                Bukkit.getScheduler().runTaskLater(plugin.host(), () -> resummonGuard.remove(uid), 20L);
            }
        }, 20L);
    }




    public void cleanupWorldOrphans(){
        for (World w : Bukkit.getWorlds()){
            for (Parrot p : w.getEntitiesByClass(Parrot.class)){
                PersistentDataContainer pdc = p.getPersistentDataContainer();
                String ownerStr = pdc.get(ownerKey(), PersistentDataType.STRING);
                if (ownerStr == null) continue;
                // if not tracked by tasks or invalid, purge
                if (!tasks.containsKey(p.getUniqueId()) || p.isDead()){
                    try{ p.remove(); } catch (Throwable ignored){}
                }
            }
        }
    }


    /**
     * Remove duplicated guardian parrots that are not part of the expected set from players.yml
     * Works only in the current world of the owner to avoid touching others inadvertently.
     */
    private void purgeDuplicates(Player owner){
        try{
            java.util.Set<java.util.UUID> expected = getPets(owner.getUniqueId())
                    .stream().map(java.util.UUID::fromString)
                    .collect(java.util.stream.Collectors.toSet());
            World w = owner.getWorld();
            for (Parrot p : w.getEntitiesByClass(Parrot.class)){
                PersistentDataContainer pdc = p.getPersistentDataContainer();
                String ownerStr = pdc.get(ownerKey(), PersistentDataType.STRING);
                if (ownerStr == null) continue;
                if (!owner.getUniqueId().toString().equals(ownerStr)) continue;
                if (!expected.contains(p.getUniqueId())){
                    try{ holo.detach(p); } catch (Throwable ignored){}
                    try{ p.remove(); } catch (Throwable ignored){}
                }
            }

            // Also remove orphan guardian armor stands for this owner that are not attached to an expected parrot
            for (ArmorStand as : w.getEntitiesByClass(ArmorStand.class)) {
                try {
                    boolean isGuardian = as.hasMetadata("guardianparrot");
                    if (!isGuardian) {
                        var pdc = as.getPersistentDataContainer();
                        isGuardian = pdc != null && pdc.has(guardKey, PersistentDataType.BYTE);
                    }
                    if (!isGuardian) continue;
                    String ownerStr = null;
                    try { ownerStr = as.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING); } catch (Throwable ignored) {}
                    if (ownerStr == null || !ownerStr.equals(owner.getUniqueId().toString())) continue;
                    Entity veh = as.getVehicle();
                    java.util.UUID parrotId = null;
                    if (veh instanceof Parrot) parrotId = veh.getUniqueId();
                    if (parrotId == null || !expected.contains(parrotId)) { try { as.remove(); } catch (Throwable ignored) {} }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored){}
    }


    
    private void handleOrphan(Entity e) {
        try {
            if (e == null || e.isDead()) return;
            if (!e.hasMetadata("guardianparrot")) {
                var pdc = e.getPersistentDataContainer();
                if (!(pdc != null && pdc.has(guardKey, PersistentDataType.BYTE))) return;
            }
            // remove stray guardian avatar or parrot
            try { e.remove(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public void cleanupOrphansOnStartup() {
        try {
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntitiesByClass(Parrot.class)) {
                    handleOrphan(e);
                }
                for (Entity e : w.getEntitiesByClass(ArmorStand.class)) {
                    try {
                        if (!(e instanceof ArmorStand)) continue;
                        ArmorStand as = (ArmorStand) e;
                        boolean isGuardian = as.hasMetadata("guardianparrot");
                        if (!isGuardian) {
                            var pdc = as.getPersistentDataContainer();
                            isGuardian = pdc != null && pdc.has(guardKey, PersistentDataType.BYTE);
                        }
                        if (!isGuardian) continue;
                        java.util.UUID ownerId = null;
                        try {
                            String ownerStr = as.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                            if (ownerStr != null && !ownerStr.isEmpty()) ownerId = java.util.UUID.fromString(ownerStr);
                        } catch (Throwable ignore) {}
                        Entity vehicle = as.getVehicle();
                        boolean attachedToValidParrot = false;
                        if (vehicle instanceof Parrot) {
                            Parrot vp = (Parrot) vehicle;
                            if (vp.isValid() && !vp.isDead()) {
                                String pOwner = null;
                                try { pOwner = vp.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING); } catch (Throwable ignore) {}
                                boolean ownerMatches = (ownerId == null && pOwner == null) || (ownerId != null && ownerId.toString().equals(pOwner));
                                if (ownerMatches) {
                                    boolean listed = false;
                                    try {
                                        if (ownerId != null) {
                                            java.util.List<String> list = playersCfg.getStringList("players." + ownerId + ".pets");
                                            if (list != null) {
                                                for (String s : list) {
                                                    try { if (java.util.UUID.fromString(s).equals(vp.getUniqueId())) { listed = true; break; } } catch (Throwable ignored) {}
                                                }
                                            }
                                        }
                                    } catch (Throwable ignore) {}
                                    attachedToValidParrot = listed;
                                }
                            }
                        }
                        if (!attachedToValidParrot) { try { as.remove(); } catch (Throwable ignored) {} }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignoredOuter) {}
    }


    public void shutdownAll() {
        // Called from /parrot reload and plugin disable.
        // Keep it defensive: never throw, even if internal state is partially initialized.

        // 1) Cancel all scheduled tasks.
        try {
            for (GuardianTask task : tasks.values()) {
                if (task != null) task.cancel();
            }
        } catch (Throwable ignored) {
        }
        tasks.clear();

        if (reaperTask != null) {
            try { reaperTask.cancel(); } catch (Throwable ignored) {}
            reaperTask = null;
        }
        if (holoRefreshTask != null) {
            try { holoRefreshTask.cancel(); } catch (Throwable ignored) {}
            holoRefreshTask = null;
        }

        // 2) Remove tracked avatar stands.
        try {
            for (ArmorStand stand : avatars.values()) {
                if (stand != null && !stand.isDead()) {
                    stand.remove();
                }
            }
        } catch (Throwable ignored) {
        }
        avatars.clear();

        // 2.5) Remove guardian holograms
        try { holo.detachAll(); } catch (Throwable ignored) {}

        // 3) Best-effort cleanup: remove any guardian parrots + orphaned avatar stands in loaded worlds.
        // We mark our parrots via PDC key 'guardKey'.
        try {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (Parrot parrot : world.getEntitiesByClass(Parrot.class)) {
                    try {
                        if (parrot.getPersistentDataContainer().has(guardKey, PersistentDataType.BYTE)) {
                            parrot.remove();
                        }
                    } catch (Throwable ignoredInner) {}
                }

                for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                    try {
                        if (stand.getPersistentDataContainer().has(avatarParrotIdKey, PersistentDataType.STRING)) {
                            stand.remove();
                        }
                    } catch (Throwable ignoredInner) {}
                }
            }
        } catch (Throwable ignored) {
        }
    }

}