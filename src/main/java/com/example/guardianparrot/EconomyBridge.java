package com.example.guardianparrot;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

public final class EconomyBridge {
    private static Object econ; // Vault Economy provider

    private EconomyBridge(){}

    private static Object getVaultEconomy(){
        if (econ != null) return econ;
        try {
            Class<?> econClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object reg = Bukkit.getServicesManager().getRegistration(econClass);
            if (reg == null) return null;
            return (econ = reg.getClass().getMethod("getProvider").invoke(reg));
        } catch (Throwable t){
            return null;
        }
    }

    public static boolean hasEconomy(){
        if (getVaultEconomy() != null) return true;
        return hasXConomy();
    }

    public static boolean deposit(Player player, double amount){
        if (player == null || amount <= 0) return false;
        // 1) Try Vault first
        try {
            Object e = getVaultEconomy();
            if (e != null){
                Class<?> econClass = e.getClass();
                Object resp;
                try {
                    resp = econClass.getMethod("depositPlayer", Player.class, double.class).invoke(e, player, amount);
                } catch (NoSuchMethodException ignore){
                    resp = econClass.getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(e, (OfflinePlayer)player, amount);
                }
                try {
                    Boolean ok = (Boolean) resp.getClass().getMethod("transactionSuccess").invoke(resp);
                    if (ok != null) return ok;
                } catch (Throwable ignored){}
                return true;
            }
        } catch (Throwable ignore){}
        // 2) Fallback: XConomy direct API (best-effort, via reflection)
        try {
            Class<?> api = Class.forName("me.yic.xconomy.api.XConomyAPI");
            try {
                // new API style: XConomyAPI.add(UUID, double)
                Method m = api.getMethod("add", UUID.class, double.class);
                m.invoke(null, player.getUniqueId(), amount);
                return true;
            } catch (NoSuchMethodException nsme){
                try {
                    // older style: XConomyAPI.deposit(UUID, double) or deposit(Player, double)
                    Method m = api.getMethod("deposit", UUID.class, double.class);
                    m.invoke(null, player.getUniqueId(), amount);
                    return true;
                } catch (NoSuchMethodException nsme2){
                    try {
                        Method m = api.getMethod("deposit", Player.class, double.class);
                        m.invoke(null, player, amount);
                        return true;
                    } catch (NoSuchMethodException ignore3){}
                }
            }
        } catch (Throwable ignore){}
        return false;
    }

    private static boolean hasXConomy(){
        try {
            Class.forName("me.yic.xconomy.api.XConomyAPI");
            return true;
        } catch (Throwable t){
            return false;
        }
    }
}