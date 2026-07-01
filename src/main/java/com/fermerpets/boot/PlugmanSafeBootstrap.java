
package com.fermerpets.boot;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlugmanSafeBootstrap {

    public static void wire(JavaPlugin plugin, CommandExecutor mainExec, CommandExecutor unsExec) {
        // bind if provided and command exists
        PluginCommand main = plugin.getCommand("fermerpets");
        if (main != null && mainExec != null) {
            main.setExecutor(mainExec);
            if (mainExec instanceof TabCompleter) main.setTabCompleter((TabCompleter) mainExec);
        }
        PluginCommand uns = plugin.getCommand("fermerunsummon");
        if (uns != null && unsExec != null) {
            uns.setExecutor(unsExec);
            if (unsExec instanceof TabCompleter) uns.setTabCompleter((TabCompleter) unsExec);
        }

        // force sync/reload commands so Brigadier sees them after PlugMan hot-load
        try {
            Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer());
            plugin.getLogger().info("Commands synced via CraftServer#syncCommands()");
        } catch (Throwable t) {
            try {
                Bukkit.getServer().getClass().getMethod("reloadCommands").invoke(Bukkit.getServer());
                plugin.getLogger().info("Commands reloaded via CraftServer#reloadCommands()");
            } catch (Throwable t2) {
                plugin.getLogger().warning("Could not sync/reload commands reflectively: " + t2.getMessage());
            }
        }
    }
}
