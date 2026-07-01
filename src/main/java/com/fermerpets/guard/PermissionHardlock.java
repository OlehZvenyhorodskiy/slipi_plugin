
package com.fermerpets.guard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PermissionHardlock implements Listener {

    public static final String P_USE = "fermerpets.use";
    public static final String P_MENU = "fermerpets.menu";
    public static final String P_GIVE = "fermerpets.give";
    public static final String P_SUMMON = "fermerpets.summon";
    public static final String P_UNSUMMON = "fermerpets.unsummon";
    public static final String P_UNSUMMON_LIST = "fermerpets.unsummonlist";
    public static final String P_RELOAD = "fermerpets.reload";

    private final JavaPlugin plugin;

    public PermissionHardlock(JavaPlugin plugin) { this.plugin = plugin; }

    public static void install(JavaPlugin plugin) {
        PermissionHardlock guard = new PermissionHardlock(plugin);
        PluginManager pm = plugin.getServer().getPluginManager();

        reg(pm, P_USE, PermissionDefault.OP);
        reg(pm, P_MENU, PermissionDefault.FALSE);
        reg(pm, P_GIVE, PermissionDefault.OP);
        reg(pm, P_SUMMON, PermissionDefault.FALSE);
        reg(pm, P_UNSUMMON, PermissionDefault.FALSE);
        reg(pm, P_UNSUMMON_LIST, PermissionDefault.FALSE);
        reg(pm, P_RELOAD, PermissionDefault.OP);

        pm.registerEvents(guard, plugin);
        plugin.getLogger().info("[PermissionHardlock] installed");
    }

    private static void reg(PluginManager pm, String node, PermissionDefault def) {
        if (pm.getPermission(node) == null) pm.addPermission(new Permission(node, def));
    }

    // Commands
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim().toLowerCase(Locale.ROOT);
        if (!(msg.startsWith("/fermerpets") || msg.startsWith("/fer ") || msg.startsWith("/fp ") || msg.equals("/fermerpets") || msg.equals("/fer") || msg.equals("/fp"))) {
            return;
        }
        String[] p = msg.split("\\s+");
        String sub = (p.length >= 2) ? p[1] : "";
        Player pl = e.getPlayer();

        if (sub.isEmpty()) {
            if (!pl.hasPermission(P_USE)) { e.setCancelled(true); deny(pl, P_USE); }
            return;
        }
        switch (sub) {
            case "menu": if (!pl.hasPermission(P_MENU)) { e.setCancelled(true); deny(pl, P_MENU);} break;
            case "summon": if (!pl.hasPermission(P_SUMMON)) { e.setCancelled(true); deny(pl, P_SUMMON);} break;
            case "unsummon": if (!pl.hasPermission(P_UNSUMMON)) { e.setCancelled(true); deny(pl, P_UNSUMMON);} break;
            case "give": if (!pl.hasPermission(P_GIVE)) { e.setCancelled(true); deny(pl, P_GIVE);} break;
            case "reload": if (!pl.hasPermission(P_RELOAD)) { e.setCancelled(true); deny(pl, P_RELOAD);} break;
            default: // pass
        }
    }

    // Menu open
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMenuOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        String t = ChatColor.stripColor(e.getView().getTitle()).toLowerCase(Locale.ROOT);
        if ((t.contains("фермер") || t.contains("fermer") || t.contains("farmer")) && !p.hasPermission(P_MENU)) {
            e.setCancelled(true);
            deny(p, P_MENU);
        }
    }

    // Menu clicks (rough heuristic)
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        String t = ChatColor.stripColor(e.getView().getTitle()).toLowerCase(Locale.ROOT);

        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() == Material.AIR) return;
        ItemMeta m = it.getItemMeta();
        String name = (m != null && m.hasDisplayName()) ? ChatColor.stripColor(m.getDisplayName()).toLowerCase(Locale.ROOT) : "";
        String lore = (m != null && m.hasLore()) ? ChatColor.stripColor(String.join(" ", m.getLore())).toLowerCase(Locale.ROOT) : "";

        boolean fermerMenu = t.contains("фермер") || t.contains("fermer") || t.contains("farmer");
        boolean looksSummon = name.contains("призыв") || name.contains("summon") || lore.contains("призыв") || lore.contains("summon");
        boolean looksGive = name.contains("выдать") || name.contains("give") || lore.contains("выдать") || lore.contains("give");
        boolean looksUnsummon = name.contains("убрать") || name.contains("unsummon") || lore.contains("убрать") || lore.contains("unsummon");

        if (fermerMenu) {
            if (looksSummon && !p.hasPermission(P_SUMMON)) { e.setCancelled(true); deny(p, P_SUMMON); }
            else if (looksGive && !p.hasPermission(P_GIVE)) { e.setCancelled(true); deny(p, P_GIVE); }
            else if (looksUnsummon && !p.hasPermission(P_UNSUMMON)) { e.setCancelled(true); deny(p, P_UNSUMMON); }
        }
    }

    // Using items like summon egg
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        ItemStack it = e.getItem();
        if (it == null) return;
        ItemMeta m = it.getItemMeta();
        if (m == null) return;
        String name = m.hasDisplayName() ? ChatColor.stripColor(m.getDisplayName()).toLowerCase(Locale.ROOT) : "";
        String lore = m.hasLore() ? ChatColor.stripColor(String.join(" ", m.getLore())).toLowerCase(Locale.ROOT) : "";
        boolean looksFermer = name.contains("фермер") || name.contains("fermer") || lore.contains("фермер") || lore.contains("fermer");
        boolean looksSummon = name.contains("призыв") || name.contains("summon") || lore.contains("призыв") || lore.contains("summon");
        if (looksFermer && looksSummon && !e.getPlayer().hasPermission(P_SUMMON)) { e.setCancelled(true); deny(e.getPlayer(), P_SUMMON); }
    }

    private void deny(Player p, String node) {
        p.sendMessage("§cНедостаточно прав (" + node + ")");
    }
}
