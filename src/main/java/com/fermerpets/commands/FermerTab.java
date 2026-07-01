
package com.fermerpets.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class FermerTab implements TabCompleter {
    private static final List<String> SUB = Arrays.asList("give","summon","unsummon","menu","reload");
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            String p = args[0].toLowerCase();
            for (String s : SUB) if (s.startsWith(p)) out.add(s);
            return out;
        }
        if (args.length == 2 && ("menu".equalsIgnoreCase(args[0]) || "unsummon".equalsIgnoreCase(args[0])) && sender instanceof Player) {
            Player pl = (Player) sender;
            int count = countFarmers(pl.getUniqueId());
            List<String> out = new ArrayList<>();
            for (int i=1;i<=count;i++) out.add(Integer.toString(i));
            return out;
        }
        return List.of();
    }

    private int countFarmers(UUID owner){
        try {
            File f = new File(Bukkit.getPluginManager().getPlugin("FermerPets").getDataFolder(), "players.yml");
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            int n = y.getStringList("players."+owner+".pets").size();
            if (n==0) n = y.getStringList("owners."+owner+".pets").size();
            if (n==0) n = y.getStringList("players."+owner).size();
            if (n==0) n = y.getStringList("players."+owner+".entities").size();
            if (n==0) n = y.getStringList("guardians."+owner+".list").size();
            return n;
        } catch (Exception e){
            return 0;
        }
    }
}
