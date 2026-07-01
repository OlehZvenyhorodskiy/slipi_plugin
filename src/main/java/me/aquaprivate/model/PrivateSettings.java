package me.aquaprivate.model;

import java.util.Objects;

public final class PrivateSettings {
    /** Allow PvP inside private regions. */
    public boolean allowPvp;
    public boolean createLightning;
    public boolean deleteLightning;
    public boolean mergeRegions;

    public boolean denyBlockBreak;
    public boolean denyBlockPlace;
    public boolean denyChestOpen;
    public boolean denyWitherBlockDamage;

    // Extra settings like in example config
    public boolean explosions;
    public java.util.List<String> exclusionExplosionsTypes;
    public boolean blockChangeWither;

    public static PrivateSettings defaults() {
        PrivateSettings s = new PrivateSettings();
        s.allowPvp = true;
        s.createLightning = true;
        s.deleteLightning = true;
        s.mergeRegions = true;
        s.denyBlockBreak = true;
        s.denyBlockPlace = true;
        s.denyChestOpen = true;
        s.denyWitherBlockDamage = true;

        s.explosions = false;
        s.exclusionExplosionsTypes = java.util.List.of("WITHER_SKULL");
        s.blockChangeWither = false;
        return s;
    }

    public PrivateSettings copy() {
        PrivateSettings s = new PrivateSettings();
        s.allowPvp = allowPvp;
        s.createLightning = createLightning;
        s.deleteLightning = deleteLightning;
        s.mergeRegions = mergeRegions;
        s.denyBlockBreak = denyBlockBreak;
        s.denyBlockPlace = denyBlockPlace;
        s.denyChestOpen = denyChestOpen;
        s.denyWitherBlockDamage = denyWitherBlockDamage;

        s.explosions = explosions;
        s.exclusionExplosionsTypes = (exclusionExplosionsTypes == null)
                ? java.util.List.of()
                : new java.util.ArrayList<>(exclusionExplosionsTypes);
        s.blockChangeWither = blockChangeWither;
        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PrivateSettings)) return false;
        PrivateSettings that = (PrivateSettings) o;
        return allowPvp == that.allowPvp &&
                createLightning == that.createLightning && deleteLightning == that.deleteLightning && mergeRegions == that.mergeRegions &&
                denyBlockBreak == that.denyBlockBreak && denyBlockPlace == that.denyBlockPlace && denyChestOpen == that.denyChestOpen &&
                denyWitherBlockDamage == that.denyWitherBlockDamage;
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowPvp, createLightning, deleteLightning, mergeRegions, denyBlockBreak, denyBlockPlace, denyChestOpen, denyWitherBlockDamage);
    }
}
