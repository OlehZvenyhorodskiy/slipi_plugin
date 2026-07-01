package me.aquaprivate.glint;

import me.aquaprivate.AquaPrivatePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

public final class GlintEntityListener implements Listener {

    private final AquaPrivatePlugin plugin;

    public GlintEntityListener(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (plugin.glints().isGlintEntity(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractAtEntityEvent e) {
        if (plugin.glints().isGlintEntity(e.getRightClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent e) {
        if (plugin.glints().isGlintEntity(e.getRightClicked())) {
            e.setCancelled(true);
        }
    }
}
