package com.fermerpets;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.file.FileConfiguration;
import com.fermerpets.Debug;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.bukkit.configuration.file.YamlConfiguration;

public class PetManager {

    private final FermerPetsModule plugin;
    
    private final java.util.Map<java.util.UUID, java.util.List<java.util.UUID>> ownerToPets = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<java.util.UUID> killOnSight = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final java.util.Map<java.util.UUID, String> petWorldHint = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, long[]> petChunkHint = new java.util.concurrent.ConcurrentHashMap<>();
    private final String bootId;
    // Spawn eggs are no longer used. Keep config objects only for backward-compatible method stubs.
    private java.io.File playersEggFile;
    private org.bukkit.configuration.file.FileConfiguration playersEggCfg;
    private WorldsDenyService worlds;

    // players.yml
    private File playersFile;
    private FileConfiguration playersCfg;
    private com.fermerpets.PlayersStore playersStore;

    // Active pets per owner
private final Map<UUID, Integer> brainTasks = new HashMap<>();

    // PDC keys
    private final NamespacedKey petKey = new NamespacedKey(FermerPetsModule.get().plugin(), "mps_pet");
    private final NamespacedKey ownerKey = new NamespacedKey(FermerPetsModule.get().plugin(), "mps_owner");
    private final NamespacedKey sessionKey = new NamespacedKey(FermerPetsModule.get().plugin(), "mps_session");

    
    private final NamespacedKey eggIdKey = new NamespacedKey(FermerPetsModule.get().plugin(), "egg_id");
    private final NamespacedKey farmerVariantKey = new NamespacedKey(FermerPetsModule.get().plugin(), "farmer_variant");
    private final NamespacedKey farmerWalkOnlyKey = new NamespacedKey(FermerPetsModule.get().plugin(), "farmer_walk");

    // index (1..3) assigned to this farmer
    private final NamespacedKey farmerIndexKey = new NamespacedKey(FermerPetsModule.get().plugin(), "farmer_index");


    // привязка фермера к привату (регион/маркер)
    private final NamespacedKey privateRegionKey = new NamespacedKey(FermerPetsModule.get().plugin(), "private_region");
    private final NamespacedKey privateMarkerKey = new NamespacedKey(FermerPetsModule.get().plugin(), "private_marker"); // worldUid:x:y:z

    private static final class SummonContext {
        final String regionId;
        final org.bukkit.Location marker;
        SummonContext(String regionId, org.bukkit.Location marker){ this.regionId = regionId; this.marker = marker; }
    }
    private final ThreadLocal<SummonContext> summonContext = new ThreadLocal<>();

    private final ThreadLocal<Integer> summonIndex = new ThreadLocal<>();

    /** Sets farmer slot (1..3) for next summonPet() call (menu integration). */
    public void setNextSummonIndex(Integer idx){
        if (idx == null || idx < 1 || idx > 3) { summonIndex.remove(); return; }
        summonIndex.set(idx);
    }

    /**
     * Устанавливает контекст привата для следующего summonPet() вызова (используется меню привата).
     * В конце вызова summonPet() контекст автоматически очищается.
     */
    public void setNextSummonPrivateContext(String regionId, org.bukkit.Location marker){
        if (regionId == null || regionId.isBlank() || marker == null || marker.getWorld() == null) {
            summonContext.remove();
            return;
        }
        summonContext.set(new SummonContext(regionId, marker));
    }

    public PetManager(FermerPetsModule plugin){
        this.plugin = plugin;
        this.bootId = plugin.getBootId().toString();

        // init players.yml
        try {
            playersFile = new File(plugin.getDataFolder(), "players.yml");
            playersCfg = YamlConfiguration.loadConfiguration(playersFile);
            playersStore = new com.fermerpets.PlayersStore(playersCfg);
        } catch (Throwable ignored) {
        }

        // playersEGG.yml (spawn eggs) are no longer used.
        // Keep an in-memory config for compatibility but DO NOT create any files on disk.
        this.playersEggFile = null;
        this.playersEggCfg = new org.bukkit.configuration.file.YamlConfiguration();
    }

    

/* ===================== eggs store (GLOBAL) ===================== */
private java.util.List<String> getAllEggs(){
    if (playersEggCfg == null) return new java.util.ArrayList<>();
    java.util.List<String> eggs = playersEggCfg.getStringList("eggs");
    return eggs == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(eggs);
}
private void setAllEggs(java.util.List<String> eggs){
    if (playersEggCfg == null) return;
    if (eggs == null || eggs.isEmpty()){
        playersEggCfg.set("eggs", null);
    } else {
        playersEggCfg.set("eggs", eggs);
    }
    try { if (playersEggFile != null) playersEggCfg.save(playersEggFile); } catch (java.io.IOException ignored){}
}
public boolean eggExists(String eggId){
    if (eggId == null) return false;
    return getAllEggs().contains(eggId);
}
public void addEggRecord(String eggId){
    java.util.List<String> eggs = getAllEggs();
    if (!eggs.contains(eggId)) eggs.add(eggId);
    setAllEggs(eggs);
}
public void removeEggRecord(String eggId){
    java.util.List<String> eggs = getAllEggs();
    eggs.remove(eggId);
    setAllEggs(eggs);
}

/* Backward-compatible overloads (ignore owner) */
public boolean eggExists(java.util.UUID owner, String eggId){
    return eggExists(eggId);
}

public void addEggRecord(java.util.UUID owner, String eggId){
    addEggRecord(eggId);
}

public void removeEggRecord(java.util.UUID owner, String eggId){
    removeEggRecord(eggId);
}


/* ====== one-time migration: flatten players.*.eggs -> eggs (unique) ====== */
private void migratePlayersEggsIfNeeded(){
    if (playersEggCfg == null) return;
    try{
        java.util.Set<String> keys = playersEggCfg.getKeys(false);
        if (keys != null && keys.contains("players")){
            java.util.Set<String> unique = new java.util.HashSet<>(getAllEggs());
            org.bukkit.configuration.ConfigurationSection playersSec = playersEggCfg.getConfigurationSection("players");
            if (playersSec != null){
                for (String ownerKey : playersSec.getKeys(false)){
                    java.util.List<String> lst = playersSec.getStringList(ownerKey + ".eggs");
                    if (lst != null) unique.addAll(lst);
                }
            }
            setAllEggs(new java.util.ArrayList<>(unique));
            playersEggCfg.set("players", null); // remove old tree
            if (playersEggFile != null) playersEggCfg.save(playersEggFile);
        }
    } catch (Throwable ignored){}
}
/* ===================== eggs store ===================== */
private java.util.List<String> getPlayerEggs(java.util.UUID owner){
    if (playersEggCfg == null) return new java.util.ArrayList<>();
    return new java.util.ArrayList<>(playersEggCfg.getStringList("players."+owner+".eggs"));
}
private void setPlayerEggs(java.util.UUID owner, java.util.List<String> eggs){
    if (playersEggCfg == null) return;
    if (eggs == null || eggs.isEmpty()){
        playersEggCfg.set("players."+owner+".eggs", null);
    } else {
        playersEggCfg.set("players."+owner+".eggs", eggs);
    }
    try { if (playersEggFile != null) playersEggCfg.save(playersEggFile); } catch (java.io.IOException ignored){}
}



/* ===================== helpers & config ===================== */

    private int maxPets(){
        try { return plugin.getConfig().getInt("pet.max-pets-per-player", 1); } catch (Throwable t){ return 1; }
    }


/** Resolve Vault prefix using Vault Chat if present. Returns empty string when unavailable. */
private String resolveVaultPrefix(org.bukkit.OfflinePlayer player, org.bukkit.World world){
    if (player == null) return "";
    String vp = "";
    try {
        Class<?> chatClazz = Class.forName("net.milkbowl.vault.chat.Chat");
        org.bukkit.plugin.RegisteredServiceProvider<?> reg =
                org.bukkit.Bukkit.getServicesManager().getRegistration((Class)chatClazz);
        if (reg != null) {
            Object chat = reg.getProvider();
            if (chat != null) {
                // Try world-aware signature first
                try {
                    java.lang.reflect.Method mm = chat.getClass().getMethod("getPlayerPrefix", String.class, org.bukkit.OfflinePlayer.class);
                    Object pref = mm.invoke(chat, (world != null ? world.getName() : ""), player);
                    if (pref != null) vp = String.valueOf(pref);
                } catch (Throwable ignored2){}
                // Fallback
                if (vp.isEmpty()){
                    try {
                        java.lang.reflect.Method mm2 = chat.getClass().getMethod("getPlayerPrefix", org.bukkit.OfflinePlayer.class);
                        Object pref2 = mm2.invoke(chat, player);
                        if (pref2 != null) vp = String.valueOf(pref2);
                    } catch (Throwable ignored2){}
                }
            }
        }
    } catch (Throwable ignored){}
    return vp == null ? "" : vp;
}

/** Build farmer display name from config with Vault prefix replacements. */
private String buildFarmerDisplayName(org.bukkit.OfflinePlayer owner, org.bukkit.World contextWorld){
    String fmt = plugin.getConfig().getString("name.format", "&6Фермер &7(%owner%)");
    String ownerName = owner != null && owner.getName() != null ? owner.getName() : (owner != null ? owner.getUniqueId().toString() : "");
    String vp = resolveVaultPrefix(owner, contextWorld);
    String name = fmt.replace("%owner%", ownerName).replace("%player%", ownerName);
    // support both %...% and /.../ placeholder styles
    name = name.replace("%vault-prefix%", vp).replace("%vault_prefix%", vp)
               .replace("/vault-prefix/", vp).replace("/vault_prefix/", vp);
    return org.bukkit.ChatColor.translateAlternateColorCodes('&', name);
}

    private List<UUID> getActivePetIds(UUID ownerId){
        List<UUID> list = ownerToPets.getOrDefault(ownerId, Collections.emptyList());
        List<UUID> alive = new ArrayList<>();
        for (UUID id : list){
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le && le.isValid() && !le.isDead()) alive.add(id);
        }
        ownerToPets.put(ownerId, new ArrayList<>(alive));
        return alive;
    }

    public boolean hasAnyActivePet(Player owner){
        return !getActivePetIds(owner.getUniqueId()).isEmpty();
    }

    private void savePlayers(){
        try { if (playersCfg != null && playersFile != null) playersCfg.save(playersFile); } catch (IOException ignored){}
    }

    private List<UUID> getPlayerPets(UUID owner){
        if (playersStore != null) return playersStore.getPets(owner);
        List<String> raw = playersCfg.getStringList("players."+owner+".pets");
        List<UUID> out = new ArrayList<>();
        for (String s : raw){ try { out.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored){} }
        return out;
    }

    private void setPlayerPets(UUID owner, List<UUID> ids){
        if (playersStore != null) playersStore.setPets(owner, ids);
        else {
            List<String> raw = new ArrayList<>();
            for (UUID u : ids) raw.add(u.toString());
            playersCfg.set("players."+owner+".pets", raw);
        }
        savePlayers();
    }

    private void addPetRecord(Player owner, UUID petId){
        List<UUID> ids = getPlayerPets(owner.getUniqueId());
        if (!ids.contains(petId)) ids.add(petId);
        setPlayerPets(owner.getUniqueId(), ids);
    }

    private void clearPetRecords(Player owner){
        setPlayerPets(owner.getUniqueId(), new ArrayList<>());
    }

    
    public java.util.UUID getPetIdByOwnerAndIndex(java.util.UUID owner, int index){
        try {
            if (owner == null) return null;
            if (index < 1 || index > 3) return null;

            org.bukkit.configuration.file.FileConfiguration playersCfg = getPlayersYaml();
            com.fermerpets.PlayersStore ps = new com.fermerpets.PlayersStore(playersCfg);

            // migrate legacy list -> fixed slots once
            ps.migrateIfNeeded(owner);

            // If the new fixed-slot schema is present for this owner, DO NOT use the legacy list as a fallback.
            // Legacy list is compacted (without gaps), so falling back would incorrectly map slot #1 -> slot #2, etc.
            boolean hasFixedSchema = false;
            try {
                String base = "players."+owner+".farmers";
                hasFixedSchema = playersCfg.isConfigurationSection(base)
                        || playersCfg.contains(base + ".1")
                        || playersCfg.contains(base + ".2")
                        || playersCfg.contains(base + ".3");
            } catch (Throwable ignored2){}

            java.util.UUID id = ps.getFarmer(owner, index);
            if (id != null) return id;
            if (hasFixedSchema) return null;

            // legacy fallback (only when fixed-slot schema is absent)
            java.util.List<java.util.UUID> ids = ps.getPets(owner);
            if (index >= 1 && index <= ids.size()) return ids.get(index-1);
        } catch (Throwable ignored){}
        return null;
    }
    /* ===================== API ===================== */

    
public ItemStack buildEgg(){
    ItemStack egg = new ItemStack(Material.FOX_SPAWN_EGG);
    ItemMeta m = egg.getItemMeta();
    m.setDisplayName(ChatColor.AQUA + "Яйцо Белой Лисы-Фермера");
    java.util.List<String> lore = new java.util.ArrayList<>();
    lore.add(ChatColor.GRAY + "ПКМ — призвать питомца");
    // генерируем уникальный ID яйца
    String id = java.util.UUID.randomUUID().toString();
    lore.add(ChatColor.DARK_GRAY + "ID: " + id);
    m.setLore(lore);
    egg.setItemMeta(m);
    // записываем ID в PDC
    org.bukkit.persistence.PersistentDataContainer pdc = m.getPersistentDataContainer();
    pdc.set(eggIdKey, org.bukkit.persistence.PersistentDataType.STRING, id);
    egg.setItemMeta(m);
    return egg;
}


    

    public void giveEgg(Player owner){
        ItemStack egg = buildEgg();
        // достаём id из PDC и записываем в playersEGG
        String id = null;
        try {
            id = egg.getItemMeta().getPersistentDataContainer().get(eggIdKey, org.bukkit.persistence.PersistentDataType.STRING);
        } catch (Throwable ignored){}
        if (id != null) addEggRecord(owner.getUniqueId(), id);
        java.util.Map<Integer, ItemStack> leftover = owner.getInventory().addItem(egg);
        if (leftover != null && !leftover.isEmpty()){
            for (ItemStack st : leftover.values()){
                owner.getWorld().dropItemNaturally(owner.getLocation(), st);
            }
            owner.sendMessage(ChatColor.GOLD + "Инвентарь заполнен — яйцо брошено рядом.");
        } else {
            owner.sendMessage(ChatColor.GREEN + "Вы получили яйцо призыва фермера.");
        }
    }

// removed stray line
// removed stray line
// removed stray line
// removed stray line


    public void summonPet(Player owner){
        if (owner == null) return;

        // Use players.yml records (fixed slots) to enforce limits even after /reload
        PlayersStore ps = new PlayersStore(playersCfg);
        ps.migrateIfNeeded(owner.getUniqueId());

        int idx = -1;
        try { idx = summonIndex.get() != null ? summonIndex.get() : -1; } catch (Throwable ignored){}
        if (idx < 1 || idx > 3){
            idx = ps.firstFreeIndex(owner.getUniqueId());
        }
        if (idx < 1){
            owner.sendMessage(ChatColor.RED + "Все 3 фермера уже призваны.");
            return;
        }
        // do not allow summon into occupied slot
        if (ps.getFarmer(owner.getUniqueId(), idx) != null){
            owner.sendMessage(ChatColor.RED + "Фермер #"+idx+" уже активен.");
            return;
        }
        if (ps.countFarmers(owner.getUniqueId()) >= maxPets()){
            owner.sendMessage(ChatColor.RED + "Достигнут лимит питомцев ("+maxPets()+")");
            return;
        }

        // spawn type
        EntityType type = EntityType.ALLAY;
        try { type = EntityType.valueOf(plugin.getConfig().getString("pet.type", "ALLAY")); } catch (IllegalArgumentException ignored){}

        // Determine spawn location: farmers are bound to private marker (if provided via menu context)
org.bukkit.Location spawnLoc = owner.getLocation().add(0,0,1);
SummonContext _ctx0 = null;
try { _ctx0 = summonContext.get(); } catch (Throwable ignored) {}
if (_ctx0 != null && _ctx0.marker != null && _ctx0.marker.getWorld() != null){
    spawnLoc = _ctx0.marker.clone().add(0.5, 1.0, 0.5);
}

        Entity e = spawnLoc.getWorld().spawnEntity(spawnLoc, type);
        if (!(e instanceof LivingEntity le)) return;

        // optional fox skin
        if (e instanceof Fox fx){ fx.setFoxType(Fox.Type.SNOW); }

// Ensure entity AI is enabled (some servers/spawn reasons may disable it)
try {
    le.setAI(true);
    if (le instanceof Villager v){
        v.setProfession(Villager.Profession.FARMER);
        v.setAdult();
        v.setCanPickupItems(false);
    }
} catch (Throwable ignored) {}

        // Name formatting (supports Vault prefix) – stable across /reload
try {
    le.setCustomName(buildFarmerDisplayName(owner, le.getWorld()));
} catch (Throwable ignored) {}
le.setCustomNameVisible(true);
        le.setInvulnerable(true);
        le.setPersistent(true);

        // PDC marks
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        int actCount = getActivePetIds(owner.getUniqueId()).size();
        int var = (actCount % 2 == 0) ? 1 : 2;
        pdc.set(farmerVariantKey, org.bukkit.persistence.PersistentDataType.INTEGER, var);
        pdc.set(farmerWalkOnlyKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);
        pdc.set(farmerIndexKey, org.bukkit.persistence.PersistentDataType.INTEGER, idx);


        pdc.set(petKey, PersistentDataType.BYTE, (byte)1);
        pdc.set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        try { pdc.set(sessionKey, org.bukkit.persistence.PersistentDataType.STRING, bootId); recordPetHint(le); } catch (Throwable ignored){}

        // Привязка к привату (если summon инициирован из меню привата)
        try {
            SummonContext ctx = summonContext.get();
            if (ctx != null && ctx.regionId != null && ctx.marker != null && ctx.marker.getWorld() != null) {
                pdc.set(privateRegionKey, org.bukkit.persistence.PersistentDataType.STRING, ctx.regionId);
                String markerStr = ctx.marker.getWorld().getUID()+":"+ctx.marker.getBlockX()+":"+ctx.marker.getBlockY()+":"+ctx.marker.getBlockZ();
                pdc.set(privateMarkerKey, org.bukkit.persistence.PersistentDataType.STRING, markerStr);
                // store stable binding by owner+slot (used after reload to respawn at the correct private block)
                try {
                    PlayersStore _psBind = new PlayersStore(playersCfg);
                    _psBind.migrateIfNeeded(owner.getUniqueId());
                    _psBind.setBinding(owner.getUniqueId(), idx, ctx.regionId, ctx.marker);
                } catch (Throwable ignored3) {}
                // persist to playershoppers.yml so we can cleanup by region even after restart
                try {
                    PlayersHoppersStore phs = new PlayersHoppersStore(plugin);
                    phs.setPrivateBindingByPet(le.getUniqueId(), ctx.regionId, ctx.marker);
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}
        finally { summonContext.remove(); summonIndex.remove(); }
        try { pdc.set(sessionKey, org.bukkit.persistence.PersistentDataType.STRING, bootId); recordPetHint(le); } catch (Throwable ignored){}

        // Start brain AFTER PDC is filled (region/marker/index/variant)
        // Pass live owner when possible (fixes edge cases where some logic relied on owner!=null)
        startPetBrain(le, owner);

        // track
        ownerToPets.computeIfAbsent(owner.getUniqueId(), k -> new ArrayList<>()).add(le.getUniqueId());
        try {
            PlayersStore ps2 = new PlayersStore(playersCfg);
            ps2.migrateIfNeeded(owner.getUniqueId());
            ps2.setFarmer(owner.getUniqueId(), idx, le.getUniqueId());
            savePlayers();
        } catch (Throwable ignored) {
            addPetRecord(owner, le.getUniqueId());
        }
    }

    /**
     * Summon a farmer for a specific owner UUID (private owner), using the actor only for feedback and as fallback
     * spawn location when no private marker context is provided.
     */
    public void summonPetForOwner(java.util.UUID ownerId, org.bukkit.entity.Player actor){
        if (ownerId == null || actor == null) return;

        PlayersStore ps = new PlayersStore(playersCfg);
        ps.migrateIfNeeded(ownerId);

        int idx = -1;
        try { idx = summonIndex.get() != null ? summonIndex.get() : -1; } catch (Throwable ignored){}
        if (idx < 1 || idx > 3) idx = ps.firstFreeIndex(ownerId);
        if (idx < 1){
            actor.sendMessage(ChatColor.RED + "Все 3 фермера уже призваны.");
            return;
        }
        if (ps.getFarmer(ownerId, idx) != null){
            actor.sendMessage(ChatColor.RED + "Фермер #"+idx+" уже активен.");
            return;
        }

        // spawn type
        EntityType type = EntityType.ALLAY;
        try { type = EntityType.valueOf(plugin.getConfig().getString("pet.type", "ALLAY")); } catch (IllegalArgumentException ignored){}

        // Determine spawn location: prefer private marker context
        org.bukkit.Location spawnLoc = actor.getLocation().add(0,0,1);
        SummonContext _ctx0 = null;
        try { _ctx0 = summonContext.get(); } catch (Throwable ignored) {}
        if (_ctx0 != null && _ctx0.marker != null && _ctx0.marker.getWorld() != null){
            spawnLoc = _ctx0.marker.clone().add(0.5, 1.0, 0.5);
        }

        Entity e = spawnLoc.getWorld().spawnEntity(spawnLoc, type);
        if (!(e instanceof LivingEntity le)) return;
        if (e instanceof Fox fx){ fx.setFoxType(Fox.Type.SNOW); }

        // Ensure entity AI is enabled
        try {
            le.setAI(true);
            if (le instanceof Villager v){
                v.setProfession(Villager.Profession.FARMER);
                v.setAdult();
                v.setCanPickupItems(false);
            }
        } catch (Throwable ignored) {}

        org.bukkit.OfflinePlayer off = org.bukkit.Bukkit.getOfflinePlayer(ownerId);
        String ownerName = off != null && off.getName() != null ? off.getName() : ownerId.toString();

        // Name formatting (supports Vault prefix) – stable across /reload and for offline owners
try {
    le.setCustomName(buildFarmerDisplayName(off, le.getWorld()));
} catch (Throwable ignored) {}
le.setCustomNameVisible(true);
        le.setInvulnerable(true);
        le.setPersistent(true);

        PersistentDataContainer pdc = le.getPersistentDataContainer();
        int actCount = getActivePetIds(ownerId).size();
        int var = (actCount % 2 == 0) ? 1 : 2;
        pdc.set(farmerVariantKey, org.bukkit.persistence.PersistentDataType.INTEGER, var);
        pdc.set(farmerWalkOnlyKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);
        pdc.set(farmerIndexKey, org.bukkit.persistence.PersistentDataType.INTEGER, idx);

        pdc.set(petKey, PersistentDataType.BYTE, (byte)1);
        pdc.set(ownerKey, PersistentDataType.STRING, ownerId.toString());
        try { pdc.set(sessionKey, org.bukkit.persistence.PersistentDataType.STRING, bootId); recordPetHint(le); } catch (Throwable ignored){}

        // Bind to private context if provided
        try {
            SummonContext ctx = summonContext.get();
            if (ctx != null && ctx.regionId != null && ctx.marker != null && ctx.marker.getWorld() != null) {
                pdc.set(privateRegionKey, org.bukkit.persistence.PersistentDataType.STRING, ctx.regionId);
                String markerStr = ctx.marker.getWorld().getUID()+":"+ctx.marker.getBlockX()+":"+ctx.marker.getBlockY()+":"+ctx.marker.getBlockZ();
                pdc.set(privateMarkerKey, org.bukkit.persistence.PersistentDataType.STRING, markerStr);
                try {
                    PlayersStore _psBind = new PlayersStore(playersCfg);
                    _psBind.migrateIfNeeded(ownerId);
                    _psBind.setBinding(ownerId, idx, ctx.regionId, ctx.marker);
                } catch (Throwable ignored3) {}
                try {
                    PlayersHoppersStore phs = new PlayersHoppersStore(plugin);
                    phs.setPrivateBindingByPet(le.getUniqueId(), ctx.regionId, ctx.marker);
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}
        finally { summonContext.remove(); summonIndex.remove(); }

        // Start brain AFTER PDC is filled (region/marker/index/variant)
        // Use actor as a live owner if the private owner is offline; brain will still behave as a farmer
        // because it follows the private marker, not the player.
        startPetBrain(le, actor);

        ownerToPets.computeIfAbsent(ownerId, k -> new java.util.ArrayList<>()).add(le.getUniqueId());
        try {
            PlayersStore ps2 = new PlayersStore(playersCfg);
            ps2.migrateIfNeeded(ownerId);
            ps2.setFarmer(ownerId, idx, le.getUniqueId());
            savePlayers();
            // Rebind any persisted hopper/beacon placements (stored by owner/index) onto this new petId
            try {
                com.fermerpets.PlayersHoppersStore _store = new com.fermerpets.PlayersHoppersStore(plugin);
                _store.rebindPersistedToPet(ownerId, idx, le.getUniqueId());
            } catch (Throwable ignored2) {}
        } catch (Throwable ignored) {
            // legacy fallback is owner-online only; ignore
        }
    }

    /** Unsummon a farmer for a specific owner UUID (works even if the owner is offline). */
    public boolean unsummonAndCleanup(java.util.UUID ownerId, int index){
        if (ownerId == null) return false;
        if (index < 1 || index > 3) return false;

        java.util.UUID petId = getPetIdByOwnerAndIndex(ownerId, index);
        if (petId == null) return false;

        // IMPORTANT:
        // When removing a farmer from the menu (slot 19 / 22 / 25), we must NOT remove placed hopper/beacon blocks.
        // Those blocks must only be removed via the dedicated delete button (slot 32).
        // Here we only detach petId-based bindings so that a future spawn can re-bind owner/index to the new petId.
        try {
            com.fermerpets.PlayersHoppersStore store = new com.fermerpets.PlayersHoppersStore(plugin);
            try { store.clearHopperByPet(petId); } catch (Throwable ignored) {}
            try { store.clearBeaconByPet(petId); } catch (Throwable ignored) {}
            try { store.remove(petId); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        // Remove entity if loaded
        try {
            org.bukkit.entity.Entity ent = org.bukkit.Bukkit.getEntity(petId);
            if (ent != null) {
                stopPetBrain(petId);
                ent.remove();
            }
        } catch (Throwable ignored) {}

        // Clear players.yml slot
        try {
            PlayersStore ps = new PlayersStore(playersCfg);
            ps.migrateIfNeeded(ownerId);
            ps.setFarmer(ownerId, index, null);
            savePlayers();
        } catch (Throwable ignored) {}

        // Update in-memory map
        try {
            java.util.List<java.util.UUID> list = ownerToPets.get(ownerId);
            if (list != null) list.remove(petId);
        } catch (Throwable ignored) {}

        return true;
    }
    public void unsummonAll(Player owner){
        List<UUID> ids = ownerToPets.remove(owner.getUniqueId());
        if (ids != null){
            for (UUID id : ids){
                Entity e = Bukkit.getEntity(id);
                if (e != null){ stopPetBrain(e.getUniqueId()); e.remove(); }
            }
        }
        // cleanup strays
        for (Entity e : owner.getWorld().getNearbyEntities(owner.getLocation(), 64,64,64)){
            if (e instanceof LivingEntity le && isOurPet(le) && isOwner(le, owner)){
                e.remove();
            }
        }
        clearPetRecords(owner);
    }

    public boolean isOurPet(LivingEntity le){
        return le.getPersistentDataContainer().has(petKey, PersistentDataType.BYTE);
    }

    public boolean isOwner(LivingEntity le, Player p){
        if (le == null || p == null) return false;
        String s = le.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return s != null && s.equalsIgnoreCase(p.getUniqueId().toString());
    }

    public Player getOwner(LivingEntity le){
        if (le == null) return null;
        String s = le.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Bukkit.getPlayer(UUID.fromString(s)); } catch (IllegalArgumentException e){ return null; }
    }

    public void shutdown(){
        // Server requirement:
        // When the plugin is disabled (/reload, restart, PlugMan, etc.),
        // we MUST remove all spawned farmers from the world to prevent "ghost" entities.
        // They will be re-spawned on next enable via reconcileFarmersAfterReload().

        // Stop brains first (prevents task spam while removing entities)
        try {
            for (java.util.UUID pid : new java.util.ArrayList<>(brainTasks.keySet())){
                try { stopPetBrain(pid); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        brainTasks.clear();
        ownerToPets.clear();

        // Remove all loaded pet entities (farmers)
        try {
            for (org.bukkit.World w : plugin.getServer().getWorlds()){
                for (org.bukkit.entity.Entity ent : new java.util.ArrayList<>(w.getEntities())){
                    if (!(ent instanceof org.bukkit.entity.LivingEntity le)) continue;
                    try {
                        org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();
                        Byte flag = pdc.get(petKey, org.bukkit.persistence.PersistentDataType.BYTE);
                        if (flag == null || flag != (byte)1) continue;
                        try { stopPetBrain(le.getUniqueId()); } catch (Throwable ignored) {}
                        le.remove();
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

	public org.bukkit.configuration.file.FileConfiguration getPlayersYaml(){ return playersCfg; }

	/**
	 * Returns the in-memory players store.
	 * IMPORTANT: do not create new PlayersStore instances (disk IO) inside hot events like ChunkLoad.
	 */
	public PlayersStore getPlayersStore(){ return playersStore; }

/**
 * Reconcile farmers after plugin reload/start.
 * Goals:
 *  - prevent duplicates (do not spawn if entity already exists)
 *  - if slot is inactive in players.yml, remove any stray farmer entities on chunk load
 *  - if slot is active but entity is missing, respawn at the bound private marker (NOT near player)
 */
public void reconcileFarmersAfterReload(){
    try {
        // ensure latest schema
        PlayersStore ps = new PlayersStore(playersCfg);

        // Scan loaded entities and group by (owner,index)
        Map<UUID, Map<Integer, List<org.bukkit.entity.LivingEntity>>> found = new HashMap<>();
        for (org.bukkit.World w : plugin.getServer().getWorlds()){
            for (org.bukkit.entity.Entity ent : w.getEntities()){
                if (!(ent instanceof org.bukkit.entity.LivingEntity le)) continue;
                try {
                    org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();
                    Byte flag = pdc.get(petKey, org.bukkit.persistence.PersistentDataType.BYTE);
                    if (flag == null || flag != (byte)1) continue;
                    String ownerStr = pdc.get(ownerKey, org.bukkit.persistence.PersistentDataType.STRING);
                    if (ownerStr == null || ownerStr.isBlank()) continue;
                    UUID oid;
                    try { oid = UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }
                    Integer idx = pdc.get(farmerIndexKey, org.bukkit.persistence.PersistentDataType.INTEGER);
                    if (idx == null || idx < 1 || idx > 3) idx = 1;
                    found.computeIfAbsent(oid, k -> new HashMap<>())
                            .computeIfAbsent(idx, k -> new ArrayList<>())
                            .add(le);
                } catch (Throwable ignored) {}
            }
        }

        // Iterate owners from players.yml
        org.bukkit.configuration.ConfigurationSection sec = playersCfg.getConfigurationSection("players");
        if (sec != null){
            for (String ownerStr : sec.getKeys(false)){
                UUID oid;
                try { oid = UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }
                ps.migrateIfNeeded(oid);

                Map<Integer, List<org.bukkit.entity.LivingEntity>> perIdx = found.getOrDefault(oid, Collections.emptyMap());
                for (int idx=1; idx<=3; idx++){
                    UUID expected = ps.getFarmer(oid, idx);
                    List<org.bukkit.entity.LivingEntity> list = perIdx.getOrDefault(idx, Collections.emptyList());

                    // If slot inactive -> remove any loaded entities for this slot
                    if (expected == null){
                        for (org.bukkit.entity.LivingEntity le : list){
                            try { stopPetBrain(le.getUniqueId()); } catch (Throwable ignored) {}
                            try { le.remove(); } catch (Throwable ignored) {}
                        }
                        continue;
                    }

                    // Slot active: choose one to keep
                    org.bukkit.entity.LivingEntity keep = null;
                    for (org.bukkit.entity.LivingEntity le : list){
                        if (le.getUniqueId().equals(expected)) { keep = le; break; }
                    }
                    if (keep == null && !list.isEmpty()){
                        // record points to missing/old uuid, but entity exists -> keep first and update record
                        keep = list.get(0);
                    }

                    // remove duplicates
                    for (org.bukkit.entity.LivingEntity le : list){
                        if (keep != null && le.getUniqueId().equals(keep.getUniqueId())) continue;
                        try { stopPetBrain(le.getUniqueId()); } catch (Throwable ignored) {}
                        try { le.remove(); } catch (Throwable ignored) {}
                    }

                    if (keep != null){
                        // update players.yml to kept uuid if needed
                        if (!keep.getUniqueId().equals(expected)){
                            // mark old expected for removal if it appears later
                            try { killOnSight.add(expected); } catch (Throwable ignored) {}
                            ps.setFarmer(oid, idx, keep.getUniqueId());
                        }
                        // rebuild active cache
                        ownerToPets.computeIfAbsent(oid, k -> new ArrayList<>());
                        if (!ownerToPets.get(oid).contains(keep.getUniqueId())) ownerToPets.get(oid).add(keep.getUniqueId());

                        // Ensure correct appearance, PDC and location (spawn at private marker, not near player)
                        try {
                            PlayersStore _ps2 = new PlayersStore(playersCfg);
                            _ps2.migrateIfNeeded(oid);
                            org.bukkit.Location marker = _ps2.getBindingMarker(oid, idx);
                            String rid = _ps2.getBindingRegionId(oid, idx);
                            normalizeFarmerEntity(keep, oid, idx, rid, marker);
                        } catch (Throwable ignoredNorm) {}

                        // ensure binding saved in playershoppers.yml (for respawn)
                        try {
                            PlayersHoppersStore phs = new PlayersHoppersStore(plugin);
                            String rid = keep.getPersistentDataContainer().get(privateRegionKey, org.bukkit.persistence.PersistentDataType.STRING);
                            String markerStr = keep.getPersistentDataContainer().get(privateMarkerKey, org.bukkit.persistence.PersistentDataType.STRING);
                            if (markerStr != null && !markerStr.isBlank()){
                                String[] parts = markerStr.split(":");
                                if (parts.length == 4){
                                    java.util.UUID wid = java.util.UUID.fromString(parts[0]);
                                    org.bukkit.World bw = org.bukkit.Bukkit.getWorld(wid);
                                    if (bw != null){
                                        int mx = Integer.parseInt(parts[1]);
                                        int my = Integer.parseInt(parts[2]);
                                        int mz = Integer.parseInt(parts[3]);
                                        org.bukkit.Location ml = new org.bukkit.Location(bw, mx, my, mz);
                                        phs.setPrivateBindingByPet(keep.getUniqueId(), rid, ml);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    } else {
                        // No entity loaded for this active slot. Try respawn at stored private marker.
                        try {
                            PlayersStore _ps3 = new PlayersStore(playersCfg);
                            _ps3.migrateIfNeeded(oid);
                            org.bukkit.Location marker = _ps3.getBindingMarker(oid, idx);
                            String rid = _ps3.getBindingRegionId(oid, idx);
                            if (marker == null || marker.getWorld() == null){
                                // fallback to old per-pet binding if present
                                PlayersHoppersStore phs = new PlayersHoppersStore(plugin);
                                marker = phs.getPrivateMarkerByPet(expected);
                                rid = rid != null ? rid : phs.getPrivateRegionIdByPet(expected);
                            }
                            if (marker != null && marker.getWorld() != null){
                                spawnFarmerAt(oid, idx, marker, rid, expected);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }

        savePlayers();
    } catch (Throwable ignored) {}
}

private void spawnFarmerAt(UUID ownerId, int idx, org.bukkit.Location marker, String regionId, UUID oldExpected){
    if (ownerId == null || marker == null || marker.getWorld() == null) return;
    if (idx < 1 || idx > 3) return;
    org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(ownerId);
    String ownerName = op != null && op.getName() != null ? op.getName() : ownerId.toString();

    org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.ALLAY;
    try { type = org.bukkit.entity.EntityType.valueOf(plugin.getConfig().getString("pet.type", "ALLAY")); } catch (IllegalArgumentException ignored) {}

    org.bukkit.Location spawnLoc = marker.clone().add(0.5, 1.0, 0.5);
    org.bukkit.entity.Entity e = spawnLoc.getWorld().spawnEntity(spawnLoc, type);
    if (!(e instanceof org.bukkit.entity.LivingEntity le)) return;

    // Apply common settings immediately
    try {
        if (e instanceof org.bukkit.entity.Fox fx){ fx.setFoxType(org.bukkit.entity.Fox.Type.SNOW); }
        if (e instanceof org.bukkit.entity.Villager v){
            v.setProfession(org.bukkit.entity.Villager.Profession.FARMER);
            v.setAdult();
            v.setCanPickupItems(false);
        }
        le.setAI(true);
    } catch (Throwable ignored) {}

    // name
    try {
        le.setCustomName(buildFarmerDisplayName(op, le.getWorld()));
    } catch (Throwable ignored) {}
    le.setCustomNameVisible(true);
    le.setInvulnerable(true);
    le.setPersistent(true);

    // PDC marks
    org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();
    pdc.set(petKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);
    pdc.set(ownerKey, org.bukkit.persistence.PersistentDataType.STRING, ownerId.toString());
    pdc.set(farmerIndexKey, org.bukkit.persistence.PersistentDataType.INTEGER, idx);
    if (regionId != null && !regionId.isBlank()){
        pdc.set(privateRegionKey, org.bukkit.persistence.PersistentDataType.STRING, regionId);
    }
    String markerStr = marker.getWorld().getUID()+":"+marker.getBlockX()+":"+marker.getBlockY()+":"+marker.getBlockZ();
    pdc.set(privateMarkerKey, org.bukkit.persistence.PersistentDataType.STRING, markerStr);
    try { pdc.set(sessionKey, org.bukkit.persistence.PersistentDataType.STRING, bootId); recordPetHint(le); } catch (Throwable ignored) {}

    // start AI with null player (farmers do not follow players)
    try { startPetBrain(le, null); } catch (Throwable ignored) {}

    // update storage: players.yml and playershoppers.yml (and move old petId records if needed)
    try {
        PlayersStore ps = new PlayersStore(playersCfg);
        ps.migrateIfNeeded(ownerId);
        ps.setFarmer(ownerId, idx, le.getUniqueId());
        ps.setBinding(ownerId, idx, regionId, marker);
        savePlayers();
    } catch (Throwable ignored) {}

    try {
        PlayersHoppersStore phs = new PlayersHoppersStore(plugin);
        phs.setPrivateBindingByPet(le.getUniqueId(), regionId, marker);
        // If we replaced an old expected uuid, move its records to the new uuid
        if (oldExpected != null && !oldExpected.equals(le.getUniqueId())){
            try {
                if (phs.cfg.isConfigurationSection(oldExpected.toString())){
                    java.util.Map<String,Object> vals = phs.cfg.getConfigurationSection(oldExpected.toString()).getValues(true);
                    for (java.util.Map.Entry<String,Object> en : vals.entrySet()){
                        phs.cfg.set(le.getUniqueId().toString()+"."+en.getKey(), en.getValue());
                    }
                    phs.cfg.set(oldExpected.toString(), null);
                    phs.save();
                }
            } catch (Throwable ignored2) {}
        }
    } catch (Throwable ignored) {}

    ownerToPets.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(le.getUniqueId());
}

/**
 * After reload we may keep an already spawned entity.
 * This method enforces: correct PDC keys, correct name, invulnerable/persistent,
 * correct binding to private marker and correct location (near marker, not near player).
 */
private void normalizeFarmerEntity(org.bukkit.entity.LivingEntity le, java.util.UUID ownerId, int idx, String regionId, org.bukkit.Location marker){
    if (le == null || ownerId == null) return;
    if (idx < 1 || idx > 3) return;

    // If entity type differs from config, replace it (cannot change type in-place)
    try {
        org.bukkit.entity.EntityType expectedType = org.bukkit.entity.EntityType.ALLAY;
        try { expectedType = org.bukkit.entity.EntityType.valueOf(plugin.getConfig().getString("pet.type", "ALLAY")); } catch (IllegalArgumentException ignored) {}
        if (le.getType() != expectedType && marker != null && marker.getWorld() != null){
            java.util.UUID old = le.getUniqueId();
            try { stopPetBrain(old); } catch (Throwable ignored) {}
            try { le.remove(); } catch (Throwable ignored) {}
            // Respawn correct type and update records to the new UUID
            spawnFarmerAt(ownerId, idx, marker, regionId, old);
            return;
        }
    } catch (Throwable ignored) {}

    // Ensure correct display name (supports Vault prefix)
try {
    org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(ownerId);
    le.setCustomName(buildFarmerDisplayName(op, le.getWorld()));
    le.setCustomNameVisible(true);
} catch (Throwable ignored) {}

    // Entity-specific normalization
    try {
        if (le instanceof org.bukkit.entity.Fox fx){ fx.setFoxType(org.bukkit.entity.Fox.Type.SNOW); }
        if (le instanceof org.bukkit.entity.Villager v){
            v.setProfession(org.bukkit.entity.Villager.Profession.FARMER);
            v.setAdult();
            v.setCanPickupItems(false);
        }
        try { le.setAI(true); } catch (Throwable ignored2) {}
    } catch (Throwable ignored) {}

    le.setInvulnerable(true);
    le.setPersistent(true);

    // PDC keys
    try {
        org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();
        pdc.set(petKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);
        pdc.set(ownerKey, org.bukkit.persistence.PersistentDataType.STRING, ownerId.toString());
        pdc.set(farmerIndexKey, org.bukkit.persistence.PersistentDataType.INTEGER, idx);
        if (regionId != null && !regionId.isBlank()) pdc.set(privateRegionKey, org.bukkit.persistence.PersistentDataType.STRING, regionId);
        if (marker != null && marker.getWorld() != null){
            String markerStr = marker.getWorld().getUID()+":"+marker.getBlockX()+":"+marker.getBlockY()+":"+marker.getBlockZ();
            pdc.set(privateMarkerKey, org.bukkit.persistence.PersistentDataType.STRING, markerStr);
        }
        pdc.set(sessionKey, org.bukkit.persistence.PersistentDataType.STRING, bootId);
    } catch (Throwable ignored) {}

    // Ensure brain uses NULL player (no following)
    try { startPetBrain(le, null); } catch (Throwable ignored) {}

    // Move to marker if needed
    try {
        if (marker != null && marker.getWorld() != null){
            org.bukkit.Location desired = marker.clone().add(0.5, 1.0, 0.5);
            if (!le.getWorld().equals(desired.getWorld()) || le.getLocation().distanceSquared(desired) > (16.0*16.0)){
                le.teleport(desired);
            }
        }
    } catch (Throwable ignored) {}
}


    /* ===================== AUTO-RESUMMON HELPERS ===================== */
    /** Return how many pets are recorded in players.yml for this owner. */
    public int countRecordedPets(java.util.UUID ownerId){
        try {
            if (ownerId == null) return 0;
            PlayersStore store = new PlayersStore(playersCfg);
            store.migrateIfNeeded(ownerId);
            return store.countFarmers(ownerId);
        } catch (Throwable t){ return 0; }
    }
    /** How many pets are currently active (spawned entities) for owner. */
    public int countActivePets(java.util.UUID ownerId){
        java.util.List<java.util.UUID> list = ownerToPets.get(ownerId);
        return list == null ? 0 : list.size();
    }
    /** Public accessor for max pets limit from config. */
    public org.bukkit.NamespacedKey getPetKey(){ return petKey; }
    public org.bukkit.NamespacedKey getOwnerKey(){ return ownerKey; }
    public org.bukkit.NamespacedKey getSessionKey(){ return sessionKey; }
    public int getMaxPets(){
        try { return maxPets(); } catch (Throwable ignored){ return 1; }
    }
    /** Summon a pet near owner but do NOT write any record into players.yml. */
    public void summonPetNoRecord(org.bukkit.entity.Player owner){
        // enforce limit based on active count only
        java.util.List<java.util.UUID> current = getActivePetIds(owner.getUniqueId());
        if (current.size() >= getMaxPets()){
            owner.sendMessage(org.bukkit.ChatColor.RED + "Достигнут лимит питомцев ("+getMaxPets()+")");
            return;
        }
        // Copy of summon logic stripped of addPetRecord
        org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.ALLAY;
        try { type = org.bukkit.entity.EntityType.valueOf(plugin.getConfig().getString("pet.type", "ALLAY")); } catch (IllegalArgumentException ignored){}
        org.bukkit.entity.Entity e = owner.getWorld().spawnEntity(owner.getLocation().add(0,0,1), type);
        if (!(e instanceof org.bukkit.entity.LivingEntity le)) return;
        // === Type-specific setup to keep pet peaceful and functional ===
        try {
            if (e instanceof org.bukkit.entity.Villager v){
                v.setProfession(org.bukkit.entity.Villager.Profession.FARMER);
                v.setAdult();
                v.setAI(true);
                v.setCollidable(false);
                v.setVillagerLevel(1);
                v.setVillagerExperience(0);
                v.setCanPickupItems(false);
            }
        } catch (Throwable ignored){}
        try {
            if (e instanceof org.bukkit.entity.Mob m){
                m.setTarget(null);
                m.setAware(true);
            }
        } catch (Throwable ignored){}
        try {
            if (e instanceof org.bukkit.entity.Enderman en){
                en.setCarriedBlock(null);
                en.setSilent(true);
            }
        } catch (Throwable ignored){}
        try {
            if (e instanceof org.bukkit.entity.Spider sp){
                sp.setSilent(true);
            }
        } catch (Throwable ignored){}
    
        if (e instanceof org.bukkit.entity.Fox fx){ fx.setFoxType(org.bukkit.entity.Fox.Type.SNOW); }
        // Build name WITHOUT PlaceholderAPI dependency (works even after /reload and when owner is offline).
        // We resolve Vault prefix using OfflinePlayer.
        String fmt = plugin.getConfig().getString("name.format", "&6Фермер &7(%owner%)");
        org.bukkit.OfflinePlayer op = owner;
        String ownerName = (op != null && op.getName() != null) ? op.getName() : owner.getUniqueId().toString();
        String name = fmt.replace("%owner%", ownerName).replace("%player%", ownerName);
        // Resolve Vault prefix placeholders
        try {
            Class<?> vault = Class.forName("net.milkbowl.vault.chat.Chat");
            org.bukkit.plugin.RegisteredServiceProvider<?> rsp = plugin.getServer().getServicesManager().getRegistration(vault);
            if (rsp != null && rsp.getProvider() != null){
                Object chat = rsp.getProvider();
                try {
                    java.lang.reflect.Method gp = chat.getClass().getMethod("getPlayerPrefix", String.class, org.bukkit.OfflinePlayer.class);
                    Object pref = gp.invoke(chat, owner.getWorld().getName(), owner);
                    String vp = pref == null ? "" : String.valueOf(pref);
                    name = name.replace("%vault-prefix%", vp).replace("%vault_prefix%", vp);
                } catch (Throwable ignored2){}
            }
        } catch (Throwable ignored2) {}
        le.setCustomName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
        le.setCustomNameVisible(true);
        le.setInvulnerable(true);
        le.setPersistent(true);
        org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();

        pdc.set(petKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);
        pdc.set(ownerKey, org.bukkit.persistence.PersistentDataType.STRING, owner.getUniqueId().toString());
        try { pdc.set(sessionKey, org.bukkit.persistence.PersistentDataType.STRING, bootId); recordPetHint(le); } catch (Throwable ignored){}
        try { pdc.set(sessionKey, org.bukkit.persistence.PersistentDataType.STRING, bootId); recordPetHint(le); } catch (Throwable ignored){}
        ownerToPets.computeIfAbsent(owner.getUniqueId(), k -> new java.util.ArrayList<>()).add(le.getUniqueId());
        // Farmers must not be bound to the player AI-wise (no follow, no inventory delivery).
        // We run the brain with owner=null and rely on private marker + regionId.
        startPetBrain(le, null);
    }
    /** Ensure that number of active pets equals the recorded count (up to max). Does NOT write to players.yml. */
    public void ensureActiveFromRecords(org.bukkit.entity.Player owner){
        int target = Math.min(countRecordedPets(owner.getUniqueId()), getMaxPets());
        int active = countActivePets(owner.getUniqueId());
        for (int i = active; i < target; i++){
            summonPetNoRecord(owner);
        }
    }
    /** Force re-create all pets from records (despawn then ensureActiveFromRecords). */
    public void resummonAllFromRecords(org.bukkit.entity.Player owner){
        unsummonAllEntitiesOnly(owner);
        ensureActiveFromRecords(owner);
    }

    /** Despawn all active pet entities for owner, but DO NOT touch players.yml records.
 *  Scans ALL loaded worlds and matches by PDC ownerKey (safer than only UUID cache).
 */
public void unsummonAllEntitiesOnly(org.bukkit.entity.Player owner){
    java.util.UUID oid = owner.getUniqueId();
    // Remove by cached UUIDs if present (fast path)
    java.util.List<java.util.UUID> ids = ownerToPets.get(oid);
    if (ids != null && !ids.isEmpty()){
        for (org.bukkit.World w : plugin.getServer().getWorlds()){
            for (org.bukkit.entity.Entity ent : w.getEntities()){
                try {
                    if (ids.contains(ent.getUniqueId())) { stopPetBrain(ent.getUniqueId()); ent.remove(); }
                } catch (Throwable ignored){}
            }
        }
    }
    // Safety pass: remove any entity that carries our PDC marks with this owner id
    for (org.bukkit.World w : plugin.getServer().getWorlds()){
        for (org.bukkit.entity.Entity ent : w.getEntities()){
            if (!(ent instanceof org.bukkit.entity.LivingEntity le)) continue;
            try {
                org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();

                Byte flag = pdc.get(petKey, org.bukkit.persistence.PersistentDataType.BYTE);
                String ownerStr = pdc.get(ownerKey, org.bukkit.persistence.PersistentDataType.STRING);
                if (flag != null && flag == (byte)1 && ownerStr != null && ownerStr.equals(oid.toString())){
                    stopPetBrain(ent.getUniqueId()); ent.remove();
                }
            } catch (Throwable ignored){}
        }
    }
    ownerToPets.remove(oid); // clear active cache
}

    /** Returns true if owner can summon one more pet (active count < max). */
    public boolean hasFreeSlot(org.bukkit.entity.Player owner){
        java.util.List<java.util.UUID> current = getActivePetIds(owner.getUniqueId());
        int limit = getMaxPets();
        return current.size() < limit;
    }
    
    
    private static int resolveFarmerIndex(FermerPetsModule plugin, java.util.UUID owner, org.bukkit.entity.LivingEntity pet){
        try {
            org.bukkit.configuration.file.FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
            com.fermerpets.PlayersStore ps = new com.fermerpets.PlayersStore(playersCfg);
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
    /** Start unified AI tick for this pet entity. */
    private void startPetBrain(org.bukkit.entity.LivingEntity le, org.bukkit.entity.Player owner){
        // owner may be null: farmers are bound to a private marker and must not follow a player
        Debug.logPet(le, "startPetBrain for owner=" + (owner == null ? "null" : owner.getName()));
        final java.util.UUID pid = le.getUniqueId();
        if (brainTasks.containsKey(pid)) return;
        final boolean fly = plugin.getConfig().getBoolean("unifiedAI.fly_like_allay", true);
        final boolean climb = plugin.getConfig().getBoolean("unifiedAI.climb_like_spider", true);
        final boolean pickupItems = plugin.getConfig().getBoolean("unifiedAI.pickup_items", true);
        final boolean pickupBlocks = plugin.getConfig().getBoolean("unifiedAI.pickup_blocks", false);
        final int radius = Math.max(2, plugin.getConfig().getInt("unifiedAI.range", 6));

        try { le.setRemoveWhenFarAway(false); } catch (Throwable ignored){}
        try { le.setCanPickupItems(true); } catch (Throwable ignored){}
        try { le.setGravity(true); } catch (Throwable ignored){}
        if (fly){ try { le.setGravity(false); } catch (Throwable ignored){} }

        int taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin.plugin(), () -> {
            Debug.logPet(le, "brain tick");
            try {
                if (le.isDead() || !le.isValid()){
                    stopPetBrain(pid);
                    return;
                }

                // Resolve live owner each tick (important after PlugMan reload / when brain was started with owner=null)
                org.bukkit.entity.Player ownerLive = owner;
                try {
                    if (ownerLive == null){
                        org.bukkit.persistence.PersistentDataContainer _pdc = le.getPersistentDataContainer();
                        String os = _pdc.get(ownerKey, org.bukkit.persistence.PersistentDataType.STRING);
                        if (os != null && !os.isBlank()){
                            try {
                                java.util.UUID ou = java.util.UUID.fromString(os);
                                ownerLive = org.bukkit.Bukkit.getPlayer(ou);
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}

                boolean aiDidWork = false;
                if (ownerLive == null || !ownerLive.isOnline() || ownerLive.getWorld() != le.getWorld()){
                    // Owner is not required. Run harvest AI and if it didn't choose any action/target,
                    // continue below with "go home" (private marker) so the farmer doesn't freeze.
                    try {
                        org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer(); Debug.logPet(le, "AI dispatch");
                        Integer var = pdc.get(farmerVariantKey, org.bukkit.persistence.PersistentDataType.INTEGER);
                        if (var != null && var == 1) { aiDidWork = com.fermerpets.HarvestAI_F1.tick(plugin, le, null); }
                        else if (var != null && var == 2) { aiDidWork = com.fermerpets.HarvestAI_F2.tick(plugin, le, null); }
                        else { aiDidWork = com.fermerpets.HarvestAI.tick(plugin, le, null); }
                    } catch (Throwable ignored) {}
                    if (aiDidWork) return;
                    // fall through to home movement
                } else {
                    try { if (com.fermerpets.HarvestAI.tick(plugin, le, ownerLive)) { return; } } catch (Throwable ignored) {}
                }
Debug.logPet(le, "FOLLOW_HOME: AI idle; moving towards private home");

                    // Фермеры НЕ следуют за игроком. Они привязаны к маркеру привата.
                    org.bukkit.Location home = le.getLocation();
                    try {
                        org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();
                        String marker = pdc.get(privateMarkerKey, org.bukkit.persistence.PersistentDataType.STRING);
                        if (marker != null && !marker.isBlank()){
                            String[] parts = marker.split(":");
                            if (parts.length == 4){
                                java.util.UUID wid = java.util.UUID.fromString(parts[0]);
                                org.bukkit.World w = org.bukkit.Bukkit.getWorld(wid);
                                if (w != null){
                                    int mx = Integer.parseInt(parts[1]);
                                    int my = Integer.parseInt(parts[2]);
                                    int mz = Integer.parseInt(parts[3]);
                                    home = new org.bukkit.Location(w, mx + 0.5, my + 1.0, mz + 0.5);
                                }
                            }
                        }
                    } catch (Throwable ignored){}
    if (com.fermerpets.HarvestAI.hasActiveTarget(pid)) {
                    return;
                }
                                // If any Harvest AI variant reports an active target, do NOT follow owner this tick
                boolean aiBusy = false;
                try { aiBusy = com.fermerpets.HarvestAI.hasActiveTarget(le.getUniqueId()); } catch (Throwable ignored) {}
                try { if (!aiBusy) aiBusy = com.fermerpets.HarvestAI_F1.hasActiveTarget(le.getUniqueId()); } catch (Throwable ignored) {}
                try { if (!aiBusy) aiBusy = com.fermerpets.HarvestAI_F2.hasActiveTarget(le.getUniqueId()); } catch (Throwable ignored) {}
                if (aiBusy) { com.fermerpets.Debug.logPet(le, "FOLLOW_OWNER: suppressed (active target present)"); return; }
                // Suppress follow-owner when any active target is present
                try { if (com.fermerpets.TargetTracker.isBusy(le.getUniqueId())) { com.fermerpets.Debug.logPet(le, "FOLLOW_OWNER: suppressed (active target present)"); return; } } catch (Throwable ignored) {}
                    org.bukkit.Location target = home.clone().add(0, 1.2, 0);
                org.bukkit.util.Vector delta = target.toVector().subtract(le.getLocation().toVector());
                double dist = delta.length();
                if (dist > 0.1){
                    org.bukkit.util.Vector vel = delta.normalize().multiply(Math.min(0.35, dist * 0.2));

                    // Fix: some farmers (notably from certain menu slots) could accumulate positive Y velocity
                    // while "walk-only" is enabled. Because gravity may be disabled (allay-like flight),
                    // keeping the old Y component makes them drift upwards "to the sky".
                    // We clamp Y towards the home target so they stabilize.
                    double yToHome = delta.getY();
                    double yVel = Math.max(-0.25, Math.min(0.25, yToHome * 0.15));

                    if (le.getPersistentDataContainer().has(farmerWalkOnlyKey, org.bukkit.persistence.PersistentDataType.BYTE)){
                        // walk-only: keep X/Z steering but do NOT keep uncontrolled Y
                        le.setVelocity(new org.bukkit.util.Vector(vel.getX(), (fly ? yVel : le.getVelocity().getY()), vel.getZ()));
                    } else if (fly){
                        le.setVelocity(new org.bukkit.util.Vector(vel.getX(), yVel, vel.getZ()));
                    } else {
                        le.setVelocity(new org.bukkit.util.Vector(vel.getX(), le.getVelocity().getY(), vel.getZ()));
                    }
                }
                if (climb){
                    org.bukkit.Location loc = le.getLocation();
                    org.bukkit.block.Block front = loc.add(loc.getDirection().setY(0).normalize().multiply(0.6)).getBlock();
                    if (front.getType().isSolid() && le.isOnGround()){
                        le.setVelocity(le.getVelocity().setY(0.4));
                    }
                }
                if (pickupItems){
                    for (org.bukkit.entity.Entity near : le.getNearbyEntities(radius, radius, radius)){
                        if (near instanceof org.bukkit.entity.Item it){
                            org.bukkit.inventory.ItemStack st = it.getItemStack();
                            java.util.HashMap<Integer, org.bukkit.inventory.ItemStack> rem = owner.getInventory().addItem(st);
                            if (rem.isEmpty()){
                                it.remove();
                            } else {
                                it.setItemStack(rem.values().iterator().next());
                                Debug.log("ITEM teleport to owner location due to full inventory: "+owner.getLocation()); it.teleport(owner.getLocation());
                            }
                        }
                    }
                }
                if (pickupBlocks){
                    java.util.List<String> wl = plugin.getConfig().getStringList("unifiedAI.block_whitelist");
                    if (!wl.isEmpty()){
                        org.bukkit.Location base = le.getLocation();
                        outer: for (int dx=-1; dx<=1; dx++) for (int dy=-1; dy<=1; dy++) for (int dz=-1; dz<=1; dz++){
                            org.bukkit.block.Block b = base.clone().add(dx, dy, dz).getBlock();
                            String name = b.getType().name();
                            if (wl.contains(name)){
                                try { b.breakNaturally(); } catch (Throwable ignored){}
                                break outer;
                            }
                        }
                    }
                }
            } catch (Throwable ignored){}
        }, 10L, 5L);
        brainTasks.put(pid, taskId);
    }

    /** Stop brain task for a pet by id. */
    private void stopPetBrain(java.util.UUID petId){
        Integer id = brainTasks.remove(petId);
        if (id != null){
            try { plugin.getServer().getScheduler().cancelTask(id); } catch (Throwable ignored){}
        }
    }


/**
 * Возвращает true, если у игрока есть записанные питомцы в players.yml.
 * Именно записи, а не активные сущности.
 */
public boolean hasRecordedPets(java.util.UUID ownerId) {
    try {
        return countRecordedPets(ownerId) > 0;
    } catch (Exception ignored) {
        return false;
    }
}



    /**
     * Убирает ровно одного питомца игрока — ПЕРВОГО из списка в players.yml.
     * Возвращает true, если запись нашлась и была удалена (и сущность убрана, если была активна).
     */
    
public boolean unsummonOne(org.bukkit.entity.Player owner) {
    if (owner == null) return false;
    java.util.UUID oid = owner.getUniqueId();
    java.util.List<java.util.UUID> recorded = new java.util.ArrayList<>(getPlayerPets(oid));
    if (recorded.isEmpty()) {
        return false;
    }
    // 1) Удаляем первую запись из players.yml
    java.util.UUID first = recorded.remove(0);
    setPlayerPets(oid, recorded);
    savePlayers();

    // 2) Пытаемся убрать соответствующую активную сущность
    boolean removedEntity = false;
    try {
        org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(first);
        if (e != null) {
            stopPetBrain(e.getUniqueId());
            e.remove();
            removedEntity = true;
        }
    } catch (Throwable ignored) {}

    // 3) Чистим кеш активных питомцев у владельца
    java.util.List<java.util.UUID> active = ownerToPets.getOrDefault(oid, new java.util.ArrayList<>());
    active.remove(first);
    if (active.isEmpty()) {
        ownerToPets.remove(oid);
    } else {
        ownerToPets.put(oid, active);
    }
    return true;
}
    /**
     * Убирает питомца по индексу (1..N) у владельца.
     * Полностью аналогично unsummonOne, только снимает запись под указанным номером.
     */
    public boolean unsummonAt(org.bukkit.entity.Player owner, int index){
        if (owner == null) return false;
        if (index < 1 || index > 3) return false;
        java.util.UUID oid = owner.getUniqueId();

        // Fixed-slot remove: slot #1 must only remove farmer #1, etc. No shifting/compaction.
        PlayersStore ps = new PlayersStore(playersCfg);
        ps.migrateIfNeeded(oid);
        java.util.UUID target = ps.getFarmer(oid, index);
        if (target == null) return false;
        ps.setFarmer(oid, index, null);
        savePlayers();

        // 2) Пытаемся убрать соответствующую активную сущность
        boolean removedEntity = false;
        try {
            org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(target);
            if (e instanceof org.bukkit.entity.LivingEntity le) {
                try { stopPetBrain(target); } catch (Throwable ignored) {}
                le.remove();
                removedEntity = true;
            }
        } catch (Throwable ignored){}

        // 3) ВАЖНО: НИКОГДА не удаляем "любой" активный пет, иначе слот #1 может удалить #2/#3.
        //    Если сущность не найдена (чанк выгружен), помечаем на удаление при следующей загрузке.
        if (!removedEntity) {
            try { killOnSight.add(target); } catch (Throwable ignored) {}
            try { tryForceLoadHint(target); } catch (Throwable ignored) {}
        }

        // 4) Чистим кеш активных питомцев у владельца
        try {
            java.util.List<java.util.UUID> active = ownerToPets.getOrDefault(oid, new java.util.ArrayList<>());
            active.remove(target);
            if (active.isEmpty()) ownerToPets.remove(oid);
            else ownerToPets.put(oid, active);
        } catch (Throwable ignored) {}

        return true; // запись удалили в любом случае
    }


    /** Remove all currently loaded entities that have our pet PDC mark (safety on plugin reload). */
    public void removeAllMarkedPets(){
        try {
            org.bukkit.NamespacedKey petKey = getPetKey();
            for (org.bukkit.World w : plugin.getServer().getWorlds()){
                for (org.bukkit.entity.Entity ent : w.getEntities()){
                    if (!(ent instanceof org.bukkit.entity.LivingEntity le)) continue;
                    try {
                        org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();
                        Byte flag = pdc.get(petKey, org.bukkit.persistence.PersistentDataType.BYTE);
                        if (flag != null && flag == (byte)1){
                            le.remove();
                        }
                    } catch (Throwable ignored){}
                }
            }
        } catch (Throwable ignored){}
    }
    

    /** Централизованный деспавн с полным рефандом и зачисткой. */
    public boolean unsummonAndCleanup(org.bukkit.entity.Player owner, int index) {
        if (owner == null) return false;
        if (index < 1 || index > 3) return false;
        java.util.UUID oid = owner.getUniqueId();
        java.util.UUID petId = getPetIdByOwnerAndIndex(oid, index);
        if (petId == null) return false;

        // 1) Рефанд и зачистка по индексу из playershoppers.yml (оба пути key/legacy)
        try {
            com.fermerpets.PlayersHoppersStore store = new com.fermerpets.PlayersHoppersStore(plugin);
            int fuel = store.getFuelByPet(petId);
            if (fuel > 0) {
                org.bukkit.Material fuelMat = org.bukkit.Material.matchMaterial(plugin.getConfig().getString("fuel.material", "AMETHYST_SHARD"));
                if (fuelMat == null) fuelMat = org.bukkit.Material.AMETHYST_SHARD;
                int remaining = fuel;
                while (remaining > 0) {
                    int give = Math.min(remaining, fuelMat.getMaxStackSize());
                    org.bukkit.inventory.ItemStack st = new org.bukkit.inventory.ItemStack(fuelMat, give);
                    java.util.Map<Integer, org.bukkit.inventory.ItemStack> left = owner.getInventory().addItem(st);
                    if (left != null && !left.isEmpty()) {
                        for (org.bukkit.inventory.ItemStack it : left.values()) {
                            if (it != null) owner.getWorld().dropItemNaturally(owner.getLocation(), it);
                        }
                    }
                    remaining -= give;
                }
                store.setFuelByPet(petId, 0);
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("[FermerPets] Refund/cleanup error: " + ex.getMessage());
        }

        // Force-remove hopper/beacon with chunk loading
        try {
            com.fermerpets.PlayersHoppersStore _store = new com.fermerpets.PlayersHoppersStore(plugin);

            // HOPPER: petId → fallback owner+index
            com.fermerpets.PlayersHoppersStore.Record _h = _store.getHopperByPet(petId);
            if (_h == null) _h = _store.getHopper(oid, index);
            if (_h != null && _h.loc != null && _h.loc.getWorld() != null) {
                plugin.getLogger().info("[FermerPets] Removing HOPPER for pet=" + petId + " at " + _h.loc);
                org.bukkit.World w = _h.loc.getWorld();
                int cx = _h.loc.getBlockX() >> 4, cz = _h.loc.getBlockZ() >> 4;
                if (!w.isChunkLoaded(cx, cz)) w.loadChunk(cx, cz);
                org.bukkit.block.Block hb = _h.loc.getBlock();
                hb.setType(org.bukkit.Material.AIR, false);
                _store.clearHopperByPet(petId);
                _store.clearHopper(oid, index);
                // ВАЖНО: Удаляем эффект тотема
                try {
                    java.lang.reflect.Field f = plugin.getClass().getDeclaredField("hopperBeaconListener");
                    f.setAccessible(true);
                    Object o = f.get(plugin);
                    if (o instanceof com.fermerpets.HopperBeaconListener) {
                        ((com.fermerpets.HopperBeaconListener) o).cancelTotem(_h.loc);
                    }
                } catch (Throwable ignored) {
                }
            }

            // BEACON: petId → fallback owner+index
            com.fermerpets.PlayersHoppersStore.Record _b = _store.getBeaconByPet(petId);
            if (_b == null) _b = _store.getBeacon(oid, index);
            if (_b != null && _b.loc != null && _b.loc.getWorld() != null) {
                plugin.getLogger().info("[FermerPets] Removing BEACON for pet=" + petId + " at " + _b.loc);
                org.bukkit.World w = _b.loc.getWorld();
                int cx = _b.loc.getBlockX() >> 4, cz = _b.loc.getBlockZ() >> 4;
                if (!w.isChunkLoaded(cx, cz)) w.loadChunk(cx, cz);
                org.bukkit.block.Block bb = _b.loc.getBlock();
                bb.setType(org.bukkit.Material.AIR, false);
                _store.clearBeaconByPet(petId);
                _store.clearBeacon(oid, index);
                // ВАЖНО: Удаляем эффект тотема и голограмму
                try {
                    java.lang.reflect.Field f = plugin.getClass().getDeclaredField("hopperBeaconListener");
                    f.setAccessible(true);
                    Object o = f.get(plugin);
                    if (o instanceof com.fermerpets.HopperBeaconListener) {
                        ((com.fermerpets.HopperBeaconListener) o).cancelTotem(_b.loc);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    com.fermerpets.BorderPainter.removeHolo(_b.loc);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("[FermerPets] cleanup blocks error: " + ex.getMessage());
        }

        // 2) Снимаем пета и удаляем запись в players.yml (+ выгружаем сущность)
        boolean removed = false;
        try {
            removed = unsummonAt(owner, index);
        } catch (Throwable ex) {
            plugin.getLogger().warning("[FermerPets] unsummonAt error: " + ex.getMessage());
        }

        // 3) Компактация индексов в playershoppers.yml, если удалили
        if (removed) {
            try {
                com.fermerpets.PlayersHoppersStore store2 = new com.fermerpets.PlayersHoppersStore(plugin);
                store2.remove(petId);
            } catch (Throwable ex) {
                plugin.getLogger().warning("[FermerPets] compactAfterRemoval error: " + ex.getMessage());
            }
        }
        return removed;
    }


    // ===== helpers injected =====

    private void recordPetHint(org.bukkit.entity.LivingEntity le){
        try {
            org.bukkit.Location loc = le.getLocation();
            org.bukkit.World w = loc.getWorld();
            if (w == null) return;
            java.util.UUID id = le.getUniqueId();
            petWorldHint.put(id, w.getName());
            petChunkHint.put(id, new long[]{loc.getBlockX() >> 4, loc.getBlockZ() >> 4});
        } catch (Throwable ignored){}
    }

    public void stopPetBrainPublic(java.util.UUID petId){ try { stopPetBrain(petId); } catch (Throwable ignored){} }

    public java.util.Set<java.util.UUID> getKillOnSight(){ return killOnSight; }

    private void tryForceLoadHint(java.util.UUID petId){
        try {
            String wname = petWorldHint.get(petId);
            long[] cz = petChunkHint.get(petId);
            if (wname == null || cz == null) return;
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(wname);
            if (w == null) return;
            int cx = (int)cz[0], czv = (int)cz[1];
            try {
                w.getChunkAt(cx, czv).load(true);
                plugin.getServer().getScheduler().runTask(plugin.plugin(), () -> {
                    org.bukkit.entity.Entity e2 = org.bukkit.Bukkit.getEntity(petId);
                    if (e2 instanceof org.bukkit.entity.LivingEntity le2){
                        try { stopPetBrain(petId); } catch (Throwable ignored){}
                        try { le2.remove(); } catch (Throwable ignored){}
                        killOnSight.remove(petId);
                    }
                });
            } catch (Throwable ignored){}
        } catch (Throwable ignored){}
    }

    public void forceDespawnAllByOwnerUUID(java.util.UUID owner){
        java.util.List<java.util.UUID> ids = ownerToPets.get(owner);
        if (ids == null) return;
        for (java.util.UUID pid : new java.util.ArrayList<>(ids)){
            try { stopPetBrain(pid); } catch (Throwable ignored){}
            org.bukkit.entity.Entity ent = org.bukkit.Bukkit.getEntity(pid);
            if (ent instanceof org.bukkit.entity.LivingEntity le){
                try { le.remove(); } catch (Throwable ignored){}
	            } else {
	                // IMPORTANT: do not force-load chunks here.
	                // Force-loading many chunks (especially when a player quits/rejoins)
	                // can tank TPS and make the server unplayable with no obvious stacktrace.
	                // We mark pets for removal and delete them when their chunks naturally load.
	                killOnSight.add(pid);
	            }
        }
        ownerToPets.remove(owner);
    }

    /**
     * Удаляет всех фермеров, привязанных к конкретному региону привата (regionId).
     * Используется при удалении привата.
     */
    public void removePetsBoundToPrivate(String regionId){
        if (regionId == null || regionId.isBlank()) return;
        try {
            PlayersHoppersStore phs = new PlayersHoppersStore(plugin);
            org.bukkit.configuration.file.FileConfiguration playersCfg = getPlayersYaml();
            PlayersStore ps = new PlayersStore(playersCfg);

            org.bukkit.configuration.ConfigurationSection sec = playersCfg.getConfigurationSection("players");
            if (sec == null) return;
            for (String ownerStr : sec.getKeys(false)){
                java.util.UUID ownerId;
                try { ownerId = java.util.UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }
                java.util.List<java.util.UUID> pets = new java.util.ArrayList<>(ps.getPets(ownerId));
                if (pets.isEmpty()) continue;
                boolean changed = false;
                for (java.util.UUID petId : new java.util.ArrayList<>(pets)){
                    try {
                        String rid = phs.getPrivateRegionIdByPet(petId);
                        if (rid == null || !rid.equalsIgnoreCase(regionId)) continue;
                        // remove entity if loaded
                        try { stopPetBrain(petId); } catch (Throwable ignored) {}
                        org.bukkit.entity.Entity ent = org.bukkit.Bukkit.getEntity(petId);
                        if (ent instanceof org.bukkit.entity.LivingEntity le){
                            try { le.remove(); } catch (Throwable ignored) {}
                        } else {
                            try { killOnSight.add(petId); } catch (Throwable ignored) {}
                            try { tryForceLoadHint(petId); } catch (Throwable ignored) {}
                        }

                        // remove hopper (and beacon if any legacy)
                        try {
                            PlayersHoppersStore.Record h = phs.getHopperByPet(petId);
                            if (h != null && h.loc != null && h.loc.getWorld() != null){
                                org.bukkit.World w = h.loc.getWorld();
                                int cx = h.loc.getBlockX()>>4, cz = h.loc.getBlockZ()>>4;
                                if (!w.isChunkLoaded(cx, cz)) w.loadChunk(cx, cz);
                                h.loc.getBlock().setType(org.bukkit.Material.AIR, false);
                                phs.clearHopperByPet(petId);
                            }
                        } catch (Throwable ignored) {}
                        try {
                            PlayersHoppersStore.Record b = phs.getBeaconByPet(petId);
                            if (b != null && b.loc != null && b.loc.getWorld() != null){
                                org.bukkit.World w = b.loc.getWorld();
                                int cx = b.loc.getBlockX()>>4, cz = b.loc.getBlockZ()>>4;
                                if (!w.isChunkLoaded(cx, cz)) w.loadChunk(cx, cz);
                                b.loc.getBlock().setType(org.bukkit.Material.AIR, false);
                                phs.clearBeaconByPet(petId);
                            }
                        } catch (Throwable ignored) {}
                        try { phs.setFuelByPet(petId, 0); } catch (Throwable ignored) {}

                        pets.remove(petId);
                        changed = true;
                    } catch (Throwable ignored) {}
                }
                if (changed){
                    ps.setPets(ownerId, pets);
                }
            }
            savePlayers();
        } catch (Throwable ignored) {}
    }

}
