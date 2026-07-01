package com.example.guardianparrot.projectile;

import com.example.guardianparrot.integration.CitizensHook;

import com.example.guardianparrot.util.HeadFactory;
import org.bukkit.*;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import com.example.guardianparrot.GPPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Objects;

public final class ParrotSkull {
    private final GPPlugin plugin;
    private final LivingEntity shooter;
    private final LivingEntity target;
    private final ItemDisplay display;
    private final Interaction hitbox;

    private final double speed;
    private final double turnRate;
    private final double radius;
    private final int maxLife;
    private final double damage;
    private final int witherDur;
    private final int witherAmp;
    private final Particle trailParticle;
    private final Particle hitParticle;
    private final Sound launchSound;
    private final Sound hitSound;

    private Vector velocity;
    private int life;
    private BukkitTask task;

    private ParrotSkull(GPPlugin plugin, LivingEntity shooter, LivingEntity target, Location spawnAt) {
        this.plugin = plugin;
        this.shooter = shooter;
        this.target = target;

        var cfg = plugin.getConfig();
        this.speed = cfg.getDouble("parrot-skull.speed", 0.9);
        this.turnRate = cfg.getDouble("parrot-skull.turn-rate", 0.12);
        this.radius = cfg.getDouble("parrot-skull.hitbox-radius", 0.5);
        this.maxLife = cfg.getInt("parrot-skull.max-life-ticks", 200);
        this.damage = cfg.getDouble("parrot-skull.damage", 8.0);
        this.witherDur = cfg.getInt("parrot-skull.wither.duration", 100);
        this.witherAmp = cfg.getInt("parrot-skull.wither.amplifier", 1);
        this.trailParticle = Particle.valueOf(cfg.getString("parrot-skull.particles.trail","SOUL_FIRE_FLAME"));
        this.hitParticle = Particle.valueOf(cfg.getString("parrot-skull.particles.hit","EXPLOSION_LARGE"));
        this.launchSound = Sound.valueOf(cfg.getString("parrot-skull.sounds.launch","ENTITY_WITHER_SHOOT"));
        this.hitSound = Sound.valueOf(cfg.getString("parrot-skull.sounds.hit","ENTITY_GENERIC_EXPLODE"));

        ItemStack head = HeadFactory.parrot(plugin);

        this.display = spawnAt.getWorld().spawn(spawnAt, ItemDisplay.class, d -> {
            d.setItemStack(head);
            d.setBillboard(Display.Billboard.FIXED);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(1);
            d.setShadowRadius(0f);
            d.setViewRange(64f);
            d.setInvulnerable(true);
            d.setGravity(false);
            d.setPersistent(false);
        });

        this.hitbox = spawnAt.getWorld().spawn(spawnAt, Interaction.class, i -> {
            i.setResponsive(false);
            i.setInteractionWidth((float) (radius*2));
            i.setInteractionHeight((float) (radius*2));
            i.setInvulnerable(true);
            i.setPersistent(false);
            i.setGravity(false);
            i.setSilent(true);
        });

        Vector dir = initialDirection(spawnAt);
        this.velocity = dir.multiply(speed);

        spawnAt.getWorld().playSound(spawnAt, launchSound, 1f, 1f);
    }

    public static void launch(GPPlugin plugin, LivingEntity shooter, LivingEntity target) {
        Location eye = shooter.getEyeLocation().clone().add(shooter.getLocation().getDirection().normalize().multiply(0.6));
        new ParrotSkull(plugin, shooter, target, eye).start();
    }

    private Vector initialDirection(Location from) {
        if (target != null && target.isValid()) {
            Vector to = target.getEyeLocation().toVector().subtract(from.toVector());
            if (to.lengthSquared() > 0.0001) return to.normalize();
        }
        return from.getDirection().normalize();
    }

    private void start() {
        int period = Math.max(1, plugin.getConfig().getInt("guardian.tick-period", 1));
        this.task = Bukkit.getScheduler().runTaskTimer(plugin.host(), this::tick, 1L, period);
    }

    private void tick() {
        if (life++ > maxLife || display.isDead() || !display.isValid() || !hitbox.isValid()) {
            remove(false);
            return;
        }

        if (target != null && target.isValid()) {
            Vector desired = target.getEyeLocation().toVector().subtract(display.getLocation().toVector()).normalize().multiply(speed);
            velocity = velocity.multiply(1.0 - turnRate).add(desired.multiply(turnRate));
        }
        if (velocity.lengthSquared() < 1e-6) velocity = velocity.setY(0.0001).normalize().multiply(speed);

        Location from = display.getLocation();
        Vector step = velocity.clone();
        Location to = from.clone().add(step);

        RayTraceResult blockHit = from.getWorld().rayTraceBlocks(from, step.normalize(), step.length(), FluidCollisionMode.NEVER, true);
        if (blockHit != null) {
            impact(blockHit.getHitPosition().toLocation(from.getWorld()), null);
            return;
        }

        RayTraceResult entHit = from.getWorld().rayTraceEntities(from, step.normalize(), step.length(),
                e -> e instanceof LivingEntity
                        && !Objects.equals(e.getUniqueId(), shooter.getUniqueId())
                        && !Objects.equals(e.getUniqueId(), display.getUniqueId())
                        && !Objects.equals(e.getUniqueId(), hitbox.getUniqueId()));

        display.teleport(to);
        display.setRotation(display.getLocation().getYaw()+180f, 0f);
        hitbox.teleport(to);
        from.getWorld().spawnParticle(trailParticle, to, 2, 0.02, 0.02, 0.02, 0.0);

        if (entHit != null && entHit.getHitEntity() instanceof LivingEntity victim) {
            impact(to, victim);
        }
    }

    private void impact(Location where, LivingEntity victim) {
        World w = where.getWorld();
        if (w != null) {
            w.spawnParticle(hitParticle, where, 1);
            w.playSound(where, hitSound, 1f, 1f);
        }
        
        if (victim != null && victim.isValid()) {
            if (CitizensHook.isNPC(victim)) { remove(false); return; }
            victim.damage(damage, shooter);
            victim.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.WITHER, witherDur, witherAmp, true, true, true));
        }
    
        remove(true);
    }

    private void remove(boolean exploded) {
        if (task != null) task.cancel();
        if (display != null && display.isValid()) display.remove();
        if (hitbox != null && hitbox.isValid()) hitbox.remove();
    }
}
