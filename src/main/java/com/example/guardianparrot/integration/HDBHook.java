package com.example.guardianparrot.integration;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class HDBHook {
    private static boolean ok;
    private static Object api;
    private static Method getItemHead;

    public static void init() {
        Plugin p = Bukkit.getPluginManager().getPlugin("HeadDatabase");
        if (p == null || !p.isEnabled()) return;
        try {
            Class<?> c = Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI");
            Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            api = ctor.newInstance();
            getItemHead = c.getMethod("getItemHead", String.class);
            ok = true;
        } catch (Throwable ignored) {}
    }
    public static ItemStack get(String id){
        if (!ok) return null;
        try {
            Object o = getItemHead.invoke(api, id);
            return (o instanceof ItemStack) ? (ItemStack) o : null;
        } catch (Throwable ignored){ return null; }
    }
}
