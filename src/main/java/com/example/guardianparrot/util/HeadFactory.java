package com.example.guardianparrot.util;

import com.example.guardianparrot.integration.HDBHook;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import com.example.guardianparrot.GPPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public final class HeadFactory {
    private static final String DEFAULT_BASE64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODM2Yjk2ZTdjNTdkZjk5Yzc3M2YyZmJjZjRmMjYwZjA5M2NmNjZlZTEyNjk4MjQ3NzFmZjk4MzA4NGEyNzA1YSJ9fX0=";

    public static ItemStack parrot(GPPlugin plugin){
        String name = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("parrot-head.display-name", "&bParrot"));
        String source = plugin.getConfig().getString("parrot-head.source","HDB");

        if ("HDB".equalsIgnoreCase(source)) {
            String id = plugin.getConfig().getString("parrot-head.hdb-id","");
            if (!id.isEmpty()){
                ItemStack hdb = HDBHook.get(id);
                if (hdb != null) {
                    var m = hdb.getItemMeta();
                    if (m != null) { m.setDisplayName(name); hdb.setItemMeta(m); }
                    return hdb;
                }
            }
        }
        // fallback base64
        String b64 = plugin.getConfig().getString("parrot-head.base64", DEFAULT_BASE64);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        try {
            String hash = extractHash(b64);
            UUID uuid = UUID.nameUUIDFromBytes(b64.getBytes(StandardCharsets.UTF_8));
            PlayerProfile prof = Bukkit.createPlayerProfile(uuid, "Parrot");
            PlayerTextures tx = prof.getTextures();
            tx.setSkin(new URL("http://textures.minecraft.net/texture/" + hash));
            prof.setTextures(tx);
            meta.setOwnerProfile(prof);
        } catch (Throwable ignored){}
        meta.setDisplayName(name);
        head.setItemMeta(meta);
        return head;
    }

    private static String extractHash(String base64){
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            String key = "textures.minecraft.net/texture/";
            int i = json.indexOf(key);
            if (i>=0){
                int s = i + key.length();
                int e = json.indexOf('"', s);
                return (e>s)? json.substring(s,e):"";
            }
        } catch (Throwable ignored){}
        return "";
    }
}
