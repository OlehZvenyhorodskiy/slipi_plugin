package com.example.guardianparrot;


import org.bukkit.persistence.PersistentDataType;import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CommandUnsummon implements CommandExecutor {
    private final ParrotManager manager;
    public CommandUnsummon(ParrotManager manager){ this.manager = manager; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок."); return true; }
        if (!p.hasPermission("guardianparrot.unsummon")) { p.sendMessage("§cНедостаточно прав."); return true; }
        manager.unsummonParrot(p, true);
        return true;
    }
}
