package local.disc;

import static org.bukkit.Material.BLUE_CONCRETE;
import static org.bukkit.Material.CYAN_CONCRETE;
import static org.bukkit.Material.GLASS;
import static org.bukkit.Material.GRAY_CONCRETE;
import static org.bukkit.Material.LIGHT_GRAY_CONCRETE;
import static org.bukkit.Material.LIME_CONCRETE;
import static org.bukkit.Material.MAGENTA_CONCRETE;
import static org.bukkit.Material.ORANGE_CONCRETE;
import static org.bukkit.Material.PINK_CONCRETE;
import static org.bukkit.Material.RED_CONCRETE;
import static org.bukkit.Material.SEA_LANTERN;
import static org.bukkit.Material.SMOOTH_QUARTZ_STAIRS;
import static org.bukkit.Material.SMOOTH_STONE;
import static org.bukkit.Material.WATER;
import static org.bukkit.Material.WHITE_CONCRETE;
import static org.bukkit.Material.YELLOW_CONCRETE;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

final class IndoorArenaGenerator extends ChunkGenerator {
    static final int EDGE = 58;
    static final int GROUND = 64;
    static final int ROOF = 85;

    private static final int FLOOR = GROUND - 1;
    private static final int GALLERY = GROUND + 2;
    private static final int CARDINAL_START = 52;
    private static final int DIAGONAL_START = 38;
    private static final Material[] COLORS = {CYAN_CONCRETE, ORANGE_CONCRETE, LIME_CONCRETE, MAGENTA_CONCRETE,
            RED_CONCRETE, BLUE_CONCRETE, YELLOW_CONCRETE, PINK_CONCRETE};
    private static final BlockFace[] FACINGS = {BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST};

    static Location spawn(World world, int slot) {
        if (slot < 0 || slot >= COLORS.length) {
            throw new IllegalArgumentException("Invalid indoor arena spawn slot: " + slot);
        }
        boolean diagonal = slot >= 4;
        int turn = slot % 4;
        Point point = diagonal ? rotate(-DIAGONAL_START, -DIAGONAL_START, turn) : rotate(0, -CARDINAL_START, turn);
        return new Location(world, point.x() + 0.5, GROUND, point.z() + 0.5, turn * 90 + (diagonal ? -45 : 0), 0);
    }

    static Location overlook(World world) {
        return new Location(world, 0.5, ROOF - 4, -0.5, 0, 82);
    }

    static int[][] outerBoxPositions() {
        int[][] positions = new int[8][];
        for (int turn = 0; turn < 4; turn++) {
            Point cardinal = rotate(0, -30, turn);
            positions[turn] = new int[] {cardinal.x(), GROUND, cardinal.z()};
            Point diagonal = rotate(-23, -23, turn);
            positions[turn + 4] = new int[] {diagonal.x(), GROUND, diagonal.z()};
        }
        return positions;
    }

    static int[] centerBoxPosition() {
        return new int[] {0, GROUND, 0};
    }

    @Override
    public void generateNoise(WorldInfo world, Random random, int chunkX, int chunkZ, ChunkData data) {
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        if (blockX > EDGE || blockX + 15 < -EDGE || blockZ > EDGE || blockZ + 15 < -EDGE) {
            return;
        }
        ChunkBlocks chunk = new ChunkBlocks(data, blockX, blockZ);
        chunk.fill(-EDGE, FLOOR - 3, -EDGE, EDGE, FLOOR - 1, EDGE, LIGHT_GRAY_CONCRETE);
        chunk.fill(-EDGE, FLOOR, -EDGE, EDGE, FLOOR, EDGE, WHITE_CONCRETE);
        chunk.fill(-EDGE, ROOF, -EDGE, EDGE, ROOF, EDGE, WHITE_CONCRETE);
        chunk.fill(-11, FLOOR, -11, 11, FLOOR, 11, SMOOTH_STONE);
        chunk.fill(-2, FLOOR, -2, 2, FLOOR, 2, LIGHT_GRAY_CONCRETE);

        for (int turn = 0; turn < 4; turn++) {
            shell(chunk, turn);
            cardinalBunker(chunk, turn);
            cornerBunker(chunk, turn);
            backRooms(chunk, turn);
            lowerArcades(chunk, turn);
            gallery(chunk, turn);
            atrium(chunk, turn);
        }
        chunk.lightOpenFloors();
    }

    private static void shell(ChunkBlocks chunk, int turn) {
        chunk.fill(turn, -EDGE, GROUND, -EDGE, EDGE, ROOF - 1, -EDGE, WHITE_CONCRETE);
        chunk.fill(turn, -EDGE + 1, GROUND + 1, -EDGE, EDGE - 1, GROUND + 2, -EDGE, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, -EDGE + 1, GROUND + 8, -EDGE, EDGE - 1, GROUND + 8, -EDGE, COLORS[turn]);
        chunk.fill(turn, -47, GROUND + 10, -45, 47, GROUND + 11, -44, WHITE_CONCRETE);
        chunk.fill(turn, -42, GROUND + 10, -45, -38, GROUND + 10, -45, SEA_LANTERN);
        chunk.fill(turn, -18, GROUND + 10, -45, -14, GROUND + 10, -45, SEA_LANTERN);
        chunk.fill(turn, 14, GROUND + 10, -45, 18, GROUND + 10, -45, SEA_LANTERN);
        chunk.fill(turn, 38, GROUND + 10, -45, 42, GROUND + 10, -45, SEA_LANTERN);
    }

    private static void cardinalBunker(ChunkBlocks chunk, int turn) {
        Material color = COLORS[turn];
        chunk.fill(turn, -5, FLOOR, -56, 5, FLOOR, -49, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, -3, FLOOR, -54, 3, FLOOR, -50, color);
        chunk.fill(turn, -6, GROUND, -48, 6, GROUND + 4, -47, WHITE_CONCRETE);
        chunk.fill(turn, -7, GROUND, -57, -7, GROUND + 4, -53, WHITE_CONCRETE);
        chunk.fill(turn, 7, GROUND, -57, 7, GROUND + 4, -53, WHITE_CONCRETE);
        chunk.fill(turn, -7, GROUND + 5, -57, 7, GROUND + 5, -47, WHITE_CONCRETE);
        chunk.fill(turn, -3, GROUND + 5, -53, 3, GROUND + 5, -53, SEA_LANTERN);
        chunk.fill(turn, -6, GROUND + 4, -47, 6, GROUND + 4, -47, color);
    }

    private static void cornerBunker(ChunkBlocks chunk, int turn) {
        Material color = COLORS[turn + 4];
        chunk.fill(turn, -43, FLOOR, -43, -35, FLOOR, -35, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, -40, FLOOR, -40, -36, FLOOR, -36, color);
        chunk.fill(turn, -34, GROUND, -41, -33, GROUND + 4, -34, WHITE_CONCRETE);
        chunk.fill(turn, -41, GROUND, -34, -34, GROUND + 4, -33, WHITE_CONCRETE);
        chunk.fill(turn, -44, GROUND, -44, -44, GROUND + 4, -39, WHITE_CONCRETE);
        chunk.fill(turn, -44, GROUND, -44, -39, GROUND + 4, -44, WHITE_CONCRETE);
        chunk.fill(turn, -44, GROUND + 5, -44, -33, GROUND + 5, -33, WHITE_CONCRETE);
        chunk.fill(turn, -40, GROUND + 5, -39, -36, GROUND + 5, -39, SEA_LANTERN);
        chunk.fill(turn, -33, GROUND + 4, -41, -33, GROUND + 4, -34, color);
        chunk.fill(turn, -41, GROUND + 4, -33, -34, GROUND + 4, -33, color);
    }

    private static void backRooms(ChunkBlocks chunk, int turn) {
        chunk.fill(turn, -43, GROUND, -57, -41, GROUND + 1, -53, WHITE_CONCRETE);
        chunk.fill(turn, 35, GROUND, -57, 37, GROUND + 1, -54, WHITE_CONCRETE);
        chunk.fill(turn, -55, GROUND, -55, -47, GROUND + 4, -53, WHITE_CONCRETE);
        chunk.fill(turn, -55, GROUND, -52, -53, GROUND + 4, -45, WHITE_CONCRETE);
        chunk.fill(turn, -49, GROUND, -51, -47, GROUND + 2, -47, WHITE_CONCRETE);
        chunk.fill(turn, -31, GROUND, -52, -18, GROUND + 4, -51, WHITE_CONCRETE);
        chunk.fill(turn, -31, GROUND, -50, -30, GROUND + 4, -44, WHITE_CONCRETE);
        chunk.fill(turn, -18, GROUND, -50, -17, GROUND + 4, -45, GLASS);
        chunk.fill(turn, -27, GROUND, -47, -22, GROUND + 1, -45, WHITE_CONCRETE);
        chunk.fill(turn, -31, GROUND + 7, -49, -17, GROUND + 7, -48, WHITE_CONCRETE);
        chunk.fill(turn, -26, GROUND + 7, -49, -22, GROUND + 7, -49, SEA_LANTERN);
        chunk.fill(turn, 13, GROUND, -55, 20, GROUND + 3, -53, WHITE_CONCRETE);
        chunk.fill(turn, 10, GROUND, -48, 13, GROUND + 2, -45, WHITE_CONCRETE);
        chunk.fill(turn, 17, GROUND, -45, 24, GROUND + 3, -44, GLASS);
        chunk.fill(turn, 24, GROUND, -51, 26, GROUND + 3, -45, WHITE_CONCRETE);
        chunk.fill(turn, 28, GROUND, -41, 31, GROUND + 2, -39, WHITE_CONCRETE);
        chunk.fill(turn, 31, GROUND, -47, 33, GROUND + 3, -43, WHITE_CONCRETE);
    }

    private static void lowerArcades(ChunkBlocks chunk, int turn) {
        chunk.fill(turn, -31, GROUND, -36, -22, GROUND + 3, -34, WHITE_CONCRETE);
        chunk.fill(turn, -31, GROUND, -33, -29, GROUND + 3, -28, WHITE_CONCRETE);
        chunk.fill(turn, -25, GROUND, -29, -20, GROUND + 2, -28, GLASS);
        chunk.fill(turn, -16, GROUND, -40, -11, GROUND + 2, -38, WHITE_CONCRETE);
        chunk.fill(turn, -7, GROUND, -35, -5, GROUND + 2, -32, WHITE_CONCRETE);
        chunk.fill(turn, 7, GROUND, -39, 10, GROUND + 3, -37, WHITE_CONCRETE);
        chunk.fill(turn, 19, GROUND, -37, 27, GROUND + 3, -36, WHITE_CONCRETE);
        chunk.fill(turn, 23, FLOOR, -35, 26, FLOOR, -29, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, 24, FLOOR, -34, 25, FLOOR, -30, WATER);
        chunk.fill(turn, 22, GROUND, -29, 27, GROUND + 2, -28, GLASS);
        chunk.fill(turn, -18, GROUND, -25, -13, GROUND + 1, -24, WHITE_CONCRETE);
        chunk.fill(turn, 9, GROUND, -20, 12, GROUND + 2, -18, GLASS);
        chunk.fill(turn, 15, GROUND, -23, 19, GROUND + 1, -21, WHITE_CONCRETE);
        chunk.fill(turn, -18, FLOOR, -15, -14, FLOOR, -7, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, -17, FLOOR, -14, -15, FLOOR, -8, WATER);
    }

    private static void gallery(ChunkBlocks chunk, int turn) {
        chunk.fill(turn, -26, GALLERY, -26, -4, GALLERY, -21, WHITE_CONCRETE);
        chunk.fill(turn, 4, GALLERY, -26, 26, GALLERY, -21, WHITE_CONCRETE);
        chunk.fill(turn, -3, GALLERY, -26, 3, GALLERY, -23, SMOOTH_STONE);
        chunk.fill(turn, -26, GALLERY, -26, 26, GALLERY, -26, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, -24, GALLERY + 1, -21, -21, GALLERY + 1, -21, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, -14, GALLERY + 1, -21, -11, GALLERY + 1, -21, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, 4, GALLERY + 1, -21, 8, GALLERY + 1, -21, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, 17, GALLERY + 1, -21, 21, GALLERY + 1, -21, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, -3, GALLERY + 1, -26, 3, GALLERY + 1, -26, LIGHT_GRAY_CONCRETE);
        chunk.fill(turn, -19, GALLERY, -25, -18, GALLERY, -24, SEA_LANTERN);
        chunk.fill(turn, 10, GALLERY, -25, 11, GALLERY, -24, SEA_LANTERN);
        chunk.fill(turn, -20, GROUND, -26, -19, GALLERY - 1, -25, WHITE_CONCRETE);
        chunk.fill(turn, 20, GROUND, -26, 21, GALLERY - 1, -25, WHITE_CONCRETE);

        chunk.fill(turn, -20, GALLERY + 1, -26, -18, GALLERY + 2, -25, WHITE_CONCRETE);
        chunk.fill(turn, 9, GALLERY + 1, -22, 12, GALLERY + 1, -21, WHITE_CONCRETE);
        chunk.fill(turn, 10, GALLERY + 2, -21, 12, GALLERY + 2, -21, GLASS);
        chunk.fill(turn, 22, GALLERY + 1, -24, 24, GALLERY + 2, -24, WHITE_CONCRETE);

        outerStairs(chunk, turn, -17);
        outerStairs(chunk, turn, 14);
        chunk.stairs(turn, -8, GROUND, -17, -5, -17, false);
        chunk.stairs(turn, -8, GROUND + 1, -18, -5, -18, false);
        chunk.stairs(turn, -8, GALLERY, -19, -5, -19, false);
        chunk.fill(turn, -8, GALLERY, -20, -5, GALLERY, -20, WHITE_CONCRETE);
    }

    private static void outerStairs(ChunkBlocks chunk, int turn, int x) {
        chunk.stairs(turn, x, GROUND, -30, x + 3, -30, true);
        chunk.stairs(turn, x, GROUND + 1, -29, x + 3, -29, true);
        chunk.stairs(turn, x, GALLERY, -28, x + 3, -28, true);
        chunk.fill(turn, x, GALLERY, -27, x + 3, GALLERY, -27, WHITE_CONCRETE);
    }

    private static void atrium(ChunkBlocks chunk, int turn) {
        chunk.fill(turn, -12, GROUND, -11, -7, GROUND + 2, -10, WHITE_CONCRETE);
        chunk.fill(turn, -12, GROUND, -9, -11, GROUND + 2, -6, WHITE_CONCRETE);
        chunk.fill(turn, 7, GROUND, -12, 11, GROUND + 2, -12, GLASS);
        chunk.fill(turn, 12, GROUND, -12, 13, GROUND + 2, -10, WHITE_CONCRETE);
        chunk.fill(turn, -6, GROUND, -5, -4, GROUND + 1, -4, WHITE_CONCRETE);
        chunk.fill(turn, -16, GROUND + 10, -16, 16, GROUND + 10, -15, WHITE_CONCRETE);
        chunk.fill(turn, -5, GROUND + 10, -16, 5, GROUND + 10, -16, SEA_LANTERN);
    }

    private static Point rotate(int x, int z, int turn) {
        return switch (turn) {
            case 0 -> new Point(x, z);
            case 1 -> new Point(-z, x);
            case 2 -> new Point(-x, -z);
            case 3 -> new Point(z, -x);
            default -> throw new IllegalArgumentException("Invalid indoor arena rotation: " + turn);
        };
    }

    private record Point(int x, int z) { }

    private record ChunkBlocks(ChunkData data, int blockX, int blockZ) {
        void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
            int startX = Math.max(0, x1 - blockX);
            int startZ = Math.max(0, z1 - blockZ);
            int endX = Math.min(16, x2 - blockX + 1);
            int endZ = Math.min(16, z2 - blockZ + 1);
            if (startX < endX && startZ < endZ) {
                data.setRegion(startX, y1, startZ, endX, y2 + 1, endZ, material);
            }
        }

        void fill(int turn, int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
            Point a = rotate(x1, z1, turn);
            Point b = rotate(x2, z2, turn);
            fill(Math.min(a.x(), b.x()), y1, Math.min(a.z(), b.z()),
                    Math.max(a.x(), b.x()), y2, Math.max(a.z(), b.z()), material);
        }

        void stairs(int turn, int x1, int y, int z1, int x2, int z2, boolean south) {
            Point a = rotate(x1, z1, turn);
            Point b = rotate(x2, z2, turn);
            int startX = Math.max(0, Math.min(a.x(), b.x()) - blockX);
            int startZ = Math.max(0, Math.min(a.z(), b.z()) - blockZ);
            int endX = Math.min(16, Math.max(a.x(), b.x()) - blockX + 1);
            int endZ = Math.min(16, Math.max(a.z(), b.z()) - blockZ + 1);
            if (startX < endX && startZ < endZ) {
                Stairs stairs = (Stairs) SMOOTH_QUARTZ_STAIRS.createBlockData();
                stairs.setFacing(FACINGS[(turn + (south ? 0 : 2)) % 4]);
                data.setRegion(startX, y, startZ, endX, y + 1, endZ, stairs);
            }
        }

        void lightOpenFloors() {
            for (int x = 0; x < 16; x++) {
                if (Math.floorMod(blockX + x, 8) != 4) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    if (Math.floorMod(blockZ + z, 8) != 4) {
                        continue;
                    }
                    Material floor = data.getType(x, FLOOR, z);
                    if ((floor == WHITE_CONCRETE || floor == SMOOTH_STONE) && data.getType(x, GROUND, z).isAir()) {
                        data.setBlock(x, FLOOR, z, SEA_LANTERN);
                    }
                }
            }
        }
    }
}
