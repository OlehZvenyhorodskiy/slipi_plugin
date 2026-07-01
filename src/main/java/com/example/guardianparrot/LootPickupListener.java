package com.example.guardianparrot;

import me.aquaprivate.AquaPrivatePlugin;
import com.fermerpets.PlayersHoppersStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Loot handling for guardians.
 *
 * Required behavior:
 * - Guardians pick up loot and deliver it to the PRIVATE BLOCK (anchor).
 * - If a FermerPets hopper is configured, deliver into that hopper instead.
 */
public class LootPickupListener implements Listener {

    private final Plugin plugin;
    private final ParrotManager manager;
    private final NamespacedKey ownerKey;
    private final NamespacedKey guardKey;
    private final NamespacedKey processedKey;

    // Keys for private anchor location (stored on the parrot)
    private final NamespacedKey privateWorldKey;
    private final NamespacedKey privateXKey;
    private final NamespacedKey privateYKey;
    private final NamespacedKey privateZKey;

    public LootPickupListener(Plugin plugin, ParrotManager manager){
        this.plugin = plugin;
        this.manager = manager;
        NamespacedKey ok = null, gk = null;
        try { ok = manager.ownerKey(); } catch (Throwable ignored){}
        try { gk = manager.guardKey(); } catch (Throwable ignored){}
        this.ownerKey = ok != null ? ok : new NamespacedKey(plugin, "gp_owner");
        this.guardKey = gk != null ? gk : new NamespacedKey(plugin, "gp_guard");
        this.processedKey = new NamespacedKey(plugin, "gp_processed");

        // same keys as ParrotManager
        this.privateWorldKey = manager.privateWorldKey;
        this.privateXKey = manager.privateXKey;
        this.privateYKey = manager.privateYKey;
        this.privateZKey = manager.privateZKey;

        // periodic sweep: pick up nearby items around guardians
        new BukkitRunnable() {
            @Override public void run() { sweepNearbyItems(); }
        }.runTaskTimer(plugin, 60L, 40L);
    }

    private boolean isGuardian(Parrot parrot){
        PersistentDataContainer pdc = parrot.getPersistentDataContainer();
        return pdc != null && pdc.has(guardKey, PersistentDataType.BYTE);
    }

    private UUID getOwnerId(Parrot parrot){
        PersistentDataContainer pdc = parrot.getPersistentDataContainer();
        if (pdc == null) return null;
        String id = pdc.get(ownerKey, PersistentDataType.STRING);
        if (id == null) return null;
        try { return UUID.fromString(id); } catch (IllegalArgumentException e){ return null; }
    }

    private Location getAnchor(Parrot parrot){
        try {
            PersistentDataContainer pdc = parrot.getPersistentDataContainer();
            String wName = pdc.get(privateWorldKey, PersistentDataType.STRING);
            Integer x = pdc.get(privateXKey, PersistentDataType.INTEGER);
            Integer y = pdc.get(privateYKey, PersistentDataType.INTEGER);
            Integer z = pdc.get(privateZKey, PersistentDataType.INTEGER);
            if (wName == null || x == null || y == null || z == null) return null;
            World w = Bukkit.getWorld(wName);
            if (w == null) return null;
            return new Location(w, x + 0.5, y + 1.0, z + 0.5);
        } catch (Throwable ignored){
            return null;
        }
    }

    /**
     * Resolve FermerPets hopper (if configured) for the private owner.
     * We keep the behavior simple: FermerPets uses a shared hopper per owner.
     */
    private Location getFermerHopperLocation(UUID ownerId){
        try {
            if (!(plugin instanceof AquaPrivatePlugin ap)) return null;
            if (ap.fermer() == null) return null;
            PlayersHoppersStore store = new PlayersHoppersStore(ap.fermer());
            PlayersHoppersStore.Record r = store.getHopper(ownerId, 1);
            if (r == null || r.loc == null || r.loc.getWorld() == null) return null;
            return r.loc;
        } catch (Throwable ignored){
            return null;
        }
    }

    private Inventory tryGetHopperInventory(Location hopperLoc){
        try {
            if (hopperLoc == null || hopperLoc.getWorld() == null) return null;
            Block b = hopperLoc.getBlock();
            if (b == null) return null;
            if (!(b.getState() instanceof Hopper hopper)) return null;
            return hopper.getInventory();
        } catch (Throwable ignored){
            return null;
        }
    }

    // ===== DENTH: capture drops from mobs killed by guardian =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event){
        LivingEntity dead = event.getEntity();
        if (dead == null) return;

        Entity damager = null;
        if (dead.getLastDamageCause() instanceof org.bukkit.event.entity.EntityDamageByEntityEvent dmg){
            damager = dmg.getDamager();
            if (damager instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Entity shooter){
                damager = shooter;
            }
        }
        if (!(damager instanceof Parrot parrot)) return;
        if (!isGuardian(parrot)) return;

        UUID ownerId = getOwnerId(parrot);
        if (ownerId == null) return;

        // XP → owner directly (no orbs)
        int xp = event.getDroppedExp();
        if (xp > 0){
            event.setDroppedExp(0);
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null && owner.isOnline()) owner.giveExp(xp);
        }

        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        deliverDropsToPrivate(ownerId, parrot, drops);
    }

    /**
     * Deliver loot to the private block (anchor) or to the FermerPets hopper if configured.
     */
    private void deliverDropsToPrivate(UUID ownerId, Parrot parrot, List<ItemStack> drops){
        if (drops == null || drops.isEmpty()) return;

        // money items still go to Vault owner (if online) - same as before
        List<ItemStack> normalized = new ArrayList<>();
        double money = 0.0;
        for (ItemStack it : drops){
            if (it == null) continue;
            double amt = tryExtractMoneyAmount(it);
            if (amt > 0 && EconomyBridge.hasEconomy()){
                money += amt * it.getAmount();
                continue;
            }
            normalized.add(it);
        }
        if (money > 0){
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null && owner.isOnline()){
                if (EconomyBridge.deposit(owner, money)){
                    owner.sendMessage(ChatColor.GREEN + "+" + money + ChatColor.GRAY + " (от хранителя)");
                }
            }
        }

        Location anchor = getAnchor(parrot);
        if (anchor == null) anchor = parrot.getLocation();
        Location hopperLoc = getFermerHopperLocation(ownerId);
        Inventory hopperInv = tryGetHopperInventory(hopperLoc);

        World w = anchor.getWorld();
        if (w == null) return;

        // Try insert into hopper; leftovers drop at anchor
        for (ItemStack it : normalized){
            if (it == null || it.getAmount() <= 0) continue;
            Map<Integer, ItemStack> rem = Collections.emptyMap();
            if (hopperInv != null){
                rem = hopperInv.addItem(it);
            } else {
                rem = Collections.singletonMap(-1, it);
            }
            if (rem != null && !rem.isEmpty()){
                for (ItemStack lf : rem.values()){
                    if (lf == null || lf.getAmount() <= 0) continue;
                    w.dropItemNaturally(anchor, lf);
                }
            }
        }
    }

    // ===== Vacuum items around guardians and deliver to private anchor / hopper =====
    private void sweepNearbyItems(){
        // Iterate all worlds and guardians (does not require players online)
        for (World w : Bukkit.getWorlds()){
            for (Entity e : w.getEntitiesByClass(Parrot.class)){
                if (!(e instanceof Parrot parrot)) continue;
                if (!isGuardian(parrot)) continue;

                UUID ownerId = getOwnerId(parrot);
                if (ownerId == null) continue;

                Location anchor = getAnchor(parrot);
                if (anchor == null) anchor = parrot.getLocation();
                Location hopperLoc = getFermerHopperLocation(ownerId);
                Inventory hopperInv = tryGetHopperInventory(hopperLoc);

                List<Item> toPick = new ArrayList<>();
                for (Entity near : parrot.getNearbyEntities(4, 2, 4)){
                    if (!(near instanceof Item it)) continue;
                    if (it.isDead() || !it.isValid()) continue;
                    if (it.getPickupDelay() > 0) continue;
                    if (it.getPersistentDataContainer().has(processedKey, PersistentDataType.BYTE)) continue;
                    toPick.add(it);
                }
                if (toPick.isEmpty()) continue;

                for (Item it : toPick){
                    ItemStack stack = it.getItemStack();
                    if (stack == null || stack.getAmount() <= 0) continue;

                    // money items to Vault owner if online
                    double amt = tryExtractMoneyAmountFromEntity(it);
                    if (amt > 0 && EconomyBridge.hasEconomy()){
                        Player owner = Bukkit.getPlayer(ownerId);
                        if (owner != null && owner.isOnline()){
                            EconomyBridge.deposit(owner, amt * stack.getAmount());
                            owner.playSound(owner.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                        }
                        it.getPersistentDataContainer().set(processedKey, PersistentDataType.BYTE, (byte)1);
                        it.remove();
                        continue;
                    }

                    Map<Integer, ItemStack> rem;
                    if (hopperInv != null){
                        rem = hopperInv.addItem(stack.clone());
                    } else {
                        rem = Collections.singletonMap(-1, stack.clone());
                    }

                    it.getPersistentDataContainer().set(processedKey, PersistentDataType.BYTE, (byte)1);
                    it.remove();

                    if (rem != null && !rem.isEmpty()){
                        for (ItemStack lf : rem.values()){
                            if (lf == null || lf.getAmount() <= 0) continue;
                            w.dropItemNaturally(anchor, lf);
                        }
                    }
                }
            }
        }
    }

    // ===== MoneyFromMobs heuristics (unchanged) =====
    private double tryExtractMoneyAmount(ItemStack item){
        try {
            if (item == null) return 0;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return 0;
            String name = meta.hasDisplayName() ? ChatColor.stripColor(meta.getDisplayName()) : "";
            List<String> lore = meta.hasLore() ? meta.getLore() : Collections.emptyList();
            StringBuilder sb = new StringBuilder();
            if (name != null) sb.append(name).append(' ');
            if (lore != null){
                for (String line : lore){
                    if (line != null) sb.append(ChatColor.stripColor(line)).append(' ');
                }
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) return 0;

            boolean looksLikeCoin = item.getType() == Material.GOLD_NUGGET
                    || text.contains("$")
                    || text.toLowerCase().contains("coin")
                    || text.toLowerCase().contains("монет");

            if (!looksLikeCoin) return 0;

            text = text.replace(',', '.').replace(" ", "");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]{1,2})?)").matcher(text);
            if (m.find()){
                try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored){}
            }
            return 0;
        } catch (Throwable t){
            return 0;
        }
    }

    private double tryExtractMoneyAmountFromEntity(Item entity){
        try {
            if (entity == null) return 0;
            String cname = entity.getCustomName();
            if (cname != null && !cname.isEmpty()){
                String s = ChatColor.stripColor(cname).replace(',', '.').replace(" ", "");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]{1,2})?)").matcher(s);
                if (m.find()){
                    try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored){}
                }
            }
            return tryExtractMoneyAmount(entity.getItemStack());
        } catch (Throwable t){
            return 0;
        }
    }
}
