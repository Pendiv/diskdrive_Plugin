package local.disc;

import static org.bukkit.persistence.PersistentDataType.BYTE_ARRAY;
import static org.bukkit.persistence.PersistentDataType.INTEGER_ARRAY;
import static org.bukkit.persistence.PersistentDataType.STRING;
import static org.bukkit.persistence.PersistentDataType.TAG_CONTAINER_ARRAY;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.BoundingBox;

final class ArenaTrapRecovery {
    private static final int RESTORE_DELAY = 700;
    private static final int RETRY_DELAY = 20;
    private static final NamespacedKey JOURNAL = new NamespacedKey("disc", "arena_trap_recovery");
    private static final NamespacedKey GROUP = new NamespacedKey("disc", "traps");
    private static final NamespacedKey POSITION = new NamespacedKey("disc", "position");
    private static final NamespacedKey BLOCK_DATA = new NamespacedKey("disc", "block_data");
    private static final NamespacedKey PAYLOAD = new NamespacedKey("disc", "payload");

    private final BiPredicate<World, List<Trap>> restore;
    private final Logger logger;
    private final Map<World, List<Batch>> pending = new IdentityHashMap<>();
    private final Set<World> activeWorlds = Collections.newSetFromMap(new IdentityHashMap<>());
    private long tick;

    ArenaTrapRecovery(BiPredicate<World, List<Trap>> restore, Logger logger) {
        this.restore = restore;
        this.logger = logger;
    }

    void prepare(World world) {
        if (isTracked(world)) {
            return;
        }
        if (!reset(world)) {
            throw new IllegalStateException("Could not recover all pending arena traps in " + world.getName());
        }
    }

    void begin(World world) {
        if (!isTracked(world)) {
            prepare(world);
            activeWorlds.add(world);
        }
    }

    void beginContinuous(World world) {
        if (!isTracked(world)) {
            reset(world);
            activeWorlds.add(world);
        }
    }

    boolean isTracked(World world) {
        return activeWorlds.contains(world);
    }

    boolean reset(World world) {
        List<Batch> batches = pending.computeIfAbsent(world, this::readJournal);
        restoreReady(world, batches, true, false);
        return batches.isEmpty();
    }

    void remember(World world, List<Trap> traps) {
        if (!isTracked(world) || traps.isEmpty()) {
            return;
        }

        List<Batch> batches = pending.computeIfAbsent(world, this::readJournal);
        Set<Cell> recorded = new HashSet<>();
        for (Batch batch : batches) {
            for (Trap trap : batch.traps) {
                recorded.add(new Cell(trap));
            }
        }
        List<Trap> firstSeen = traps.stream().filter(trap -> recorded.add(new Cell(trap))).toList();
        if (firstSeen.isEmpty()) {
            return;
        }

        Batch batch = new Batch(firstSeen, tick + RESTORE_DELAY);
        List<Batch> updated = new ArrayList<>(batches);
        updated.add(batch);
        writeJournal(world, updated);
        batches.add(batch);
    }

    void tick() {
        tick++;
        activeWorlds.removeIf(world -> Bukkit.getWorld(world.getUID()) != world);
        for (World world : List.copyOf(pending.keySet())) {
            List<Batch> batches = pending.get(world);
            if (batches == null) {
                continue;
            }
            if (Bukkit.getWorld(world.getUID()) != world) {
                pending.remove(world);
                continue;
            }
            restoreReady(world, batches, false, false);
        }
    }

    void end(World world, boolean force) {
        activeWorlds.remove(world);
        List<Batch> batches = pending.get(world);
        if (batches != null && Bukkit.getWorld(world.getUID()) == world) {
            restoreReady(world, batches, true, force);
        }
    }

    private void restoreReady(World world, List<Batch> batches, boolean allDue, boolean force) {
        for (Batch batch : List.copyOf(batches)) {
            if (!allDue && tick < batch.readyAt) {
                continue;
            }
            if (!force && occupied(world, batch.traps)) {
                batch.readyAt = tick + RETRY_DELAY;
                continue;
            }
            try {
                if (!restore.test(world, batch.traps)) {
                    retry(world, batch, null);
                    continue;
                }
                List<Batch> remaining = new ArrayList<>(batches);
                remaining.remove(batch);
                writeJournal(world, remaining);
                batches.remove(batch);
            } catch (RuntimeException error) {
                retry(world, batch, error);
            }
        }
        if (batches.isEmpty()) {
            pending.remove(world, batches);
        }
    }

    private void retry(World world, Batch batch, RuntimeException error) {
        batch.readyAt = tick + RETRY_DELAY;
        if (!batch.warned) {
            batch.warned = true;
            logger.log(Level.WARNING, "Disc could not restore an arena trap group in " + world.getName(), error);
        }
    }

    private static boolean occupied(World world, List<Trap> traps) {
        for (Player player : world.getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            BoundingBox body = player.getBoundingBox();
            for (Trap trap : traps) {
                if (body.overlaps(new BoundingBox(trap.x(), trap.y(), trap.z(),
                        trap.x() + 1, trap.y() + 1, trap.z() + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Batch> readJournal(World world) {
        PersistentDataContainer[] groups = world.getPersistentDataContainer().get(JOURNAL, TAG_CONTAINER_ARRAY);
        List<Batch> batches = new ArrayList<>();
        if (groups == null) {
            return batches;
        }
        for (PersistentDataContainer group : groups) {
            PersistentDataContainer[] records = group.get(GROUP, TAG_CONTAINER_ARRAY);
            if (records == null || records.length == 0) {
                throw new IllegalStateException("Invalid arena trap recovery group");
            }
            List<Trap> traps = new ArrayList<>(records.length);
            for (PersistentDataContainer record : records) {
                int[] position = record.get(POSITION, INTEGER_ARRAY);
                String data = record.get(BLOCK_DATA, STRING);
                if (position == null || position.length != 3 || data == null) {
                    throw new IllegalStateException("Invalid arena trap recovery entry");
                }
                traps.add(new Trap(position[0], position[1], position[2], data, record.get(PAYLOAD, BYTE_ARRAY)));
            }
            batches.add(new Batch(List.copyOf(traps), tick));
        }
        return batches;
    }

    private static void writeJournal(World world, List<Batch> batches) {
        PersistentDataContainer data = world.getPersistentDataContainer();
        if (batches.isEmpty()) {
            data.remove(JOURNAL);
            return;
        }

        PersistentDataContainer[] groups = new PersistentDataContainer[batches.size()];
        for (int i = 0; i < batches.size(); i++) {
            PersistentDataContainer group = data.getAdapterContext().newPersistentDataContainer();
            List<Trap> traps = batches.get(i).traps;
            PersistentDataContainer[] records = new PersistentDataContainer[traps.size()];
            for (int j = 0; j < traps.size(); j++) {
                Trap trap = traps.get(j);
                PersistentDataContainer record = data.getAdapterContext().newPersistentDataContainer();
                record.set(POSITION, INTEGER_ARRAY, new int[] {trap.x(), trap.y(), trap.z()});
                record.set(BLOCK_DATA, STRING, trap.blockData());
                byte[] payload = trap.payload();
                if (payload != null) {
                    record.set(PAYLOAD, BYTE_ARRAY, payload);
                }
                records[j] = record;
            }
            group.set(GROUP, TAG_CONTAINER_ARRAY, records);
            groups[i] = group;
        }
        data.set(JOURNAL, TAG_CONTAINER_ARRAY, groups);
    }

    record Trap(int x, int y, int z, String blockData, byte[] payload) {
        Trap {
            Objects.requireNonNull(blockData, "blockData");
            payload = payload == null ? null : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload == null ? null : payload.clone();
        }
    }

    private record Cell(int x, int y, int z) {
        private Cell(Trap trap) {
            this(trap.x(), trap.y(), trap.z());
        }
    }

    private static final class Batch {
        private final List<Trap> traps;
        private long readyAt;
        private boolean warned;

        private Batch(List<Trap> traps, long readyAt) {
            this.traps = traps;
            this.readyAt = readyAt;
        }
    }
}
