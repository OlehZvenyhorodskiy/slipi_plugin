package me.aquaprivate.cmd;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateBlockType;
import me.aquaprivate.util.ItemFactory;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class AquaPrivateCommand implements CommandExecutor {

    private final AquaPrivatePlugin plugin;

    public AquaPrivateCommand(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.cfg().msg("give-usage"));
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("aquaprivate.admin")) {
                sender.sendMessage(plugin.cfg().msg("no-permission"));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(plugin.cfg().msg("give-usage"));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("Player not found: " + args[1]);
                return true;
            }

            String blockKey = args[2].toUpperCase();
            PrivateBlockType type = plugin.cfg().block(blockKey);
            if (type == null) {
                sender.sendMessage(plugin.cfg().msg("unknown-block").replace("%block%", blockKey));
                return true;
            }

            int amount = 1;
            if (args.length >= 4) {
                try { amount = Math.max(1, Integer.parseInt(args[3])); } catch (Exception ignored) {}
            }

            ItemStack item = ItemFactory.createPrivateItem(plugin, type, target.getName());
            item.setAmount(amount);
            target.getInventory().addItem(item);

            sender.sendMessage(plugin.cfg().msg("give-success")
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%block%", plugin.cfg().color(type.name))
                    .replace("%player%", target.getName()));
            return true;
        }

        sender.sendMessage(plugin.cfg().msg("give-usage"));
        return true;
    }
}
