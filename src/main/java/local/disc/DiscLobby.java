package local.disc;

import static org.bukkit.Material.BLACK_CONCRETE;
import static org.bukkit.Material.CYAN_CONCRETE;
import static org.bukkit.Material.GRAY_CONCRETE;
import static org.bukkit.Material.LIGHT_GRAY_CONCRETE;
import static org.bukkit.Material.LIME_CONCRETE;
import static org.bukkit.Material.ORANGE_CONCRETE;
import static org.bukkit.Material.POLISHED_ANDESITE;
import static org.bukkit.Material.SEA_LANTERN;
import static org.bukkit.Material.SPRUCE_SIGN;
import static org.bukkit.Material.SPRUCE_WALL_SIGN;
import static org.bukkit.Material.WHITE_CONCRETE;

import io.papermc.paper.math.Position;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.DyeColor;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class DiscLobby {
    private static final NamespacedKey WORLD_KEY = new NamespacedKey("disc", "lobby");
    private static final int EDGE = 15;
    private static final int FLOOR = 63;
    private static final int GROUND = FLOOR + 1;
    private static final double SPAWN_X = -5.5;
    private static final double SPAWN_Z = -9.5;
    private static final int SUPPLY_X = -6;
    private static final int SUPPLY_Z = -7;
    private static final int DUEL_X = -11;
    private static final int OUTDOOR_X = -9;
    private static final int HALL_X = -7;
    private static final int ROLE_X = -6;
    private static final int START_X = -3;
    private static final int WAIT_X = -2;
    private static final int LEAVE_X = 0;
    private static final int SIGN_Y = GROUND + 1;
    private static final int SIGN_Z = 8;
    private static final String DUMMY_TAG = "disc_test_dummy";
    private static final String SHIELD_TAG = "disc_test_shield";
    private static final String OPEN_TARGET = "disc_lobby_open_target";
    private static final String SHIELD_TARGET = "disc_lobby_shield_target";

    private final Server server;
    private World configuredWorld;
    private boolean preparing;

    DiscLobby(Server server) {
        this.server = server;
    }

    enum Action {
        DUEL, OUTDOOR, HALL, ROLE, START, WAIT, LEAVE, SUPPLY
    }

    static boolean isLobby(World world) {
        return world != null && WORLD_KEY.equals(world.getKey());
    }

    Action signAction(Block block) {
        if (block == null || !isLobby(block.getWorld())) {
            return null;
        }
        if (block.getType() == SPRUCE_SIGN && block.getX() == SUPPLY_X
                && block.getY() == GROUND && block.getZ() == SUPPLY_Z) {
            return Action.SUPPLY;
        }
        if (block.getType() != SPRUCE_WALL_SIGN || block.getY() != SIGN_Y || block.getZ() != SIGN_Z) {
            return null;
        }
        return switch (block.getX()) {
            case DUEL_X -> Action.DUEL;
            case OUTDOOR_X -> Action.OUTDOOR;
            case HALL_X -> Action.HALL;
            case ROLE_X -> Action.ROLE;
            case START_X -> Action.START;
            case WAIT_X -> Action.WAIT;
            case LEAVE_X -> Action.LEAVE;
            default -> null;
        };
    }

    Location spawn() {
        if (preparing) {
            throw new IllegalStateException("Disc lobby is still loading");
        }
        preparing = true;
        try {
            World world = server.getWorld(WORLD_KEY);
            if (world == null) {
                world = server.createWorld(WorldCreator.ofKey(WORLD_KEY)
                        .type(WorldType.FLAT)
                        .generator(new LobbyGenerator())
                        .generateStructures(false)
                        .forcedSpawnPosition(Position.fine(SPAWN_X, GROUND, SPAWN_Z), 0, 0));
            }
            if (!isLobby(world) || !(world.getGenerator() instanceof LobbyGenerator)) {
                throw new IllegalStateException("Could not load Disc's lobby world " + WORLD_KEY);
            }
            if (configuredWorld != world) {
                configure(world);
                LobbyExpansion.apply(world);
                placeSupplySign(world);
                for (DiscArena.Layout layout : DiscArena.Layout.values()) {
                    placeSign(world, matchSignX(layout), layout.matchName(), "空", "右クリックで参加");
                }
                placeSign(world, ROLE_X, "役職", "右クリック", "役職を選ぶ");
                placeSign(world, START_X, "この参加者で開始", "右クリック", "空き枠はBOT");
                placeSign(world, WAIT_X, "他の人を待つ", "右クリック", "満員で自動開始");
                placeSign(world, LEAVE_X, "待機をやめる", "右クリック", "待機列から退出");
                if (configuredWorld == null) {
                    server.getScheduler().runTaskTimer(JavaPlugin.getProvidingPlugin(DiscLobby.class),
                            this::restoreLobbyFood, 1L, 1L);
                }
                configuredWorld = world;
            }
            ensureTargets(world);
            return new Location(world, SPAWN_X, GROUND, SPAWN_Z, 0, 0);
        } finally {
            preparing = false;
        }
    }

    private void restoreLobbyFood() {
        for (Player player : configuredWorld.getPlayers()) {
            if (player.getFoodLevel() < 20) {
                player.setFoodLevel(20);
                player.setSaturation(5);
                player.setExhaustion(0);
            }
        }
    }

    void updateMatchSign(DiscArena.Layout layout, String status, int waiting, int capacity) {
        if (configuredWorld == null) {
            return;
        }
        Block block = configuredWorld.getBlockAt(matchSignX(layout), SIGN_Y, SIGN_Z);
        if (block.getType() == SPRUCE_WALL_SIGN) {
            writeSign(block, layout.matchName(), status, waiting + " / " + capacity + "人待機", "右クリックで参加");
        }
    }

    private static int matchSignX(DiscArena.Layout layout) {
        return switch (layout) {
            case DUEL -> DUEL_X;
            case OUTDOOR -> OUTDOOR_X;
            case HALL -> HALL_X;
        };
    }

    private static void configure(World world) {
        world.setGameRule(GameRules.PVP, false);
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
        border.setSize(LobbyExpansion.EDGE * 2 + 1);
        border.setWarningDistance(0);
    }

    private static void placeSupplySign(World world) {
        Block block = world.getBlockAt(SUPPLY_X, GROUND, SUPPLY_Z);
        if (!block.getType().isAir()) {
            return;
        }
        Rotatable data = (Rotatable) SPRUCE_SIGN.createBlockData();
        data.setRotation(BlockFace.NORTH);
        block.setBlockData(data, false);
        writeSign(block, "ディスク配布", "右クリック", "不足分を受け取る", "");
    }

    private static void placeSign(World world, int x, String title, String action, String detail) {
        Block block = world.getBlockAt(x, SIGN_Y, SIGN_Z);
        if (block.getType().isAir()) {
            Directional data = (Directional) SPRUCE_WALL_SIGN.createBlockData();
            data.setFacing(BlockFace.NORTH);
            block.setBlockData(data, false);
        } else if (block.getType() != SPRUCE_WALL_SIGN) {
            return;
        }
        writeSign(block, title, action, detail, "");
    }

    private static void writeSign(Block block, String... lines) {
        Sign sign = (Sign) block.getState();
        SignSide front = sign.getSide(Side.FRONT);
        boolean changed = front.getColor() != DyeColor.WHITE || !front.isGlowingText() || !sign.isWaxed();
        for (int i = 0; i < lines.length; i++) {
            Component line = Component.text(lines[i]);
            if (!front.line(i).equals(line)) {
                front.line(i, line);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        front.setColor(DyeColor.WHITE);
        front.setGlowingText(true);
        sign.setWaxed(true);
        sign.update(true, false);
    }

    private static void ensureTargets(World world) {
        Set<String> loadedTargets = new HashSet<>();
        boolean allEntitiesLoaded = true;
        for (int chunkX = -1; chunkX <= 0; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 0; chunkZ++) {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                allEntitiesLoaded &= chunk.isEntitiesLoaded();
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Mannequin && !entity.isDead()) {
                        loadedTargets.addAll(entity.getScoreboardTags());
                    }
                }
            }
        }
        ensureTarget(world, loadedTargets, allEntitiesLoaded, 7.5, OPEN_TARGET, "試投ダミー", false);
        ensureTarget(world, loadedTargets, allEntitiesLoaded, 11.5, SHIELD_TARGET, "盾ダミー", true);
    }

    private static void ensureTarget(World world, Set<String> loadedTargets, boolean allEntitiesLoaded,
                                     double x, String tag, String name, boolean shield) {
        var data = world.getPersistentDataContainer();
        NamespacedKey marker = new NamespacedKey("disc", tag);
        if (loadedTargets.contains(tag)) {
            data.set(marker, PersistentDataType.BOOLEAN, true);
            return;
        }
        // Full terrain can be available before its saved entity data on the first visit after restart.
        if (!allEntitiesLoaded && data.has(marker, PersistentDataType.BOOLEAN)) {
            return;
        }
        Mannequin target = world.spawn(new Location(world, x, GROUND, 8.5, 180, 0), Mannequin.class, entity -> {
            entity.addScoreboardTag(DUMMY_TAG);
            entity.addScoreboardTag(tag);
            if (shield) {
                entity.addScoreboardTag(SHIELD_TAG);
            }
            entity.setPersistent(true);
            entity.setCollidable(false);
            entity.customName(Component.text(name));
            entity.setCustomNameVisible(true);
        });
        if (target.isInWorld() && !target.isDead()) {
            data.set(marker, PersistentDataType.BOOLEAN, true);
        }
    }

    private static final class LobbyGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(WorldInfo world, Random random, int chunkX, int chunkZ, ChunkData data) {
            if (chunkX < -1 || chunkX > 0 || chunkZ < -1 || chunkZ > 0) {
                return;
            }
            ChunkBlocks chunk = new ChunkBlocks(data, chunkX << 4, chunkZ << 4);
            chunk.fill(-EDGE, FLOOR - 2, -EDGE, EDGE, FLOOR - 1, EDGE, GRAY_CONCRETE);
            chunk.fill(-EDGE, FLOOR, -EDGE, EDGE, FLOOR, EDGE, LIGHT_GRAY_CONCRETE);
            chunk.fill(-EDGE, GROUND, -EDGE, EDGE, GROUND + 5, -EDGE, GRAY_CONCRETE);
            chunk.fill(-EDGE, GROUND, EDGE, EDGE, GROUND + 5, EDGE, GRAY_CONCRETE);
            chunk.fill(-EDGE, GROUND, -EDGE + 1, -EDGE, GROUND + 5, EDGE - 1, GRAY_CONCRETE);
            chunk.fill(EDGE, GROUND, -EDGE + 1, EDGE, GROUND + 5, EDGE - 1, GRAY_CONCRETE);

            chunk.fill(-11, FLOOR, -12, 0, FLOOR, 7, POLISHED_ANDESITE);
            chunk.fill(-7, FLOOR, -11, -4, FLOOR, -8, WHITE_CONCRETE);
            chunk.fill(-12, GROUND, SIGN_Z + 1, 0, GROUND + 3, SIGN_Z + 1, GRAY_CONCRETE);
            chunk.fill(OUTDOOR_X - 2, SIGN_Y, SIGN_Z + 1, OUTDOOR_X + 2, SIGN_Y + 1, SIGN_Z + 1, LIME_CONCRETE);
            chunk.fill(START_X - 2, SIGN_Y, SIGN_Z + 1, START_X + 2, SIGN_Y + 1, SIGN_Z + 1, ORANGE_CONCRETE);
            chunk.fill(-12, GROUND + 4, SIGN_Z, 0, GROUND + 4, SIGN_Z + 1, BLACK_CONCRETE);
            chunk.fill(OUTDOOR_X, GROUND + 4, SIGN_Z, OUTDOOR_X, GROUND + 4, SIGN_Z, SEA_LANTERN);
            chunk.fill(START_X, GROUND + 4, SIGN_Z, START_X, GROUND + 4, SIGN_Z, SEA_LANTERN);

            chunk.fill(3, GROUND, -8, 3, GROUND + 4, EDGE, GRAY_CONCRETE);
            chunk.fill(3, GROUND + 5, -8, 3, GROUND + 5, EDGE, CYAN_CONCRETE);
            chunk.fill(4, FLOOR, -14, 14, FLOOR, 14, POLISHED_ANDESITE);
            chunk.fill(4, FLOOR, -8, 14, FLOOR, -8, CYAN_CONCRETE);
            chunk.fill(6, FLOOR, 7, 8, FLOOR, 9, LIME_CONCRETE);
            chunk.fill(10, FLOOR, 7, 12, FLOOR, 9, ORANGE_CONCRETE);
            chunk.fill(4, GROUND, 11, 14, GROUND + 4, 11, BLACK_CONCRETE);
        }
    }

    private record ChunkBlocks(ChunkGenerator.ChunkData data, int blockX, int blockZ) {
        void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
            int startX = Math.max(0, x1 - blockX);
            int startZ = Math.max(0, z1 - blockZ);
            int endX = Math.min(16, x2 - blockX + 1);
            int endZ = Math.min(16, z2 - blockZ + 1);
            if (startX < endX && startZ < endZ) {
                data.setRegion(startX, y1, startZ, endX, y2 + 1, endZ, material);
            }
        }
    }
}
