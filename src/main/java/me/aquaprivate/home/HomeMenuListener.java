package me.aquaprivate.home;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public final class HomeMenuListener implements Listener {

    private final AquaPrivatePlugin plugin;
    private final HomeMenu menu;
    private final TeleportService teleportService;

    public HomeMenuListener(AquaPrivatePlugin plugin, HomeMenu menu, TeleportService teleportService) {
        this.plugin = plugin;
        this.menu = menu;
        this.teleportService = teleportService;
    }

    private boolean isHomeMenuTitle(String title) {
        String raw = plugin.cfg().msgRaw("home-gui-title");
        if (raw == null || raw.isEmpty()) raw = "&bПриваты: &f%player%";
        String colored = ColorUtil.color(raw.replace("%player%", ""));
        // if config starts with colors, take prefix before %player%
        String prefix = colored.split("%player%")[0];
        return title != null && (title.startsWith(prefix) || title.contains(ColorUtil.color("&bПриваты")));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView() == null) return;
        String title = e.getView().getTitle();
        if (!isHomeMenuTitle(title)) return;

        e.setCancelled(true);

        ItemStack it = e.getCurrentItem();
        if (it == null) return;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String ownerStr = pdc.get(menu.keyOwner(), PersistentDataType.STRING);
        String worldName = pdc.get(menu.keyWorld(), PersistentDataType.STRING);
        Integer x = pdc.get(menu.keyX(), PersistentDataType.INTEGER);
        Integer y = pdc.get(menu.keyY(), PersistentDataType.INTEGER);
        Integer z = pdc.get(menu.keyZ(), PersistentDataType.INTEGER);

        if (ownerStr == null || worldName == null || x == null || y == null || z == null) return;

        UUID ownerUuid;
        try { ownerUuid = UUID.fromString(ownerStr); } catch (Exception ex) { return; }

        // Permission: player can TP only to own homes unless admin
        boolean admin = p.isOp() || p.hasPermission("aquaprivate.home.admin");
        if (!admin && !p.getUniqueId().equals(ownerUuid)) {
            p.sendMessage(plugin.cfg().msg("no-permission"));
            return;
        }
        if (!p.hasPermission("aquaprivate.home") && !admin) {
            p.sendMessage(plugin.cfg().msg("no-permission"));
            return;
        }

        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            p.sendMessage(plugin.cfg().msg("home-world-missing").replace("%world%", worldName));
            return;
        }

        Location loc = new Location(w, x + 0.5, y + 1.0, z + 0.5);
        p.closeInventory();

        teleportService.teleport(p, loc);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // nothing
    }
}