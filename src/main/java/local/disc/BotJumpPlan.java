package local.disc;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class BotJumpPlan {
    private static final int MAX_TICKS = 40;
    private static final int MAX_SWEEP_SAMPLES = 32;
    private static final double SWEEP_STEP = 0.25;
    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.98;
    private static final double MIN_VERTICAL_SPEED = -1.4;
    private static final double BOUNDARY_MARGIN = 0.01;
    private static final double EPSILON = 1.0e-5;

    private BotJumpPlan() { }

    static Location landing(
            Player player, Location from, Location waypoint, double horizontalSpeed, double verticalSpeed) {
        World world = from.getWorld();
        if (waypoint == null || world != waypoint.getWorld() || player.getWorld() != world || !DiscArena.isArena(world)
                || !Double.isFinite(horizontalSpeed) || !Double.isFinite(verticalSpeed)
                || horizontalSpeed <= 0 || verticalSpeed <= 0) {
            return null;
        }
        Vector horizontal = waypoint.toVector().subtract(from.toVector()).setY(0);
        if (horizontal.lengthSquared() < 0.01) {
            return null;
        }
        horizontal.normalize().multiply(horizontalSpeed);

        int halfWidth = DiscArena.isDuel(world) ? DuelArenaGenerator.HALF_WIDTH : DiscArena.edge(world) - 1;
        int halfLength = DiscArena.isDuel(world) ? DuelArenaGenerator.HALF_LENGTH : halfWidth;
        var border = world.getWorldBorder();
        Location center = border.getCenter();
        double halfBorder = border.getSize() * 0.5;
        var area = new JumpArea(world, Math.max(-halfWidth, center.getX() - halfBorder),
                Math.min(halfWidth + 1.0, center.getX() + halfBorder),
                Math.max(-halfLength, center.getZ() - halfBorder),
                Math.min(halfLength + 1.0, center.getZ() + halfBorder),
                player.getWidth() * 0.5 + BOUNDARY_MARGIN, player.getHeight());
        if (!area.contains(from)) {
            return null;
        }

        Location current = from.clone();
        Location sample = from.clone();
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            Location loweredOrRaised = current.clone().add(0, verticalSpeed, 0);
            boolean lands = false;
            if (verticalSpeed < 0) {
                int firstFloor = Location.locToBlock(current.getY() + EPSILON);
                int lastFloor = (int) Math.ceil(loweredOrRaised.getY() - EPSILON);
                for (int y = firstFloor; y >= lastFloor; y--) {
                    sample.setX(current.getX());
                    sample.setY(y);
                    sample.setZ(current.getZ());
                    if (floorAt(area, sample)) {
                        loweredOrRaised.setY(y);
                        lands = true;
                        break;
                    }
                }
            }
            Location next = loweredOrRaised.clone().add(horizontal);
            if (!clearSweep(player, area, current, loweredOrRaised, sample)
                    || !clearSweep(player, area, loweredOrRaised, next, sample)) {
                return null;
            }
            if (lands) {
                return floorAt(area, next) ? next : null;
            }
            current = next;
            verticalSpeed = Math.max(MIN_VERTICAL_SPEED, (verticalSpeed - GRAVITY) * VERTICAL_DRAG);
        }
        return null;
    }

    private static boolean clearSweep(Player player, JumpArea area, Location from, Location to, Location sample) {
        if (!area.contains(to)) {
            return false;
        }
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        int samples = Math.max(1, (int) Math.ceil(from.distance(to) / SWEEP_STEP));
        if (samples > MAX_SWEEP_SAMPLES) {
            return false;
        }
        for (int index = 1; index <= samples; index++) {
            double progress = (double) index / samples;
            sample.setX(from.getX() + dx * progress);
            sample.setY(from.getY() + dy * progress);
            sample.setZ(from.getZ() + dz * progress);
            if (!area.contains(sample) || touchesHazard(area, sample) || player.collidesAt(sample)) {
                return false;
            }
        }
        return true;
    }

    private static boolean floorAt(JumpArea area, Location point) {
        if (!area.contains(point) || point.getBlockY() <= area.world().getMinHeight()) {
            return false;
        }
        Location footing = BotPath.standingNear(point);
        return footing != null && footing.getBlockY() == point.getBlockY()
                && Math.abs(point.getBlock().getRelative(0, -1, 0).getBoundingBox().getMaxY()
                        - point.getY()) <= EPSILON;
    }

    private static boolean touchesHazard(JumpArea area, Location point) {
        int left = Location.locToBlock(point.getX() - area.margin());
        int right = Location.locToBlock(point.getX() + area.margin());
        int north = Location.locToBlock(point.getZ() - area.margin());
        int south = Location.locToBlock(point.getZ() + area.margin());
        int bottom = Location.locToBlock(point.getY() + EPSILON);
        int top = Location.locToBlock(point.getY() + area.height() - EPSILON);
        for (int x = left; x <= right; x++) {
            for (int z = north; z <= south; z++) {
                for (int y = bottom; y <= top; y++) {
                    Material material = area.world().getBlockAt(x, y, z).getType();
                    if (material == Material.LAVA || material == Material.FIRE || material == Material.SOUL_FIRE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private record JumpArea(World world, double minX, double maxX, double minZ, double maxZ, double margin,
                            double height) {
        boolean contains(Location point) {
            double left = point.getX() - margin;
            double right = point.getX() + margin;
            double north = point.getZ() - margin;
            double south = point.getZ() + margin;
            if (left < minX || right >= maxX || north < minZ || south >= maxZ
                    || point.getY() < world.getMinHeight() || point.getY() + height >= world.getMaxHeight()) {
                return false;
            }
            int leftChunk = Location.locToBlock(left - 1) >> 4;
            int rightChunk = Location.locToBlock(right + 1) >> 4;
            int northChunk = Location.locToBlock(north - 1) >> 4;
            int southChunk = Location.locToBlock(south + 1) >> 4;
            return world.isChunkLoaded(leftChunk, northChunk) && world.isChunkLoaded(leftChunk, southChunk)
                    && world.isChunkLoaded(rightChunk, northChunk) && world.isChunkLoaded(rightChunk, southChunk);
        }
    }
}
