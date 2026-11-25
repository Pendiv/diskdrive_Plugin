package local.disc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class ItemBoxes implements Listener {
    private static final double DISPLAY_HEIGHT = 1.10;
    private static final double HITBOX_BASE = 0.70;
    private static final float HITBOX_HEIGHT = 0.80F;
    private static final float HITBOX_WIDTH = 0.90F;
    private static final int ROTATION_INTERVAL = 4;
    private static final int ROTATION_STEPS = 30;

    private final DiscPlugin plugin;
    private final DiscItem discItem;
    private final Map<UUID, Box> boxes = new HashMap<>();
    private final Map<ChunkPosition, Integer> chunkUsage = new HashMap<>();
    private long placementRevision;
    private int ticks;
    private int angleStep;

    ItemBoxes(DiscPlugin plugin, DiscItem discItem) {
        this.plugin = plugin;
        this.discItem = discItem;
    }

    UUID place(Location ground, Function<Player, List<ItemStack>> rewards) {
        return place(ground, rewards, player -> true, null);
    }

    UUID place(Location ground, Function<Player, List<ItemStack>> rewards, Predicate<Player> canOpen, Component label) {
        ground.checkFinite();
        World world = Objects.requireNonNull(ground.getWorld(), "Item boxes require a world");
        Objects.requireNonNull(rewards, "Item boxes require a reward provider");
        Objects.requireNonNull(canOpen, "Item boxes require an access predicate");
        if (ground.getY() < world.getMinHeight() || ground.getY() + HITBOX_BASE + HITBOX_HEIGHT >= world.getMaxHeight()
                || Math.abs(ground.getX()) >= 30_000_000 || Math.abs(ground.getZ()) >= 30_000_000) {
            throw new IllegalArgumentException("Item box position is outside the world");
        }
        Location base = new Location(world, ground.getX(), ground.getY(), ground.getZ());
        ChunkPosition chunk = new ChunkPosition(world, base.getBlockX() >> 4, base.getBlockZ() >> 4);
        retain(chunk);

        ItemDisplay display = null;
        Interaction hitbox = null;
        try {
            display = world.spawn(base.clone().add(0, DISPLAY_HEIGHT, 0), ItemDisplay.class, entity -> {
                entity.setItemStack(new ItemStack(Material.CHEST));
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setGravity(false);
                entity.setPersistent(false);
                entity.setInterpolationDuration(ROTATION_INTERVAL);
                entity.setTransformation(pose(angleStep));
                if (label != null) {
                    entity.customName(label);
                    entity.setCustomNameVisible(true);
                }
            });
            hitbox = world.spawn(base.clone().add(0, HITBOX_BASE, 0), Interaction.class, entity -> {
                entity.setInteractionHeight(HITBOX_HEIGHT);
                entity.setInteractionWidth(HITBOX_WIDTH);
                entity.setPersistent(false);
            });
            if (!display.isValid() || !hitbox.isValid()) {
                throw new IllegalStateException("Item box entity spawn was cancelled");
            }
            UUID id = hitbox.getUniqueId();
            boxes.put(id, new Box(display, hitbox, base, chunk, rewards, canOpen, ++placementRevision));
            return id;
        } catch (RuntimeException error) {
            if (display != null) {
                display.remove();
            }
            if (hitbox != null) {
                hitbox.remove();
            }
            release(chunk);
            throw error;
        }
    }

    boolean contains(Entity entity) {
        return entity instanceof Interaction && boxes.containsKey(entity.getUniqueId());
    }

    boolean hasBoxAt(Location ground) {
        return boxes.values().stream().anyMatch(box -> box.ground().equals(ground));
    }

    long placementRevision() {
        return placementRevision;
    }

    boolean isPlaced(UUID id) {
        return boxes.containsKey(id);
    }

    boolean canOpen(Entity entity, long revision, Player player) {
        Box box = entity instanceof Interaction ? boxes.get(entity.getUniqueId()) : null;
        return box != null && box.revision() <= revision && box.canOpen().test(player);
    }

    boolean open(Entity entity, Player player) {
        UUID id = entity.getUniqueId();
        Box box = boxes.get(id);
        if (box == null || !box.canOpen().test(player) || !boxes.remove(id, box)) {
            return false;
        }
        Location location = box.display().getLocation();
        destroy(box);
        location.getWorld().playSound(location, Sound.BLOCK_CHEST_OPEN, 0.8F, 1.25F);
        try {
            ItemStack[] rewards = box.rewards().apply(player).stream()
                    .filter(item -> !item.isEmpty()).map(ItemStack::clone).toArray(ItemStack[]::new);
            World world = location.getWorld();
            if (rewards.length == 0 && DiscArena.isArena(world) && !DiscArena.isDuel(world)) {
                rewards = MatchLoot.rollOuterReward(discItem).toArray(ItemStack[]::new);
            }
            if (plugin.receivesBoxFragment(player)) {
                rewards = Arrays.copyOf(rewards, rewards.length + 1);
                rewards[rewards.length - 1] = discItem.create(DiscType.FRAGMENT);
            }
            if (rewards.length != 0) {
                player.getInventory().addItem(rewards).values().forEach(item -> {
                    Location drop = player.getLocation();
                    drop.getWorld().dropItem(drop, item, entityItem -> entityItem.setOwner(player.getUniqueId()));
                });
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(
                    Level.WARNING, "Could not grant rewards for item box " + entity.getUniqueId(), error);
        }
        return true;
    }

    boolean open(UUID id, Player player) {
        Box box = boxes.get(id);
        return box != null && open(box.hitbox(), player);
    }

    void openAlong(Location start, Vector direction, double distance, Player player) {
        if (boxes.isEmpty()) {
            return;
        }
        long revision = placementRevision;
        RayTraceResult hit;
        while ((hit = start.getWorld().rayTraceEntities(start, direction, distance,
                entity -> canOpen(entity, revision, player))) != null) {
            Entity entity = hit.getHitEntity();
            if (entity == null || !open(entity, player)) {
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && contains(event.getEntity())) {
            event.setCancelled(true);
            open(event.getEntity(), player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        for (Box box : List.copyOf(boxes.values())) {
            if (box.chunk().world() == event.getWorld()) {
                remove(box.hitbox().getUniqueId());
            }
        }
    }

    void tick() {
        if (++ticks % ROTATION_INTERVAL != 0 || boxes.isEmpty()) {
            return;
        }
        angleStep = (angleStep + 1) % ROTATION_STEPS;
        for (Box box : List.copyOf(boxes.values())) {
            if (!box.hitbox().isValid() || !box.display().isValid()) {
                remove(box.hitbox().getUniqueId());
            } else {
                box.display().setInterpolationDelay(0);
                box.display().setTransformation(pose(angleStep));
            }
        }
    }

    void clear() {
        for (UUID id : List.copyOf(boxes.keySet())) {
            remove(id);
        }
    }

    boolean remove(UUID id) {
        Box box = boxes.remove(id);
        if (box == null) {
            return false;
        }
        destroy(box);
        return true;
    }

    private void destroy(Box box) {
        box.hitbox().remove();
        box.display().remove();
        release(box.chunk());
    }

    void retainChunk(World world, int x, int z) {
        retain(new ChunkPosition(world, x, z));
    }

    void releaseChunk(World world, int x, int z) {
        release(new ChunkPosition(world, x, z));
    }

    private void retain(ChunkPosition chunk) {
        if (!chunkUsage.containsKey(chunk)) {
            chunk.world().addPluginChunkTicket(chunk.x(), chunk.z(), plugin);
        }
        chunkUsage.merge(chunk, 1, Integer::sum);
    }

    private void release(ChunkPosition chunk) {
        if (chunkUsage.computeIfPresent(chunk, (key, count) -> count == 1 ? null : count - 1) == null) {
            chunk.world().removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
        }
    }

    private static Transformation pose(int step) {
        float angle = (float) (step * (2 * Math.PI / ROTATION_STEPS));
        return new Transformation(new Vector3f(), new Quaternionf().rotationY(angle),
                new Vector3f(0.68F), new Quaternionf());
    }

    private record ChunkPosition(World world, int x, int z) { }

    private record Box(ItemDisplay display, Interaction hitbox, Location ground, ChunkPosition chunk,
                       Function<Player, List<ItemStack>> rewards, Predicate<Player> canOpen, long revision) { }
}
