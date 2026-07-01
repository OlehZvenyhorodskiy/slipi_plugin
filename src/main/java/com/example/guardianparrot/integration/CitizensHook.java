package com.example.guardianparrot.integration;

import org.bukkit.entity.Entity;

public final class CitizensHook {
    private static final boolean PRESENT;
    static {
        boolean ok;
        try {
            Class.forName("net.citizensnpcs.api.CitizensAPI");
            ok = true;
        } catch (Throwable t) { ok = false; }
        PRESENT = ok;
    }

    public static boolean isPresent() { return PRESENT; }

    public static boolean isNPC(Entity e) {
        if (e == null) return false;
        try {
            // Citizens always sets metadata "NPC" on its entities
            if (e.hasMetadata("NPC")) return true;
            if (!PRESENT) return false;
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = api.getMethod("getNPCRegistry").invoke(null);
            return (Boolean) registry.getClass().getMethod("isNPC", org.bukkit.entity.Entity.class).invoke(registry, e);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private CitizensHook() {}
}
