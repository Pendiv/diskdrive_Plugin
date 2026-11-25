package local.disc;

import java.util.List;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

final class BotSonicShot {
    private static final double CRAMPED_RANGE = 3.5;
    private static final List<BlockFace> SIDES = List.of(
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

    private final Vector origin;
    private final Vector direction;
    private final double length;
    private final double radius;

    private BotSonicShot(Vector origin, Vector direction, double length, double radius) {
        this.origin = origin;
        this.direction = direction;
        this.length = length;
        this.radius = radius;
    }

    static BotSonicShot trace(Location origin, Vector direction, MatchRole role) {
        if (!valid(origin) || direction == null) {
            return null;
        }
        double magnitudeSquared = direction.lengthSquared();
        if (!Double.isFinite(magnitudeSquared) || magnitudeSquared < 1.0e-8) {
            return null;
        }

        Vector aim = direction.clone().normalize();
        double range = loadedDistance(origin, aim, DiscFlight.directRange(DiscType.WARDEN));
        if (range <= 0) {
            return null;
        }
        Vector start = origin.toVector();
        RayTraceResult wall = DiscFlight.traceWall(origin, aim, range);
        double length = wall == null ? range : Math.min(range, wall.getHitPosition().distance(start));
        return length > 0 ? new BotSonicShot(start, aim, length, DiscFlight.shockwaveRadius(role)) : null;
    }

    boolean reaches(BoundingBox target) {
        if (target == null) {
            return false;
        }
        Vector offset = target.getCenter().subtract(origin);
        double along = Math.clamp(offset.dot(direction), 0, length);
        return offset.subtract(direction.clone().multiply(along)).lengthSquared()
                <= radius * radius;
    }

    static boolean cramped(Location around) {
        if (!valid(around)) {
            return false;
        }
        Location start = around.clone().add(0, 1, 0);
        Block first = null;
        for (BlockFace side : SIDES) {
            Vector direction = side.getDirection();
            double length = loadedDistance(start, direction, CRAMPED_RANGE);
            RayTraceResult wall = length > 0
                    ? start.getWorld().rayTraceBlocks(start, direction, length, FluidCollisionMode.NEVER, true) : null;
            Block hit = wall == null ? null : wall.getHitBlock();
            if (hit == null) {
                continue;
            }
            if (first != null && !first.equals(hit)) {
                return true;
            }
            first = hit;
        }
        return false;
    }

    private static boolean valid(Location location) {
        return location != null && location.getWorld() != null && Double.isFinite(location.getX())
                && Double.isFinite(location.getY()) && Double.isFinite(location.getZ());
    }

    private static double loadedDistance(Location origin, Vector direction, double range) {
        World world = origin.getWorld();
        int fromX = origin.getBlockX() >> 4;
        int fromZ = origin.getBlockZ() >> 4;
        double distance = 0;
        double step = DiscFlight.launchSpeed(DiscType.WARDEN);
        while (distance < range) {
            double end = Math.min(range, distance + step);
            int toX = Location.locToBlock(origin.getX() + direction.getX() * end) >> 4;
            int toZ = Location.locToBlock(origin.getZ() + direction.getZ() * end) >> 4;
            if (!world.isChunkLoaded(fromX, fromZ) || !world.isChunkLoaded(toX, toZ)
                    || !world.isChunkLoaded(fromX, toZ) || !world.isChunkLoaded(toX, fromZ)) {
                break;
            }
            fromX = toX;
            fromZ = toZ;
            distance = end;
        }
        return distance;
    }
}
