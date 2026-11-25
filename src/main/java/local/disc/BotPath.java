package local.disc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

final class BotPath {
    private static final int SEARCH_LIMIT = 560;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[] HEIGHTS = {0, 1, -1, -2};

    private BotPath() { }

    static Location standingNear(Location location) {
        World world = location.getWorld();
        for (int offset : HEIGHTS) {
            Cell cell = new Cell(location.getBlockX(), location.getBlockY() + offset, location.getBlockZ());
            if (walkable(world, cell)) {
                return new Location(world, cell.x + 0.5, cell.y, cell.z + 0.5);
            }
        }
        return null;
    }

    static List<Location> find(Location from, Location to) {
        World world = from.getWorld();
        Cell start = new Cell(from.getBlockX(), (int) Math.floor(from.getY() + 0.08), from.getBlockZ());
        Cell goal = new Cell(to.getBlockX(), to.getBlockY(), to.getBlockZ());
        PriorityQueue<Node> frontier = new PriorityQueue<>(Comparator.comparingDouble(
                node -> node.cost + node.cell.estimate(goal)));
        Map<Cell, Double> costs = new HashMap<>();
        Node first = new Node(start, null, 0);
        frontier.add(first);
        costs.put(start, 0.0);
        Node best = first;
        for (int visited = 0; visited < SEARCH_LIMIT && !frontier.isEmpty(); visited++) {
            Node node = frontier.remove();
            if (node.cost > costs.get(node.cell)) {
                continue;
            }
            if (node.cell.estimate(goal) < best.cell.estimate(goal)) {
                best = node;
            }
            if (node.cell.equals(goal)) {
                break;
            }
            for (int[] direction : DIRECTIONS) {
                int x = node.cell.x + direction[0];
                int z = node.cell.z + direction[1];
                for (int rise : HEIGHTS) {
                    Cell next = new Cell(x, node.cell.y + rise, z);
                    if (!walkable(world, next) || !clear(world, node.cell.x,
                            Math.max(node.cell.y, next.y) + 1, node.cell.z)) {
                        continue;
                    }
                    double cost = node.cost + 1 + Math.abs(rise) * 0.45;
                    if (cost < costs.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                        costs.put(next, cost);
                        frontier.add(new Node(next, node, cost));
                    }
                    break;
                }
            }
        }
        List<Location> route = new ArrayList<>();
        for (Node node = best; node.previous != null; node = node.previous) {
            route.add(new Location(world, node.cell.x + 0.5, node.cell.y, node.cell.z + 0.5));
        }
        return List.copyOf(route.reversed());
    }

    private static boolean walkable(World world, Cell cell) {
        if (cell.y <= world.getMinHeight() || cell.y + 1 >= world.getMaxHeight()
                || !world.isChunkLoaded(cell.x >> 4, cell.z >> 4)) {
            return false;
        }
        Block floor = world.getBlockAt(cell.x, cell.y - 1, cell.z);
        return !floor.isPassable() && !hazard(floor.getType())
                && clear(world, cell.x, cell.y, cell.z) && clear(world, cell.x, cell.y + 1, cell.z);
    }

    private static boolean clear(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        return block.isPassable() && !hazard(block.getType());
    }

    private static boolean hazard(Material material) {
        return switch (material) {
            case TNT, LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE -> true;
            default -> false;
        };
    }

    private record Cell(int x, int y, int z) {
        double estimate(Cell other) {
            return Math.abs(x - other.x) + Math.abs(z - other.z) + Math.abs(y - other.y) * 1.4;
        }
    }

    private record Node(Cell cell, Node previous, double cost) { }
}
