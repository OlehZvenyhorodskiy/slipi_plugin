package me.aquaprivate.util;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.hook.AquaClansHook;
import me.aquaprivate.model.PrivateRecord;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Central placeholder replacement used by GUI + holograms.
 * Keep everything here so new placeholders work everywhere.
 */
public final class PlaceholderUtil {

    private PlaceholderUtil() {}

    public static String apply(AquaPrivatePlugin plugin, PrivateRecord rec, String s, String ownerName) {
        return apply(plugin, rec, s, ownerName, null);
    }

    /**
     * Applies AquaPrivate placeholders and, if available, PlaceholderAPI placeholders.
     * Viewer is required for placeholders like %aquaclans_name%.
     */
    public static String apply(AquaPrivatePlugin plugin, PrivateRecord rec, String s, String ownerName, Player viewer) {
        return apply(plugin, rec, s, ownerName, (OfflinePlayer) viewer);
    }

    /**
     * Same as {@link #apply(AquaPrivatePlugin, PrivateRecord, String, String, Player)} but accepts OfflinePlayer.
     * Used by holograms (owner can be offline).
     */
    public static String apply(AquaPrivatePlugin plugin, PrivateRecord rec, String s, String ownerName, OfflinePlayer viewer) {
        if (s == null) return "";

        int lvl = Math.max(1, Math.min(20, rec.level));
        int size = PrivateLevelUtil.sizeForLevel(lvl);

        String typeKey = (rec.privateType == null || rec.privateType.isEmpty()) ? "normal" : rec.privateType;
        String typeDisplay = plugin.getConfig().getString("settings.privatetype." + typeKey, typeKey);

        // фермер: статус отображения зависит от условий доступа
        // For GUI we use viewer; for holograms (viewer can be null) we check owner.
        boolean clanOk = (viewer != null) ? AquaClansHook.hasClan(viewer.getUniqueId()) : AquaClansHook.hasClan(rec.owner);
        boolean lvlOk = lvl >= 20;
        String farmerDisplay;
        if (lvlOk && clanOk) {
            farmerDisplay = plugin.getConfig().getString("settings.fermer.status.available", "&aдоступно");
        } else {
            farmerDisplay = plugin.getConfig().getString("settings.fermer.status.locked", "&cнедоступно");
        }
        // фермер: "топливо" = аметисты из меню фермеров (menufuel.yml в embedded FermerPets)
        // В старых сборках оно могло храниться в rec.farmerFuel, но теперь источник истины — menufuel.yml.
        String fuel;
        int farmerFuelAmt = 0;
        try {
            if (plugin.fermer() != null && plugin.fermer().getMenuService() != null
                    && plugin.fermer().getMenuService().getMenuFuelStore() != null && rec.owner != null) {
                farmerFuelAmt = plugin.fermer().getMenuService().getMenuFuelStore().get(rec.owner);
            } else {
                farmerFuelAmt = Integer.parseInt(rec.farmerFuel == null || rec.farmerFuel.isEmpty() ? "0" : rec.farmerFuel);
            }
        } catch (Throwable ignored) {
            try { farmerFuelAmt = Integer.parseInt(rec.farmerFuel == null || rec.farmerFuel.isEmpty() ? "0" : rec.farmerFuel); } catch (Throwable ignored2) { farmerFuelAmt = 0; }
        }
        fuel = String.valueOf(Math.max(0, farmerFuelAmt));

        // Farmer EXP transfer placeholders
        String expOn = plugin.getConfig().getString("settings.fermer.exp-transfer.active", "&6перекачка опыта активна");
        String expOff = plugin.getConfig().getString("settings.fermer.exp-transfer.inactive", "&fперекачка опыта не активна");
        String expStatus = (rec.farmerExpTransferEnabled && rec.farmerExpLevels < 100) ? expOn : expOff;
        String expLevels = String.valueOf(Math.max(0, Math.min(100, rec.farmerExpLevels)));

        // Guardian EXP transfer placeholders
        // %explevlguard% - transfer status (active/inactive)
        // %explevlplayerprivatguard% - stored levels progress/complete
        final int needLevels = plugin.getConfig().getInt("settings.guard.required-levels", 100);
        int gLvl = Math.max(0, Math.min(needLevels, rec.guardExpLevels));

        String gActive = plugin.getConfig().getString("settings.guard.exp-transfer.active", "&6перекачка опыта активна");
        String gInactive = plugin.getConfig().getString("settings.guard.exp-transfer.inactive", "&fперекачка опыта не активна");
        String guardTransferStatus = (rec.guardExpTransferEnabled && gLvl < needLevels) ? gActive : gInactive;

        String gProgressFmt = plugin.getConfig().getString("settings.guard.exp-placeholder.progress", "&e%current%&7");
        String gDoneFmt = plugin.getConfig().getString("settings.guard.exp-placeholder.complete", "&2готово");
        String guardLevelsPlaceholder = (gLvl >= needLevels) ? gDoneFmt : gProgressFmt.replace("%current%", String.valueOf(gLvl));

        // Leveling quest placeholders
        String on = plugin.getConfig().getString("settings.level-quest.status.active", "&6активно");
        String off = plugin.getConfig().getString("settings.level-quest.status.inactive", "&7неактивно");
        String max = plugin.getConfig().getString("settings.level-quest.status.max", "&2макс уровень");
        String status = (lvl >= 20) ? max : (rec.levelQuestEnabled ? on : off);

        String out = s;
        out = out.replace("%player%", ownerName == null ? "" : ownerName);
        out = out.replace("%owner%", ownerName == null ? "" : ownerName);

        out = out.replace("%levlprivata%", String.valueOf(lvl));
        out = out.replace("%priv_size%", String.valueOf(size));
        // On max level, private becomes clan-type.
        // (We also persist this in PrivateStore migration + on upgrade.)
        if (lvl >= 20) {
            String clanDisplay = plugin.getConfig().getString("settings.privatetype.clan", "&6Клановый");
            out = out.replace("%privatetype%", clanDisplay);
        } else {
            out = out.replace("%privatetype%", typeDisplay);
        }
        out = out.replace("%fermerprivat%", farmerDisplay);
        out = out.replace("%fermerprivattoplivo%", fuel);

        // Guard placeholders
        String guardStatusAvailable = plugin.getConfig().getString("settings.guard.status.available", "&aдоступно");
        String guardStatusLocked = plugin.getConfig().getString("settings.guard.status.locked", "&cнедоступно");
        String guardDisplay = (lvlOk && clanOk) ? guardStatusAvailable : guardStatusLocked;

        // Guardian fuel (amethysts) is REGION-AWARE.
        // If regionId is missing (legacy), fall back to global.
        int guardFuelAmt = 0;
        try {
            if (plugin.guardFuel() != null && rec.owner != null) {
                if (rec.regionId != null && !rec.regionId.isBlank()) {
                    guardFuelAmt = plugin.guardFuel().get(rec.owner, rec.regionId);
                } else {
                    guardFuelAmt = plugin.guardFuel().get(rec.owner);
                }
            }
        } catch (Throwable ignored) {}

        out = out.replace("%guardprivat%", plugin.cfg().colorize(guardDisplay));
        out = out.replace("%guardprivattoplivo%", String.valueOf(Math.max(0, guardFuelAmt)));
        out = out.replace("%explevlferner%", plugin.cfg().colorize(expStatus));
        out = out.replace("%explevlplayerprivat%", expLevels);
        out = out.replace("%explevlguard%", plugin.cfg().colorize(guardTransferStatus));
        out = out.replace("%explevlplayerprivatguard%", plugin.cfg().colorize(guardLevelsPlaceholder));

        String glovesOff = plugin.getConfig().getString("settings.placeholders.glow.disabled", "&7подсветка отключена");
        String glovesOn = plugin.getConfig().getString("settings.placeholders.glow.enabled", "&2подсветка включена");
        out = out.replace("%priv_gloves%", plugin.cfg().colorize(rec.borderGlow ? glovesOn : glovesOff));

        // Fermer menu slots placeholders
        String fOn = plugin.getConfig().getString("settings.fermer.slots.active", "&2Активен");
        String fOff = plugin.getConfig().getString("settings.fermer.slots.inactive", "&7не активен");

        boolean a1 = rec.fermer1;
        boolean a2 = rec.fermer2;
        boolean a3 = rec.fermer3;
        // Prefer live state from FermerPets players.yml (fixes incorrect placeholders after /reload).
        try {
            if (plugin.fermer() != null && plugin.fermer().getManager() != null && rec.owner != null) {
                org.bukkit.configuration.file.FileConfiguration players = plugin.fermer().getManager().getPlayersYaml();
                com.fermerpets.PlayersStore ps = new com.fermerpets.PlayersStore(players);
                ps.migrateIfNeeded(rec.owner);
                a1 = ps.getFarmer(rec.owner, 1) != null;
                a2 = ps.getFarmer(rec.owner, 2) != null;
                a3 = ps.getFarmer(rec.owner, 3) != null;
            }
        } catch (Throwable ignored) {}

        out = out.replace("%fermer1%", plugin.cfg().colorize(a1 ? fOn : fOff));
        out = out.replace("%fermer2%", plugin.cfg().colorize(a2 ? fOn : fOff));
        out = out.replace("%fermer3%", plugin.cfg().colorize(a3 ? fOn : fOff));

        // AquaClans: menu uses custom %level% token (NOT a PlaceholderAPI token)
        // so we resolve it ourselves for both GUI and holograms.
        UUID clanUuid = (viewer != null) ? viewer.getUniqueId() : rec.owner;
        out = out.replace("%level%", String.valueOf(AquaClansHook.getClanLevel(clanUuid)));

        out = out.replace("%levloffon%", plugin.cfg().colorize(status));
        out = out.replace("%entytyamound%", String.valueOf(Math.max(0, rec.levelQuestEntityKills)));
        out = out.replace("%minersamound%", String.valueOf(Math.max(0, rec.levelQuestMined)));
        out = out.replace("%killamound%", String.valueOf(Math.max(0, rec.levelQuestPlayerKills)));

        // PlaceholderAPI (to resolve %aquaclans_name% and other external placeholders)
        out = applyPapiIfPresent(viewer, out);

        return out;
    }

    private static String applyPapiIfPresent(OfflinePlayer viewer, String text) {
        if (viewer == null || text == null) return text;
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return text;
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            java.lang.reflect.Method m = papi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            Object out = m.invoke(null, viewer, text);
            return out == null ? text : String.valueOf(out);
        } catch (Throwable ignored) {
            return text;
        }
    }
}
