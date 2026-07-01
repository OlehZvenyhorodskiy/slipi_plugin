package com.fermerpets;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Stores shared amethyst balance used by Farmer menu (slots 36-53). */
public final class MenuFuelStore {
    private final FermerPetsModule plugin;
    private final File file;
    private FileConfiguration cfg;

    public MenuFuelStore(FermerPetsModule plugin){
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "menufuel.yml");
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()){
            try { file.createNewFile(); } catch (IOException ignored) {}
        }
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    private void reload(){
        try { cfg.load(file); } catch (Throwable ignored) {}
    }

    private void save(){
        try { cfg.save(file); } catch (IOException ignored) {}
    }

    public int get(UUID owner){
        if (owner == null) return 0;
        reload();
        return Math.max(0, cfg.getInt("players."+owner+".amethysts", 0));
    }

    public void set(UUID owner, int amount){
        if (owner == null) return;
        if (amount < 0) amount = 0;
        cfg.set("players."+owner+".amethysts", amount);
        save();
    }

    /** Adds (or subtracts) balance and returns new value. */
    public int add(UUID owner, int delta){
        if (owner == null) return 0;
        reload();
        int cur = Math.max(0, cfg.getInt("players."+owner+".amethysts", 0));
        int next = Math.max(0, cur + delta);
        cfg.set("players."+owner+".amethysts", next);
        save();
        return next;
    }

    /** Tries to take amount from balance. Returns true if successful. */
    public boolean take(UUID owner, int amount){
        if (owner == null) return false;
        if (amount <= 0) return true;
        reload();
        int cur = Math.max(0, cfg.getInt("players."+owner+".amethysts", 0));
        if (cur < amount) return false;
        cfg.set("players."+owner+".amethysts", cur - amount);
        save();
        return true;
    }
}
