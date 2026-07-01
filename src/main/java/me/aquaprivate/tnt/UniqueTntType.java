package me.aquaprivate.tnt;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record UniqueTntType(
        String id,
        Map<String, Boolean> explosionsAllowed,
        Material material,
        String name,
        List<String> lore,
        boolean glow,
        List<String> pattern,
        Map<Character, Material> ingredients
) {
}
