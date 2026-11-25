package local.disc;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

final class DiscLanding {
    private static final int SEARCH_ATTEMPTS = 128;

    private final Location origin;
    private final World world;
    private final Bounds playable;
    private final int preferredY;
    private final int heightSearch;

    DiscLanding(Location origin, int preferredY, int heightSearch) {
        this.origin = origin;
        this.world = origin.getWorld();
        this.playable = playableBounds(world);
        this.preferredY = preferredY;
        this.heightSearch = heightSearch;
    }

    Block random(int minimumRadius, int radius, Block excluded) {
        return pick(minimumRadius, radius, excluded, 1, null);
    }

    Block closestOf(int radius, int choices, ToDoubleFunction<Block> distance) {
        return pick(0, radius, null, choices, distance);
    }

    private Block pick(int minimumRadius, int radius, Block excluded, int choices, ToDoubleFunction<Block> distance) {
        Bounds bounds = playable.intersect(Bounds.around(origin.getBlockX(), origin.getBlockZ(), radius));
        if (bounds.empty()) {
            return null;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        Block best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        int found = 0;
        for (int attempt = 0; attempt < SEARCH_ATTEMPTS && found < choices; attempt++) {
            int x = random.nextInt(bounds.minX, bounds.maxX + 1);
            int z = random.nextInt(bounds.minZ, bounds.maxZ + 1);
            double dx = x + 0.5 - origin.getX();
            double dz = z + 0.5 - origin.getZ();
            double squared = dx * dx + dz * dz;
            if (squared < minimumRadius * minimumRadius || squared > radius * radius
                    || !world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block candidate = safeAtHeight(x, z, excluded);
            if (candidate == null) {
                continue;
            }
            if (choices == 1) {
                return candidate;
            }
            double score = distance.applyAsDouble(candidate);
            if (score < bestDistance) {
                best = candidate;
                bestDistance = score;
            }
            found++;
        }
        return best;
    }

    private Block safeAtHeight(int x, int z, Block excluded) {
        for (int vertical = 0; vertical <= heightSearch * 2; vertical++) {
            int y = preferredY + (vertical % 2 == 0 ? -vertical / 2 : (vertical + 1) / 2);
            if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
                continue;
            }
            Block block = world.getBlockAt(x, y, z);
            if (!block.equals(excluded) && safe(block)) {
                return block;
            }
        }
        return null;
    }

    static boolean safe(Block target) {
        if (!target.getType().isAir() || !target.getRelative(BlockFace.UP).getType().isAir()) {
            return false;
        }
        Block floor = target.getRelative(BlockFace.DOWN);
        return floor.getType().isSolid() && floor.getType() != Material.MAGMA_BLOCK
                && Math.abs(floor.getBoundingBox().getMaxY() - target.getY()) < 0.001
                && target.getWorld().getNearbyEntities(BoundingBox.of(target.getLocation(),
                        target.getLocation().add(1, 2, 1)), entity -> entity instanceof LivingEntity
                        && (!(entity instanceof Player player) || player.getGameMode() != GameMode.SPECTATOR))
                .isEmpty();
    }

    private static Bounds playableBounds(World world) {
        var border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = border.getSize() * 0.5;
        Bounds limits = new Bounds((int) Math.ceil(center.getX() - half), (int) Math.floor(center.getX() + half) - 1,
                (int) Math.ceil(center.getZ() - half), (int) Math.floor(center.getZ() + half) - 1);
        if (DiscArena.isDuel(world)) {
            return limits.intersect(new Bounds(-DuelArenaGenerator.HALF_WIDTH, DuelArenaGenerator.HALF_WIDTH,
                    -DuelArenaGenerator.HALF_LENGTH, DuelArenaGenerator.HALF_LENGTH));
        }
        return DiscArena.isArena(world) ? limits.intersect(Bounds.around(0, 0, DiscArena.edge(world) - 1)) : limits;
    }

    private record Bounds(int minX, int maxX, int minZ, int maxZ) {
        static Bounds around(int x, int z, int radius) {
            return new Bounds(x - radius, x + radius, z - radius, z + radius);
        }

        Bounds intersect(Bounds other) {
            return new Bounds(Math.max(minX, other.minX), Math.min(maxX, other.maxX),
                    Math.max(minZ, other.minZ), Math.min(maxZ, other.maxZ));
        }

        boolean empty() {
            return minX > maxX || minZ > maxZ;
        }
    }
}
