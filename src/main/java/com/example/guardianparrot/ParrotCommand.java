package com.example.guardianparrot;
import com.example.guardianparrot.GuardianParrotModule;


import org.bukkit.persistence.PersistentDataType;import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ParrotCommand implements CommandExecutor {
    private final ParrotManager manager;
    public ParrotCommand(ParrotManager manager){ this.manager = manager; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок."); return true; }
        if (args.length==0){ p.sendMessage("/"+label+" give|summon|unsummon|reload"); return true; }
        switch (args[0].toLowerCase()){
            case "give" -> {
            if (!p.hasPermission("guardianparrot.give")) { p.sendMessage("§cНедостаточно прав."); return true; } manager.giveEgg(p); p.sendMessage("Выдано яйцо хранителя."); }
            case "summon" -> {
            if (!p.hasPermission("guardianparrot.summon")) { p.sendMessage("§cНедостаточно прав."); return true; }
                p.sendMessage("§aПризван хранитель"); Location loc = p.getLocation(); manager.summonParrot(p, loc); }
            case "unsummon" -> {
            if (!p.hasPermission("guardianparrot.unsummon")) { p.sendMessage("§cНедостаточно прав."); return true; } manager.unsummonParrot(p, true); }
            case "reload" -> {
            if (!p.hasPermission("guardianparrot.admin.reload")) { p.sendMessage("§cНедостаточно прав."); return true; }
                if (!p.hasPermission("guardianparrot.admin.reload")) { p.sendMessage("Нет прав."); break; }
                try {
                    GuardianParrotModule module = GuardianParrotRuntime.get();
                    manager.shutdownAll();
                    module.reload();
                    manager.reload();
                    manager.cleanupWorldOrphans();
                    p.sendMessage("GuardianParrot перезагружен.");
                } catch (Throwable t){ p.sendMessage("Ошибка: "+t.getMessage()); t.printStackTrace(); }
            }
            default -> p.sendMessage("/"+label+" give|summon|unsummon|reload");
        }
        return true;
    }
}
