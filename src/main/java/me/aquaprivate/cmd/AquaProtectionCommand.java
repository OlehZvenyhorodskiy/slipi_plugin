package me.aquaprivate.cmd;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateBlockType;
import me.aquaprivate.util.ClickChat;
import me.aquaprivate.util.ItemFactory;
import me.aquaprivate.tnt.UniqueTntType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * /aquaprotection give <player> <amount>
 * /aquaprotection list [player]
 *
 * Aliases: /aquaprivate
 */
public final class AquaProtectionCommand implements CommandExecutor, TabCompleter {

    private final AquaPrivatePlugin plugin;

    public AquaProtectionCommand(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.cfg().msg("give-usage"));
            sender.sendMessage(plugin.cfg().msg("list-usage"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "give" -> {
                return handleGive(sender, args);
            }
            case "givetnt" -> {
                return handleGiveTnt(sender, args);
            }
            case "list" -> {
                return handleList(sender, args);
            }
            case "home" -> {
                return handleHome(sender, args);
            }
            case "hometp" -> {
                return handleHomeTp(sender, args);
            }
            default -> {
                sender.sendMessage(plugin.cfg().msg("give-usage"));
                sender.sendMessage(plugin.cfg().msg("givetnt-usage"));
                sender.sendMessage(plugin.cfg().msg("list-usage"));
                return true;
            }
        }
    }

    private boolean handleGiveTnt(CommandSender sender, String[] args) {
        // /aquaprivate givetnt <type> <player> <amount>
        if (!sender.isOp()) {
            sender.sendMessage(plugin.cfg().msg("no-permission"));
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage(plugin.cfg().msg("givetnt-usage"));
            return true;
        }

        String typeId = args[1];
        UniqueTntType type = plugin.uniqueTnt().getType(typeId);
        if (type == null) {
            sender.sendMessage(plugin.cfg().msg("givetnt-type-not-found").replace("%type%", typeId));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(plugin.cfg().msg("player-not-found").replace("%player%", args[2]));
            return true;
        }

        int amount;
        try {
            amount = Math.max(1, Integer.parseInt(args[3]));
        } catch (Exception e) {
            sender.sendMessage(plugin.cfg().msg("givetnt-usage"));
            return true;
        }

        ItemStack item = plugin.uniqueTnt().createItem(type);
        item.setAmount(amount);
        target.getInventory().addItem(item);

        String msg = plugin.cfg().msg("givetnt-success");
        // If config.yml is old/missing this key, Spigot prints an empty line.
        // Provide a safe fallback so admins always see a clear response.
        if (msg == null || msg.trim().isEmpty()) {
            // do NOT rely on config prefix here (it can be missing in old configs)
            msg = plugin.cfg().color("&8[&bAquaPrivate&8] &aВыдали &f%amount% &aшт. TNT типа &f%type% &aигроку &f%player%");
        }

        sender.sendMessage(msg
                .replace("%player%", target.getName())
                .replace("%amount%", String.valueOf(amount))
                .replace("%type%", type.id()));
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aquaprivate.give")) {
            sender.sendMessage(plugin.cfg().msg("no-permission"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.cfg().msg("give-usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.cfg().msg("player-not-found").replace("%player%", args[1]));
            return true;
        }

        int amount;
        try {
            amount = Math.max(1, Integer.parseInt(args[2]));
        } catch (Exception e) {
            sender.sendMessage(plugin.cfg().msg("give-usage"));
            return true;
        }

        // give first configured block type (or DIAMOND_ORE if present)
        PrivateBlockType type = plugin.cfg().block("DIAMOND_ORE");
        if (type == null) {
            type = plugin.cfg().blocks().values().stream().findFirst().orElse(null);
        }
        if (type == null) {
            sender.sendMessage(plugin.cfg().msg("no-blocks"));
            return true;
        }

        ItemStack item = ItemFactory.createPrivateItem(plugin, type, target.getName());
        item.setAmount(amount);
        target.getInventory().addItem(item);

        sender.sendMessage(plugin.cfg().msg("give-success")
                .replace("%amount%", String.valueOf(amount))
                .replace("%player%", target.getName())
                .replace("%block%", plugin.cfg().color(type.name)));
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        // /aquaprivate list
        // /aquaprivate list <player>  (admin)

        UUID ownerUuid;
        String ownerName;

        if (args.length == 1) {
            // only self
            if (!(sender instanceof Player p)) {
                sender.sendMessage(plugin.cfg().msg("list-usage"));
                return true;
            }
            if (!sender.hasPermission("aquaprivate.list")) {
                sender.sendMessage(plugin.cfg().msg("no-permission"));
                return true;
            }
            ownerUuid = p.getUniqueId();
            ownerName = p.getName();
        } else {
            // admin mode
            if (!(sender.isOp() || sender.hasPermission("aquaprivate.list.admin"))) {
                sender.sendMessage(plugin.cfg().msg("no-permission"));
                return true;
            }

            OfflinePlayer off = Bukkit.getOfflinePlayer(args[1]);
            // We can't reliably know if the name never existed on offline-mode servers; but we can at least require a UUID.
            if (off == null || off.getUniqueId() == null) {
                sender.sendMessage(plugin.cfg().msg("player-not-found").replace("%player%", args[1]));
                return true;
            }
            ownerUuid = off.getUniqueId();
            ownerName = off.getName() != null ? off.getName() : args[1];
        }

        java.util.List<me.aquaprivate.hook.WorldGuardHook.PrivateEntry> list = plugin.wg().listPrivates(ownerUuid);

        sender.sendMessage(plugin.cfg().msg("list-header")
                .replace("%player%", ownerName)
                .replace("%count%", String.valueOf(list.size())));

        if (list.isEmpty()) {
            sender.sendMessage(plugin.cfg().msg("list-empty"));
            return true;
        }

        for (me.aquaprivate.hook.WorldGuardHook.PrivateEntry r : list) {
            sender.sendMessage(plugin.cfg().msg("list-entry")
                    .replace("%world%", r.world())
                    .replace("%x%", String.valueOf(r.x()))
                    .replace("%y%", String.valueOf(r.y()))
                    .replace("%z%", String.valueOf(r.z()))
                    .replace("%region%", r.regionId()));
        }
        return true;
    }

    
private boolean handleHome(CommandSender sender, String[] args) {
    // /aquaprivate home
    // /aquaprivate home <player>  (admin)

    UUID ownerUuid;
    String ownerName;

    if (args.length == 1) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.cfg().msg("home-usage"));
            return true;
        }
        if (!sender.hasPermission("aquaprivate.home")) {
            sender.sendMessage(plugin.cfg().msg("no-permission"));
            return true;
        }
        ownerUuid = p.getUniqueId();
        ownerName = p.getName();

        var list = plugin.wg().listPrivates(ownerUuid);
        sender.sendMessage(plugin.cfg().msg("home-header")
                .replace("%player%", ownerName)
                .replace("%count%", String.valueOf(list.size())));
        if (list.isEmpty()) {
            sender.sendMessage(plugin.cfg().msg("home-empty"));
            return true;
        }

        // Send clickable chat entries (CMI-like behaviour). If not supported, player still has GUI fallback.
        for (var r : list) {
            String line = plugin.cfg().msg("home-entry")
                    .replace("%world%", r.world())
                    .replace("%x%", String.valueOf(r.x()))
                    .replace("%y%", String.valueOf(r.y()))
                    .replace("%z%", String.valueOf(r.z()))
                    .replace("%region%", r.regionId());
            String cmd = "/aquaprivate hometp " + ownerUuid + " " + r.world() + " " + r.x() + " " + r.y() + " " + r.z();
            boolean okSend = ClickChat.sendRunCommand(p, line, plugin.cfg().msg("home-hover"), cmd);
            if (!okSend) {
                p.sendMessage(line + " " + plugin.cfg().msg("home-fallback").replace("%cmd%", cmd));
            }
        }

        // Also open GUI (works even if client blocks chat click events)
        if (plugin.cfg().getBoolean("home.open-gui", true)) {
            plugin.homeMenu().open(p, ownerUuid, ownerName, list);
        }
        return true;
    } else {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.cfg().msg("home-usage"));
            return true;
        }
        if (!(sender.isOp() || sender.hasPermission("aquaprivate.home.admin"))) {
            sender.sendMessage(plugin.cfg().msg("no-permission"));
            return true;
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(args[1]);
        if (off == null || off.getUniqueId() == null) {
            sender.sendMessage(plugin.cfg().msg("player-not-found").replace("%player%", args[1]));
            return true;
        }
        ownerUuid = off.getUniqueId();
        ownerName = off.getName() != null ? off.getName() : args[1];

        var list = plugin.wg().listPrivates(ownerUuid);
        sender.sendMessage(plugin.cfg().msg("home-header")
                .replace("%player%", ownerName)
                .replace("%count%", String.valueOf(list.size())));
        if (list.isEmpty()) {
            sender.sendMessage(plugin.cfg().msg("home-empty"));
            return true;
        }

        for (var r : list) {
            String line = plugin.cfg().msg("home-entry")
                    .replace("%world%", r.world())
                    .replace("%x%", String.valueOf(r.x()))
                    .replace("%y%", String.valueOf(r.y()))
                    .replace("%z%", String.valueOf(r.z()))
                    .replace("%region%", r.regionId());
            String cmd = "/aquaprivate hometp " + ownerUuid + " " + r.world() + " " + r.x() + " " + r.y() + " " + r.z();
            boolean okSend = ClickChat.sendRunCommand(p, line, plugin.cfg().msg("home-hover"), cmd);
            if (!okSend) {
                p.sendMessage(line + " " + plugin.cfg().msg("home-fallback").replace("%cmd%", cmd));
            }
        }

        if (plugin.cfg().getBoolean("home.open-gui", true)) {
            plugin.homeMenu().open(p, ownerUuid, ownerName, list);
        }
        return true;
    }
}

    private boolean handleHomeTp(CommandSender sender, String[] args) {
        // /aquaprivate hometp <ownerUuid> <world> <x> <y> <z>
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.cfg().msg("home-usage"));
            return true;
        }
        if (args.length < 6) {
            p.sendMessage(plugin.cfg().msg("home-usage"));
            return true;
        }

        UUID owner;
        try {
            owner = UUID.fromString(args[1]);
        } catch (Exception ex) {
            p.sendMessage(plugin.cfg().msg("home-invalid"));
            return true;
        }

        boolean admin = p.isOp() || p.hasPermission("aquaprivate.home.admin");
        if (!admin) {
            if (!p.hasPermission("aquaprivate.home")) {
                p.sendMessage(plugin.cfg().msg("no-permission"));
                return true;
            }
            if (!owner.equals(p.getUniqueId())) {
                p.sendMessage(plugin.cfg().msg("no-permission"));
                return true;
            }
        }

        String worldName = args[2];
        int x, y, z;
        try {
            x = Integer.parseInt(args[3]);
            y = Integer.parseInt(args[4]);
            z = Integer.parseInt(args[5]);
        } catch (Exception ex) {
            p.sendMessage(plugin.cfg().msg("home-invalid"));
            return true;
        }

        // Verify this private really belongs to that owner (anti spoof)
        var list = plugin.wg().listPrivates(owner);
        boolean ok = false;
        for (var e : list) {
            if (e.world().equalsIgnoreCase(worldName) && e.x() == x && e.y() == y && e.z() == z) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            p.sendMessage(plugin.cfg().msg("home-invalid"));
            return true;
        }

        plugin.teleportService().teleportWithEffects(p, worldName, x, y, z);
        return true;
    }

@Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            String a = args[0].toLowerCase(Locale.ROOT);
            if ("give".startsWith(a)) out.add("give");
            if ("givetnt".startsWith(a)) out.add("givetnt");
            if ("list".startsWith(a)) out.add("list");
            if ("home".startsWith(a)) out.add("home");
            if ("hometp".startsWith(a)) out.add("hometp");
            return out;
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
                }
                return out;
            }

            if (args[0].equalsIgnoreCase("givetnt")) {
                if (!sender.isOp()) return out;
                String pref = args[1].toLowerCase(Locale.ROOT);
                for (String t : plugin.uniqueTnt().typeIds()) {
                    if (t.toLowerCase(Locale.ROOT).startsWith(pref)) out.add(t);
                }
                return out;
            }

            if (args[0].equalsIgnoreCase("list")) {
                if (sender.isOp() || sender.hasPermission("aquaprivate.list.admin")) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
                    }
                }
                return out;
            }

            if (args[0].equalsIgnoreCase("home")) {
                if (sender.isOp() || sender.hasPermission("aquaprivate.home.admin")) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
                    }
                }
                return out;
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("givetnt")) {
            if (!sender.isOp()) return out;
            String pref = args[2].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(pref)) out.add(p.getName());
            }
            return out;
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("givetnt")) {
            out.add("1");
            out.add("16");
            out.add("32");
            out.add("64");
            return out;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            out.add("1");
            out.add("16");
            out.add("32");
            out.add("64");
            return out;
        }

        return out;
    }
}
