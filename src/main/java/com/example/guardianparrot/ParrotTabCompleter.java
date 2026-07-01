package com.example.guardianparrot;


import org.bukkit.persistence.PersistentDataType;import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class ParrotTabCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!(sender instanceof Player)) return null;
        List<String> base = new ArrayList<>(Arrays.asList("give","summon","unsummon"));
        if (sender.hasPermission("guardianparrot.admin.reload")) base.add("reload");
        if (args.length == 1){
            String pref = args[0].toLowerCase(Locale.ROOT);
            return base.stream().filter(s -> s.startsWith(pref)).collect(Collectors.toList());
        }
        return List.of();
    }
}
