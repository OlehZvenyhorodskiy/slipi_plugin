
package com.fermerpets;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public final class Debug {
    private static java.io.File logFile;

    // We intentionally re-check the flag from disk periodically so that server owners
    // can toggle debug in fermerpets/config.yml without needing a plugin reload.
    private static volatile long lastFlagCheckMs = 0L;
    private static volatile boolean cachedEnabled = false;
    private static final long FLAG_REFRESH_MS = 2000L;
    private static synchronized void ensureFile(){
        try {
            if (logFile == null) {
                java.io.File dir = FermerPetsModule.get().getDataFolder();
                if (!dir.exists()) dir.mkdirs();
                logFile = new java.io.File(dir, "farmer_behavior_report.txt");
            }
        } catch (Throwable ignored) {}
    }
    private static void writeToFile(String line){
        try {
            ensureFile();
            if (logFile == null) return;
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date());
            String out = "["+ts+"] "+line+"\n";
            java.nio.file.Files.writeString(logFile.toPath(), out, java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {}
    }

    private Debug(){}
    public static boolean enabled(){
        // Default OFF to avoid console spam on production servers.
        try {
            long now = System.currentTimeMillis();
            if (now - lastFlagCheckMs < FLAG_REFRESH_MS) {
                return cachedEnabled;
            }
            lastFlagCheckMs = now;

            FermerPetsModule m = FermerPetsModule.get();
            if (m == null) {
                cachedEnabled = false;
                return false;
            }

            // Read the embedded config as the primary source.
            boolean enabled = false;
            try {
                // Try hot-read from disk (plugins/AquaPrivate/fermerpets/config.yml)
                java.io.File f = new java.io.File(m.getDataFolder(), "config.yml");
                if (f.exists()) {
                    org.bukkit.configuration.file.FileConfiguration yc =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                    enabled = yc.getBoolean("debug.enable", false);
                } else if (m.getConfig() != null) {
                    enabled = m.getConfig().getBoolean("debug.enable", false);
                }
            } catch (Throwable ignored) {
                enabled = (m.getConfig() != null) && m.getConfig().getBoolean("debug.enable", false);
            }

            // Global master switch in the HOST config (plugins/AquaPrivate/config.yml):
            // - If host debug.enable is NOT set, we treat it as FALSE (debug OFF).
            // - Debug can only be enabled when host debug.enable=true.
            // This guarantees that setting debug.enable: false actually stops spam.
            try {
                if (m.plugin() != null) {
                    boolean hostEnabled = false;

                    // 1) Hot-read from disk
                    try {
                        java.io.File hostFile = new java.io.File(m.plugin().getDataFolder(), "config.yml");
                        if (hostFile.exists()) {
                            org.bukkit.configuration.file.FileConfiguration hc =
                                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(hostFile);
                            hostEnabled = hc.getBoolean("debug.enable", false);
                        }
                    } catch (Throwable ignored2) {}

                    // 2) Fallback to in-memory config
                    if (!hostEnabled && m.plugin().getConfig() != null) {
                        hostEnabled = m.plugin().getConfig().getBoolean("debug.enable", false);
                    }

                    if (!hostEnabled) {
                        enabled = false;
                    }
                }
            } catch (Throwable ignored) {}

            cachedEnabled = enabled;
            return enabled;
        } catch (Throwable t){
            cachedEnabled = false;
            return false;
        }
    }
    public static void log(String msg){
        if (!enabled()) return;
        // also document behavior to a txt file for offline analysis
        writeToFile(msg);
        try { FermerPetsModule.get().getLogger().info("[DEBUG] " + msg); } catch (Throwable ignored){}
    }
    public static void logPet(Entity pet, String msg){
        if (!enabled()) return;
        String who = "pet=" + (pet==null? "null" : pet.getUniqueId()+"@"+pet.getWorld().getName());
        log(who + " | " + msg);
    }
    public static void logOwner(Player p, String msg){
        if (!enabled()) return;
        String who = "owner=" + (p==null? "null" : p.getUniqueId()+"("+p.getName()+")");
        log(who + " | " + msg);
    }
}
