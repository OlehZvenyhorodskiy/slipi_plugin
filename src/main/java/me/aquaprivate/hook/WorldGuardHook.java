package me.aquaprivate.hook;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.aquaprivate.AquaPrivatePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

public final class WorldGuardHook {
    private final AquaPrivatePlugin plugin;

    public WorldGuardHook(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isReady() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null
                && Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
    }

    public RegionManager regionManager(World world) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    public boolean hasAnyRegionAt(Location loc) {
        RegionManager rm = regionManager(loc.getWorld());
        if (rm == null) return false;
        ApplicableRegionSet set = rm.getApplicableRegions(BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        return set != null && set.size() > 0;
    }

    public boolean intersectsAnyOtherRegion(World world, ProtectedRegion candidate) {
        RegionManager rm = regionManager(world);
        if (rm == null) return false;
        ApplicableRegionSet set = rm.getApplicableRegions(candidate);
        if (set == null) return false;
        for (ProtectedRegion r : set) {
            if (Objects.equals(r.getId(), candidate.getId())) continue;
            return true;
        }
        return false;
    }


    public record PrivateEntry(String world, String regionId, int x, int y, int z) {}

    private static final java.util.regex.Pattern AP_ID = java.util.regex.Pattern.compile("^ap-(-?\\d+)x(-?\\d+)y(-?\\d+)z$");

    public java.util.List<PrivateEntry> listPrivates(java.util.UUID ownerUuid) {
        java.util.List<PrivateEntry> out = new java.util.ArrayList<>();
        if (!isReady()) return out;

        for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
            RegionManager rm = regionManager(w);
            if (rm == null) continue;

            for (ProtectedRegion r : rm.getRegions().values()) {
                if (r == null) continue;
                String id = r.getId();
                if (id == null || !id.startsWith("ap-")) continue;

                try {
                    if (!r.getOwners().contains(ownerUuid)) continue;
                } catch (Throwable t) {
                    // fallback: if API differs, skip
                    continue;
                }

                int x = r.getMinimumPoint().getBlockX();
                int y = r.getMinimumPoint().getBlockY();
                int z = r.getMinimumPoint().getBlockZ();

                var m = AP_ID.matcher(id);
                if (m.matches()) {
                    try {
                        x = Integer.parseInt(m.group(1));
                        y = Integer.parseInt(m.group(2));
                        z = Integer.parseInt(m.group(3));
                    } catch (Exception ignored) {}
                } else {
                    // use center as best-effort
                    try {
                        x = (r.getMinimumPoint().getBlockX() + r.getMaximumPoint().getBlockX()) / 2;
                        y = (r.getMinimumPoint().getBlockY() + r.getMaximumPoint().getBlockY()) / 2;
                        z = (r.getMinimumPoint().getBlockZ() + r.getMaximumPoint().getBlockZ()) / 2;
                    } catch (Exception ignored) {}
                }

                out.add(new PrivateEntry(w.getName(), id, x, y, z));
            }
        }
        out.sort(java.util.Comparator
                .comparing(PrivateEntry::world)
                .thenComparingInt(PrivateEntry::x)
                .thenComparingInt(PrivateEntry::y)
                .thenComparingInt(PrivateEntry::z));
        return out;
    }

}
