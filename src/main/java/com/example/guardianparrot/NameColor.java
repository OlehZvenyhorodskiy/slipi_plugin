package com.example.guardianparrot;

import org.bukkit.persistence.PersistentDataType;import org.bukkit.ChatColor;
public enum NameColor {
    BLUE(ChatColor.BLUE), GOLD(ChatColor.GOLD), GRAY(ChatColor.GRAY);
    private final ChatColor color;
    NameColor(ChatColor c){ this.color=c; }
    public ChatColor get(){ return color; }
}
