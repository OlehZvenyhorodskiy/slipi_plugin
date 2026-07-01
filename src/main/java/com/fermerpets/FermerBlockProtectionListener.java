
package com.fermerpets;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import java.util.List;

public class FermerBlockProtectionListener implements Listener {
    private final FermerPetsModule plugin;
    private final PlayersHoppersStore store;

    public FermerBlockProtectionListener(FermerPetsModule plugin){
        this.plugin = plugin;
        this.store = new PlayersHoppersStore(plugin);
    }

    private boolean isProtectedLocation(Location loc){
        try {
            // iterate all pets of all owners; stop on first match
            PlayersStore ps = new PlayersStore(plugin.getManager().getPlayersYaml());
            java.util.Set<String> owners = plugin.getManager().getPlayersYaml().getConfigurationSection("players")==null
                    ? java.util.Collections.emptySet()
                    : plugin.getManager().getPlayersYaml().getConfigurationSection("players").getKeys(false);
            for (String ownerStr : owners){
                java.util.UUID owner;
                try { owner = java.util.UUID.fromString(ownerStr); } catch (IllegalArgumentException ex){ continue; }
                java.util.List<java.util.UUID> pets = ps.getPets(owner);
                if (pets == null) continue;
                for (java.util.UUID petId : pets){
                    PlayersHoppersStore.Record h = store.getHopperByPet(petId);
                    if (h != null && same(loc, h.loc)) return true;
                    PlayersHoppersStore.Record b = store.getBeaconByPet(petId);
                    if (b != null && same(loc, b.loc)) return true;
                }
            }
        } catch (Throwable ignored){}
        return false;
    }

    private boolean same(Location a, Location b){
        if (a==null || b==null) return false;
        return a.getWorld()!=null && a.getWorld().equals(b.getWorld())
                && a.getBlockX()==b.getBlockX() && a.getBlockY()==b.getBlockY() && a.getBlockZ()==b.getBlockZ();
    }

    // --- Players cannot break our blocks ---
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e){
        if (isProtectedLocation(e.getBlock().getLocation())){
            e.setCancelled(true);
            if (e.getPlayer()!=null) e.getPlayer().sendMessage(ChatColor.RED+"Эту конструкцию можно удалить только через меню (слот 42).");
        }
    }

    // Explosions do not remove them
    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e){
        e.blockList().removeIf(b -> isProtectedLocation(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e){
        e.blockList().removeIf(b -> isProtectedLocation(b.getLocation()));
    }

    // Pistons cannot move them
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e){
        List<Block> bs = e.getBlocks();
        for (Block b : bs){
            if (isProtectedLocation(b.getLocation())){ e.setCancelled(true); return; }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e){
        List<Block> bs = e.getBlocks();
        for (Block b : bs){
            if (isProtectedLocation(b.getLocation())){ e.setCancelled(true); return; }
        }
    }
}
