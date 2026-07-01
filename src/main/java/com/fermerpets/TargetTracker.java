package com.fermerpets;

import org.bukkit.Location;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TargetTracker {
    private TargetTracker(){}
    private static final ConcurrentHashMap<UUID, Location> pursuing = new ConcurrentHashMap<>();

    public static void setActive(UUID id, Location target){ if (id!=null && target!=null) pursuing.put(id, target); }
    public static void clear(UUID id){ if (id!=null) pursuing.remove(id); }
    public static boolean isBusy(UUID id){ return id!=null && pursuing.containsKey(id); }
    public static Location get(UUID id){ return id==null? null : pursuing.get(id); }
    public static void clearAll(){ pursuing.clear(); }
}