package com.fermerpets.motion;

public class ModePlanner {

    public static class Plan {
        public double baseSpeed;
        public boolean doSpiderHop;
        public boolean disableThrottle;
    }

    public static Plan plan(double dist2, boolean nearObstacle, boolean inLiquid) {
        Plan p = new Plan();
        double dist = Math.sqrt(Math.max(0.0, dist2));
        p.baseSpeed = clamp(map(dist, 0.0, 12.0, 0.12, 0.35), 0.10, 0.40);
        p.doSpiderHop = nearObstacle && dist < 5.5 && !inLiquid;
        p.disableThrottle = dist < 5.0;
        return p;
    }

    private static double map(double x, double a, double b, double c, double d) {
        if (x <= a) return c;
        if (x >= b) return d;
        double t = (x - a) / (b - a);
        return c + (d - c) * t;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}