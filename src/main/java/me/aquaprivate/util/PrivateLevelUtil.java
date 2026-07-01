package me.aquaprivate.util;

/**
 * Private level sizing rules requested by the server owner.
 *
 * Level 1 => 15
 * Level 2 => 16
 * ...
 * Level 20 => 35
 */
public final class PrivateLevelUtil {

    private PrivateLevelUtil() {}

    /**
     * Returns {minOffset, maxOffset} for a cuboid of the given size centered on the marker block.
     * Odd sizes are symmetric (-7..+7). Even sizes are biased to the positive side (-7..+8) to keep exact size.
     */
    public static int[] offsetsForSize(int size) {
        int s = Math.max(1, size);
        if ((s & 1) == 1) {
            int half = s / 2;
            return new int[]{-half, half};
        }
        int half = s / 2;
        return new int[]{-(half - 1), half};
    }


    /** Returns the configured "size" for the given level (1..20). */
    public static int sizeForLevel(int level) {
        int lvl = Math.max(1, Math.min(20, level));

        // Important nuance:
        // User explicitly requested level 20 = 35 while level 1 = 15.
        // That cannot be a strict +1 each level all the way (15 + 19 = 34),
        // so we implement an explicit mapping that reaches 35 at level 20.
        return switch (lvl) {
            case 1 -> 15;
            case 2 -> 16;
            case 3 -> 17;
            case 4 -> 18;
            case 5 -> 19;
            case 6 -> 20;
            case 7 -> 21;
            case 8 -> 22;
            case 9 -> 23;
            case 10 -> 24;
            case 11 -> 25;
            case 12 -> 26;
            case 13 -> 27;
            case 14 -> 28;
            case 15 -> 29;
            case 16 -> 30;
            case 17 -> 31;
            case 18 -> 32;
            case 19 -> 34;
            case 20 -> 35;
            default -> 15;
        };
    }
}
