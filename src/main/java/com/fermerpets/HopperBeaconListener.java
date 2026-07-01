package com.fermerpets;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import com.fermerpets.PlayersStore;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public class HopperBeaconListener implements Listener {
    private static final java.util.Map<String, BukkitTask> totemTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private static String hopperKey(org.bukkit.Location loc){
        if (loc==null || loc.getWorld()==null) return "null";
        java.util.UUID w = loc.getWorld().getUID();
        return w.toString()+":"+loc.getBlockX()+":"+loc.getBlockY()+":"+loc.getBlockZ();
    }

    private final FermerPetsModule plugin;
    private final FermerMenuService menu;
    private final PlayersHoppersStore store;

    private BukkitTask borderTask;
    private BukkitTask notifyTask;
    private BukkitTask fuelTask;

    public HopperBeaconListener(FermerPetsModule plugin, FermerMenuService menu){
        this.plugin = plugin;
        this.menu = menu;
        this.store = menu.store();
        startBorderDrawer();
    }

    @EventHandler public void onPlace(BlockPlaceEvent e){
        ItemStack inHand = e.getItemInHand();
        if (inHand==null) return;
        ItemMeta m = inHand.getItemMeta();
        if (m==null) return;
        String owner = m.getPersistentDataContainer().get(menu.ownerKey(), PersistentDataType.STRING);
        Integer idx = m.getPersistentDataContainer().get(menu.farmerKey(), PersistentDataType.INTEGER);
        String petIdStr = m.getPersistentDataContainer().get(menu.petIdKey(), PersistentDataType.STRING);
        if (owner==null || idx==null) return;
        UUID ownerId; try { ownerId = UUID.fromString(owner); } catch (IllegalArgumentException ex){ return; }

        if (m.getPersistentDataContainer().has(menu.hopperKey(), PersistentDataType.STRING) &&
                e.getBlockPlaced().getType()==Material.HOPPER){
            if (store.getHopper(ownerId, idx) != null){
                cancelAndRemove(e);
                e.getPlayer().sendMessage(ChatColor.RED+"Недействительная воронка: уже установлена.");
                return;
            }
            String hidStr = m.getPersistentDataContainer().get(menu.hopperKey(), PersistentDataType.STRING);
            UUID hid; try { hid = UUID.fromString(hidStr); } catch (IllegalArgumentException ex){ return; }
            store.setHopper(ownerId, idx, hid, e.getBlockPlaced().getLocation());
            try {
                org.bukkit.Bukkit.dispatchCommand(
                    org.bukkit.Bukkit.getConsoleSender(),
                    "cmi toast " + e.getPlayer().getName() + " &6Ты установил маяк " + idx
                );
            } catch (Throwable ignored) {}

            try { e.getBlockPlaced().getWorld().playSound(e.getBlockPlaced().getLocation(), org.bukkit.Sound.BLOCK_ANVIL_USE, 1f, 1f); } catch (Throwable ignored) {}
            scheduleTotemAbove(e.getBlockPlaced().getLocation());
            e.getPlayer().sendMessage(ChatColor.GREEN+"Воронка установлена.");
        }
        else if (m.getPersistentDataContainer().has(menu.beaconKey(), PersistentDataType.STRING) &&
                e.getBlockPlaced().getType()==Material.END_ROD){
            if (store.getBeacon(ownerId, idx) != null){
                cancelAndRemove(e);
                e.getPlayer().sendMessage(ChatColor.RED+"Недействительный светильник: уже установлен.");
                return;
            }
            String bidStr = m.getPersistentDataContainer().get(menu.beaconKey(), PersistentDataType.STRING);
            UUID bid; try { bid = UUID.fromString(bidStr); } catch (IllegalArgumentException ex){ return; }
            store.setBeacon(ownerId, idx, bid, e.getBlockPlaced().getLocation());

            try {
                org.bukkit.Bukkit.dispatchCommand(
                    org.bukkit.Bukkit.getConsoleSender(),
                    "cmi toast " + e.getPlayer().getName() + " &6Ты установил маяк " + idx
                );
            } catch (Throwable ignored) {}

            BorderPainter.scheduleBeaconOutline(plugin, ownerId, e.getBlockPlaced().getLocation());
            e.getPlayer().sendMessage(ChatColor.GREEN+"Светильник установлен.");
        }
    }

    private void cancelAndRemove(BlockPlaceEvent e){
        e.setCancelled(true);
        ItemStack inHand = e.getItemInHand();
        if (inHand==null) return;
        int amt = inHand.getAmount();
        if (amt <= 1) { e.getPlayer().getInventory().setItemInMainHand(null); }
        else { inHand.setAmount(amt-1); }
        e.getPlayer().updateInventory();
        e.getPlayer().playSound(e.getBlock().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e){
        if (e.isCancelled()) return;
        Block b = e.getBlock();
        if (b.getType() == org.bukkit.Material.HOPPER) {
            cancelTotemAbove(b.getLocation());
        }

        if (b.getType()!=Material.HOPPER && b.getType()!=Material.END_ROD) return;

        try {
            org.bukkit.configuration.file.FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
            com.fermerpets.PlayersStore ps = new com.fermerpets.PlayersStore(playersCfg);

            java.util.Set<String> owners = new java.util.HashSet<>();
            if (playersCfg.getConfigurationSection("players") != null) {
                owners.addAll(playersCfg.getConfigurationSection("players").getKeys(false));
            }
            if (playersCfg.getConfigurationSection("owners") != null) {
                owners.addAll(playersCfg.getConfigurationSection("owners").getKeys(false));
            }

            for (String ownerStr : owners){
                java.util.UUID owner;
                try { owner = java.util.UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }
                java.util.List<java.util.UUID> pets = ps.getPets(owner);
                if (pets == null) continue;

                for (java.util.UUID petId : pets){
                    PlayersHoppersStore.Record rH = store.getHopperByPet(petId);
                    if (rH != null && same(b.getLocation(), rH.loc) && b.getType()==Material.HOPPER){
                        e.setCancelled(true);
                        if (e.getPlayer()!=null) e.getPlayer().sendMessage(ChatColor.RED + "Удаление запрещено- воспользуйтесь меню");
                        return;
                    }
                    PlayersHoppersStore.Record rB = store.getBeaconByPet(petId);
                    if (rB != null && same(b.getLocation(), rB.loc) && b.getType()==Material.END_ROD){
                        e.setCancelled(true);
                        if (e.getPlayer()!=null) e.getPlayer().sendMessage(ChatColor.RED + "Удаление запрещено- воспользуйтесь меню");
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean same(Location a, Location b){
        if (a==null || b==null) return false;
        return a.getWorld().equals(b.getWorld()) && a.getBlockX()==b.getBlockX() && a.getBlockY()==b.getBlockY() && a.getBlockZ()==b.getBlockZ();
    }

    private void startBorderDrawer(){
        if (borderTask != null) borderTask.cancel();
        borderTask = new BukkitRunnable(){
            @Override public void run(){
                for (Player p : Bukkit.getOnlinePlayers()){
                    for (int idx=1; idx<=5; idx++){
                        PlayersHoppersStore.Record rec = store.getBeacon(p.getUniqueId(), idx);
                        if (rec==null || rec.loc==null) continue;
                        int fuel = menu.store().getFuel(p.getUniqueId(), idx);
                        int r = menu.resolveRadius(fuel);
                        drawBorder(rec.loc, r);
                    }
                }
            }
        }.runTaskTimer(plugin.plugin(), 20L, 20L);
    }

    private void drawBorder(Location center, int radius){
        World w = center.getWorld();
        if (w==null) return;
        Particle particle;
        int step = 4;
        if (radius==20){ particle = Particle.FLAME; }
        else if (radius==40){ particle = Particle.SOUL_FIRE_FLAME; }
        else if (radius==60){ particle = Particle.SNOWFLAKE; }
        else if (radius==80){ particle = Particle.REVERSE_PORTAL; }
        else { particle = Particle.NAUTILUS; }

        int y = center.getBlockY();
        int r = radius;
        for (int x=-r; x<=r; x+=step){
            w.spawnParticle(particle, center.clone().add(x+0.5, y+0.1, -r+0.5), 3);
            w.spawnParticle(particle, center.clone().add(x+0.5, y+0.1, r+0.5), 3);
        }
        for (int z=-r; z<=r; z+=step){
            w.spawnParticle(particle, center.clone().add(-r+0.5, y+0.1, z+0.5), 3);
            w.spawnParticle(particle, center.clone().add(r+0.5, y+0.1, z+0.5), 3);
        }
    }

    public void scheduleTotemAbove(org.bukkit.Location loc){
        try {
            String key = hopperKey(loc);
            BukkitTask prev = totemTasks.remove(key);
            if (prev != null) prev.cancel();
            BukkitTask task = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin.plugin(), () -> {
                try {
                    if (org.bukkit.Bukkit.getOnlinePlayers().isEmpty()) return;
                    org.bukkit.World w = loc.getWorld();
                    if (w == null) return;
                    int cx = loc.getBlockX() >> 4;
                    int cz = loc.getBlockZ() >> 4;
                    if (!w.isChunkLoaded(cx, cz)) return;
                    w.spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, loc.clone().add(0.5, 1.4, 0.5), 8, 0.1,0.2,0.1, 0.05);
                } catch (Throwable ignored) {}
            }, 0L, 20L);
            totemTasks.put(key, task);
        } catch (Throwable ignored) {}
    }

    public void cancelTotem(org.bukkit.Location loc){
        try {
            String key = hopperKey(loc);
            BukkitTask prev = totemTasks.remove(key);
            if (prev != null) prev.cancel();
        } catch (Throwable ignored) {}
    }

    public void ensureAllBlocksPresent(){
        try {
            boolean loadOnStartup = false;
            boolean keepOffline = false;
            try {
                loadOnStartup = plugin.getConfig().getBoolean("chunks.load_on_startup", false);
                keepOffline = plugin.getConfig().getBoolean("chunks.keep_loaded_offline", false);
            } catch (Throwable ignored) {}

            // NEW: restore particles/blocks for owner-based hopper storage (global hopper for all farmers)
            try {
                org.bukkit.configuration.ConfigurationSection ownerSec = store.cfg.getConfigurationSection("owner");
                if (ownerSec != null){
                    for (String ownerStr : ownerSec.getKeys(false)){
                        java.util.UUID owner;
                        try { owner = java.util.UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }

                        boolean online = org.bukkit.Bukkit.getPlayer(owner) != null && org.bukkit.Bukkit.getPlayer(owner).isOnline();
                        if (!online && !keepOffline) continue;

                        // Global hopper (preferred)
                        PlayersHoppersStore.Record gh = store.getHopper(owner, 1);
                        if (gh != null && gh.loc != null){
                            org.bukkit.World w = gh.loc.getWorld();
                            if (w != null){
                                int cx = gh.loc.getBlockX()>>4, cz = gh.loc.getBlockZ()>>4;
                                if (!w.isChunkLoaded(cx, cz)) {
                                    if (!loadOnStartup) continue;
                                    w.loadChunk(cx, cz);
                                }
                                org.bukkit.block.Block hb = gh.loc.getBlock();
                                if (hb.getType() != org.bukkit.Material.HOPPER){
                                    hb.setType(org.bukkit.Material.HOPPER, false);
                                    try {
                                        if (com.fermerpets.ChunkPinService.isForcePinningEnabled())
                                            com.fermerpets.ChunkPinService.pinChunkAt(plugin.plugin(), gh.loc, true);
                                    } catch (Throwable ignored) {}
                                }
                                try { scheduleTotemAbove(gh.loc); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            org.bukkit.configuration.file.FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
            com.fermerpets.PlayersStore ps = new com.fermerpets.PlayersStore(playersCfg);
            java.util.Set<String> owners = new java.util.HashSet<>(playersCfg.getConfigurationSection("players") == null
                    ? java.util.Collections.emptySet()
                    : playersCfg.getConfigurationSection("players").getKeys(false));
            if (playersCfg.getConfigurationSection("owners") != null){
                owners.addAll(playersCfg.getConfigurationSection("owners").getKeys(false));
            }
            for (String ownerStr : owners){
                java.util.UUID owner;
                try { owner = java.util.UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }

                boolean online = org.bukkit.Bukkit.getPlayer(owner) != null && org.bukkit.Bukkit.getPlayer(owner).isOnline();
                if (!online && !keepOffline) continue;

                java.util.List<java.util.UUID> pets = ps.getPets(owner);
                if (pets == null) continue;
                for (java.util.UUID petId : pets){
                    com.fermerpets.PlayersHoppersStore.Record h = store.getHopperByPet(petId);
                    if (h != null && h.loc != null){
                        org.bukkit.World w = h.loc.getWorld();
                        if (w != null){
                            int cx = h.loc.getBlockX()>>4, cz = h.loc.getBlockZ()>>4;
                            if (!w.isChunkLoaded(cx, cz)) {
                                if (!loadOnStartup) continue;
                                w.loadChunk(cx, cz);
                            }
                            org.bukkit.block.Block hb = h.loc.getBlock();
                            if (hb.getType() != org.bukkit.Material.HOPPER){
                                hb.setType(org.bukkit.Material.HOPPER, false);
                            try {
                                if (com.fermerpets.ChunkPinService.isForcePinningEnabled())
                                    com.fermerpets.ChunkPinService.pinChunkAt(plugin.plugin(), h.loc, true);
                            } catch (Throwable ignored) {}
                            }
                            try { scheduleTotemAbove(h.loc); } catch (Throwable ignored) {}
                        }
                    }
                    com.fermerpets.PlayersHoppersStore.Record b = store.getBeaconByPet(petId);
                    if (b != null && b.loc != null){
                        org.bukkit.World w = b.loc.getWorld();
                        if (w != null){
                            int cx = b.loc.getBlockX()>>4, cz = b.loc.getBlockZ()>>4;
                            if (!w.isChunkLoaded(cx, cz)) {
                                if (!loadOnStartup) continue;
                                w.loadChunk(cx, cz);
                            }
                            org.bukkit.block.Block bb = b.loc.getBlock();
                            if (bb.getType() != org.bukkit.Material.END_ROD){
                                bb.setType(org.bukkit.Material.END_ROD, false);
                                try {
                                    if (com.fermerpets.ChunkPinService.isForcePinningEnabled())
                                        com.fermerpets.ChunkPinService.pinChunkAt(plugin.plugin(), b.loc, true);
                                    try { new com.fermerpets.PlayerAnchorService(plugin).ensureAnchorAt(b.loc);
                                    try { new com.fermerpets.NmsAnchorService(plugin).ensureAnchorAt(b.loc); } catch (Throwable ignored) {} } catch (Throwable ignored) {}
                                } catch (Throwable ignored) {}
                            }
                            try { BorderPainter.scheduleBeaconOutline(plugin, owner, b.loc); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    void rescheduleAllTotems(){
        try {
            // NEW: owner-based/global hopper support (particles must survive /reload)
            try {
                org.bukkit.configuration.ConfigurationSection ownerSec = store.cfg.getConfigurationSection("owner");
                if (ownerSec != null){
                    for (String ownerStr : ownerSec.getKeys(false)){
                        java.util.UUID owner;
                        try { owner = java.util.UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }
                        PlayersHoppersStore.Record gh = store.getHopper(owner, 1);
                        if (gh != null && gh.loc != null){
                            scheduleTotemAbove(gh.loc);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            org.bukkit.configuration.file.FileConfiguration playersCfg = plugin.getManager().getPlayersYaml();
            com.fermerpets.PlayersStore ps = new com.fermerpets.PlayersStore(playersCfg);
            java.util.Set<String> owners = new java.util.HashSet<>(playersCfg.getConfigurationSection("players") == null
                    ? java.util.Collections.emptySet()
                    : playersCfg.getConfigurationSection("players").getKeys(false));
            if (playersCfg.getConfigurationSection("owners") != null){
                owners.addAll(playersCfg.getConfigurationSection("owners").getKeys(false));
            }
            for (String ownerStr : owners){
                java.util.UUID owner;
                try { owner = java.util.UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }
                java.util.List<java.util.UUID> pets = ps.getPets(owner);
                if (pets == null) continue;
                for (java.util.UUID petId : pets){
                    com.fermerpets.PlayersHoppersStore.Record r = store.getHopperByPet(petId);
                    if (r != null && r.loc != null){
                        scheduleTotemAbove(r.loc);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public void shutdown(){
        try {
            for (BukkitTask t : totemTasks.values()) {
                try { t.cancel(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        totemTasks.clear();
        try { if (fuelTask != null) fuelTask.cancel(); } catch (Throwable ignored) {}
        try { if (notifyTask != null) notifyTask.cancel(); } catch (Throwable ignored) {}
        try { if (borderTask != null) borderTask.cancel(); } catch (Throwable ignored) {}
    }

    private void cancelTotemAbove(org.bukkit.Location loc){
        try {
            String key = hopperKey(loc);
            org.bukkit.scheduler.BukkitTask t = totemTasks.remove(key);
            if (t != null) t.cancel();
        } catch (Throwable ignored) {}
    }

}