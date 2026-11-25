package local.disc;

import static org.bukkit.Material.BLACK_CONCRETE;
import static org.bukkit.Material.CYAN_CONCRETE;
import static org.bukkit.Material.GLASS;
import static org.bukkit.Material.GRAY_CONCRETE;
import static org.bukkit.Material.LIGHT_GRAY_CONCRETE;
import static org.bukkit.Material.ORANGE_CONCRETE;
import static org.bukkit.Material.POLISHED_ANDESITE;
import static org.bukkit.Material.SEA_LANTERN;
import static org.bukkit.Material.SMOOTH_STONE;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

final class DuelArenaGenerator extends ChunkGenerator {
    static final int HALF_WIDTH = 12;
    static final int HALF_LENGTH = 14;
    private static final int WALL_X = HALF_WIDTH + 1;
    private static final int WALL_Z = HALF_LENGTH + 1;
    private static final int FLOOR = DiscArena.GROUND - 1;
    private static final int GROUND = DiscArena.GROUND;
    private static final int CEILING = GROUND + 8;

    static Location spawn(World world, int slot) {
        return new Location(world, 0.5, GROUND, slot == 0 ? -11.5 : 12.5, slot * 180, 0);
    }

    static Location overlook(World world) {
        return new Location(world, 0.5, GROUND + 5, -11.5, 0, 25);
    }

    @Override
    public void generateNoise(WorldInfo world, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        var room = new DiscArena.ChunkBlocks(chunkData, chunkX << 4, chunkZ << 4);
        room.fill(-WALL_X, FLOOR - 2, -WALL_Z, WALL_X, FLOOR, WALL_Z, GRAY_CONCRETE);
        room.fill(-HALF_WIDTH, FLOOR, -HALF_LENGTH, HALF_WIDTH, FLOOR, HALF_LENGTH, SMOOTH_STONE);
        room.fill(-2, FLOOR, -5, 2, FLOOR, 5, POLISHED_ANDESITE);
        room.fill(-WALL_X, CEILING, -WALL_Z, WALL_X, CEILING, WALL_Z, GRAY_CONCRETE);

        for (int wall : new int[] {-WALL_X, WALL_X}) {
            room.fill(wall, GROUND, -WALL_Z, wall, CEILING - 1, WALL_Z, LIGHT_GRAY_CONCRETE);
            room.fill(wall, GROUND, -HALF_LENGTH, wall, GROUND + 1, HALF_LENGTH, GRAY_CONCRETE);
            room.fill(wall, GROUND + 4, -10, wall, GROUND + 4, 10, SEA_LANTERN);
        }
        for (int x : new int[] {-9, 0, 9}) {
            room.fill(x, CEILING, -12, x, CEILING, 12, SEA_LANTERN);
        }
        half(room, 0, CYAN_CONCRETE);
        half(room, 2, ORANGE_CONCRETE);
    }

    private static void half(DiscArena.ChunkBlocks room, int turn, Material color) {
        room.fill(turn, -HALF_WIDTH, GROUND, -WALL_Z, HALF_WIDTH, CEILING - 1, -WALL_Z, LIGHT_GRAY_CONCRETE);
        room.fill(turn, -HALF_WIDTH, GROUND, -WALL_Z, HALF_WIDTH, GROUND + 1, -WALL_Z, GRAY_CONCRETE);
        room.fill(turn, -5, GROUND + 2, -WALL_Z, 5, GROUND + 4, -WALL_Z, color);
        room.fill(turn, -3, GROUND + 3, -WALL_Z, 3, GROUND + 3, -WALL_Z, SEA_LANTERN);
        room.fill(turn, -2, FLOOR, -13, 2, FLOOR, -11, color);

        room.fill(turn, -3, GROUND, -8, 2, GROUND + 1, -8, GRAY_CONCRETE);
        room.fill(turn, -3, GROUND + 1, -8, -3, GROUND + 1, -8, color);
        room.fill(turn, 2, GROUND + 1, -8, 2, GROUND + 1, -8, color);
        room.fill(turn, -6, GROUND, -4, -4, GROUND + 2, -2, BLACK_CONCRETE);
        room.fill(turn, -6, GROUND + 3, -4, -4, GROUND + 3, -2, LIGHT_GRAY_CONCRETE);
        room.fill(turn, -9, GROUND, 1, -9, GROUND + 2, 4, GLASS);
        room.fill(turn, -9, GROUND, 0, -9, GROUND + 2, 0, GRAY_CONCRETE);
        room.fill(turn, -9, GROUND, 5, -9, GROUND + 2, 5, GRAY_CONCRETE);
    }
}
