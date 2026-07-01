package me.aquaprivate.glint;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.model.PrivateBlockType;
import me.aquaprivate.model.PrivateRecord;
import me.aquaprivate.util.ItemFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Minecraft can't render "glint" on placed blocks.
 * To keep the same look as the item, we spawn a tiny invisible ArmorStand
 * with the private item as a helmet. This visually looks like a glint on the block.
 */
public final class GlintService {

    private static final String TAG = "aquaprivate_glint";

    
    private static boolean isChunkLoaded(org.bukkit.World w, int x, int z) {
        if (w == null) return false;
        return w.isChunkLoaded(x >> 4, z >> 4);
    }

private final AquaPrivatePlugin plugin;
    private final Map<String, UUID> spawned = new HashMap<>();

    public GlintService(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    private static String key(World w, int x, int y, int z) {
        return w.getName() + ":" + x + ":" + y + ":" + z;
    }

    public void spawnFor(PrivateRecord rec) {
        World w = Bukkit.getWorld(rec.world);
        if (w == null) return;
        if (!isChunkLoaded(w, rec.x, rec.z)) return;

        PrivateBlockType type = plugin.resolveBlockType(rec);
        if (type == null) return;

        // Try to resolve owner name (can be null for offline players)
        String ownerName = Optional.ofNullable(Bukkit.getOfflinePlayer(rec.owner).getName()).orElse("Player");
        ItemStack item = ItemFactory.createPrivateItem(plugin, type, ownerName);

        spawn(w, rec.x, rec.y, rec.z, item);
    }

    public void spawn(World w, int x, int y, int z, ItemStack item) {
        String k = key(w, x, y, z);
        if (spawned.containsKey(k)) return;

        Location base = new Location(w, x + 0.5, y, z + 0.5);

        // Prefer ITEM_DISPLAY when available (1.19.4+), otherwise ArmorStand.
        Entity display = null;
        try {
            EntityType t = EntityType.valueOf("ITEM_DISPLAY");
            // Center of the block looks best for a "block-like" display.
            display = w.spawnEntity(base.clone().add(0, 0.5, 0), t);
            display.addScoreboardTag(TAG);
            // reflection: setItemStack(ItemStack)
            display.getClass().getMethod("setItemStack", ItemStack.class).invoke(display, item);

            // reflection: setItemDisplayTransform(ItemDisplayTransform.FIXED)
            try {
                var m = display.getClass().getMethod("setItemDisplayTransform", display.getClass().getClassLoader()
                        .loadClass("org.bukkit.entity.ItemDisplay$ItemDisplayTransform"));
                Class<?> enumClz = m.getParameterTypes()[0];
                Object fixed = Enum.valueOf((Class<? extends Enum>) enumClz, "FIXED");
                m.invoke(display, fixed);
            } catch (Throwable ignored2) {
                // older API variants – ignore
            }
        } catch (Throwable ignored) {
            display = null;
        }

        if (display == null) {
            ArmorStand as = (ArmorStand) w.spawnEntity(base.clone().add(0, 0.05, 0), EntityType.ARMOR_STAND);
            as.addScoreboardTag(TAG);
            as.setInvisible(true);
            as.setMarker(true);
            as.setSmall(true);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.getEquipment().setHelmet(item);
            as.setRemoveWhenFarAway(false);
            display = as;
        }

        spawned.put(k, display.getUniqueId());
    }

    public void removeFor(PrivateRecord rec) {
        World w = Bukkit.getWorld(rec.world);
        if (w == null) return;
        if (!isChunkLoaded(w, rec.x, rec.z)) return;
        remove(w, rec.x, rec.y, rec.z);
    }

    public void remove(World w, int x, int y, int z) {
        String k = key(w, x, y, z);
        UUID id = spawned.remove(k);
        if (id == null) return;

        Entity e = w.getEntities().stream().filter(ent -> ent.getUniqueId().equals(id)).findFirst().orElse(null);
        if (e != null) e.remove();
    }

    public boolean isGlintEntity(Entity e) {
        return e != null && e.getScoreboardTags().contains(TAG);
    }

    public void respawnAll() {
        removeAll();
        for (PrivateRecord rec : plugin.store().all()) {
            World w = Bukkit.getWorld(rec.world);
            if (w == null) continue;
            if (!isChunkLoaded(w, rec.x, rec.z)) continue;
            spawnFor(rec);
        }
    }

    public void removeAll() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (isGlintEntity(e)) e.remove();
            }
        }
        spawned.clear();
    }
}
