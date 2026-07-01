package me.aquaprivate.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;

public final class PrivateRecord {
    public String blockKey;
    public String world;
    public int x;
    public int y;
    public int z;

    public String regionId;
    public UUID owner;
    public Set<UUID> members = new HashSet<>();

    /** Private level (1..20). Level affects region size and GUI display. */
    public int level = 1;

    /** Private type: normal or clan. Displayed via %privatetype% */
    public String privateType = "normal";

    /** Farmer private mode: active/inactive displayed via %fermerprivat% */
    public boolean farmerActive = false;

    /** Farmer fuel amount displayed via %fermerprivattoplivo% (string for future flexibility). */
    public String farmerFuel = "";

    /**
     * Farmer activation payment: siphon player EXP LEVELS into the private.
     * When enabled, the plugin will take EXP levels from the owner as they gain experience
     * until {@link #farmerExpLevels} reaches 100.
     */
    public boolean farmerExpTransferEnabled = false;

    /** How many EXP LEVELS have been transferred into this private for farmer activation (0..100). */
    public int farmerExpLevels = 0;

    /**
     * Guardian (Хранитель) activation payment: siphon player EXP LEVELS into the private.
     * When enabled, the plugin will take EXP levels from the clicking player until {@link #guardExpLevels} reaches 100.
     */
    public boolean guardExpTransferEnabled = false;

    /** How many EXP LEVELS have been transferred into this private for guardian activation (0..100). */
    public int guardExpLevels = 0;

    /** Fermer menu slots state (per private). Used for %fermer1%/%fermer2%/%fermer3% placeholders. */
    public boolean fermer1 = false;
    public boolean fermer2 = false;
    public boolean fermer3 = false;

    /** Region border highlight (particles) toggle from menu. */
    public boolean borderGlow = false;

    /**
     * Level-up quest toggle and progress.
     * When enabled, the owner can complete configured tasks to upgrade this private by 1 level.
     */
    public boolean levelQuestEnabled = false;
    public int levelQuestEntityKills = 0;
    public int levelQuestMined = 0;
    public int levelQuestPlayerKills = 0;

    /** UUIDs of ArmorStand hologram entities for this private (one per line). */
    public List<UUID> holograms = new ArrayList<>();

    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z);
    }

    public String locKey() {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
