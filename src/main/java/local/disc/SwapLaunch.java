package local.disc;

import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

final class SwapLaunch {
    private static final double DISTANCE = 4;
    private static final double SPEED = 0.8;
    private static final double STOP_DISTANCE = 0.025;
    private static final int MAX_TICKS = 12;
    private static final int MAX_STALLED_TICKS = 3;

    private final LivingEntity target;
    private final World world;
    private final Vector direction;
    private Vector origin;
    private double lastDistance;
    private int ticks;
    private int stalledTicks;

    SwapLaunch(LivingEntity target, Vector direction) {
        this.target = target;
        this.world = target.getWorld();
        this.direction = direction.clone();
    }

    boolean tick() {
        if (!target.isValid() || target.isDead() || target.getWorld() != world) {
            return false;
        }
        Vector position = target.getLocation().toVector();
        if (origin == null) {
            // Apply the launch after the damage event's normal knockback has been processed.
            origin = position;
            target.setVelocity(direction.clone().multiply(SPEED));
            return true;
        }
        double traveled = position.subtract(origin).dot(direction);
        double remaining = DISTANCE - traveled;
        stalledTicks = traveled <= lastDistance + STOP_DISTANCE ? stalledTicks + 1 : 0;
        lastDistance = traveled;
        if (remaining <= STOP_DISTANCE || ++ticks >= MAX_TICKS || stalledTicks >= MAX_STALLED_TICKS) {
            stop();
            return false;
        }
        target.setVelocity(direction.clone().multiply(Math.min(SPEED, remaining)));
        return true;
    }

    void stop() {
        if (origin == null || !target.isValid() || target.isDead() || target.getWorld() != world) {
            return;
        }
        Vector velocity = target.getVelocity();
        double forward = velocity.dot(direction);
        if (forward > 0) {
            target.setVelocity(velocity.subtract(direction.clone().multiply(forward)));
        }
    }
}
