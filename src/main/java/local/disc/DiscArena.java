package local.disc;

import static org.bukkit.Material.BLACK_CONCRETE;
import static org.bukkit.Material.CYAN_CONCRETE;
import static org.bukkit.Material.GLASS;
import static org.bukkit.Material.GRAY_CONCRETE;
import static org.bukkit.Material.LAVA;
import static org.bukkit.Material.LIGHT_GRAY_CONCRETE;
import static org.bukkit.Material.LIME_CONCRETE;
import static org.bukkit.Material.MAGENTA_CONCRETE;
import static org.bukkit.Material.ORANGE_CONCRETE;
import static org.bukkit.Material.POLISHED_ANDESITE;
import static org.bukkit.Material.SEA_LANTERN;
import static org.bukkit.Material.SMOOTH_STONE;
import static org.bukkit.Material.WATER;
import static org.bukkit.Material.WHITE_CONCRETE;

import io.papermc.paper.math.Position;
import java.util.Random;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

final class DiscArena {
    static final int EDGE = 39;
    private static final int FLOOR = 63;
    static final int GROUND = FLOOR + 1;
    private static final int SPAWN_DISTANCE = 34;
    private static final StartColor[] START_COLORS = {
            new StartColor(CYAN_CONCRETE, DyeColor.CYAN),
            new StartColor(ORANGE_CONCRETE, DyeColor.ORANGE),
            new StartColor(LIME_CONCRETE, DyeColor.LIME),
            new StartColor(MAGENTA_CONCRETE, DyeColor.MAGENTA)
    };
    private static final DyeColor[] HALL_ARMOR_COLORS = {
            DyeColor.CYAN, DyeColor.ORANGE, DyeColor.LIME, DyeColor.MAGENTA,
            DyeColor.RED, DyeColor.BLUE, DyeColor.YELLOW, DyeColor.PINK
    };

    private final Server server;
    private final Layout layout;
    private World configuredWorld;

    DiscArena(Server server) {
        this(server, Layout.OUTDOOR);
    }

    DiscArena(Server server, Layout layout) {
        this.server = server;
        this.layout = layout;
    }

    enum Layout {
        DUEL("duel", "1on1屋内コート", "1on1マッチ"),
        OUTDOOR("arena", "屋外アリーナ", "4人マッチ"),
        HALL("hall", "屋内ホール", "8人マッチ");

        private final NamespacedKey worldKey;
        private final String displayName;
        private final String matchName;

        Layout(String id, String displayName, String matchName) {
            this.worldKey = new NamespacedKey("disc", id);
            this.displayName = displayName;
            this.matchName = matchName;
        }

        String id() {
            return worldKey.getKey();
        }

        String displayName() {
            return displayName;
        }

        String matchName() {
            return matchName;
        }
    }

    Layout layout() {
        return layout;
    }

    int capacity() {
        return switch (layout) {
            case OUTDOOR -> START_COLORS.length;
            case HALL -> HALL_ARMOR_COLORS.length;
            case DUEL -> 2;
        };
    }

    static boolean isArena(World world) {
        return world != null && (Layout.OUTDOOR.worldKey.equals(world.getKey()) || isHall(world) || isDuel(world));
    }

    static boolean isHall(World world) {
        return world != null && Layout.HALL.worldKey.equals(world.getKey());
    }

    static boolean isDuel(World world) {
        return world != null && Layout.DUEL.worldKey.equals(world.getKey());
    }

    static int edge(World world) {
        if (isDuel(world)) {
            return DuelArenaGenerator.HALF_LENGTH + 1;
        }
        return isHall(world) ? IndoorArenaGenerator.EDGE : EDGE;
    }

    static int ground(World world) {
        return isHall(world) ? IndoorArenaGenerator.GROUND : GROUND;
    }

    World world() {
        World world = server.getWorld(layout.worldKey);
        if (world == null) {
            Location initial = switch (layout) {
                case OUTDOOR -> new Location(null, 0.5, GROUND, -8.5);
                case HALL -> IndoorArenaGenerator.spawn(null, 0);
                case DUEL -> DuelArenaGenerator.spawn(null, 0);
            };
            ChunkGenerator generator = switch (layout) {
                case OUTDOOR -> new ArenaGenerator();
                case HALL -> new IndoorArenaGenerator();
                case DUEL -> new DuelArenaGenerator();
            };
            WorldCreator creator = WorldCreator.ofKey(layout.worldKey)
                    .type(WorldType.FLAT)
                    .generator(generator)
                    .generateStructures(false)
                    .forcedSpawnPosition(Position.fine(initial.getX(), initial.getY(), initial.getZ()),
                            initial.getYaw(), initial.getPitch());
            world = server.createWorld(creator);
        }
        if (world == null || !layout.worldKey.equals(world.getKey()) || !expectedGenerator(world.getGenerator())) {
            throw new IllegalStateException("Could not load Disc's arena world " + layout.worldKey);
        }
        if (configuredWorld != world) {
            configure(world);
            configuredWorld = world;
        }
        return world;
    }

    private boolean expectedGenerator(ChunkGenerator generator) {
        return switch (layout) {
            case OUTDOOR -> generator instanceof ArenaGenerator;
            case HALL -> generator instanceof IndoorArenaGenerator;
            case DUEL -> generator instanceof DuelArenaGenerator;
        };
    }

    Location spawn(int slot) {
        checkSlot(slot);
        World world = world();
        if (layout == Layout.HALL) {
            return IndoorArenaGenerator.spawn(world, slot);
        }
        if (layout == Layout.DUEL) {
            return DuelArenaGenerator.spawn(world, slot);
        }
        BlockPoint point = rotate(0, -SPAWN_DISTANCE, slot);
        return new Location(world, point.x() + 0.5, GROUND, point.z() + 0.5, slot * 90, 0);
    }

    Color armorColor(int slot) {
        checkSlot(slot);
        DyeColor dye = layout == Layout.HALL ? HALL_ARMOR_COLORS[slot] : START_COLORS[slot].dye();
        return dye.getColor();
    }

    Location overlook() {
        World world = world();
        return switch (layout) {
            case OUTDOOR -> new Location(world, 0.5, GROUND + 25, 0.5, 0, 90);
            case HALL -> IndoorArenaGenerator.overlook(world);
            case DUEL -> DuelArenaGenerator.overlook(world);
        };
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= capacity()) {
            throw new IllegalArgumentException("Invalid Disc arena spawn slot: " + slot);
        }
    }

    private static void configure(World world) {
        if (world.getDifficulty() == Difficulty.PEACEFUL) {
            world.setDifficulty(Difficulty.NORMAL);
        }
        world.setGameRule(GameRules.PVP, true);
        world.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.PROJECTILES_CAN_BREAK_BLOCKS, false);
        world.setGameRule(GameRules.RESPAWN_RADIUS, 0);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);

        WorldBorder border = world.getWorldBorder();
        border.setCenter(0.5, 0.5);
        border.setSize(edge(world) * 2 + 1);
        border.setWarningDistance(0);
    }

    private static final class ArenaGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(WorldInfo world, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
            int blockX = chunkX << 4;
            int blockZ = chunkZ << 4;
            if (blockX > EDGE || blockX + 15 < -EDGE || blockZ > EDGE || blockZ + 15 < -EDGE) {
                return;
            }
            ChunkBlocks chunk = new ChunkBlocks(chunkData, blockX, blockZ);
            chunk.fill(-EDGE, FLOOR - 3, -EDGE, EDGE, FLOOR - 1, EDGE, GRAY_CONCRETE);
            chunk.fill(-EDGE, FLOOR, -EDGE, EDGE, FLOOR, EDGE, LIGHT_GRAY_CONCRETE);
            chunk.fill(-8, FLOOR, -8, 8, FLOOR, 8, SMOOTH_STONE);

            for (int turn = 0; turn < START_COLORS.length; turn++) {
                perimeter(chunk, turn);
                startBunker(chunk, turn);
                cover(chunk, turn);
                pools(chunk, turn);
            }
        }

        private static void perimeter(ChunkBlocks chunk, int turn) {
            Material color = START_COLORS[turn].block();
            chunk.fill(turn, -EDGE, GROUND, -EDGE, EDGE, GROUND + 6, -EDGE, GRAY_CONCRETE);
            chunk.fill(turn, -EDGE, GROUND + 7, -EDGE, EDGE, GROUND + 7, -EDGE, BLACK_CONCRETE);
            chunk.fill(turn, -35, GROUND + 4, -EDGE, -10, GROUND + 4, -EDGE, LIGHT_GRAY_CONCRETE);
            chunk.fill(turn, 10, GROUND + 4, -EDGE, 35, GROUND + 4, -EDGE, LIGHT_GRAY_CONCRETE);
            chunk.fill(turn, -5, GROUND + 4, -EDGE, 5, GROUND + 4, -EDGE, color);
        }

        private static void startBunker(ChunkBlocks chunk, int turn) {
            Material color = START_COLORS[turn].block();
            chunk.fill(turn, -5, FLOOR, -38, 5, FLOOR, -30, POLISHED_ANDESITE);
            chunk.fill(turn, -2, FLOOR, -36, 2, FLOOR, -33, color);
            chunk.fill(turn, -5, GROUND, -30, 5, GROUND + 4, -29, GRAY_CONCRETE);
            chunk.fill(turn, -6, GROUND, -38, -6, GROUND + 4, -35, GRAY_CONCRETE);
            chunk.fill(turn, 6, GROUND, -38, 6, GROUND + 4, -35, GRAY_CONCRETE);
            chunk.fill(turn, -6, GROUND + 5, -38, 6, GROUND + 5, -29, GRAY_CONCRETE);
            chunk.fill(turn, -2, GROUND + 5, -35, 2, GROUND + 5, -35, SEA_LANTERN);
            chunk.fill(turn, -5, GROUND + 3, -29, 5, GROUND + 3, -29, color);
        }

        private static void cover(ChunkBlocks chunk, int turn) {
            chunk.fill(turn, -30, GROUND, -30, -18, GROUND + 3, -28, GRAY_CONCRETE);
            chunk.fill(turn, -26, GROUND, -28, -24, GROUND + 3, -20, GRAY_CONCRETE);
            chunk.fill(turn, -31, GROUND, -20, -31, GROUND + 2, -17, GRAY_CONCRETE);
            chunk.fill(turn, 18, GROUND, -25, 24, GROUND + 2, -23, GRAY_CONCRETE);
            chunk.fill(turn, 21, GROUND, -29, 23, GROUND + 2, -25, GRAY_CONCRETE);
            chunk.fill(turn, -14, GROUND, -15, -11, GROUND + 2, -7, GRAY_CONCRETE);
            chunk.fill(turn, 12, GROUND, -12, 17, GROUND + 1, -10, GRAY_CONCRETE);
            chunk.fill(turn, -4, GROUND, -4, -1, GROUND + 2, -4, GLASS);
            chunk.fill(turn, -4, GROUND + 3, -4, -1, GROUND + 3, -4, WHITE_CONCRETE);
        }

        private static void pools(ChunkBlocks chunk, int turn) {
            chunk.fill(turn, 8, FLOOR, -22, 16, FLOOR, -16, CYAN_CONCRETE);
            chunk.fill(turn, 9, FLOOR, -21, 15, FLOOR, -17, WATER);
            chunk.fill(turn, -30, FLOOR, -15, -25, FLOOR, -11, ORANGE_CONCRETE);
            chunk.fill(turn, -29, FLOOR, -14, -26, FLOOR, -12, LAVA);
        }
    }

    record ChunkBlocks(ChunkGenerator.ChunkData data, int blockX, int blockZ) {
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
            BlockPoint a = rotate(x1, z1, turn);
            BlockPoint b = rotate(x2, z2, turn);
            fill(Math.min(a.x(), b.x()), y1, Math.min(a.z(), b.z()),
                    Math.max(a.x(), b.x()), y2, Math.max(a.z(), b.z()), material);
        }
    }

    private static BlockPoint rotate(int x, int z, int turn) {
        return switch (turn) {
            case 0 -> new BlockPoint(x, z);
            case 1 -> new BlockPoint(-z, x);
            case 2 -> new BlockPoint(-x, -z);
            case 3 -> new BlockPoint(z, -x);
            default -> throw new IllegalArgumentException("Invalid arena rotation: " + turn);
        };
    }

    private record BlockPoint(int x, int z) { }

    private record StartColor(Material block, DyeColor dye) { }
}
