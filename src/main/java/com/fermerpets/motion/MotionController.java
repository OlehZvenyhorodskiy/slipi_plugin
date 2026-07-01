package com.fermerpets.motion;

import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

public class MotionController {
    private final LivingEntity entity;
    private Vector prevVel = new Vector(0, 0, 0);
    private float yaw;
    private int modeCooldownTicks = 0;
    private MoveMode currentMode = MoveMode.WALK;
    private int stepBoostLeft = 0;

    public static class Params {
        public double alpha = 0.35;
        public double jerkLimit = 0.06;
        public double yawDegPerTick = 8.0;
        public double stopRadius = 0.6;
        public double forwardBias = 0.02;
        public int switchCooldown = 10;
        public int stepBoostTicks = 3;
        public double stepBoost = 0.08;
        public double flyUpVy = 0.45;
        public double walkSpeedCap = 0.33;
        public double flySpeedCap  = 0.50;
        public double flyEnterGap = 1.00;
        public double flyExitGap  = 0.60;
    }
    public static class StepContext {
        public boolean onGround;
        public boolean inLiquid;
        public boolean nearObstacle;
        public boolean justSteppedUp;
        public double verticalGap;
    }
    public enum MoveMode { WALK, FLY }
    private final Params cfg;

    public MotionController(LivingEntity entity) { this(entity, new Params()); }
    public MotionController(LivingEntity entity, Params params) {
        this.entity = entity;
        this.cfg = params;
        this.yaw = entity.getLocation().getYaw();
    }

    public Vector stepTowards(Vector targetDir, double baseSpeed, boolean onGround,
                              boolean inLiquid, boolean nearObstacle, double dtTicks) {
        StepContext ctx = new StepContext();
        ctx.onGround = onGround;
        ctx.inLiquid = inLiquid;
        ctx.nearObstacle = nearObstacle;
        ctx.verticalGap = targetDir.getY();
        ctx.justSteppedUp = false;

        Vector dir = targetDir.clone();
        if (dir.lengthSquared() > 0) dir.normalize();

        updateMoveMode(ctx);

        double speedCap = (currentMode == MoveMode.WALK) ? cfg.walkSpeedCap : cfg.flySpeedCap;
        double speed = Math.min(baseSpeed, speedCap);

        if (dir.getX() != 0 || dir.getZ() != 0) {
            float yawTarget = (float)Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
            float dyaw = wrapDeltaYaw(yawTarget - yaw);
            float clamp = (float)Math.signum(dyaw) * (float)Math.min(Math.abs(dyaw), cfg.yawDegPerTick);
            yaw += clamp;
            try { entity.getLocation().setYaw(yaw); } catch (Throwable ignored) {}
        }

        Vector targetVel = new Vector(dir.getX() * speed, prevVel.getY(), dir.getZ() * speed);

        if (currentMode == MoveMode.FLY || inLiquid || ctx.verticalGap > cfg.flyEnterGap) {
            targetVel.setY(cfg.flyUpVy);
        } else if (!ctx.onGround) {
            targetVel.setY(prevVel.getY());
        } else {
            targetVel.setY(0);
        }

        if (prevVel.lengthSquared() > 1e-6) {
            Vector fwd = prevVel.clone().setY(0);
            if (fwd.lengthSquared() > 1e-8){
                fwd.normalize();
                double proj = targetVel.clone().setY(0).dot(fwd);
                if (proj < cfg.forwardBias) {
                    Vector boost = fwd.clone().multiply(cfg.forwardBias - proj);
                    targetVel.add(boost);
                }
            }
        }

        if (stepBoostLeft > 0) {
            Vector hv = prevVel.clone().setY(0);
            if (hv.lengthSquared()>1e-8){
                hv.normalize().multiply(cfg.stepBoost);
                targetVel.add(hv);
            }
            stepBoostLeft--;
        }

        Vector dv = targetVel.clone().subtract(prevVel);
        double maxDv = cfg.jerkLimit;
        if (dv.length() > maxDv) {
            dv.normalize().multiply(maxDv);
            targetVel = prevVel.clone().add(dv);
        }

        double a = 1.0 - Math.pow(1.0 - cfg.alpha, dtTicks);
        Vector smoothed = prevVel.clone().multiply(1.0 - a).add(targetVel.clone().multiply(a));

        if (targetDir.length() < cfg.stopRadius) {
            double k = targetDir.length() / Math.max(cfg.stopRadius, 1e-3);
            smoothed.multiply(k);
        }

        prevVel = smoothed;
        return smoothed;
    }

    private void updateMoveMode(StepContext ctx) {
        if (modeCooldownTicks > 0) modeCooldownTicks--;
        boolean wantFly = ctx.inLiquid || ctx.verticalGap > cfg.flyEnterGap;
        boolean wantWalk = !ctx.inLiquid && ctx.verticalGap < cfg.flyExitGap;
        MoveMode next = currentMode;
        if (currentMode == MoveMode.WALK && wantFly && modeCooldownTicks == 0) next = MoveMode.FLY;
        else if (currentMode == MoveMode.FLY && wantWalk && modeCooldownTicks == 0) next = MoveMode.WALK;
        if (next != currentMode) { currentMode = next; modeCooldownTicks = cfg.switchCooldown; }
    }

    private static float wrapDeltaYaw(float d) {
        while (d < -180f) d += 360f;
        while (d > 180f) d -= 360f;
        return d;
    }
}