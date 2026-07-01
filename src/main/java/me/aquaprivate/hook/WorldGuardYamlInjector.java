package me.aquaprivate.hook;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * WorldGuard регистрирует только известные флаги. После старта реестр флагов блокируется,
 * а PlugManX/горячая загрузка часто происходит уже после блокировки.
 *
 * Чтобы гарантированно получить в regions.yml поля ps-home/ps-tax-autopayer/ps-block-material/farewell-action,
 * мы записываем их напрямую в YAML-файл мира WorldGuard.
 */
public final class WorldGuardYamlInjector {
    private WorldGuardYamlInjector() {}

    public static void writeCustomFlags(World world, String regionId, Map<String, String> flags) {
        try {
            // В Bukkit/Spigot нет Bukkit#getPluginsFolder().
            // Самый совместимый способ — идти от стандартной папки "plugins" относительно корня сервера.
            File wgFolder = new File(new File("plugins"), "WorldGuard");
            File worldsFolder = new File(wgFolder, "worlds");
            File worldFolder = new File(worldsFolder, world.getName());
            File regionsFile = new File(worldFolder, "regions.yml");
            if (!regionsFile.exists()) return;

            YamlConfiguration yml = YamlConfiguration.loadConfiguration(regionsFile);

            ConfigurationSection root = yml.getConfigurationSection("regions");
            if (root == null) {
                // на случай старого формата без секции regions
                root = yml.createSection("regions");
            }

            ConfigurationSection regionSec = root.getConfigurationSection(regionId);
            if (regionSec == null) {
                // если WG ещё не записал регион (редко) — не создаём, чтобы не ломать формат
                return;
            }

            ConfigurationSection flagsSec = regionSec.getConfigurationSection("flags");
            if (flagsSec == null) flagsSec = regionSec.createSection("flags");

            for (var e : flags.entrySet()) {
                flagsSec.set(e.getKey(), e.getValue());
            }

            yml.save(regionsFile);
        } catch (IOException ignored) {
            // намеренно тихо — функционал защиты не зависит от этих полей
        }
    }
}
