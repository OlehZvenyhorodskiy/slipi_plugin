package com.fermerpets;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class UnsummonListCommand implements CommandExecutor {
    private final FermerPetsModule plugin;
    public UnsummonListCommand(FermerPetsModule plugin){ this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { 
            sender.sendMessage("Run in-game."); 
            return true; 
        }
        
        Player p = (Player) sender;
        
        if (!p.hasPermission("fermerpets.unsummon")) {
            p.sendMessage(ChatColor.RED + "У вас нет прав для удаления фермеров.");
            return true;
        }
        
        PetManager manager = plugin.getManager();
        int total = manager.countRecordedPets(p.getUniqueId());
        if (total <= 0) {
            manager.unsummonAllEntitiesOnly(p);
            p.sendMessage(ChatColor.YELLOW + "У вас нет призванных фермеров.");
            return true;
        }
        p.sendMessage(ChatColor.GOLD + "Выберите питомца для удаления:");
        for (int i = 1; i <= total; i++) {
            p.sendMessage(ChatColor.YELLOW + "[" + i + "] " + ChatColor.GRAY + "/fermerpets unsummon " + i);
        }
        return true;
    }
}
