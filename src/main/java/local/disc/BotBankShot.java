package local.disc;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

final class BotBankShot {
    private static final double RANGE = DiscFlight.directRange(DiscType.NORMAL);
    private static final double WALL_SEARCH = 16;
    private static final int[] YAW_OFFSETS = {0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};

    private BotBankShot() { }

    static boolean inRange(double distance) {
        return distance <= RANGE + DiscFlight.BOUNCE_RANGE;
    }

    static Vector aim(Location origin, BoundingBox owner, BoundingBox target) {
        Vector facing = origin.getDirection();
        if (hits(origin, facing, owner, target)) {
            return facing;
        }
        Location probe = origin.clone();
        probe.setPitch(0);
        Vector best = null;
        double bestAlignment = -2;
        for (int yaw : YAW_OFFSETS) {
            probe.setYaw(origin.getYaw() + yaw);
            Vector direction = aimOffFace(origin, probe.getDirection(), target.getCenter());
            if (direction != null && direction.dot(facing) > bestAlignment && hits(origin, direction, owner, target)) {
                best = direction;
                bestAlignment = direction.dot(facing);
            }
        }
        probe.setYaw(origin.getYaw());
        for (int pitch : new int[]{-60, 60}) {
            probe.setPitch(pitch);
            Vector direction = aimOffFace(origin, probe.getDirection(), target.getCenter());
            if (direction != null && direction.dot(facing) > bestAlignment && hits(origin, direction, owner, target)) {
                best = direction;
                bestAlignment = direction.dot(facing);
            }
        }
        return best;
    }

    private static Vector aimOffFace(Location origin, Vector probe, Vector target) {
        RayTraceResult wall = DiscFlight.traceWall(origin, probe, WALL_SEARCH);
        if (wall == null || wall.getHitBlockFace() == null) {
            return null;
        }
        Vector normal = wall.getHitBlockFace().getDirection();
        Vector surface = wall.getHitPosition();
        double side = target.clone().subtract(surface).dot(normal);
        if (side <= DiscFlight.EDGE_OFFSET) {
            return null;
        }
        Vector mirrored = target.clone().subtract(normal.multiply(2 * side)).subtract(origin.toVector());
        return mirrored.lengthSquared() > 0.001 ? mirrored.normalize() : null;
    }

    static boolean hits(Location origin, Vector direction, BoundingBox owner, BoundingBox target) {
        RayTraceResult wall = DiscFlight.traceWall(origin, direction, RANGE);
        if (wall == null) {
            return false;
        }
        BlockFace face = wall.getHitBlockFace();
        Vector start = origin.toVector();
        Vector surface = wall.getHitPosition();
        double firstLeg = start.distance(surface);
        if (face == null || firstLeg <= DiscFlight.EDGE_OFFSET
                || target.rayTrace(start, direction, firstLeg) != null) {
            return false;
        }

        Vector reflected = DiscFlight.reflect(direction, face.getDirection());
        Vector bounce = surface.add(face.getDirection().multiply(DiscFlight.EDGE_OFFSET));
        RayTraceResult contact = target.rayTrace(bounce, reflected, RANGE - firstLeg + DiscFlight.BOUNCE_RANGE);
        if (contact == null) {
            return false;
        }
        double secondLeg = bounce.distance(contact.getHitPosition());
        return secondLeg > DiscFlight.EDGE_OFFSET
                && owner.rayTrace(bounce, reflected, secondLeg) == null
                && DiscFlight.traceWall(bounce.toLocation(origin.getWorld()), reflected, secondLeg) == null;
    }
}
