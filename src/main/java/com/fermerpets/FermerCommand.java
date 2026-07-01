package com.fermerpets;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class FermerCommand implements CommandExecutor {
    private final FermerPetsModule plugin;
    private final PetManager manager;

    public FermerCommand(FermerPetsModule plugin, PetManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String lbl, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        Player p = (Player) sender;
        
        if (args.length == 0) {
            if (!p.hasPermission("fermerpets.help")) {
                p.sendMessage(ChatColor.RED + "У вас нет прав для просмотра помощи.");
                return true;
            }
            p.sendMessage(ChatColor.GRAY + "/fermerpets [give|summon|unsummon|menu <№>|reload]");
            return true;
        }

        if (args.length >= 1 && "menu".equalsIgnoreCase(args[0])) {
            if (!p.hasPermission("fermerpets.menu")) {
                p.sendMessage(ChatColor.RED + "У вас нет прав для использования меню фермеров.");
                return true;
            }
            return FermerMenus.handleMenu(p, args);
        }

        switch (args[0].toLowerCase()) {
            case "give":
                if (!p.hasPermission("fermerpets.give")) {
                    p.sendMessage(ChatColor.RED + "У вас нет прав для получения яйца фермера.");
                    return true;
                }
                // /fermerpets give [player] [amount]
                if (args.length >= 2) {
                    org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        p.sendMessage(ChatColor.RED + "Игрок не найден: " + args[1]);
                        return true;
                    }
                    int amount = 1;
                    if (args.length >= 3) {
                        try { amount = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}
                    }
                    for (int i = 0; i < amount; i++) manager.giveEgg(target);
                    p.sendMessage(ChatColor.GREEN + "Выдали яйцо фермера игроку " + target.getName() + " x" + amount);
                } else {
                    manager.giveEgg(p);
                }
                return true;

            case "summon":
                if (!p.hasPermission("fermerpets.summon")) {
                    p.sendMessage(ChatColor.RED + "У вас нет прав для призыва фермера.");
                    return true;
                }
                if (!manager.hasFreeSlot(p)) {
                    p.sendMessage(ChatColor.RED + "Достигнут лимит питомцев (" + manager.getMaxPets() + ")");
                } else {
                    manager.summonPet(p);
                    p.sendMessage(ChatColor.GREEN + "Питомец-фермер призван.");
                }
                return true;

            case "unsummon":
                if (!p.hasPermission("fermerpets.unsummon")) {
                    p.sendMessage(ChatColor.RED + "У вас нет прав для удаления фермера.");
                    return true;
                }
                
                if (args.length < 2) {
                    int total = manager.countRecordedPets(p.getUniqueId());
                    if (total <= 0) {
                        manager.unsummonAllEntitiesOnly(p);
                        p.sendMessage(ChatColor.YELLOW + "У вас нет фермеров.");
                    } else {
                        p.sendMessage(ChatColor.RED + "Укажите номер питомца. Пример: /fermerpets unsummon 1" + (total > 1 ? (".."+total) : ""));
                    }
                    return true;
                }
                
                int farmerIndex;
                try {
                    farmerIndex = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "Некорректный номер. Используйте: /fermerpets unsummon <№>");
                    return true;
                }
                
                int total = manager.countRecordedPets(p.getUniqueId());
                if (farmerIndex < 1 || farmerIndex > total) {
                    p.sendMessage(ChatColor.RED + "Неверный номер. Всего питомцев: " + total + ". Пример: /fermerpets unsummon 1" + (total > 1 ? (".."+total) : ""));
                    return true;
                }

                boolean ok = manager.unsummonAndCleanup(p, farmerIndex);
                if (ok) {
                    p.sendMessage(ChatColor.YELLOW + "Питомец #" + farmerIndex + " убран.");
                } else {
                    p.sendMessage(ChatColor.RED + "Не удалось убрать питомца.");
                }
                return true;

            case "reload":
                if (!p.hasPermission("fermerpets.reload")) {
                    p.sendMessage(ChatColor.RED + "У вас нет прав для перезагрузки конфигурации.");
                    return true;
                }
                plugin.reloadConfig();
                p.sendMessage(ChatColor.AQUA + "Конфигурация перезагружена.");
                return true;

            default:
                if (!p.hasPermission("fermerpets.help")) {
                    p.sendMessage(ChatColor.RED + "Неизвестная команда и нет прав для просмотра помощи.");
                    return true;
                }
                p.sendMessage(ChatColor.GRAY + "/fermerpets [give|summon|unsummon|menu <№>|reload]");
                return true;
        }
    }
}
