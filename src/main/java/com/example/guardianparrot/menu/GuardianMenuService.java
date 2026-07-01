package com.example.guardianparrot.menu;

import com.example.guardianparrot.GuardianParrotModule;
import com.example.guardianparrot.GuardianStateStore;
import com.example.guardianparrot.ParrotManager;
import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import me.aquaprivate.data.GuardFuelStore;
import me.aquaprivate.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * GuardianParrot menu backed by guardianparrot.yml.
 */
public final class GuardianMenuService {

    final GuardianParrotModule module;
    private final ParrotManager manager;
    private final GuardianStateStore state;
    // Package-private so the listener can persist bank changes without exposing a public API.
    final GuardFuelStore fuel;

    private String title;
    private int size;
    private int activationCost;
    private int upkeepMinutes;
    private int upkeepAmount;

    public GuardianMenuService(GuardianParrotModule module, ParrotManager manager, GuardianStateStore state, GuardFuelStore fuel) {
        this.module = module;
        this.manager = manager;
        this.state = state;
        this.fuel = fuel;
    }

    public void reload() {
        var cfg = module.getConfig();
        this.title = ColorUtil.color(cfg.getString("menu.title", "&bХРАНИТЕЛЬ"));
        this.size = cfg.getInt("size", 54);
        this.activationCost = cfg.getInt("cost.activation", 10);
        this.upkeepMinutes = cfg.getInt("cost.upkeep_minutes", 30);
        this.upkeepAmount = cfg.getInt("cost.upkeep_amount", 5);
    }

    public int getUpkeepMinutes() { return upkeepMinutes; }
    public int getUpkeepAmount() { return upkeepAmount; }

    public void openMenu(Player player, String regionId, Location privateMarker) {
        Inventory inv = Bukkit.createInventory(new GuardianMenuHolder(player.getUniqueId(), regionId, privateMarker), size, title);
        build(inv, player);
        player.openInventory(inv);
    }

    // Backwards compatible API used by AquaPrivate menu.
    public void open(Player player, String regionId) {
        openMenu(player, regionId, null);
    }

    public void open(Player player, String regionId, Location privateMarker) {
        openMenu(player, regionId, privateMarker);
    }

    public void onClose(Player player, Inventory inv) {
        if (!(inv.getHolder() instanceof GuardianMenuHolder holder)) return;
        int count = 0;
        for (int slot : holder.amethystSlots) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType() == Material.AIR) continue;
            if (it.getType() == Material.AMETHYST_SHARD) count += it.getAmount();
        }
        fuel.set(player.getUniqueId(), holder.regionId, count);
    }

    public void refreshOpenMenus(UUID owner, boolean preserveBank) {
        Player p = Bukkit.getPlayer(owner);
        if (p == null) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof GuardianMenuHolder holder)) return;

        Map<Integer, ItemStack> bankSnapshot = new HashMap<>();
        if (preserveBank) {
            // Preserve current amethyst bank contents so a refresh doesn't wipe items
            // the player is currently moving around.
            try {
                for (int slot : holder.amethystSlots) {
                    ItemStack it = top.getItem(slot);
                    if (it == null || it.getType() == Material.AIR) continue;
                    if (it.getType() != Material.AMETHYST_SHARD) continue;
                    bankSnapshot.put(slot, it.clone());
                }
            } catch (Throwable ignored) {}
        }

        build(top, p);

        // Restore bank items only when requested
        if (preserveBank && !bankSnapshot.isEmpty()) {
            for (Map.Entry<Integer, ItemStack> en : bankSnapshot.entrySet()) {
                int slot = en.getKey();
                if (slot < 0 || slot >= top.getSize()) continue;
                top.setItem(slot, en.getValue());
            }
        }
    }

    public void refreshOpenMenus(UUID owner) {
        refreshOpenMenus(owner, true);
    }

    public void handleClick(Player player, Inventory inv, int rawSlot, ItemStack cursor, ItemStack current, boolean shiftClick) {
        if (!(inv.getHolder() instanceof GuardianMenuHolder holder)) return;

        // Amethyst bank slots are handled in the listener (including move rules).
        // DO NOT refresh/rebuild the menu here, otherwise items placed into the bank
        // would be wiped by renderFuel() before onClose() saves them.
        if (holder.amethystSlots.contains(rawSlot)) return;

        String action = holder.actions.get(rawSlot);
        if (action == null) return;
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f);

        switch (action) {
            case "toggle_return" -> {
                player.closeInventory();
                if (module.host() instanceof AquaPrivatePlugin app) {
                    PrivateRecord rec = holder.regionId != null ? app.store().byRegionId(holder.regionId).orElse(null) : null;
                    if (rec != null) app.menuPrivate().open(player, rec);
                }
            }
            case "toggle_guard1" -> toggleGuardian(player, holder, 1);
            case "toggle_guard2" -> toggleGuardian(player, holder, 2);
            case "toggle_guard3" -> toggleGuardian(player, holder, 3);
            default -> {
                if (action.startsWith("toggle_guard")) {
                    // If config uses generic "toggle_guard" for multiple buttons,
                    // use explicit mapping by clicked GUI slot.
                    Integer mapped = holder.guardButtons.get(rawSlot);
                    if (mapped != null) {
                        toggleGuardian(player, holder, mapped);
                        return;
                    }
                    int slot = 2;
                    try { slot = Integer.parseInt(action.replace("toggle_guard", "").trim()); } catch (Exception ignored) {}
                    toggleGuardian(player, holder, slot);
                }
            }
        }
    }

    private void toggleGuardian(Player player, GuardianMenuHolder holder, int slot) {
        if (slot < 1 || slot > 3) slot = 1;

        UUID existing = state.getPet(player.getUniqueId(), holder.regionId, slot);
        if (existing != null) {
            forceUnsummon(player.getUniqueId(), holder.regionId, slot, false);
            player.sendMessage(ColorUtil.color(module.host().getConfig().getString("messages.guard-dismiss-ok", "&7Хранитель отозван.")));
            refreshOpenMenus(player.getUniqueId(), false);
            return;
        }

        int cur = fuel.get(player.getUniqueId(), holder.regionId);
        if (cur < activationCost) {
            String msg = module.host().getConfig().getString("messages.guard-no-amethyst", "&cНедостаточно аметистов! Нужно &f%need%&c.");
            player.sendMessage(ColorUtil.color(msg.replace("%need%", String.valueOf(activationCost))));
            return;
        }

        fuel.set(player.getUniqueId(), holder.regionId, cur - activationCost);

        // Guardians are bound to the private-block region. Prefer spawning at the private marker
        // (private block location) when available, otherwise fall back to player location.
        Location spawnAt = (holder.privateMarker != null ? holder.privateMarker.clone().add(0.5, 1.0, 0.5) : player.getLocation());
        UUID pet = manager.summonGuardian(player, spawnAt, holder.regionId, holder.privateMarker, slot);
        if (pet == null) {
            fuel.set(player.getUniqueId(), holder.regionId, cur);
            player.sendMessage(ColorUtil.color(module.host().getConfig().getString("messages.guard-open-error", "&cНе удалось призвать хранителя.")));
            return;
        }
        state.setPet(player.getUniqueId(), holder.regionId, slot, pet);
        state.setLastCharge(player.getUniqueId(), holder.regionId, slot, System.currentTimeMillis());
        // Persist private-block anchor for correct respawn after reload.
        if (holder.privateMarker != null) {
            state.setAnchor(player.getUniqueId(), holder.regionId, slot, holder.privateMarker);
        }

        String ok = module.host().getConfig().getString("messages.guard-summon-ok", "&aХранитель призван. Списано &f%cost%&a аметистов.");
        player.sendMessage(ColorUtil.color(ok.replace("%cost%", String.valueOf(activationCost))));
        refreshOpenMenus(player.getUniqueId(), false);
    }

    public void forceUnsummon(UUID owner, String regionId, int slot, boolean upkeepStop) {
        UUID pet = state.getPet(owner, regionId, slot);
        if (pet == null) return;
        manager.dismissPet(pet);
        state.setPet(owner, regionId, slot, null);
        if (upkeepStop) {
            Player p = Bukkit.getPlayer(owner);
            if (p != null) {
                p.sendMessage(ColorUtil.color(module.host().getConfig().getString("messages.guard-upkeep-stopped", "&cАметисты закончились — хранитель отключен.")));
            }
        }
    }

    private void build(Inventory inv, Player viewer) {
        if (!(inv.getHolder() instanceof GuardianMenuHolder holder)) return;
        holder.actions.clear();
        holder.amethystSlots.clear();
        holder.guardButtons.clear();

        inv.clear();

        var cfg = module.getConfig();
        addItemFromSection(inv, cfg.getConfigurationSection("hoe"), holder, viewer);
        addItemFromSection(inv, cfg.getConfigurationSection("return"), holder, viewer);
        addItemFromSection(inv, cfg.getConfigurationSection("eggguard1"), holder, viewer, 1);
        addItemFromSection(inv, cfg.getConfigurationSection("eggguard2"), holder, viewer, 2);
        addItemFromSection(inv, cfg.getConfigurationSection("eggguard3"), holder, viewer, 3);
        fillMulti(inv, cfg.getConfigurationSection("orange"));

        ConfigurationSection amet = cfg.getConfigurationSection("ametyst");
        if (amet != null) {
            holder.amethystSlots.addAll(amet.getIntegerList("slots"));
        }

        renderFuel(inv, holder, viewer.getUniqueId());
    }

    private void renderFuel(Inventory inv, GuardianMenuHolder holder, UUID owner) {
        int amount = Math.max(0, fuel.get(owner, holder.regionId));
        for (int slot : holder.amethystSlots) inv.setItem(slot, null);
        int i = 0;
        while (amount > 0 && i < holder.amethystSlots.size()) {
            int give = Math.min(64, amount);
            inv.setItem(holder.amethystSlots.get(i), new ItemStack(Material.AMETHYST_SHARD, give));
            amount -= give;
            i++;
        }
    }

    private void fillMulti(Inventory inv, ConfigurationSection sec) {
        if (sec == null) return;
        String matName = sec.getString("material", "");
        if (matName == null || matName.isBlank()) return;
        Material mat;
        try { mat = Material.valueOf(matName.toUpperCase(Locale.ROOT)); } catch (Exception e) { return; }
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.color(sec.getString("display_name", "&7")));
            it.setItemMeta(meta);
        }
        for (int s : sec.getIntegerList("slots")) inv.setItem(s, it);
    }

    private void addItemFromSection(Inventory inv, ConfigurationSection sec, GuardianMenuHolder holder, Player viewer) {
        addItemFromSection(inv, sec, holder, viewer, null);
    }

    private void addItemFromSection(Inventory inv, ConfigurationSection sec, GuardianMenuHolder holder, Player viewer, Integer guardSlot) {
        if (sec == null) return;
        String matName = sec.getString("material", "STONE");
        Material mat;
        try { mat = Material.valueOf(matName.toUpperCase(Locale.ROOT)); } catch (Exception e) { mat = Material.STONE; }
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = sec.getString("name", sec.getString("display_name", ""));
            if (name != null) {
                meta.setDisplayName(ColorUtil.color(replaceGuardianPlaceholders(viewer.getUniqueId(), holder.regionId, name)));
            }
            List<String> lore = sec.getStringList("lore");
            if (lore != null && !lore.isEmpty()) {
                List<String> out = new ArrayList<>(lore.size());
                for (String l : lore) out.add(ColorUtil.color(replaceGuardianPlaceholders(viewer.getUniqueId(), holder.regionId, l)));
                meta.setLore(out);
            }
            it.setItemMeta(meta);
        }
        int slot = sec.getInt("slot", -1);
        if (slot >= 0 && slot < inv.getSize()) {
            inv.setItem(slot, it);
            String action = sec.getString("click_action");
            if (action != null && !action.isBlank()) holder.actions.put(slot, action.trim());
            if (guardSlot != null) holder.guardButtons.put(slot, guardSlot);
        }
    }

    private String replaceGuardianPlaceholders(UUID owner, String regionId, String s) {
        if (s == null) return null;
        String active = module.host().getConfig().getString("guard.summon-status.active", "&2Активен");
        String inactive = module.host().getConfig().getString("guard.summon-status.inactive", "&7не активен");
        for (int i = 1; i <= 3; i++) {
            boolean on = state.getPet(owner, regionId, i) != null;
            s = s.replace("%guard" + i + "%", on ? active : inactive);
        }
        return s;
    }
}
