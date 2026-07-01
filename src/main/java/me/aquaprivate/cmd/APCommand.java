package me.aquaprivate.cmd;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateRecord;
import me.aquaprivate.util.PrivateLevelUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /ap add <player>           - add member to the private where you stand
 * /ap remove <player>        - remove member from the private where you stand
 *
 * Admin upgrade (for many privates per player):
 * /ap upgrade <player> <private>
 *   where <private> is region id from /aquaprivate list, or number from that list.
 *
 * aliases: ps
 */
public final class APCommand implements CommandExecutor, TabCompleter {

    private final AquaPrivatePlugin plugin;

    public APCommand(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.cfg().msg("ap-usage"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // --- Member management: player-only, uses private at player's location
        if (sub.equals("add") || sub.equals("remove") || sub.equals("del") || sub.equals("delete")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(plugin.cfg().msg("only-player"));
                return true;
            }
            if (args.length < 2) {
                p.sendMessage(plugin.cfg().msg("ap-usage"));
                return true;
            }

            String targetName = args[1];
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                p.sendMessage(plugin.cfg().msg("player-not-found").replace("%player%", targetName));
                return true;
            }

            Optional<PrivateRecord> current = getPrivateAtPlayer(p);
            if (current.isEmpty()) {
                p.sendMessage(plugin.cfg().msg("not-in-private"));
                return true;
            }
            PrivateRecord rec = current.get();

            if (!p.getUniqueId().equals(rec.owner)) {
                p.sendMessage(plugin.cfg().msg("not-owner"));
                return true;
            }

            if (!p.hasPermission("aquaprivate.addmember")) {
                p.sendMessage(plugin.cfg().msg("no-permission"));
                return true;
            }

            if (sub.equals("add")) {
                if (rec.members.contains(target.getUniqueId())) {
                    p.sendMessage(plugin.cfg().msg("member-already"));
                    return true;
                }
                rec.members.add(target.getUniqueId());
                plugin.store().save();
                addMemberToWg(rec, target.getUniqueId());
                p.sendMessage(plugin.cfg().msg("member-added").replace("%player%", target.getName()));
                return true;
            }

            // remove
            rec.members.remove(target.getUniqueId());
            plugin.store().save();
            removeMemberFromWg(rec, target.getUniqueId());
            p.sendMessage(plugin.cfg().msg("member-removed").replace("%player%", target.getName()));
            return true;
        }

        // --- Upgrade
        if (sub.equals("upgrade") || sub.equals("level") || sub.equals("up")) {
            if (!sender.hasPermission("aquaprivate.upgrade")) {
                sender.sendMessage(plugin.cfg().msg("no-permission"));
                return true;
            }

            // Self-upgrade convenience:
            // /ap upgrade                      -> upgrade the private where player is standing
            // /ap upgrade <private>            -> upgrade one of player's privates by id or number
            // Admin upgrade:
            // /ap upgrade <player> <private>
            if (args.length < 3) {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(plugin.cfg().msg("upgrade-usage"));
                    return true;
                }

                UUID ownerUuid = p.getUniqueId();
                String ownerName = p.getName();
                List<PrivateRecord> privs = getPrivatesOf(ownerUuid);
                if (privs.isEmpty()) {
                    p.sendMessage(plugin.cfg().msg("list-empty").replace("%player%", ownerName));
                    return true;
                }

                PrivateRecord targetPriv = null;
                if (args.length >= 2) {
                    targetPriv = resolvePrivateArg(privs, args[1]);
                }
                if (targetPriv == null) {
                    targetPriv = getPrivateAtPlayer(p).orElse(null);
                }
                if (targetPriv == null) {
                    p.sendMessage(plugin.cfg().msg("upgrade-private-not-found")
                            .replace("%player%", ownerName)
                            .replace("%private%", args.length >= 2 ? args[1] : "here"));
                    return true;
                }

                // Only allow upgrading own private (unless OP/admin upgrades using full syntax)
                if (!ownerUuid.equals(targetPriv.owner)) {
                    p.sendMessage(plugin.cfg().msg("no-permission"));
                    return true;
                }

                if (targetPriv.level >= 20) {
                    p.sendMessage(plugin.cfg().msg("upgrade-max"));
                    return true;
                }

                int newLevel = Math.min(20, targetPriv.level + 1);
                boolean ok = upgradeRegion(targetPriv, newLevel);
                if (!ok) {
                    p.sendMessage(plugin.cfg().msg("upgrade-failed"));
                    return true;
                }

                targetPriv.level = newLevel;
                plugin.store().save();

                // Refresh hologram/glow so placeholders like %priv_size% update after upgrade.
                plugin.holograms().spawnFor(targetPriv);
                plugin.glints().spawnFor(targetPriv);

                int newSize = PrivateLevelUtil.sizeForLevel(newLevel);
                playUpgradeEffects(targetPriv.world, targetPriv.x, targetPriv.y, targetPriv.z, newSize, newLevel, ownerName);

                p.sendMessage(plugin.cfg().msg("upgrade-success")
                        .replace("%player%", ownerName)
                        .replace("%private%", targetPriv.regionId)
                        .replace("%level%", String.valueOf(newLevel))
                        .replace("%size%", String.valueOf(newSize)));

                return true;
            }

            String ownerName = args[1];
            UUID ownerUuid = resolvePlayerUuid(ownerName);
            if (ownerUuid == null) {
                sender.sendMessage(plugin.cfg().msg("player-not-found").replace("%player%", ownerName));
                return true;
            }

            List<PrivateRecord> privs = getPrivatesOf(ownerUuid);
            if (privs.isEmpty()) {
                sender.sendMessage(plugin.cfg().msg("list-empty").replace("%player%", ownerName));
                return true;
            }

            PrivateRecord targetPriv = resolvePrivateArg(privs, args[2]);
            if (targetPriv == null) {
                sender.sendMessage(plugin.cfg().msg("upgrade-private-not-found")
                        .replace("%player%", ownerName)
                        .replace("%private%", args[2]));
                return true;
            }

            if (targetPriv.level >= 20) {
                sender.sendMessage(plugin.cfg().msg("upgrade-max"));
                return true;
            }

            int newLevel = Math.min(20, targetPriv.level + 1);
            boolean ok = upgradeRegion(targetPriv, newLevel);
            if (!ok) {
                sender.sendMessage(plugin.cfg().msg("upgrade-failed"));
                return true;
            }

            targetPriv.level = newLevel;
            plugin.store().save();

            // Refresh hologram/glow so placeholders like %priv_size% update after upgrade.
            plugin.holograms().spawnFor(targetPriv);
            plugin.glints().spawnFor(targetPriv);

            int newSize = PrivateLevelUtil.sizeForLevel(newLevel);
            // local chat + particles + sound
            playUpgradeEffects(targetPriv.world, targetPriv.x, targetPriv.y, targetPriv.z, newSize, newLevel, ownerName);

            sender.sendMessage(plugin.cfg().msg("upgrade-success")
                    .replace("%player%", ownerName)
                    .replace("%private%", targetPriv.regionId)
                    .replace("%level%", String.valueOf(newLevel))
                    .replace("%size%", String.valueOf(newSize)));

            return true;
        }

        sender.sendMessage(plugin.cfg().msg("ap-usage"));
        return true;
    }

    
    private void playUpgradeEffects(String worldName, int x, int y, int z, int newSize, int newLevel, String actorName) {
        try {
            var cfg = plugin.getConfig();
            boolean enabled = cfg.getBoolean("settings.upgrade-effects.enabled", true);
            if (!enabled) return;

            String particleName = cfg.getString("settings.upgrade-effects.particle", "NAUTILUS");
            int pointsPerTick = Math.max(1, cfg.getInt("settings.upgrade-effects.particles-per-tick", 24));
            double step = Math.max(0.1, cfg.getDouble("settings.upgrade-effects.step", 0.5));
            int duration = Math.max(1, cfg.getInt("settings.upgrade-effects.duration-ticks", 30));
            int localRadius = Math.max(1, cfg.getInt("settings.upgrade-effects.local-chat-radius", 64));
            String soundName = cfg.getString("settings.upgrade-effects.sound", "BLOCK_BEACON_ACTIVATE");
            float soundVolume = (float) cfg.getDouble("settings.upgrade-effects.sound-volume", 1.0);
            float soundPitch = (float) cfg.getDouble("settings.upgrade-effects.sound-pitch", 1.0);

            World w = Bukkit.getWorld(worldName);
            if (w == null) return;

            var center = new org.bukkit.Location(w, x + 0.5, y + 0.5, z + 0.5);

            // Local chat message
            String msg = plugin.cfg().msg("upgrade-local-chat")
                    .replace("%player%", actorName)
                    .replace("%size%", String.valueOf(newSize))
                    .replace("%level%", String.valueOf(newLevel));
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(center) <= (double) localRadius * (double) localRadius) {
                    p.sendMessage(msg);
                }
            }

            // Sound at center
            try {
                org.bukkit.Sound s = org.bukkit.Sound.valueOf(soundName);
                w.playSound(center, s, soundVolume, soundPitch);
            } catch (Throwable ignored) {}

            // Particle animation: to every border block
            me.aquaprivate.util.RegionEffects.playBorderExpand(plugin, worldName, x, y, z, newSize);
            return; // particle animation handled by RegionEffects

        } catch (Throwable ignored) {
            // never break command execution due to effect errors
        }
    }

    private org.bukkit.Particle parseParticle(String name) {
        if (name == null || name.isEmpty()) return org.bukkit.Particle.NAUTILUS;
        try {
            return org.bukkit.Particle.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable ignored) {
            return org.bukkit.Particle.NAUTILUS;
        }
    }

private UUID resolvePlayerUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op != null && (op.hasPlayedBefore() || op.isOnline())) return op.getUniqueId();
        } catch (Throwable ignored) {}
        return null;
    }

    private List<PrivateRecord> getPrivatesOf(UUID owner) {
        List<PrivateRecord> out = new ArrayList<>();
        for (PrivateRecord r : plugin.store().all()) {
            if (r != null && owner.equals(r.owner)) out.add(r);
        }
        out.sort(Comparator.comparing(a -> a.regionId == null ? "" : a.regionId, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private PrivateRecord resolvePrivateArg(List<PrivateRecord> privs, String arg) {
        // try regionId exact
        for (PrivateRecord r : privs) {
            if (r.regionId != null && r.regionId.equalsIgnoreCase(arg)) return r;
        }
        // try number (1..n)
        try {
            int idx = Integer.parseInt(arg);
            if (idx >= 1 && idx <= privs.size()) return privs.get(idx - 1);
        } catch (Exception ignored) {}
        return null;
    }

    private Optional<PrivateRecord> getPrivateAtPlayer(Player p) {
        RegionManager rm = plugin.wg().regionManager(p.getWorld());
        if (rm == null) return Optional.empty();
        var set = rm.getApplicableRegions(BlockVector3.at(
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ()
        ));
        for (ProtectedRegion pr : set) {
            Optional<PrivateRecord> r = plugin.store().byRegionId(pr.getId());
            if (r.isPresent()) return r;
        }
        return Optional.empty();
    }

    private void addMemberToWg(PrivateRecord rec, UUID uuid) {
        World w = Bukkit.getWorld(rec.world);
        if (w == null) return;

        RegionManager rm = plugin.wg().regionManager(w);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(rec.regionId);
        if (region == null) return;

        DefaultDomain members = region.getMembers();
        members.addPlayer(uuid);
        region.setMembers(members);
        try { rm.save(); } catch (Exception ignored) {}
    }

    private void removeMemberFromWg(PrivateRecord rec, UUID uuid) {
        World w = Bukkit.getWorld(rec.world);
        if (w == null) return;

        RegionManager rm = plugin.wg().regionManager(w);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(rec.regionId);
        if (region == null) return;

        DefaultDomain members = region.getMembers();
        members.removePlayer(uuid);
        region.setMembers(members);
        try { rm.save(); } catch (Exception ignored) {}
    }

    private boolean upgradeRegion(PrivateRecord rec, int newLevel) {
        World w = Bukkit.getWorld(rec.world);
        if (w == null) return false;

        RegionManager rm = plugin.wg().regionManager(w);
        if (rm == null) return false;

        ProtectedRegion existing = rm.getRegion(rec.regionId);
        if (existing == null) return false;

        // Sizes can be both odd and even (e.g. 15, 16, ...). To get the exact requested size
        // around the private block (center), we use an asymmetric half-range for even sizes.
        int size = PrivateLevelUtil.sizeForLevel(newLevel);
        int halfDown = (size - 1) / 2; // e.g. 16 => 7, 15 => 7
        int halfUp = size / 2;         // e.g. 16 => 8, 15 => 7

        BlockVector3 min = BlockVector3.at(rec.x - halfDown, rec.y - halfDown, rec.z - halfDown);
        BlockVector3 max = BlockVector3.at(rec.x + halfUp, rec.y + halfUp, rec.z + halfUp);

        ProtectedCuboidRegion upgraded = new ProtectedCuboidRegion(rec.regionId, min, max);

        try { upgraded.setOwners(existing.getOwners()); } catch (Throwable ignored) {}
        try { upgraded.setMembers(existing.getMembers()); } catch (Throwable ignored) {}

        // Copy flags (best-effort)
        try {
            for (var e : existing.getFlags().entrySet()) {
                try {
                    upgraded.setFlag((com.sk89q.worldguard.protection.flags.Flag<Object>) e.getKey(), e.getValue());
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}

        rm.removeRegion(rec.regionId);
        rm.addRegion(upgraded);

        try {
            rm.save();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save WG regions on upgrade: " + ex.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("add", "remove", "upgrade")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("add") || sub.equals("remove") || sub.equals("del") || sub.equals("delete")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
                }
                return out;
            }
            if (sub.equals("upgrade") || sub.equals("level") || sub.equals("up")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
                }
                return out;
            }
            return out;
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("upgrade") || sub.equals("level") || sub.equals("up")) {
                UUID ownerUuid = resolvePlayerUuid(args[1]);
                if (ownerUuid == null) return out;
                List<PrivateRecord> privs = getPrivatesOf(ownerUuid);
                for (PrivateRecord r : privs) {
                    if (r.regionId == null) continue;
                    if (r.regionId.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(r.regionId);
                }
                // also suggest numbers
                if (args[2].isEmpty() || Character.isDigit(args[2].charAt(0))) {
                    for (int i = 1; i <= Math.min(20, privs.size()); i++) {
                        String s = String.valueOf(i);
                        if (s.startsWith(args[2])) out.add(s);
                    }
                }
                return out;
            }
        }

        return out;
    }
}
