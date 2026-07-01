package com.fermerpets;

import org.bukkit.NamespacedKey;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;

import java.util.*;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import me.aquaprivate.util.PlaceholderUtil;

public class FermerMenuService implements CommandExecutor, Listener {
    /** Inventory holder to reliably identify Farmer menu instances. */
    public static final class FarmerMenuHolder implements org.bukkit.inventory.InventoryHolder {
        private final java.util.UUID owner;
        private org.bukkit.inventory.Inventory inv;
        public FarmerMenuHolder(java.util.UUID owner){ this.owner = owner; }
        public java.util.UUID owner(){ return owner; }
        public void setInventory(org.bukkit.inventory.Inventory inv){ this.inv = inv; }
        @Override public org.bukkit.inventory.Inventory getInventory(){ return inv; }
    }

    private int titlePhase = 0;
    private void writePetId(ItemMeta meta, java.util.UUID petId) { if (meta!=null && petId!=null) meta.getPersistentDataContainer().set(petIdKey(), PersistentDataType.STRING, petId.toString()); }
    private void writeFarmerIndex(ItemMeta meta, int idx) { if (meta!=null) meta.getPersistentDataContainer().set(farmerKey, PersistentDataType.INTEGER, idx); }

    private final FermerPetsModule plugin;
    private final PlayersHoppersStore store;
    private final MenuFuelStore menuFuelStore;
    private final NamespacedKey hopperKey, beaconKey, ownerKey, petIdKey, farmerKey;
    private final Material fuelMat;
    private FileConfiguration menuCfg;

    public static final int SIZE = 54;
    public static final int AMETHYST_SLOT_MIN = 36;
    public static final int AMETHYST_SLOT_MAX = 53;
    private boolean isAmethystSlot(int rawSlot){ return rawSlot >= AMETHYST_SLOT_MIN && rawSlot <= AMETHYST_SLOT_MAX; }

    public static final int SLOT_EGG = 4;
    public static final int SLOT_FUEL = 22;
    public static final int SLOT_GIVE = 39;
    public static final int SLOT_DELETE = 42;
    public static final int SLOT_PREV = 10;
    public static final int SLOT_NEXT = 16;
    public static final int SLOT_GLOBAL_HOPPER = 38;
    public static final int SLOT_GLOBAL_BEACON = 43;
    public static final int SLOT_RETURN = 35;

    private final Map<UUID,Integer> openMenus = new HashMap<>();
    private final Map<UUID, String> regionContext = new HashMap<>();
    private org.bukkit.scheduler.BukkitTask refresher;

    public FermerMenuService(FermerPetsModule plugin){
        this.plugin = plugin;
        this.store = new PlayersHoppersStore(plugin);
        this.menuFuelStore = new MenuFuelStore(plugin);
        this.hopperKey = new NamespacedKey(plugin.plugin(), "hopperId");
        this.beaconKey = new NamespacedKey(plugin.plugin(), "beaconId");
        this.ownerKey = new NamespacedKey(plugin.plugin(), "ownerId");
        this.petIdKey = new NamespacedKey(plugin.plugin(), "petId");
        this.farmerKey = new NamespacedKey(plugin.plugin(), "farmerIndex");
        Material tmpFuel = Material.matchMaterial(plugin.getConfig().getString("fuel.material","AMETHYST_SHARD"));
        this.fuelMat = tmpFuel != null ? tmpFuel : Material.AMETHYST_SHARD;
        loadMenuConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin.plugin());
        try { refresher = new org.bukkit.scheduler.BukkitRunnable(){ @Override public void run(){ try { refreshOpenMenus(); } catch (Throwable ignored) {} } }.runTaskTimer(plugin.plugin(), 40L, 40L); } catch (Throwable ignored) {}
    }

    /** Exposes shared amethyst balance used by Farmer menu so AquaPrivate placeholders can display it. */
    public MenuFuelStore getMenuFuelStore() {
        return menuFuelStore;
    }

private void loadMenuConfig(){
    try {
        File f = new File(plugin.getDataFolder(), "fermermenu.yml");
        if (!f.exists()) {
            try { plugin.saveResource("fermermenu.yml", false); } catch (Throwable ignored) {}
        }
        if (f.exists()) menuCfg = YamlConfiguration.loadConfiguration(f);
        else menuCfg = new YamlConfiguration();
    } catch (Throwable t){
        menuCfg = new YamlConfiguration();
    }
}

    /**
     * Backward compatible config lookup.
     * New format uses root keys like egg.*, give_tokens.*, delete_tokens.*, orange.*
     * Old format used items.egg.*, items.give_tokens.* etc.
     */
    private String pickPath(String... paths){
        if (paths == null || paths.length == 0) return "";
        if (menuCfg == null) return paths[paths.length-1];
        for (String p : paths){
            try {
                if (p != null && menuCfg.contains(p)) return p;
            } catch (Throwable ignored) {}
        }
        return paths[paths.length-1];
    }

    private void applyOrangeFill(Inventory inv){
        if (inv == null || menuCfg == null) return;
        String base = pickPath("orange", "items.orange");
        if (!menuCfg.contains(base + ".slots")) return;
        java.util.List<Integer> slots = menuCfg.getIntegerList(base + ".slots");
        if (slots == null || slots.isEmpty()) return;

        Material mat = cfgMat(base + ".material", Material.LIME_STAINED_GLASS_PANE);
        String name = cfgString(base + ".display_name", "&7");

        ItemStack pane = new ItemStack(mat);
        ItemMeta pm = pane.getItemMeta();
        if (pm != null){
            pm.setDisplayName(name);
            pane.setItemMeta(pm);
        }

        for (Integer s : slots){
            if (s == null) continue;
            if (s < 0 || s >= inv.getSize()) continue;
            inv.setItem(s, pane);
        }
    }

    private void applyFill(Inventory inv, String key, Material defMat){
        if (inv == null || menuCfg == null) return;
        String base = pickPath(key, "items." + key);
        if (!menuCfg.contains(base + ".slots")) return;
        java.util.List<Integer> slots = menuCfg.getIntegerList(base + ".slots");
        if (slots == null || slots.isEmpty()) return;

        // allow disabling by empty material
        String matRaw = null;
        try { matRaw = menuCfg.getString(base + ".material"); } catch (Throwable ignored) {}
        if (matRaw != null && matRaw.trim().isEmpty()) return;

        Material mat = cfgMat(base + ".material", defMat);
        if (mat == null || mat == Material.AIR) return;

        String name = cfgString(base + ".display_name", "&7");
        ItemStack pane = new ItemStack(mat);
        ItemMeta pm = pane.getItemMeta();
        if (pm != null){
            pm.setDisplayName(name);
            pane.setItemMeta(pm);
        }
        for (Integer s : slots){
            if (s == null) continue;
            if (s < 0 || s >= inv.getSize()) continue;
            inv.setItem(s, pane);
        }
    }

    private String menuTitle(){
        return cfgString(pickPath("menu.title", "menu_title"), " ");
    }

    private int menuSize(){
        String p = pickPath("menu.size", "size");
        int sz = menuCfg != null ? menuCfg.getInt(p, SIZE) : SIZE;

        // Ensure inventory is large enough for all configured buttons (e.g. return.slot=35)
        int requiredSlot = 0;
        try {
            requiredSlot = Math.max(requiredSlot, cfgSlot(pickPath("egg", "items.egg"), SLOT_EGG));
            requiredSlot = Math.max(requiredSlot, cfgSlot(pickPath("give_tokens", "items.give_tokens"), SLOT_GIVE));
            requiredSlot = Math.max(requiredSlot, cfgSlot(pickPath("delete_tokens", "items.delete_tokens"), SLOT_DELETE));
            requiredSlot = Math.max(requiredSlot, menuCfg != null ? menuCfg.getInt("return.slot", SLOT_RETURN) : SLOT_RETURN);
        } catch (Throwable ignored) {}

        int minSize = ((requiredSlot + 9) / 9) * 9; // round up to multiple of 9

        if (sz <= 0) sz = SIZE;
        if (sz % 9 != 0) sz = minSize;
        sz = Math.max(minSize, sz);
        sz = Math.max(9, Math.min(54, sz));
        return sz;
    }

    private void renderReturnButton(Inventory inv){
        if (inv == null) return;
        String base = pickPath("return", "items.return");

        Material mat = cfgMat(base + ".material", Material.ARROW);
        if (mat == null || mat == Material.AIR) mat = Material.ARROW;

        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null){
            im.setDisplayName(cfgString(base + ".name", "&6ВОЗВРАТ"));
            java.util.List<String> lore = cfgLore(base + ".lore", java.util.Collections.emptyList());
            if (lore != null && !lore.isEmpty()) im.setLore(lore);
            it.setItemMeta(im);
        }

        int slot = menuCfg != null ? menuCfg.getInt("return.slot", SLOT_RETURN) : SLOT_RETURN;
        setItemSafe(inv, slot, it);
    }

    private int cfgSlot(String base, int def){
        try { return menuCfg != null ? menuCfg.getInt(base + ".slot", def) : def; } catch (Throwable ignored){ return def; }
    }

    private void setItemSafe(Inventory inv, int slot, ItemStack it){
        if (inv == null || it == null) return;
        if (slot < 0 || slot >= inv.getSize()) return;
        inv.setItem(slot, it);
    }

private String mc(String s){
    if (s == null) return null;
    return ChatColor.translateAlternateColorCodes('&', s);
}

private String cfgString(String path, String def){
    if (menuCfg == null) return mc(def);
    String v = menuCfg.getString(path);
    if (v == null) v = def;
    return mc(v);
}

private java.util.List<String> cfgLore(String path, java.util.List<String> def){
    java.util.List<String> list = menuCfg != null ? menuCfg.getStringList(path) : null;
    if (list == null || list.isEmpty()) list = def;
    java.util.List<String> out = new java.util.ArrayList<>();
    if (list != null) for (String s : list) out.add(mc(s));
    return out;
}

    private String colorize(String s) {
        return s == null ? null : org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    private PrivateRecord resolvePrivateFor(Player viewer) {
        try {
            if (viewer == null) return null;
            String regionId = regionContext.get(viewer.getUniqueId());
            if (regionId == null || regionId.isBlank()) return null;
            org.bukkit.plugin.Plugin host = plugin.plugin();
            if (!(host instanceof AquaPrivatePlugin ap)) return null;
            return ap.store().byRegionId(regionId).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

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

    private String applyPrivatePlaceholders(Player viewer, String text) {
        if (text == null) return null;
        try {
            PrivateRecord rec = resolvePrivateFor(viewer);
            if (rec == null) return text;
            org.bukkit.plugin.Plugin host = plugin.plugin();
            if (!(host instanceof AquaPrivatePlugin ap)) return text;
            return PlaceholderUtil.apply(ap, rec, text, viewer.getName(), viewer);
        } catch (Throwable ignored) {
            return text;
        }
    }

    private java.util.List<String> applyPrivatePlaceholders(Player viewer, java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) return lines;
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : lines) out.add(applyPrivatePlaceholders(viewer, s));
        return out;
    }


private int cfgInt(String path, int def){
    try {
        if (menuCfg == null) return def;
        return menuCfg.getInt(path, def);
    } catch (Throwable ignored){ return def; }
}

private Material cfgMat(String path, Material def){
    try {
        String m = menuCfg != null ? menuCfg.getString(path) : null;
        if (m == null || m.isEmpty()) return def;
        Material mm = Material.matchMaterial(m);
        return mm != null ? mm : def;
    } catch (Throwable ignored){ return def; }
}

private boolean cfgBool(String path, boolean def){
    try { return menuCfg != null ? menuCfg.getBoolean(path, def) : def; } catch (Throwable ignored){ return def; }
}

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Only players.");
            return true;
        }

        // /fermerpets menu
        if (args.length >= 1 && args[0].equalsIgnoreCase("menu")) {
            openMainMenu(p);
            return true;
        }

        // /fermerpets open <index>
        if (args.length >= 2 && args[0].equalsIgnoreCase("open")) {
            int idx;
            try {
                idx = Integer.parseInt(args[1]);
            } catch (Exception e) {
                p.sendMessage(ChatColor.RED + "Номер фермера должен быть числом.");
                return true;
            }
            int count = countFarmers(p.getUniqueId());
            if (count <= 0) {
                p.sendMessage(ChatColor.RED + "У вас нет фермеров.");
                return true;
            }
            if (idx < 1 || idx > count) {
                p.sendMessage(ChatColor.RED + "У вас нет фермера с номером " + idx + ". Доступно: 1.." + count);
                return true;
            }
            openMenu(p, idx);
            return true;
        }

        p.sendMessage(ChatColor.GRAY + "/" + label + " menu");
        p.sendMessage(ChatColor.GRAY + "/" + label + " open <1..N>");
        return true;
    }

    /** Opens farmer GUI for the player (first farmer). Used by AquaPrivate integration. */
    public void openFirstMenu(Player p){
        openFirstMenu(p, null);
    }

    /** Opens farmer GUI with optional private region context for placeholder rendering. */
    public void openFirstMenu(Player p, String regionId){
        if (p == null) return;
        if (regionId != null && !regionId.isBlank()) regionContext.put(p.getUniqueId(), regionId);
        else regionContext.remove(p.getUniqueId());
        // Always open the main menu so configured slots are visible even if player has no farmers yet.
        openMainMenu(p);
    }

    /** Main menu built from fermermenu.yml. Shows eggfermer1..N (or egg fallback) and token buttons. */
    public void openMainMenu(Player p){
        if (p == null) return;
        // Ensure %fermer1%/%fermer2%/%fermer3% placeholders are consistent with real active farmers
        try { syncPrivateFermerFlags(p); } catch (Throwable ignored) {}

        // In private context, amethysts and farmers belong to the PRIVATE OWNER.
        java.util.UUID menuOwner = p.getUniqueId();
        try {
            PrivateRecord pr = resolvePrivateFor(p);
            if (pr != null && pr.owner != null) menuOwner = pr.owner;
        } catch (Throwable ignored) {}

        FarmerMenuHolder holder = new FarmerMenuHolder(menuOwner);
        Inventory inv = Bukkit.createInventory(holder, menuSize(), menuTitle());
        holder.setInventory(inv);
        applyOrangeFill(inv);
        applyFill(inv, "ametyst", Material.AMETHYST_SHARD);

        // Custom single-slot items from config (example: hoe)
        renderSingleSlotItem(inv, p, "hoe");

        renderEggSlots(inv, p);
        renderGiveDelete(inv, p);

        // restore amethysts into editable slots 36-53
        fillAmethystsFromStore(inv, menuOwner);

        // default selected farmer index for token actions
        openMenus.put(p.getUniqueId(), 1);

        // Back button (return to AquaPrivate menu)
        renderReturnButton(inv);
        p.openInventory(inv);
    }

    private java.util.UUID menuOwnerFrom(Inventory top, Player viewer){
        try {
            if (top != null && top.getHolder() instanceof FarmerMenuHolder h) return h.owner();
        } catch (Throwable ignored) {}
        return viewer != null ? viewer.getUniqueId() : null;
    }

    private void fillAmethystsFromStore(Inventory inv, java.util.UUID owner){
        if (inv == null || owner == null) return;
        int total = 0;
        try { total = menuFuelStore.get(owner); } catch (Throwable ignored) {}
        if (total <= 0) return;
        org.bukkit.Material mat = fuelMat != null ? fuelMat : org.bukkit.Material.AMETHYST_SHARD;
        // Fill slots 36..53 in order with stacks up to 64
        for (int s = AMETHYST_SLOT_MIN; s <= AMETHYST_SLOT_MAX && total > 0; s++){
            int give = Math.min(64, total);
            org.bukkit.inventory.ItemStack st = new org.bukkit.inventory.ItemStack(mat, give);
            inv.setItem(s, st);
            total -= give;
        }
    }

    private int countAmethystsInMenu(Inventory inv){
        if (inv == null) return 0;
        int total = 0;
        org.bukkit.Material mat = fuelMat != null ? fuelMat : org.bukkit.Material.AMETHYST_SHARD;
        for (int s = AMETHYST_SLOT_MIN; s <= AMETHYST_SLOT_MAX; s++){
            org.bukkit.inventory.ItemStack it = inv.getItem(s);
            if (it == null || it.getType() == org.bukkit.Material.AIR) continue;
            if (it.getType() != mat) continue;
            total += it.getAmount();
        }
        return total;
    }

    private void saveAmethystsToStore(Inventory inv, java.util.UUID owner){
        if (inv == null || owner == null) return;
        int total = countAmethystsInMenu(inv);
        try { menuFuelStore.set(owner, total); } catch (Throwable ignored) {}
    }

    private boolean removeAmethystsFromMenu(Inventory inv, int amount){
        if (inv == null || amount <= 0) return true;
        org.bukkit.Material mat = fuelMat != null ? fuelMat : org.bukkit.Material.AMETHYST_SHARD;
        int left = amount;
        for (int s = AMETHYST_SLOT_MIN; s <= AMETHYST_SLOT_MAX && left > 0; s++){
            org.bukkit.inventory.ItemStack it = inv.getItem(s);
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(left, it.getAmount());
            int newAmt = it.getAmount() - take;
            left -= take;
            if (newAmt <= 0) inv.setItem(s, null);
            else { it.setAmount(newAmt); inv.setItem(s, it); }
        }
        return left <= 0;
    }

    private boolean depositToAmethystSlots(Inventory inv, org.bukkit.inventory.ItemStack from){
        if (inv == null || from == null) return false;
        org.bukkit.Material mat = fuelMat != null ? fuelMat : org.bukkit.Material.AMETHYST_SHARD;
        if (from.getType() != mat) return false;
        int move = from.getAmount();
        if (move <= 0) return true;
        // first fill partial stacks
        for (int s = AMETHYST_SLOT_MIN; s <= AMETHYST_SLOT_MAX && move > 0; s++){
            org.bukkit.inventory.ItemStack it = inv.getItem(s);
            if (it == null || it.getType() == org.bukkit.Material.AIR) continue;
            if (it.getType() != mat) continue;
            int space = 64 - it.getAmount();
            if (space <= 0) continue;
            int add = Math.min(space, move);
            it.setAmount(it.getAmount() + add);
            inv.setItem(s, it);
            move -= add;
        }
        // then empty slots
        for (int s = AMETHYST_SLOT_MIN; s <= AMETHYST_SLOT_MAX && move > 0; s++){
            org.bukkit.inventory.ItemStack it = inv.getItem(s);
            if (it != null && it.getType() != org.bukkit.Material.AIR) continue;
            int add = Math.min(64, move);
            inv.setItem(s, new org.bukkit.inventory.ItemStack(mat, add));
            move -= add;
        }
        int moved = from.getAmount() - move;
        from.setAmount(move);
        return moved > 0;
    }



    private void renderEggSlots(Inventory inv, Player viewer){
        if (inv == null || menuCfg == null) return;

        // Prefer explicit eggfermer1.. sections
        java.util.List<String> keys;
        try { keys = new java.util.ArrayList<>(menuCfg.getKeys(false)); } catch (Throwable t){ keys = java.util.Collections.emptyList(); }
        java.util.List<String> eggKeys = new java.util.ArrayList<>();
        for (String k : keys){
            if (k == null) continue;
            if (k.toLowerCase(java.util.Locale.ROOT).startsWith("eggfermer")) eggKeys.add(k);
        }
        eggKeys.sort(java.util.Comparator.comparingInt(this::extractIndexSafe));

        if (!eggKeys.isEmpty()){
            for (String k : eggKeys){
                int idx = extractIndexSafe(k);
                if (idx <= 0) continue;
                ItemStack egg = new ItemStack(cfgMat(k + ".material", resolveEggMaterial(plugin)));
                ItemMeta em = egg.getItemMeta();
                if (em != null){
                    String name = cfgString(k + ".name", "&6Фермер %index%").replace("%index%", String.valueOf(idx));
                    em.setDisplayName(applyPrivatePlaceholders(viewer, name));
                    writeFarmerIndex(em, idx);
                    java.util.List<String> lore = cfgLore(k + ".lore", java.util.Collections.emptyList());
                    if (lore != null && !lore.isEmpty()) {
                        java.util.List<String> outLore = new java.util.ArrayList<>();
                        for (String line : lore) outLore.add(applyPrivatePlaceholders(viewer, line.replace("%index%", String.valueOf(idx))));
                        em.setLore(outLore);
                    }
                    egg.setItemMeta(em);
                }
                setItemSafe(inv, cfgSlot(k, SLOT_EGG), egg);
            }
            return;
        }

        // Fallback: single egg section
        String eggBase = pickPath("egg", "items.egg");
        ItemStack egg = new ItemStack(cfgMat(eggBase + ".material", resolveEggMaterial(plugin)));
        ItemMeta em = egg.getItemMeta();
        if (em != null){
            String name = cfgString(eggBase + ".name", "&6Фермер %index%").replace("%index%", "1");
            em.setDisplayName(applyPrivatePlaceholders(viewer, name));
            writeFarmerIndex(em, 1);
            egg.setItemMeta(em);
        }
        setItemSafe(inv, cfgSlot(eggBase, SLOT_EGG), egg);
    }

    private int extractIndexSafe(String key){
        if (key == null) return -1;
        String digits = key.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return -1;
        try { return Integer.parseInt(digits); } catch (Exception e){ return -1; }
    }

    private void renderGiveDelete(Inventory inv, Player viewer){
        if (inv == null) return;

        String giveBase = pickPath("give_tokens", "items.give_tokens");
        if (menuCfg == null || menuCfg.contains(giveBase)){
            ItemStack give = new ItemStack(cfgMat(giveBase + ".material", Material.HOPPER));
            ItemMeta gm = give.getItemMeta();
            if (gm != null){
                if (cfgBool(giveBase + ".glow", true)) gm.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
                gm.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                gm.setDisplayName(applyPrivatePlaceholders(viewer, cfgString(giveBase + ".name", "&dВыдать Воронку для сбора урожая")));
                gm.setLore(applyPrivatePlaceholders(viewer, cfgLore(giveBase + ".lore", java.util.Arrays.asList("&7Установите воронку там, куда приносить лут."))));
                give.setItemMeta(gm);
            }
            setItemSafe(inv, cfgSlot(giveBase, SLOT_GIVE), give);
        }

        String delBase = pickPath("delete_tokens", "items.delete_tokens");
        if (menuCfg == null || menuCfg.contains(delBase)){
            ItemStack del = new ItemStack(cfgMat(delBase + ".material", Material.BARRIER));
            ItemMeta dm = del.getItemMeta();
            if (dm != null){
                dm.setDisplayName(applyPrivatePlaceholders(viewer, cfgString(delBase + ".name", "&cУдалить воронку фермера")));
                dm.setLore(applyPrivatePlaceholders(viewer, cfgLore(delBase + ".lore", java.util.Arrays.asList("&7Удаляет выданную воронку (если потеряли)."))));
                del.setItemMeta(dm);
            }
            setItemSafe(inv, cfgSlot(delBase, SLOT_DELETE), del);
        }
    }

    /** Renders a config section with 'slot' (single) if present. */
    private void renderSingleSlotItem(Inventory inv, Player viewer, String sectionKey) {
        if (inv == null || viewer == null) return;
        if (menuCfg == null) return;
        if (!menuCfg.contains(sectionKey)) return;

        String matRaw = menuCfg.getString(sectionKey + ".material", "");
        if (matRaw == null || matRaw.isBlank()) return;
        Material mat = Material.matchMaterial(matRaw);
        if (mat == null) return;

        int slot = menuCfg.getInt(sectionKey + ".slot", -1);
        if (slot < 0 || slot >= inv.getSize()) return;

        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            String name = menuCfg.getString(sectionKey + ".name", menuCfg.getString(sectionKey + ".display_name", ""));
            if (name != null && !name.isBlank()) im.setDisplayName(applyPrivatePlaceholders(viewer, colorize(name)));

            List<String> lore = menuCfg.getStringList(sectionKey + ".lore");
            if (lore != null && !lore.isEmpty()) {
                List<String> outLore = new ArrayList<>();
                for (String line : lore) outLore.add(applyPrivatePlaceholders(viewer, colorize(line)));
                im.setLore(outLore);
            }

            if (menuCfg.getBoolean(sectionKey + ".glow", false)) {
                im.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
                im.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            it.setItemMeta(im);
        }

        setItemSafe(inv, slot, it);
    }

    private void openNoFarmersMenu(Player p){
        Inventory inv = Bukkit.createInventory(p, menuSize(), menuTitle());

        // optional background
        applyOrangeFill(inv);
        applyFill(inv, "ametyst", Material.AMETHYST_SHARD);
        ItemStack info = new ItemStack(cfgMat("no_farmers.material", resolveEggMaterial(plugin)));
        ItemMeta im = info.getItemMeta();
        im.setDisplayName(cfgString("no_farmers.name", "&cУ вас нет фермеров"));
        im.setLore(cfgLore("no_farmers.lore", java.util.Arrays.asList("&7Чтобы получить фермера, нужно","&7получить яйцо фермера у администратора.","&eКоманда: /fermerpets give")));
        info.setItemMeta(im);
        setItemSafe(inv, menuCfg != null ? menuCfg.getInt("no_farmers.slot", SLOT_EGG) : SLOT_EGG, info);

        // Back button (return to AquaPrivate menu)
        renderReturnButton(inv);
        p.openInventory(inv);
    }

    private void openMenu(Player p, int farmerIndex){
        // Use a custom holder so InventoryClickEvent can reliably detect our menu.
        // Owner is the private owner if we are opened from a region context.
        java.util.UUID ownerId = p.getUniqueId();
        try {
            PrivateRecord pr = resolvePrivateFor(p);
            if (pr != null && pr.owner != null) ownerId = pr.owner;
        } catch (Throwable ignored) {}

        FarmerMenuHolder holder = new FarmerMenuHolder(ownerId);
        Inventory inv = Bukkit.createInventory(holder, menuSize(), menuTitle());
        holder.setInventory(inv);

        // optional background
        applyOrangeFill(inv);
        applyFill(inv, "ametyst", Material.AMETHYST_SHARD);

        String eggBase = pickPath("egg", "items.egg");
        ItemStack egg = new ItemStack(cfgMat(eggBase + ".material", resolveEggMaterial(plugin)));
        ItemMeta em = egg.getItemMeta();
        em.setDisplayName(cfgString(eggBase + ".name", "&6Фермер %index%").replace("%index%", String.valueOf(farmerIndex)));
        egg.setItemMeta(em);
        setItemSafe(inv, cfgSlot(eggBase, SLOT_EGG), egg);

        renderFuelSlot(p, inv, farmerIndex);
        try {
            int total = countFarmers(p.getUniqueId());
            ItemStack navPane = new ItemStack(cfgMat("items.nav.filler.material", Material.YELLOW_STAINED_GLASS_PANE));
            ItemMeta npm = navPane.getItemMeta();
            npm.setDisplayName(cfgString("items.nav.filler.name", "&e"));
            navPane.setItemMeta(npm);
            setItemSafe(inv, menuCfg != null ? menuCfg.getInt("items.nav.slot_prev", SLOT_PREV) : SLOT_PREV, navPane);
            setItemSafe(inv, menuCfg != null ? menuCfg.getInt("items.nav.slot_next", SLOT_NEXT) : SLOT_NEXT, navPane);
            if (total > 1){
                ItemStack next = new ItemStack(cfgMat("items.nav.next.material", Material.ARROW));
                ItemMeta nm = next.getItemMeta();
                nm.setDisplayName(cfgString("items.nav.next.name", "&eСледующий фермер"));
                next.setItemMeta(nm);
                setItemSafe(inv, menuCfg != null ? menuCfg.getInt("items.nav.slot_next", SLOT_NEXT) : SLOT_NEXT, next);

                ItemStack prev = new ItemStack(cfgMat("items.nav.prev.material", Material.ARROW));
                ItemMeta pm = prev.getItemMeta();
                pm.setDisplayName(cfgString("items.nav.prev.name", "&eПредыдущий фермер"));
                prev.setItemMeta(pm);
                setItemSafe(inv, menuCfg != null ? menuCfg.getInt("items.nav.slot_prev", SLOT_PREV) : SLOT_PREV, prev);
            }
        } catch (Throwable ignored) {}
renderGlobalHopperButton(p, inv, farmerIndex);
        renderGlobalBeaconButton(p, inv, farmerIndex);

        String giveBase = pickPath("give_tokens", "items.give_tokens");
        ItemStack give = new ItemStack(cfgMat(giveBase + ".material", Material.HOPPER));
        ItemMeta gm = give.getItemMeta();
        if (cfgBool(giveBase + ".glow", true)) gm.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        gm.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        gm.setDisplayName(cfgString(giveBase + ".name", "&dВыдать Воронку для сбора урожая"));
        gm.setLore(cfgLore(giveBase + ".lore", java.util.Arrays.asList("&7Установите воронку там, куда приносить лут.","&7Также выдаётся светильник энда – точка центра радиуса.")));
        give.setItemMeta(gm);
        setItemSafe(inv, cfgSlot(giveBase, SLOT_GIVE), give);

        String delBase = pickPath("delete_tokens", "items.delete_tokens");
        ItemStack del = new ItemStack(cfgMat(delBase + ".material", Material.BARRIER));
        ItemMeta dm = del.getItemMeta();
        if (dm != null){
            dm.setDisplayName(cfgString(delBase + ".name", "&cУдалить воронку фермера"));
            dm.setLore(cfgLore(delBase + ".lore", java.util.Arrays.asList("&7Удаляет выданную воронку (если потеряли).")));
            del.setItemMeta(dm);
        }
        setItemSafe(inv, cfgSlot(delBase, SLOT_DELETE), del);

        // Back button (return to AquaPrivate menu)
        renderReturnButton(inv);

        p.openInventory(inv);
        openMenus.put(p.getUniqueId(), farmerIndex);
    }

    private void renderGlobalHopperButton(Player p, Inventory inv, int farmerIndex) {
        boolean isGlobalMode = isGlobalHopperMode(p.getUniqueId(), farmerIndex);
        ItemStack button;

        if (isGlobalMode) {
            button = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta meta = button.getItemMeta();
            meta.setDisplayName(ChatColor.RED + "Отсоединить фермеров от этой воронки");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Все фермеры вернутся к владельцу",
                    ChatColor.GRAY + "или к своим воронкам"
            ));
            button.setItemMeta(meta);
        } else {
            button = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
            ItemMeta meta = button.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + "Все фермеры в одну воронку - включить");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Все ваши фермеры будут приносить",
                    ChatColor.GRAY + "добычу в эту воронку"
            ));
            button.setItemMeta(meta);
        }
        inv.setItem(SLOT_GLOBAL_HOPPER, button);
    }

    private void renderGlobalBeaconButton(Player p, Inventory inv, int farmerIndex) {
        boolean isGlobalMode = isGlobalBeaconMode(p.getUniqueId(), farmerIndex);
        ItemStack button;

        if (isGlobalMode) {
            button = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta meta = button.getItemMeta();
            meta.setDisplayName(ChatColor.RED + "Отсоединить всех питомцев");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Фермеры вернутся к владельцу",
                    ChatColor.GRAY + "или к своим стержням"
            ));
            button.setItemMeta(meta);
        } else {
            button = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
            ItemMeta meta = button.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + "Все фермеры к одному маяку");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Все фермеры будут работать",
                    ChatColor.GRAY + "в радиусе этого стержня"
            ));
            button.setItemMeta(meta);
        }
        inv.setItem(SLOT_GLOBAL_BEACON, button);
    }

    private boolean isGlobalHopperMode(UUID ownerId, int farmerIndex) {
        try {
            return store.cfg.getBoolean("global." + ownerId + ".hopper." + farmerIndex, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isGlobalBeaconMode(UUID ownerId, int farmerIndex) {
        try {
            return store.cfg.getBoolean("global." + ownerId + ".beacon." + farmerIndex, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void setGlobalHopperMode(UUID ownerId, int farmerIndex, boolean enabled) {
        try {
            store.cfg.set("global." + ownerId + ".hopper." + farmerIndex, enabled);
            store.save();
        } catch (Throwable ignored) {}
    }

    private void setGlobalBeaconMode(UUID ownerId, int farmerIndex, boolean enabled) {
        try {
            store.cfg.set("global." + ownerId + ".beacon." + farmerIndex, enabled);
            store.save();
        } catch (Throwable ignored) {}
    }

    private void addRadiiLore(java.util.List<String> lore, int amt){
        int[][] tiers = new int[][]{
                {0,0,20},
                {1,15,25},
                {16,30,30},
                {31,45,35},
                {46,60,40}
        };
        for (int i=0;i<tiers.length;i++){
            int from = tiers[i][0], to = tiers[i][1], radius = tiers[i][2];
            boolean active = (amt>=from && amt<=to) || (from==0 && amt==0);
            String left = (from==0? "0" : from+" - "+to);
            String line = (active? ChatColor.GOLD : ChatColor.GRAY)+ left + ChatColor.GRAY+" – радиус " + (active? ChatColor.GOLD:ChatColor.GRAY) + radius + ChatColor.GRAY + " блоков.";
            lore.add(line);
        }
    }

    private String toRus(String mat){
        return mat.replace('_',' ').toLowerCase();
    }

    private void renderFuelSlot(Player p, Inventory inv, int farmerIndex){
        int amount = store.getFuel(p.getUniqueId(), farmerIndex);
        ItemStack fuelItem;
        if (amount <= 0){
            fuelItem = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
            ItemMeta fm = fuelItem.getItemMeta();
            ChatColor titleColor = (titlePhase % 2 == 0 ? ChatColor.GOLD : ChatColor.YELLOW);
            fm.setDisplayName(titleColor + "Топливо для фермера");
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(ChatColor.GRAY+"Вложи топливо – "+toRus(fuelMat.name())+".");
            lore.add(ChatColor.GRAY+"Сейчас: "+ChatColor.AQUA+0+ChatColor.GRAY+" шт.");
            lore.add(ChatColor.DARK_GRAY+"1 кристалл тратится раз в "+plugin.getConfig().getInt("fuel.period_minutes",10)+" мин.");
            addRadiiLore(lore, 0);
            fm.setLore(lore);
            fuelItem.setItemMeta(fm);
        } else {
            int stack = Math.min(amount, fuelMat.getMaxStackSize());
            fuelItem = new ItemStack(fuelMat, stack);
            ItemMeta fm = fuelItem.getItemMeta();
            ChatColor titleColor = (titlePhase % 2 == 0 ? ChatColor.GOLD : ChatColor.YELLOW);
            fm.setDisplayName(titleColor + "Топливо для фермера");
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(ChatColor.GRAY+"Вложи топливо – "+toRus(fuelMat.name())+".");
            lore.add(ChatColor.GRAY+"Сейчас: "+ChatColor.AQUA+amount+ChatColor.GRAY+" шт.");
            lore.add(ChatColor.DARK_GRAY+"1 кристалл тратится раз в "+plugin.getConfig().getInt("fuel.period_minutes",10)+" мин.");
            addRadiiLore(lore, amount);
            fm.setLore(lore);
            fuelItem.setItemMeta(fm);
        }
        inv.setItem(SLOT_FUEL, fuelItem);
        try { p.updateInventory(); } catch (Throwable ignored) {}
    }

    @EventHandler public void onClick(InventoryClickEvent e){
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // Only handle our Farmer menu (top inventory holder)
        if (e.getView() == null || e.getView().getTopInventory() == null) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof FarmerMenuHolder)) return;

        Inventory top = e.getView().getTopInventory();
        int raw = e.getRawSlot();
        int topSize = top.getSize();

        // --- Return button (back to AquaPrivate menu)
        // Must be handled for TOP inventory clicks (raw < topSize).
        int returnSlot = menuCfg != null ? menuCfg.getInt("return.slot", SLOT_RETURN) : SLOT_RETURN;
        if (raw < topSize && raw == returnSlot){
            e.setCancelled(true);
            try { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f); } catch (Throwable ignored) {}
            try {
                String regionId = regionContext.get(p.getUniqueId());
                if (regionId != null){
                    AquaPrivatePlugin ap = (AquaPrivatePlugin) plugin.plugin();
                    ap.store().byRegionId(regionId).ifPresent(r -> ap.menuPrivate().open(p, r));
                }
            } catch (Throwable ignored) {}
            return;
        }

        // --- SHIFT from player inventory into menu: only AMETHYST_SHARD into slots 36-53
        if (raw >= topSize){
            if (e.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY){
                ItemStack current = e.getCurrentItem();
                if (current == null || current.getType() == Material.AIR) return;
                if (current.getType() != fuelMat){
                    e.setCancelled(true);
                    return;
                }
                e.setCancelled(true);
                ItemStack moving = current.clone();
                boolean moved = depositToAmethystSlots(top, moving);
                if (moved){
                    // update source stack
                    int left = moving.getAmount();
                    if (left <= 0) e.setCurrentItem(null);
                    else {
                        current.setAmount(left);
                        e.setCurrentItem(current);
                    }
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
                    saveAmethystsToStore(top, menuOwnerFrom(top, p));
                    try { p.updateInventory(); } catch (Throwable ignored) {}
                }
                return;
            }
            return; // normal player inventory clicks: don't interfere
        }

        // --- Click in TOP inventory
        // Default: block everything except amethyst slots 36-53
        if (!isAmethystSlot(raw)){
            e.setCancelled(true);

            // Handle egg buttons (slots are configured; also read farmer index from PDC)
            ItemStack clicked = e.getCurrentItem();
            Integer idx = null;
            try {
                if (clicked != null && clicked.hasItemMeta()){
                    ItemMeta cm = clicked.getItemMeta();
                    idx = cm.getPersistentDataContainer().get(farmerKey, PersistentDataType.INTEGER);
                }
            } catch (Throwable ignored) {}
            if (idx != null && idx >= 1 && idx <= 3){
                handleFarmerToggleClick(p, top, idx);
                return;
            }

            // handle give/delete buttons as before (uses selected index = 1 by default)
            Integer farmerIndex = openMenus.get(p.getUniqueId());
            if (farmerIndex == null) farmerIndex = 1;

            String giveBase = pickPath("give_tokens", "items.give_tokens");
            String delBase = pickPath("delete_tokens", "items.delete_tokens");
            int slotGive = cfgSlot(giveBase, SLOT_GIVE);
            int slotDel = cfgSlot(delBase, SLOT_DELETE);

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
            return;
        }

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
    }

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
        PrivateRecord prCtx = null;
        try { prCtx = resolvePrivateFor(p); } catch (Throwable ignored) {}
        if (prCtx != null && prCtx.owner != null) ownerId = prCtx.owner;

        // Determine active by fixed-slot record in players.yml (stable across reload)
        boolean isActive = false;
        try {
            java.util.UUID petId = plugin.getManager().getPetIdByOwnerAndIndex(ownerId, idx);
            if (petId != null) isActive = true;
        } catch (Throwable ignored) {}

        if (isActive){
            boolean ok = false;
            try {
                // Owner may be offline; this method must still remove the entity and records.
                ok = plugin.getManager().unsummonAndCleanup(ownerId, idx);
            } catch (Throwable ignored) {}
            if (ok){
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                p.sendMessage(ChatColor.GOLD + "Фермер #" + idx + ChatColor.GRAY + " удалён.");
                try { syncPrivateFermerFlags(p); } catch (Throwable ignored) {}
            } else {
                p.sendMessage(ChatColor.RED + "Не удалось убрать фермера #" + idx);
            }
            Bukkit.getScheduler().runTask(plugin.plugin(), () -> openMainMenu(p));
            return;
        }

        // Activation cost from menu lore/config: default 10.
        int cost = 10;
        try { cost = Math.max(0, menuCfg != null ? menuCfg.getInt("cost.activation", 10) : 10); } catch (Throwable ignored) {}
        // По плану: призыв фермера всегда требует оплату активации.
        // (Это предотвращает ситуацию, когда разные слоты ведут себя по-разному из-за старых флагов farmer_paid.)
        int have = countAmethystsInMenu(top);
        if (have < cost){
            p.sendMessage(ChatColor.RED + "Недостаточно аметистов. Нужно: " + cost + ", есть: " + have);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            return;
        }
        if (!removeAmethystsFromMenu(top, cost)){
            p.sendMessage(ChatColor.RED + "Не удалось списать аметисты.");
            return;
        }
        // Persist fuel for the PRIVATE OWNER (not necessarily the viewer)
        saveAmethystsToStore(top, ownerId);

        // Bind summon to this private & this farmer index
        try {
            PrivateRecord pr = prCtx != null ? prCtx : resolvePrivateFor(p);
            if (pr != null){
                org.bukkit.Location marker = pr.toLocation();
                plugin.getManager().setNextSummonPrivateContext(pr.regionId, marker);
            } else {
                plugin.getManager().setNextSummonPrivateContext(null, null);
            }
            plugin.getManager().setNextSummonIndex(idx);
        } catch (Throwable ignored) {}

        // Summon for the private owner (may differ from the viewer)
        if (ownerId.equals(p.getUniqueId())) {
            plugin.getManager().summonPet(p);
        } else {
            plugin.getManager().summonPetForOwner(ownerId, p);
        }

        try { syncPrivateFermerFlags(p); } catch (Throwable ignored) {}
        p.sendMessage(ChatColor.GOLD + "Фермер #" + idx + ChatColor.GRAY + " призван.");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        Bukkit.getScheduler().runTask(plugin.plugin(), () -> openMainMenu(p));
    }

    /**
     * Updates PrivateRecord.fermer1/2/3 for the private that opened this menu (if region context exists),
     * based on real active farmers count (players.yml). This is required so %fermer1%/%fermer2%/%fermer3%
     * placeholders immediately reflect summon/unsummon from the menu.
     */
    private void syncPrivateFermerFlags(Player viewer){
        if (viewer == null) return;
        try {
            PrivateRecord rec = resolvePrivateFor(viewer);
            if (rec == null) return;
            org.bukkit.plugin.Plugin host = plugin.plugin();
            if (!(host instanceof AquaPrivatePlugin ap)) return;

            java.util.UUID ownerId = rec.owner != null ? rec.owner : viewer.getUniqueId();

            // Read stable fixed-slot records from players.yml (per PRIVATE OWNER)
            java.io.File f = new java.io.File(plugin.getDataFolder(), "players.yml");
            org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            com.fermerpets.PlayersStore ps = new com.fermerpets.PlayersStore(y);
            ps.migrateIfNeeded(ownerId);

            boolean n1 = ps.getFarmer(ownerId, 1) != null;
            boolean n2 = ps.getFarmer(ownerId, 2) != null;
            boolean n3 = ps.getFarmer(ownerId, 3) != null;

            if (rec.fermer1 != n1 || rec.fermer2 != n2 || rec.fermer3 != n3){
                rec.fermer1 = n1;
                rec.fermer2 = n2;
                rec.fermer3 = n3;
                ap.store().save();
            }
        } catch (Throwable ignored) {}
    }

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

    @EventHandler public void onClose(InventoryCloseEvent e){
        try {
            if (e.getView() != null && e.getView().getTopInventory() != null && e.getView().getTopInventory().getHolder() instanceof FarmerMenuHolder){
                java.util.UUID owner = ((FarmerMenuHolder)e.getView().getTopInventory().getHolder()).owner();
                saveAmethystsToStore(e.getView().getTopInventory(), owner);
            }
        } catch (Throwable ignored) {}
        openMenus.remove(e.getPlayer().getUniqueId());
    }

    private java.util.UUID resolvePetId(java.util.UUID owner, int idx){
        try {
            org.bukkit.configuration.file.FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
            com.fermerpets.PlayersStore ps = new com.fermerpets.PlayersStore(playersCfg);
            ps.migrateIfNeeded(owner);
            java.util.UUID id = ps.getFarmer(owner, idx);
            if (id != null) return id;
        } catch (Throwable ignored) {}
        return null;
    }

    void giveTokens(Player viewer, java.util.UUID privateOwner, int farmerIndex){
        UUID hid = java.util.UUID.randomUUID();
        UUID bid = java.util.UUID.randomUUID();
        ItemStack hopper = new ItemStack(Material.HOPPER);
        ItemMeta hm = hopper.getItemMeta();
        // UI text: one common hopper name (no per-index label)
        String hopperNameRaw = plugin.getConfig().getString("items.hopper_name", "&6Воронка для фермера");
        if (hopperNameRaw == null || hopperNameRaw.isBlank()) hopperNameRaw = "&6Воронка для фермера";
        // Legacy configs used "... #%index%"; user requested NO "#1" label, so override that pattern.
        if (hopperNameRaw.contains("%index%")) {
            hopperNameRaw = "&6Воронка для фермера";
        }
        String hopperName = org.bukkit.ChatColor.translateAlternateColorCodes('&', hopperNameRaw);
        hm.setDisplayName(hopperName);
        hm.getPersistentDataContainer().set(hopperKey, PersistentDataType.STRING, hid.toString());
        // Owner of the hopper binding must be the PRIVATE OWNER (farmers belong to the private),
        // but the item is given to whoever opened the menu.
        java.util.UUID bindOwner = (privateOwner != null ? privateOwner : viewer.getUniqueId());
        hm.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, bindOwner.toString());
        hm.getPersistentDataContainer().set(farmerKey, PersistentDataType.INTEGER, farmerIndex);
        hopper.setItemMeta(hm);

        viewer.getInventory().addItem(hopper);
        viewer.sendMessage(ChatColor.GREEN + "Выдана воронка.");
    }

    private void deleteTokens(Player viewer, java.util.UUID privateOwner, int farmerIndex){
        boolean found = false;
        java.util.UUID ownerId = (privateOwner != null ? privateOwner : viewer.getUniqueId());
        java.util.UUID petId = resolvePetId(ownerId, farmerIndex);

        com.fermerpets.PlayersHoppersStore.Record h = (petId == null ? null : store.getHopperByPet(petId));
        if (h == null) h = store.getHopper(ownerId, farmerIndex);
        if (h != null && h.loc != null && h.loc.getWorld() != null){
            org.bukkit.World w = h.loc.getWorld();
            int cx = h.loc.getBlockX()>>4, cz = h.loc.getBlockZ()>>4;
            if (!w.isChunkLoaded(cx, cz)) { w.loadChunk(cx, cz); }
            org.bukkit.block.Block hb = h.loc.getBlock();
            if (hb.getType() == org.bukkit.Material.HOPPER) {
                // Drop all contents before removing the hopper block (so loot is not deleted)
                try {
                    org.bukkit.block.BlockState st = hb.getState();
                    if (st instanceof org.bukkit.block.Hopper) {
                        org.bukkit.inventory.Inventory inv = ((org.bukkit.block.Hopper) st).getInventory();
                        if (inv != null) {
                            for (org.bukkit.inventory.ItemStack it : inv.getContents()){
                                if (it == null || it.getType().isAir()) continue;
                                w.dropItemNaturally(h.loc.clone().add(0.5, 0.5, 0.5), it.clone());
                            }
                            inv.clear();
                        }
                        st.update(true, false);
                    }
                } catch (Throwable ignored) {}
                hb.setType(org.bukkit.Material.AIR, false);
            } else {
                hb.setType(org.bukkit.Material.AIR, false);
            }
            found = true;
            if (petId != null) store.clearHopperByPet(petId);
            store.clearHopper(ownerId, farmerIndex);
            try { java.lang.reflect.Field f = plugin.getClass().getDeclaredField("hopperBeaconListener"); f.setAccessible(true); Object o = f.get(plugin); if (o instanceof com.fermerpets.HopperBeaconListener) ((com.fermerpets.HopperBeaconListener)o).cancelTotem(h.loc); } catch (Throwable ignored) {}
        }
        // Note: beacon/end_rod was removed from farmer workflow. Only hopper token exists.

        // Remove token items from the viewer's inventory (who is currently interacting with the menu)
        for (int i = 0; i < viewer.getInventory().getSize(); i++) {
            org.bukkit.inventory.ItemStack it = viewer.getInventory().getItem(i);
            if (it == null) continue;
            org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
            if (m == null) continue;
            Integer idx = m.getPersistentDataContainer().get(farmerKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            String hid = m.getPersistentDataContainer().get(hopperKey, org.bukkit.persistence.PersistentDataType.STRING);
            String oidStr = m.getPersistentDataContainer().get(ownerKey, org.bukkit.persistence.PersistentDataType.STRING);
            // Remove only hopper token items
            if (idx != null && idx == farmerIndex && hid != null && oidStr != null && oidStr.equals(ownerId.toString())) {
                viewer.getInventory().setItem(i, null);
                found = true;
            }
        }

        if (!found) {
            viewer.sendMessage(org.bukkit.ChatColor.RED+"У данного фермера нет воронки.");
            return;
        }
        viewer.updateInventory();
        viewer.sendMessage(org.bukkit.ChatColor.YELLOW+"Воронка удалена.");
        // Refresh main menu built from fermermenu.yml (do not fall back to internal menu)
        reopenMainMenu(viewer, farmerIndex);
    }

    /** Rebuilds the main menu from fermermenu.yml and keeps selected farmer index. */
    private void reopenMainMenu(Player p, int selectedIndex){
        if (p == null) return;
        int idx = Math.min(3, Math.max(1, selectedIndex));
        try { syncPrivateFermerFlags(p); } catch (Throwable ignored) {}
        FarmerMenuHolder holder = new FarmerMenuHolder(p.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, menuSize(), menuTitle());
        holder.setInventory(inv);
        applyOrangeFill(inv);
        applyFill(inv, "ametyst", Material.AMETHYST_SHARD);
        // Custom single-slot items from config
        renderSingleSlotItem(inv, p, "hoe");
        renderEggSlots(inv, p);
        renderGiveDelete(inv, p);
        fillAmethystsFromStore(inv, p.getUniqueId());
        openMenus.put(p.getUniqueId(), idx);
        p.openInventory(inv);
    }

    private void removeTagged(Player p, NamespacedKey tag, NamespacedKey tag2, int value){
        for (int i=0;i<p.getInventory().getSize();i++){
            ItemStack it = p.getInventory().getItem(i);
            if (it==null) continue;
            ItemMeta m = it.getItemMeta();
            if (m==null) continue;
            String s = m.getPersistentDataContainer().get(tag, PersistentDataType.STRING);
            Integer idx = m.getPersistentDataContainer().get(tag2, PersistentDataType.INTEGER);
            if (s!=null && idx!=null && idx==value){
                p.getInventory().setItem(i, null);
            }
        }
    }

    public int resolveRadius(int fuel){
        java.util.List<Integer> radii = plugin.getConfig().getIntegerList("fuel.radii");
        if (radii == null || radii.size() < 5){
            radii = java.util.Arrays.asList(20,25,30,35,40);
        }
        if (fuel <= 0) return radii.get(0);
        if (fuel <= 15) return radii.get(1);
        if (fuel <= 30) return radii.get(2);
        if (fuel <= 45) return radii.get(3);
        return radii.get(4);
    }

    public PlayersHoppersStore store(){ return store; }
    public NamespacedKey hopperKey(){ return hopperKey; }
    public NamespacedKey beaconKey(){ return beaconKey; }
    public NamespacedKey ownerKey(){ return ownerKey; }
    public NamespacedKey petIdKey(){ return petIdKey; }
    public NamespacedKey farmerKey(){ return farmerKey; }
    public Material fuelMaterial(){ return fuelMat; }

    public void refreshOpenMenus(){
        try {
            java.util.List<java.util.UUID> toRemove = new java.util.ArrayList<>();
            for (java.util.Map.Entry<java.util.UUID,Integer> e : openMenus.entrySet()){
                java.util.UUID uuid = e.getKey();
                Integer farmerIndex = e.getValue();
                org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()){
                    toRemove.add(uuid);
                    continue;
                }
                org.bukkit.inventory.Inventory inv = p.getOpenInventory().getTopInventory();
                if (inv == null || inv.getSize() != SIZE){
                    toRemove.add(uuid);
                    continue;
                }
                renderFuelSlot(p, inv, farmerIndex);
                try {
                    int total = countFarmers(p.getUniqueId());
                    ItemStack navPane = new ItemStack(cfgMat("items.nav.filler.material", Material.YELLOW_STAINED_GLASS_PANE));
                    ItemMeta npm = navPane.getItemMeta();
                    npm.setDisplayName(cfgString("items.nav.filler.name", "&e"));
                    navPane.setItemMeta(npm);
                    inv.setItem(menuCfg != null ? menuCfg.getInt("items.nav.slot_prev", SLOT_PREV) : SLOT_PREV, navPane);
                    inv.setItem(menuCfg != null ? menuCfg.getInt("items.nav.slot_next", SLOT_NEXT) : SLOT_NEXT, navPane);
                    if (total > 1){
                        ItemStack next = new ItemStack(cfgMat("items.nav.next.material", Material.ARROW));
                        ItemMeta nm = next.getItemMeta();
                        nm.setDisplayName(cfgString("items.nav.next.name", "&eСледующий фермер"));
                        next.setItemMeta(nm);
                        inv.setItem(menuCfg != null ? menuCfg.getInt("items.nav.slot_next", SLOT_NEXT) : SLOT_NEXT, next);

                        ItemStack prev = new ItemStack(cfgMat("items.nav.prev.material", Material.ARROW));
                        ItemMeta pm = prev.getItemMeta();
                        pm.setDisplayName(cfgString("items.nav.prev.name", "&eПредыдущий фермер"));
                        prev.setItemMeta(pm);
                        inv.setItem(menuCfg != null ? menuCfg.getInt("items.nav.slot_prev", SLOT_PREV) : SLOT_PREV, prev);
                    }
                } catch (Throwable ignored) {}

            }
            for (java.util.UUID id : toRemove) openMenus.remove(id);
        } catch (Throwable ignored){}
    }
    public void shutdown(){ openMenus.clear(); try { if (refresher != null) refresher.cancel(); } catch (Throwable ignored) {} }

    private boolean hasAnyTokens(Player p, int farmerIndex){
        if (store.getHopper(p.getUniqueId(), farmerIndex)!=null) return true;
        if (store.getBeacon(p.getUniqueId(), farmerIndex)!=null) return true;
        for (ItemStack it : p.getInventory().getContents()){
            if (it==null) continue;
            ItemMeta m = it.getItemMeta();
            if (m==null) continue;
            Integer idx = m.getPersistentDataContainer().get(farmerKey, PersistentDataType.INTEGER);
            String hid = m.getPersistentDataContainer().get(hopperKey, PersistentDataType.STRING);
            String bid = m.getPersistentDataContainer().get(beaconKey, PersistentDataType.STRING);
            if (idx!=null && idx==farmerIndex && (hid!=null || bid!=null)) return true;
        }
        return false;
    }

    private int countFarmers(java.util.UUID owner){
        try {
            // Embedded mode: players.yml is stored in the module subfolder (AquaPrivate/fermerpets/players.yml)
            java.io.File f = new java.io.File(plugin.getDataFolder(), "players.yml");
            org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            int n = y.getStringList("players."+owner+".pets").size();
            if (n==0) n = y.getStringList("owners."+owner+".pets").size();
            if (n==0) n = y.getStringList("players."+owner).size();
            if (n==0) n = y.getStringList("players."+owner+".entities").size();
            if (n==0) n = y.getStringList("guardians."+owner+".list").size();
            return n;
        } catch (Exception e){ return 0; }
    }

    private static Material resolveEggMaterial(FermerPetsModule plugin){
        String t = plugin.getConfig().getString("pet.type","VILLAGER");
        try {
            return Material.valueOf(t.trim().toUpperCase(java.util.Locale.ROOT) + "_SPAWN_EGG");
        } catch (IllegalArgumentException ex){
            return Material.VILLAGER_SPAWN_EGG;
        }
    
    }
private void teleportAllActivePetsToHopper(org.bukkit.entity.Player p, int masterFarmerIndex){
        try {
            com.fermerpets.PlayersHoppersStore.Record hopper = store.getHopper(p.getUniqueId(), masterFarmerIndex);
            if (hopper == null) return;
            org.bukkit.Location loc = hopper.loc.clone().add(0, 1, 0);
            org.bukkit.NamespacedKey petKey = plugin.getManager().getPetKey();
	            // NamespacedKey requires a Bukkit Plugin instance
	            org.bukkit.NamespacedKey ownerKey = new org.bukkit.NamespacedKey(plugin.plugin(), "mps_owner");
	            org.bukkit.NamespacedKey tpKey = new org.bukkit.NamespacedKey(plugin.plugin(), "tp_whitelist");

            for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()){
                for (org.bukkit.entity.Entity e : w.getEntities()){
                    if (!(e instanceof org.bukkit.entity.LivingEntity le)) continue;
                    try{
                        org.bukkit.persistence.PersistentDataContainer pdc = le.getPersistentDataContainer();
                        Byte isPet = pdc.get(petKey, org.bukkit.persistence.PersistentDataType.BYTE);
                        String ownerStr = pdc.get(ownerKey, org.bukkit.persistence.PersistentDataType.STRING);
                        if (isPet != null && isPet == (byte)1 && ownerStr != null && ownerStr.equals(p.getUniqueId().toString())){
                            pdc.set(tpKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);
                            le.teleport(loc);
                            pdc.remove(tpKey);
                        }
                    } catch (Throwable ignored){}
                }
            }
        } catch (Throwable ignored){}
    }
    private void handleGlobalHopperClick(Player p, int farmerIndex) {
        PlayersHoppersStore.Record hopper = store.getHopper(p.getUniqueId(), farmerIndex);
        if (hopper == null) {
            p.sendMessage(ChatColor.RED + "Сначала установите воронку для этого фермера!");
            return;
        }

        boolean currentMode = isGlobalHopperMode(p.getUniqueId(), farmerIndex);
        setGlobalHopperMode(p.getUniqueId(), farmerIndex, !currentMode);

        if (!currentMode) {
            setAllFarmersToGlobalHopper(p.getUniqueId(), farmerIndex);
            teleportAllActivePetsToHopper(p, farmerIndex);
            p.sendMessage(ChatColor.GREEN + "Все фермеры теперь приносят добычу в эту воронку!");
        } else {
            resetAllFarmersFromGlobalHopper(p.getUniqueId());
            p.sendMessage(ChatColor.YELLOW + "Фермеры вернулись к обычному режиму работы.");
        }

        openMenu(p, farmerIndex);
    }

    private void handleGlobalBeaconClick(Player p, int farmerIndex) {
        PlayersHoppersStore.Record beacon = store.getBeacon(p.getUniqueId(), farmerIndex);
        if (beacon == null) {
            p.sendMessage(ChatColor.RED + "Сначала установите стержень для этого фермера!");
            return;
        }

        boolean currentMode = isGlobalBeaconMode(p.getUniqueId(), farmerIndex);
        setGlobalBeaconMode(p.getUniqueId(), farmerIndex, !currentMode);

        if (!currentMode) {
            setAllFarmersToGlobalBeacon(p.getUniqueId(), farmerIndex);
            p.sendMessage(ChatColor.GREEN + "Все фермеры теперь работают в радиусе этого стержня!");
        } else {
            resetAllFarmersFromGlobalBeacon(p.getUniqueId());
            p.sendMessage(ChatColor.YELLOW + "Фермеры вернулись к своим стержням или к владельцу.");
        }

        openMenu(p, farmerIndex);
    }

    private void setAllFarmersToGlobalHopper(UUID ownerId, int masterFarmerIndex) {
        try {
            for (int i = 1; i <= countFarmers(ownerId); i++) {
                if (i != masterFarmerIndex) {
                    store.cfg.set("redirect." + ownerId + "." + i + ".hopper_to", masterFarmerIndex);
                }
            }
            store.save();
        } catch (Throwable ignored) {}
    }

    private void setAllFarmersToGlobalBeacon(UUID ownerId, int masterFarmerIndex) {
        try {
            for (int i = 1; i <= countFarmers(ownerId); i++) {
                if (i != masterFarmerIndex) {
                    store.cfg.set("redirect." + ownerId + "." + i + ".beacon_to", masterFarmerIndex);
                }
            }
            store.save();
        } catch (Throwable ignored) {}
    }

    private void resetAllFarmersFromGlobalHopper(UUID ownerId) {
        try {
            for (int i = 1; i <= countFarmers(ownerId); i++) {
                store.cfg.set("redirect." + ownerId + "." + i + ".hopper_to", null);
            }
            store.save();
        } catch (Throwable ignored) {}
    }

    private void resetAllFarmersFromGlobalBeacon(UUID ownerId) {
        try {
            for (int i = 1; i <= countFarmers(ownerId); i++) {
                store.cfg.set("redirect." + ownerId + "." + i + ".beacon_to", null);
            }
            store.save();
        } catch (Throwable ignored) {}
    }

    public Location getEffectiveHopperLocation(UUID ownerId, int farmerIndex) {
        try {
            Integer redirectTo = store.cfg.getInt("redirect." + ownerId + "." + farmerIndex + ".hopper_to", -1);
            if (redirectTo != -1) {
                PlayersHoppersStore.Record hopper = store.getHopper(ownerId, redirectTo);
                return hopper != null ? hopper.loc : null;
            }
        } catch (Throwable ignored) {}

        PlayersHoppersStore.Record hopper = store.getHopper(ownerId, farmerIndex);
        return hopper != null ? hopper.loc : null;
    }

    public Location getEffectiveBeaconLocation(UUID ownerId, int farmerIndex) {
        try {
            Integer redirectTo = store.cfg.getInt("redirect." + ownerId + "." + farmerIndex + ".beacon_to", -1);
            if (redirectTo != -1) {
                PlayersHoppersStore.Record beacon = store.getBeacon(ownerId, redirectTo);
                return beacon != null ? beacon.loc : null;
            }
        } catch (Throwable ignored) {}

        PlayersHoppersStore.Record beacon = store.getBeacon(ownerId, farmerIndex);
        return beacon != null ? beacon.loc : null;
    }
}
