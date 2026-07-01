package me.aquaprivate.model;

import java.util.Collections;
import java.util.List;

public final class HologramSettings {
    public final boolean enable;
    public final double height;
    public final List<String> lines;

    public HologramSettings(boolean enable, double height, List<String> lines) {
        this.enable = enable;
        this.height = height;
        this.lines = lines == null ? Collections.emptyList() : List.copyOf(lines);
    }

    public static HologramSettings defaults() {
        return new HologramSettings(false, 1.75, List.of());
    }
}
