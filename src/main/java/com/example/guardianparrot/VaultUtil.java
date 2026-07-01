package com.example.guardianparrot;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class VaultUtil {
    private VaultUtil(){}
    public static String getPrefix(Player player){
        try {
            Class<?> chatClass = Class.forName("net.milkbowl.vault.chat.Chat");
            Object reg = Bukkit.getServicesManager().getRegistration(chatClass);
            if (reg == null) return "";
            Object provider = reg.getClass().getMethod("getProvider").invoke(reg);
            if (provider == null) return "";
            try {
                return colorize((String) provider.getClass().getMethod("getPlayerPrefix", org.bukkit.World.class, OfflinePlayer.class)
                        .invoke(provider, player.getWorld(), (OfflinePlayer)player));
            } catch (NoSuchMethodException nsme) {
                try {
                    return colorize((String) provider.getClass().getMethod("getPlayerPrefix", Player.class).invoke(provider, player));
                } catch (NoSuchMethodException ignore) { return ""; }
            }
        } catch (Throwable ignored){ return ""; }
    }
    private static String colorize(String s){
        if (s == null) return "";
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }
}
