package me.aquaprivate.model;

import org.bukkit.Material;

import java.util.List;

public final class PrivateBlockType {
    public final String key; // config key
    public final Material material;
    public final String name;
    public final List<String> lore;

    public final int xRadius;
    public final int yRadius;
    public final int zRadius;

    public final PrivateSettings settings;
    public final HologramSettings holograms;

    public PrivateBlockType(String key, Material material, String name, List<String> lore,
                            int xRadius, int yRadius, int zRadius,
                            PrivateSettings settings,
                            HologramSettings holograms) {
        this.key = key;
        this.material = material;
        this.name = name;
        this.lore = lore;
        this.xRadius = xRadius;
        this.yRadius = yRadius;
        this.zRadius = zRadius;
        this.settings = settings;
        this.holograms = holograms;
    }
}
