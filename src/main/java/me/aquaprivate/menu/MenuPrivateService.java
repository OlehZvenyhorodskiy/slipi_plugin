package me.aquaprivate.menu;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import me.aquaprivate.util.ColorUtil;
import me.aquaprivate.util.NbtUtil;
import me.aquaprivate.util.PlaceholderUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.*;

public final class MenuPrivateService {

    private final AquaPrivatePlugin plugin;
    private final File file;
    private YamlConfiguration yaml;

    public MenuPrivateService(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "menuprivate.yml");
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource("menuprivate.yml", false);
        }
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    public boolean isMenu(Inventory inv) {
        if (inv == null) return false;
        return inv.getHolder() instanceof PrivateMenuHolder;
    }

    /** Called from GUI click. Toggles the "level quest" tracking for a specific private. */
    public void toggleLevelQuest(org.bukkit.entity.Player player, String regionId) {
        if (player == null || regionId == null) return;
        Optional<PrivateRecord> opt = plugin.store().byRegionId(regionId);
        if (opt.isEmpty()) return;
        PrivateRecord rec = opt.get();

        // Max level: no more leveling quest.
        if (rec.level >= 20) {
            player.sendMessage(plugin.cfg().msg("upgrade-max"));
            // Ensure state is consistent
            rec.levelQuestEnabled = false;
            plugin.store().save();
            open(player, rec);
            return;
        }

        // Only owner (or admin) can toggle.
        if (!player.isOp() && !rec.owner.equals(player.getUniqueId()) && !player.hasPermission("aquaprivate.admin")) {
            player.sendMessage(plugin.cfg().msg("not-owner"));
            return;
        }

        rec.levelQuestEnabled = !rec.levelQuestEnabled;
        plugin.store().save();

        String key = rec.levelQuestEnabled ? "level-quest-enabled" : "level-quest-disabled";
        player.sendMessage(plugin.cfg().msg(key));

        // Refresh menu
        open(player, rec);
    }


    /** Toggles particle border highlight for this private. */
    public void toggleBorderGlow(Player player, String regionId) {
        if (player == null || regionId == null) return;
        Optional<PrivateRecord> opt = plugin.store().byRegionId(regionId);
        if (opt.isEmpty()) return;
        PrivateRecord rec = opt.get();

        // Only owner (or admin) can toggle.
        if (!player.isOp() && !rec.owner.equals(player.getUniqueId()) && !player.hasPermission("aquaprivate.admin")) {
            player.sendMessage(plugin.cfg().msg("not-owner"));
            return;
        }

        rec.borderGlow = !rec.borderGlow;
        plugin.store().save();

        if (plugin.borderGlow() != null) {
            if (rec.borderGlow) plugin.borderGlow().enable(rec);
            else plugin.borderGlow().disable(rec);
        }

        String msg = rec.borderGlow ? "&aПодсветка границы включена." : "&7Подсветка границы отключена.";
        player.sendMessage(ColorUtil.color(msg));
        open(player, rec);
    }

    /** Opens embedded FermerPets menu if available and conditions are met. */
    public void openFermerMenu(Player player, String regionId) {
        if (player == null || regionId == null) return;
        Optional<PrivateRecord> opt = plugin.store().byRegionId(regionId);
        if (opt.isEmpty()) return;
        PrivateRecord rec = opt.get();

        internalOpenFermerMenu(player, rec);
    }

    /**
     * Handles click on the 'fermer' button in the private menu.
     * Before payment is complete: toggles EXP siphon on/off.
     * After 100 levels transferred: opens Fermer menu.
     */
    public void handleFermerButton(Player player, String regionId) {
        if (player == null || regionId == null) return;
        Optional<PrivateRecord> opt = plugin.store().byRegionId(regionId);
        if (opt.isEmpty()) return;
        PrivateRecord rec = opt.get();

        // Only owner (or admin) can run the farmer activation payment.
        if (!player.isOp() && !rec.owner.equals(player.getUniqueId()) && !player.hasPermission("aquaprivate.admin")) {
            player.sendMessage(plugin.cfg().msg("not-owner"));
            return;
        }

        // Preconditions
        if (rec.level < 20) {
            player.sendMessage(plugin.cfg().msg("fermer-need-private-level"));
            return;
        }
        if (!me.aquaprivate.hook.AquaClansHook.hasClan(player)) {
            player.sendMessage(plugin.cfg().msg("fermer-need-clan"));
            return;
        }
        int clanLevel = me.aquaprivate.hook.AquaClansHook.getClanLevel(player.getUniqueId());
        if (clanLevel < 8) {
            player.sendMessage(plugin.cfg().msg("fermer-need-clan-level"));
            return;
        }

        // If payment complete -> open farmer menu
        if (rec.farmerExpLevels >= 100) {
            internalOpenFermerMenu(player, rec);
            return;
        }

        // Toggle siphon
        rec.farmerExpTransferEnabled = !rec.farmerExpTransferEnabled;

        // Ensure only ONE active siphon per owner.
        if (rec.farmerExpTransferEnabled) {
            for (PrivateRecord other : plugin.store().byOwner(rec.owner)) {
                if (other == rec) continue;
                if (other.farmerExpTransferEnabled) {
                    other.farmerExpTransferEnabled = false;
                }
            }
        }

        plugin.store().save();

        // Immediate drain attempt (if player already has levels)
        if (rec.farmerExpTransferEnabled) {
            player.sendMessage(plugin.cfg().msg("fermer-exp-started"));
            try {
                int remaining = 100 - Math.max(0, rec.farmerExpLevels);
                int can = player.getLevel();
                int take = Math.min(can, remaining);
                if (take > 0) {
                    player.giveExpLevels(-take);
                    rec.farmerExpLevels = Math.min(100, rec.farmerExpLevels + take);
                    if (rec.farmerExpLevels >= 100) {
                        rec.farmerExpLevels = 100;
                        rec.farmerExpTransferEnabled = false;
                        player.sendMessage(plugin.cfg().msg("fermer-exp-complete"));
                    }
                    plugin.store().save();
                }
            } catch (Throwable ignored) {}
        } else {
            player.sendMessage(plugin.cfg().msg("fermer-exp-stopped"));
        }

        // Refresh hologram + menu
        try { plugin.holograms().refreshFor(rec); } catch (Throwable ignored) {}
        open(player, rec);
    }

    /**
     * Handles click on the 'guard' button in the private menu.
     * Before payment is complete: toggles EXP siphon on/off.
     * After 100 levels transferred: opens GuardianParrot menu.
     */
    public void handleGuardButton(Player player, String regionId) {
        if (player == null || regionId == null) return;
        Optional<PrivateRecord> opt = plugin.store().byRegionId(regionId);
        if (opt.isEmpty()) return;
        PrivateRecord rec = opt.get();

        // Only owner (or admin) can run the guardian activation payment.
        if (!player.isOp() && !rec.owner.equals(player.getUniqueId()) && !player.hasPermission("aquaprivate.admin")) {
            player.sendMessage(plugin.cfg().msg("not-owner"));
            return;
        }

        // Preconditions
        if (rec.level < 20) {
            player.sendMessage(plugin.cfg().msg("guard-need-private-level"));
            return;
        }
        if (!me.aquaprivate.hook.AquaClansHook.hasClan(player)) {
            player.sendMessage(plugin.cfg().msg("guard-need-clan"));
            return;
        }
        int clanLevel = me.aquaprivate.hook.AquaClansHook.getClanLevel(player.getUniqueId());
        if (clanLevel < 8) {
            player.sendMessage(plugin.cfg().msg("guard-need-clan-level"));
            return;
        }

        final int needLevels = plugin.getConfig().getInt("settings.guard.required-levels", 100);

        // If payment complete -> open guardian menu
        if (rec.guardExpLevels >= needLevels) {
            internalOpenGuardMenu(player, rec);
            return;
        }

        // Toggle siphon
        rec.guardExpTransferEnabled = !rec.guardExpTransferEnabled;

        // Ensure only ONE active siphon per owner across guard+farmer and across privates.
        if (rec.guardExpTransferEnabled) {
            for (PrivateRecord other : plugin.store().byOwner(rec.owner)) {
                if (other == rec) continue;
                other.guardExpTransferEnabled = false;
                other.farmerExpTransferEnabled = false;
            }
            // Also stop farmer siphon on this private (only one payment at a time)
            rec.farmerExpTransferEnabled = false;
        }

        plugin.store().save();

        // Immediate drain attempt
        if (rec.guardExpTransferEnabled) {
            player.sendMessage(plugin.cfg().msg("guard-exp-started"));
            try {
                int remaining = needLevels - Math.max(0, rec.guardExpLevels);
                int can = player.getLevel();
                int take = Math.min(can, remaining);
                if (take > 0) {
                    player.giveExpLevels(-take);
                    rec.guardExpLevels = Math.min(needLevels, rec.guardExpLevels + take);
                    if (rec.guardExpLevels >= needLevels) {
                        rec.guardExpLevels = needLevels;
                        rec.guardExpTransferEnabled = false;
                        player.sendMessage(plugin.cfg().msg("guard-exp-complete"));
                    }
                    plugin.store().save();
                }
            } catch (Throwable ignored) {}
        } else {
            player.sendMessage(plugin.cfg().msg("guard-exp-stopped"));
        }

        try { plugin.holograms().refreshFor(rec); } catch (Throwable ignored) {}
        open(player, rec);
    }

    private void internalOpenGuardMenu(Player player, PrivateRecord rec) {
        try {
            if (plugin.guardianParrot() != null && plugin.guardianParrot().getMenuService() != null) {
                // markerLoc ранее использовался как локация маркера/привата,
                // но в текущей модели данных хранится только позиция блока привата.
                plugin.guardianParrot().getMenuService().open(player, rec.regionId, rec.toLocation());
                return;
            }
        } catch (Throwable ignored) {}
        player.sendMessage(plugin.cfg().msg("guard-open-error"));
    }

    private void internalOpenFermerMenu(Player player, PrivateRecord rec) {
        try {
            if (plugin.fermer() != null && plugin.fermer().getMenuService() != null) {
                plugin.fermer().getMenuService().openFirstMenu(player, rec.regionId);
                try { plugin.holograms().refreshFor(rec); } catch (Throwable ignored) {}
                return;
            }
        } catch (Throwable ignored) {}
        player.sendMessage(plugin.cfg().msg("fermer-open-error"));
    }

    public void open(Player viewer, PrivateRecord rec) {
        if (yaml == null) reload();

        String title = yaml.getString("menu_title", null);
        if (title == null) title = yaml.getString("menu.title", "&6МЕНЮ ПРИВАТА");
        title = ColorUtil.color(applyPlaceholders(title, rec, viewer));

        int size = yaml.getInt("size", 0);
        if (size <= 0) size = yaml.getInt("menu.size", 54);
        size = Math.max(9, Math.min(54, size));
        size = ((size + 8) / 9) * 9;

        Map<Integer, String> clickActions = new HashMap<>();
        Inventory inv = Bukkit.createInventory(new PrivateMenuHolder(rec.regionId, clickActions), size, title);

        // Build all configured items (top-level sections)
        for (String key : yaml.getKeys(false)) {
            if (key.equalsIgnoreCase("menu_title") || key.equalsIgnoreCase("size") || key.equalsIgnoreCase("menu")) continue;

            ConfigurationSection sec = yaml.getConfigurationSection(key);
            if (sec == null) continue;

            List<Integer> slots = sec.getIntegerList("slots");
            if (slots == null || slots.isEmpty()) continue;

            String action = sec.getString("click_action", null);
            // Backward/typo compatibility: some configs used toggle_leveling for the border highlight slot.
            // Force the dedicated action so the button always works.
            if (key.equalsIgnoreCase("granica")) {
                action = "toggle_glow";
            }
            // Guard button: we keep click_action in config for legacy, but handle it specially.
            if (key.equalsIgnoreCase("guard")) {
                action = "guard_button";
            }

            ItemStack item;
            if (key.equalsIgnoreCase("ovner") || key.equalsIgnoreCase("owner")) {
                item = buildOwnerHead(rec, sec, viewer);
            } else {
                item = buildNormalItem(rec, sec, viewer);
            }
            if (item == null) continue;

            for (int slot : slots) {
                if (slot < 0 || slot >= inv.getSize()) continue;
                inv.setItem(slot, item);
                if (action != null && !action.isBlank()) {
                    // Disable clickable toggle on max level (except the Fermer button which uses legacy action name).
                    // Border highlight (granica) must remain clickable even on max level.
                    if (!key.equalsIgnoreCase("fermer") && !key.equalsIgnoreCase("granica") && !key.equalsIgnoreCase("guard")
                            && rec.level >= 20 && action.equalsIgnoreCase("toggle_leveling")) {
                        continue;
                    }
                    // Fermer button uses click_action in config but is handled specially:
                    // - before payment complete: toggles EXP siphon on/off
                    // - after 100 levels transferred: opens Fermer menu
                    if (key.equalsIgnoreCase("fermer")) {
                        // IMPORTANT: the slot must remain clickable even when requirements are not met,
                        // so we can show a specific chat message (private level, clan missing, clan level, etc.).
                        // The actual checks are performed in handleFermerButton().
                        action = "fermer_button";
                    }
                    if (key.equalsIgnoreCase("guard")) {
                        action = "guard_button";
                    }
                    clickActions.put(slot, action.trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        viewer.openInventory(inv);
    }

    /** Plays a click sound for interactive menu slots (configured in config.yml). */
    public void playClickSound(Player player) {
        if (player == null) return;
        try {
            boolean enabled = plugin.getConfig().getBoolean("settings.menu-click-sound.enabled", true);
            if (!enabled) return;

            String soundName = plugin.getConfig().getString("settings.menu-click-sound.sound", "UI_BUTTON_CLICK");
            float volume = (float) plugin.getConfig().getDouble("settings.menu-click-sound.volume", 1.0);
            float pitch = (float) plugin.getConfig().getDouble("settings.menu-click-sound.pitch", 1.2);

            Sound sound;
            try {
                sound = Sound.valueOf(String.valueOf(soundName).toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                return;
            }

            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception ignored) {
            // Never break menu interaction due to a bad sound config.
        }
    }

    private ItemStack buildNormalItem(PrivateRecord rec, ConfigurationSection sec, Player viewer) {
        String matName = sec.getString("material", "STONE");
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.STONE;

        ItemStack it = new ItemStack(mat, Math.max(1, sec.getInt("amount", 1)));
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        String dn = sec.getString("display_name", null);
        if (dn != null) meta.setDisplayName(ColorUtil.color(applyPlaceholders(dn, rec, viewer)));

        List<String> loreCfg = sec.getStringList("lore");
        if (loreCfg != null && !loreCfg.isEmpty()) {
            List<String> lore = new ArrayList<>();

            // Optional status line (display_levl)
            String dl = sec.getString("display_levl", null);
            if (dl != null && !dl.isBlank()) {
                lore.add(ColorUtil.color(applyPlaceholders(dl, rec, viewer)));
            }

            for (String line : loreCfg) {
                if (line == null) continue;
                lore.add(ColorUtil.color(applyPlaceholders(line, rec, viewer)));
            }
            meta.setLore(lore);
        }

        // Optional "glow" in GUI without showing enchant text.
        // Used for the level quest toggle item.
        boolean glowWhenActive = sec.getBoolean("glow_when_active", false);
        String action = sec.getString("click_action", "");
        if (glowWhenActive && action != null && action.equalsIgnoreCase("toggle_leveling")
                && rec.levelQuestEnabled && rec.level < 20) {
            it.setItemMeta(meta);
            it = NbtUtil.withEmptyEnchantGlint(it);
            return it;
        }

        it.setItemMeta(meta);
        return it;
    }

    private ItemStack buildOwnerHead(PrivateRecord rec, ConfigurationSection sec, Player viewer) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        ItemMeta im = head.getItemMeta();
        if (!(im instanceof SkullMeta meta)) return head;

        OfflinePlayer owner = Bukkit.getOfflinePlayer(rec.owner);
        meta.setOwningPlayer(owner);

        String name = sec.getString("display_name", "&6ВЛАДЕЛЕЦ");
        meta.setDisplayName(ColorUtil.color(applyPlaceholders(name, rec, viewer)));

        boolean showMembers = sec.getBoolean("display_members", true);

        List<String> loreCfg = sec.getStringList("lore");
        List<String> lore = new ArrayList<>();

        boolean containsMembersPlaceholder = false;
        if (loreCfg != null && !loreCfg.isEmpty()) {
            for (String line : loreCfg) {
                if (line == null) continue;

                if (line.contains("%priv_members%")) {
                    containsMembersPlaceholder = true;
                    if (!showMembers) continue;

                    lore.addAll(buildMembersLines(rec));
                    continue;
                }

                lore.add(ColorUtil.color(applyPlaceholders(line, rec, viewer)));
            }
        }

        if (showMembers && !containsMembersPlaceholder) {
            lore.add(ColorUtil.color("&8--------------------"));
            lore.add(ColorUtil.color("&7Участники:"));
            lore.addAll(buildMembersLines(rec));
        }

        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private List<String> buildMembersLines(PrivateRecord rec) {
        List<String> out = new ArrayList<>();
        if (rec.members == null || rec.members.isEmpty()) {
            out.add(ColorUtil.color("&8(нет)"));
            return out;
        }
        List<String> names = new ArrayList<>();
        for (UUID u : rec.members) {
            try {
                OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                String n = op != null ? op.getName() : null;
                names.add(n != null ? n : u.toString());
            } catch (Exception e) {
                names.add(u.toString());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        for (String n : names) {
            out.add(ColorUtil.color("&f- " + n));
        }
        return out;
    }

    private String applyPlaceholders(String s, PrivateRecord rec, Player viewer) {
        if (s == null) return "";
        String ownerName = Optional.ofNullable(Bukkit.getOfflinePlayer(rec.owner).getName()).orElse("Player");
        return PlaceholderUtil.apply(plugin, rec, s, ownerName, viewer);
    }
}
