package com.example.guardianparrot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import com.example.guardianparrot.GPPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.util.*;

public class ParrotProjectileCosmetics implements Listener {
    private final GPPlugin plugin;
    private final ParrotManager manager;
    private final NamespacedKey visualKey;
    private final Map<UUID, ArmorStand> visuals = new HashMap<>();
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();

    private static final UUID HEAD_UUID = java.util.UUID.fromString("04049c90-d3e9-4621-9caf-0000aaa28461");
    private static final String HEAD_NAME = "Parrot";
    private static final String TEXTURE_BASE64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODM2Yjk2ZTdjNTdkZjk5Yzc3M2YyZmJjZjRmMjYwZjA5M2NmNjZlZTEyNjk4MjQ3NzFmZjk4MzA4NGEyNzA1YSJ9fX0=";

    public ParrotProjectileCosmetics(GPPlugin plugin, ParrotManager manager){
        this.plugin = plugin;
        this.manager = manager;
        this.visualKey = new NamespacedKey(plugin.host(), "guardianparrot_visual");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e){
        Projectile proj = e.getEntity();
        if (!(proj instanceof WitherSkull)) return;
        Entity shooter = proj.getShooter() instanceof Entity ? (Entity) proj.getShooter() : null;
        if (!(shooter instanceof Parrot parrot)) return;
        if (!parrot.getPersistentDataContainer().has(manager.guardKey(), PersistentDataType.BYTE)) return;

        ArmorStand as = spawnHeadStand(proj.getLocation(), buildParrotHead());
        visuals.put(proj.getUniqueId(), as);

        BukkitTask t = plugin.getServer().getScheduler().runTaskTimer(plugin.host(), () -> {
            if (proj.isDead() || !proj.isValid()){
                cleanup(proj.getUniqueId());
                return;
            }
            Location here = proj.getLocation();
            try { as.teleport(here); } catch (Throwable ignored){}
        }, 0L, 1L);
        tasks.put(proj.getUniqueId(), t);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent e){
        if (!(e.getEntity() instanceof WitherSkull)) return;
        cleanup(e.getEntity().getUniqueId());
    }

    private void cleanup(UUID id){
        BukkitTask t = tasks.remove(id);
        if (t != null) try { t.cancel(); } catch (Throwable ignored){}
        ArmorStand as = visuals.remove(id);
        if (as != null) try { as.remove(); } catch (Throwable ignored){}
    }

    private ArmorStand spawnHeadStand(Location at, ItemStack head){
        ArmorStand as = at.getWorld().spawn(at, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setPersistent(false);
            stand.setInvulnerable(true);
            stand.getPersistentDataContainer().set(visualKey, PersistentDataType.BYTE, (byte)1);
        });
        EntityEquipment eq = as.getEquipment();
        if (eq != null) eq.setHelmet(head);
        return as;
    }

    private ItemStack buildParrotHead(){
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(HEAD_UUID, HEAD_NAME);
            PlayerTextures textures = profile.getTextures();
            String json = new String(java.util.Base64.getDecoder().decode(TEXTURE_BASE64), java.nio.charset.StandardCharsets.UTF_8);
            String url = json.replaceAll(".*\"url\"\s*:\s*\"(.*?)\".*", "$1");
            try { textures.setSkin(URI.create(url).toURL()); } catch (Throwable ignored){}
            profile.setTextures(textures);

            org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) Bukkit.getItemFactory().getItemMeta(org.bukkit.Material.PLAYER_HEAD);
            if (meta != null){
                meta.setOwnerProfile(profile);
                meta.setDisplayName(org.bukkit.ChatColor.BLUE + "Parrot");
                ItemStack head = new ItemStack(org.bukkit.Material.PLAYER_HEAD, 1);
                head.setItemMeta(meta);
                return head;
            }
        } catch (Throwable ignored){}
        return new ItemStack(org.bukkit.Material.PLAYER_HEAD, 1);
    }

    public void detachAll(){
        for (BukkitTask t : tasks.values()) { try { t.cancel(); } catch (Throwable ignored){} }
        tasks.clear();
        for (ArmorStand as : visuals.values()) { try { as.remove(); } catch (Throwable ignored){} }
        visuals.clear();
    }
}
