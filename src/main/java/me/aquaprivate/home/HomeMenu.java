package me.aquaprivate.home;

import me.aquaprivate.AquaPrivatePlugin;
import me.aquaprivate.hook.WorldGuardHook;
import me.aquaprivate.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HomeMenu {

    private final AquaPrivatePlugin plugin;
    public static final int SIZE = 54;

    private final NamespacedKey keyOwner;
    private final NamespacedKey keyWorld;
    private final NamespacedKey keyX;
    private final NamespacedKey keyY;
    private final NamespacedKey keyZ;

    public HomeMenu(AquaPrivatePlugin plugin) {
        this.plugin = plugin;
        this.keyOwner = new NamespacedKey(plugin, "home_owner");
        this.keyWorld = new NamespacedKey(plugin, "home_world");
        this.keyX = new NamespacedKey(plugin, "home_x");
        this.keyY = new NamespacedKey(plugin, "home_y");
        this.keyZ = new NamespacedKey(plugin, "home_z");
    }

    public void open(Player viewer, UUID ownerUuid, String ownerName, List<WorldGuardHook.PrivateEntry> homes) {
        String title = plugin.cfg().msgRaw("home-gui-title");
        if (title == null || title.isEmpty()) title = "&bПриваты: &f%player%";
        title = title.replace("%player%", ownerName).replace("%count%", String.valueOf(homes.size()));
        title = ColorUtil.color(title);

        Inventory inv = Bukkit.createInventory(viewer, SIZE, title);

        int slot = 0;
        for (WorldGuardHook.PrivateEntry h : homes) {
            if (slot >= SIZE) break;

            Material mat = Material.COMPASS;
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();

            String name = plugin.cfg().msgRaw("home-item-name");
            if (name == null || name.isEmpty()) name = "&aДом &f(%world% &7%x% %y% %z%&f)";
            name = name.replace("%world%", h.world())
                    .replace("%x%", String.valueOf(h.x()))
                    .replace("%y%", String.valueOf(h.y()))
                    .replace("%z%", String.valueOf(h.z()))
                    .replace("%region%", h.regionId());
            meta.setDisplayName(ColorUtil.color(name));

            List<String> lore = plugin.cfg().msgList("home-item-lore");
            if (lore == null || lore.isEmpty()) {
                lore = new ArrayList<>();
                lore.add("&7Мир: &f%world%");
                lore.add("&7Координаты: &f%x% &f%y% &f%z%");
                lore.add("&8Нажми, чтобы телепортироваться");
            }
            List<String> outLore = new ArrayList<>();
            for (String l : lore) {
                outLore.add(ColorUtil.color(l
                        .replace("%world%", h.world())
                        .replace("%x%", String.valueOf(h.x()))
                        .replace("%y%", String.valueOf(h.y()))
                        .replace("%z%", String.valueOf(h.z()))
                        .replace("%region%", h.regionId())
                        .replace("%player%", ownerName)));
            }
            meta.setLore(outLore);

            // store data
            meta.getPersistentDataContainer().set(keyOwner, PersistentDataType.STRING, ownerUuid.toString());
            meta.getPersistentDataContainer().set(keyWorld, PersistentDataType.STRING, h.world());
            meta.getPersistentDataContainer().set(keyX, PersistentDataType.INTEGER, h.x());
            meta.getPersistentDataContainer().set(keyY, PersistentDataType.INTEGER, h.y());
            meta.getPersistentDataContainer().set(keyZ, PersistentDataType.INTEGER, h.z());

            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        // filler if desired could be added later
        viewer.openInventory(inv);
    }

    public boolean isMenu(Inventory inv) {
        if (inv == null) return false;
        // Title is dynamic; use size and holder player check handled in listener via view title prefix.
        return inv.getSize() == SIZE;
    }

    public NamespacedKey keyOwner() { return keyOwner; }
    public NamespacedKey keyWorld() { return keyWorld; }
    public NamespacedKey keyX() { return keyX; }
    public NamespacedKey keyY() { return keyY; }
    public NamespacedKey keyZ() { return keyZ; }
}