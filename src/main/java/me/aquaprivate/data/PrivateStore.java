package me.aquaprivate.data;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class PrivateStore {
    private final AquaPrivatePlugin plugin;
    private final File file;
    private YamlConfiguration yaml;

    private final Map<String, PrivateRecord> byLocation = new HashMap<>();
    private final Map<String, PrivateRecord> byRegion = new HashMap<>();

    public PrivateStore(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "privates.yml");
    }

    public void load() {
        if (!file.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Не удалось создать privates.yml: " + e.getMessage());
            }
        }
        yaml = YamlConfiguration.loadConfiguration(file);

        byLocation.clear();
        byRegion.clear();

        ConfigurationSection sec = yaml.getConfigurationSection("privates");
        if (sec == null) return;

        boolean changed = false;

        for (String id : sec.getKeys(false)) {
            ConfigurationSection p = sec.getConfigurationSection(id);
            if (p == null) continue;

            PrivateRecord r = new PrivateRecord();
            r.blockKey = p.getString("blockKey", "DIAMOND_ORE");
            r.world = p.getString("world", "world");
            r.x = p.getInt("x");
            r.y = p.getInt("y");
            r.z = p.getInt("z");
            r.regionId = p.getString("regionId", id);
            r.level = Math.max(1, Math.min(20, p.getInt("level", 1)));
            r.privateType = p.getString("privateType", "normal");
            r.farmerActive = p.getBoolean("farmerActive", false);
            r.farmerFuel = p.getString("farmerFuel", "");

            r.farmerExpTransferEnabled = p.getBoolean("farmerExpTransferEnabled", false);
            r.farmerExpLevels = Math.max(0, Math.min(100, p.getInt("farmerExpLevels", 0)));

            r.guardExpTransferEnabled = p.getBoolean("guardExpTransferEnabled", false);
            r.guardExpLevels = Math.max(0, Math.min(100, p.getInt("guardExpLevels", 0)));

            r.fermer1 = p.getBoolean("fermer1", false);
            r.fermer2 = p.getBoolean("fermer2", false);
            r.fermer3 = p.getBoolean("fermer3", false);
            r.borderGlow = p.getBoolean("borderGlow", false);

            r.levelQuestEnabled = p.getBoolean("levelQuestEnabled", false);
            r.levelQuestEntityKills = p.getInt("levelQuestEntityKills", 0);
            r.levelQuestMined = p.getInt("levelQuestMined", 0);
            r.levelQuestPlayerKills = p.getInt("levelQuestPlayerKills", 0);

            // Migration / consistency: max level privates are clan and cannot be leveled further.
            if (r.level >= 20) {
                if (!"clan".equalsIgnoreCase(r.privateType)) {
                    r.privateType = "clan";
                    changed = true;
                }
                if (r.levelQuestEnabled || r.levelQuestEntityKills != 0 || r.levelQuestMined != 0 || r.levelQuestPlayerKills != 0) {
                    r.levelQuestEnabled = false;
                    r.levelQuestEntityKills = 0;
                    r.levelQuestMined = 0;
                    r.levelQuestPlayerKills = 0;
                    changed = true;
                }
            }

            // If farmer payment is complete, stop siphoning.
            if (r.farmerExpLevels >= 100 && r.farmerExpTransferEnabled) {
                r.farmerExpTransferEnabled = false;
                changed = true;
            }

            // If guardian payment is complete, stop siphoning.
            if (r.guardExpLevels >= 100 && r.guardExpTransferEnabled) {
                r.guardExpTransferEnabled = false;
                changed = true;
            }

            String owner = p.getString("owner", "");
            try {
                r.owner = UUID.fromString(owner);
            } catch (Exception ignored) {
                continue;
            }

            for (String m : p.getStringList("members")) {
                try {
                    r.members.add(UUID.fromString(m));
                } catch (Exception ignored) {
                }
            }

            for (String h : p.getStringList("holograms")) {
                try {
                    r.holograms.add(UUID.fromString(h));
                } catch (Exception ignored) {
                }
            }

            byLocation.put(r.locKey(), r);
            byRegion.put(r.regionId, r);
        }

        if (changed) {
            save();
        }
    }

    public void save() {
        if (yaml == null) yaml = new YamlConfiguration();
        yaml.set("privates", null);

        for (PrivateRecord r : byLocation.values()) {
            String path = "privates." + r.regionId;
            yaml.set(path + ".blockKey", r.blockKey);
            yaml.set(path + ".world", r.world);
            yaml.set(path + ".x", r.x);
            yaml.set(path + ".y", r.y);
            yaml.set(path + ".z", r.z);
            yaml.set(path + ".regionId", r.regionId);
            yaml.set(path + ".level", r.level);
            yaml.set(path + ".privateType", r.privateType);
            yaml.set(path + ".farmerActive", r.farmerActive);
            yaml.set(path + ".farmerFuel", r.farmerFuel);
            yaml.set(path + ".farmerExpTransferEnabled", r.farmerExpTransferEnabled);
            yaml.set(path + ".farmerExpLevels", r.farmerExpLevels);

            yaml.set(path + ".guardExpTransferEnabled", r.guardExpTransferEnabled);
            yaml.set(path + ".guardExpLevels", r.guardExpLevels);
            yaml.set(path + ".fermer1", r.fermer1);
            yaml.set(path + ".fermer2", r.fermer2);
            yaml.set(path + ".fermer3", r.fermer3);
            yaml.set(path + ".borderGlow", r.borderGlow);

            yaml.set(path + ".levelQuestEnabled", r.levelQuestEnabled);
            yaml.set(path + ".levelQuestEntityKills", r.levelQuestEntityKills);
            yaml.set(path + ".levelQuestMined", r.levelQuestMined);
            yaml.set(path + ".levelQuestPlayerKills", r.levelQuestPlayerKills);
            yaml.set(path + ".owner", r.owner.toString());
            List<String> mem = new ArrayList<>();
            for (UUID u : r.members) mem.add(u.toString());
            yaml.set(path + ".members", mem);

            List<String> holos = new ArrayList<>();
            for (UUID u : r.holograms) holos.add(u.toString());
            yaml.set(path + ".holograms", holos);
        }

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось сохранить privates.yml: " + e.getMessage());
        }
    }

    public Optional<PrivateRecord> byLocation(String world, int x, int y, int z) {
        return Optional.ofNullable(byLocation.get(world + ":" + x + ":" + y + ":" + z));
    }

    public Optional<PrivateRecord> byRegionId(String regionId) {
        return Optional.ofNullable(byRegion.get(regionId));
    }

    public Collection<PrivateRecord> all() {
        return Collections.unmodifiableCollection(byLocation.values());
    }

    public java.util.List<PrivateRecord> byOwner(UUID owner) {
        if (owner == null) return java.util.Collections.emptyList();
        java.util.List<PrivateRecord> out = new java.util.ArrayList<>();
        for (PrivateRecord r : byLocation.values()) {
            if (owner.equals(r.owner)) out.add(r);
        }
        return out;
    }

    public void put(PrivateRecord r) {
        byLocation.put(r.locKey(), r);
        byRegion.put(r.regionId, r);
    }

    public void remove(PrivateRecord r) {
        byLocation.remove(r.locKey());
        byRegion.remove(r.regionId);
    }
}