package local.disc;

import static org.bukkit.Material.CYAN_CONCRETE;
import static org.bukkit.Material.GLASS;
import static org.bukkit.Material.GRAY_CONCRETE;
import static org.bukkit.Material.LIGHT_GRAY_CONCRETE;
import static org.bukkit.Material.LIME_CONCRETE;
import static org.bukkit.Material.MAGENTA_CONCRETE;
import static org.bukkit.Material.ORANGE_CONCRETE;
import static org.bukkit.Material.POLISHED_ANDESITE;
import static org.bukkit.Material.SEA_LANTERN;
import static org.bukkit.Material.SMOOTH_STONE;
import static org.bukkit.Material.WHITE_CONCRETE;
import static org.bukkit.persistence.PersistentDataType.BOOLEAN;

import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;

final class LobbyExpansion {
    static final int EDGE = 35;
    private static final int CORE_EDGE = 15;
    private static final int FLOOR = 63;
    private static final int GROUND = FLOOR + 1;
    private static final NamespacedKey APPLIED = new NamespacedKey("disc", "lobby_expansion_v1");

    private static final List<Pad> PADS = List.of(
            new Pad(-12, -33, 12, -23, CYAN_CONCRETE),
            new Pad(23, -12, 33, 12, MAGENTA_CONCRETE),
            new Pad(-12, 23, 12, 33, ORANGE_CONCRETE),
            new Pad(-33, -12, -23, 12, LIME_CONCRETE),
            new Pad(-32, -32, -22, -22, GRAY_CONCRETE),
            new Pad(22, -32, 32, -22, GRAY_CONCRETE),
            new Pad(22, 22, 32, 32, GRAY_CONCRETE),
            new Pad(-32, 22, -22, 32, GRAY_CONCRETE));
    private static final List<Pad> APPROACHES = List.of(
            new Pad(-8, -22, -4, -16, CYAN_CONCRETE),
            new Pad(16, -11, 22, -7, MAGENTA_CONCRETE),
            new Pad(-10, 16, -6, 22, ORANGE_CONCRETE),
            new Pad(-22, -9, -16, -5, LIME_CONCRETE));

    private LobbyExpansion() { }

    static void apply(World world) {
        if (!DiscLobby.isLobby(world)) {
            throw new IllegalArgumentException("Lobby expansion requires disc:lobby");
        }
        var data = world.getPersistentDataContainer();
        if (data.getOrDefault(APPLIED, BOOLEAN, false)) {
            return;
        }

        layOuterFloor(world);
        furnishPads(world);
        openStockWall(world, -8, -CORE_EDGE, -4, -CORE_EDGE);
        openStockWall(world, CORE_EDGE, -11, CORE_EDGE, -7);
        openStockWall(world, -10, CORE_EDGE, -6, CORE_EDGE);
        openStockWall(world, -CORE_EDGE, -9, -CORE_EDGE, -5);

        // Flush the geometry before recording that migration has completed.
        world.save(true);
        data.set(APPLIED, BOOLEAN, true);
    }

    private static void layOuterFloor(World world) {
        for (int chunkX = -EDGE >> 4; chunkX <= EDGE >> 4; chunkX++) {
            for (int chunkZ = -EDGE >> 4; chunkZ <= EDGE >> 4; chunkZ++) {
                int originX = chunkX << 4;
                int originZ = chunkZ << 4;
                int minX = Math.max(-EDGE, originX);
                int maxX = Math.min(EDGE, originX + 15);
                int minZ = Math.max(-EDGE, originZ);
                int maxZ = Math.min(EDGE, originZ + 15);
                if (minX >= -CORE_EDGE && maxX <= CORE_EDGE && minZ >= -CORE_EDGE && maxZ <= CORE_EDGE) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (Math.abs(x) <= CORE_EDGE && Math.abs(z) <= CORE_EDGE) {
                            continue;
                        }
                        fillAir(chunk.getBlock(x & 15, FLOOR - 1, z & 15), GRAY_CONCRETE);
                        fillAir(chunk.getBlock(x & 15, FLOOR, z & 15), floorAt(x, z));
                        if (Math.abs(x) == EDGE || Math.abs(z) == EDGE) {
                            for (int y = GROUND; y <= GROUND + 4; y++) {
                                Material material = y == GROUND + 4 ? LIGHT_GRAY_CONCRETE : WHITE_CONCRETE;
                                fillAir(chunk.getBlock(x & 15, y, z & 15), material);
                            }
                        }
                    }
                }
            }
        }
    }

    private static Material floorAt(int x, int z) {
        for (Pad pad : PADS) {
            if (pad.contains(x, z)) {
                return pad.onEdge(x, z) ? pad.accent() : SMOOTH_STONE;
            }
        }
        for (Pad approach : APPROACHES) {
            if (approach.contains(x, z)) {
                return approach.onEdge(x, z) ? approach.accent() : POLISHED_ANDESITE;
            }
        }
        if (Math.floorMod(x, 8) == 4 && Math.floorMod(z, 8) == 4) {
            return SEA_LANTERN;
        }
        return Math.max(Math.abs(x), Math.abs(z)) <= 20 ? POLISHED_ANDESITE : LIGHT_GRAY_CONCRETE;
    }

    private static void furnishPads(World world) {
        // Two high mounting beams leave the floor below open for hanging payloads.
        for (int z : new int[] {-30, -26}) {
            fillAir(world, -10, GROUND, z, -10, GROUND + 5, z, WHITE_CONCRETE);
            fillAir(world, 10, GROUND, z, 10, GROUND + 5, z, WHITE_CONCRETE);
            for (int x = -10; x <= 10; x++) {
                fillAir(world.getBlockAt(x, GROUND + 6, z), x % 6 == 0 ? SEA_LANTERN : WHITE_CONCRETE);
            }
        }

        fillAir(world, 32, GROUND, -9, 32, GROUND + 3, -4, WHITE_CONCRETE);
        fillAir(world, 31, GROUND, 3, 31, GROUND + 3, 8, GLASS);
        fillAir(world, -11, GROUND, 31, -9, GROUND + 1, 31, WHITE_CONCRETE);
        fillAir(world, 9, GROUND, 31, 11, GROUND + 1, 31, WHITE_CONCRETE);
        fillAir(world, -32, GROUND, -8, -29, GROUND, -4, LIGHT_GRAY_CONCRETE);
        fillAir(world, -32, GROUND + 1, -8, -31, GROUND + 1, -4, WHITE_CONCRETE);
        fillAir(world, -31, GROUND, 5, -28, GROUND, 8, WHITE_CONCRETE);
    }

    private static void openStockWall(World world, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = GROUND; y <= GROUND + 3; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == GRAY_CONCRETE) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private static void fillAir(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                Material material) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    fillAir(world.getBlockAt(x, y, z), material);
                }
            }
        }
    }

    private static void fillAir(Block block, Material material) {
        if (block.getType().isAir()) {
            block.setType(material, false);
        }
    }

    private record Pad(int minX, int minZ, int maxX, int maxZ, Material accent) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        boolean onEdge(int x, int z) {
            return x == minX || x == maxX || z == minZ || z == maxZ;
        }
    }
}
