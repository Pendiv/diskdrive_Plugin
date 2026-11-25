package local.disc;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class DashPads implements Listener {
    private static final NamespacedKey MARKER = new NamespacedKey("disc", "dash_pad");
    private static final double FLOOR_TOLERANCE = 0.035;
    private static final double LIFT = 0.28;
    private static final double DISPLAY_GAP = 0.0125;
    private static final float DISPLAY_THICKNESS = 0.015F;

    private final DiscPlugin plugin;
    private final Predicate<Player> canEdit;
    private final Map<UUID, Pad> byFrame = new HashMap<>();
    private final Map<Cell, Pad> byFloor = new HashMap<>();

    DashPads(DiscPlugin plugin, Predicate<Player> canEdit) {
        this.plugin = plugin;
        this.canEdit = canEdit;
    }

    void start() {
        for (World world : plugin.getServer().getWorlds()) {
            for (ItemFrame frame : world.getEntitiesByClass(ItemFrame.class)) {
                if (registered(frame)) {
                    refresh(frame);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameChange(PlayerItemFrameChangeEvent event) {
        ItemFrame frame = event.getItemFrame();
        if (!canEdit.test(event.getPlayer())) {
            if (registered(frame)) {
                event.setCancelled(true);
            }
            return;
        }
        if (frame.getFacing() == BlockFace.UP) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (frame.isValid() && !event.isCancelled()) {
                    boolean alreadyRegistered = registered(frame);
                    refresh(frame);
                    if (!alreadyRegistered && registered(frame)) {
                        event.getPlayer().sendMessage("ダッシュ床を登録しました。額縁を回すと方向が変わります。");
                    }
                }
            });
        }
    }

    @EventHandler
    public void onLoad(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof ItemFrame frame && registered(frame)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (frame.isValid()) {
                    refresh(frame);
                }
            });
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            Pad pad = untrack(frame.getUniqueId());
            if (pad != null && plugin.isEnabled()) {
                // Paper may still be updating the entity section when this event fires.
                plugin.getServer().getScheduler().runTask(plugin, pad.display()::remove);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStep(PlayerMoveEvent event) {
        if (event.hasChangedPosition()) {
            step(event.getPlayer(), event.getFrom(), event.getTo());
        }
    }

    void step(Player player, Location from, Location to) {
        if (byFloor.isEmpty() || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Cell cell = Cell.under(to);
        if (Math.abs(to.getY() - cell.y() - 1) > FLOOR_TOLERANCE
                || cell.equals(Cell.under(from))) {
            return;
        }
        Pad pad = byFloor.get(cell);
        if (pad != null && pad.frame().isValid() && pad.display().isValid()) {
            player.setVelocity(new Vector(
                    Math.sin(pad.angle()) * pad.speed(),
                    LIFT,
                    -Math.cos(pad.angle()) * pad.speed()));
        }
    }

    Location escapePad(Location from, Vector away, boolean urgent) {
        Location best = null;
        int radius = urgent ? 13 : 9;
        double distance = radius * radius;
        double alignment = urgent ? 0.15 : 0.45;
        for (Pad pad : byFloor.values()) {
            if (pad.frame().getWorld() != from.getWorld() || !pad.frame().isValid() || !pad.display().isValid()) {
                continue;
            }
            Location goal = pad.cell().world().getBlockAt(pad.cell().x(), pad.cell().y(), pad.cell().z())
                    .getLocation().add(0.5, 1, 0.5);
            Vector launch = new Vector(Math.sin(pad.angle()), 0, -Math.cos(pad.angle()));
            double candidate = from.distanceSquared(goal);
            if (candidate < distance && launch.dot(away) > alignment) {
                distance = candidate;
                best = goal;
            }
        }
        return best;
    }

    void updateVisibility() {
        for (Pad pad : List.copyOf(byFrame.values())) {
            ItemFrame frame = pad.frame();
            for (Player player : frame.getWorld().getPlayers()) {
                boolean editing = canEdit.test(player);
                if (editing && !player.canSee(frame)) {
                    player.showEntity(plugin, frame);
                } else if (!editing && player.canSee(frame)) {
                    player.hideEntity(plugin, frame);
                }
            }
        }
    }

    private void refresh(ItemFrame frame) {
        double speed = speed(frame.getItem().getType());
        UUID id = frame.getUniqueId();
        if (frame.getFacing() != BlockFace.UP || speed == 0) {
            if (registered(frame)) {
                frame.getPersistentDataContainer().remove(MARKER);
                remove(id);
                showFrame(frame);
            }
            return;
        }

        Block support = frame.getLocation().getBlock().getRelative(BlockFace.DOWN);
        Cell cell = new Cell(support.getWorld(), support.getX(), support.getY(), support.getZ());
        Pad previous = byFrame.get(id);
        if (previous != null && (!previous.cell().equals(cell) || !previous.display().isValid())) {
            remove(id);
            previous = null;
        }
        if (byFloor.containsKey(cell) && previous == null) {
            return;
        }

        // In an upward frame, the unrotated spear points northeast. The south-facing tile's top arrow points north.
        int steps = frame.getRotation().ordinal() + 1;
        double angle = steps * Math.PI / 4;
        BlockDisplay display = previous == null ? createDisplay(support) : previous.display();
        if (!display.isValid() || !frame.isValid()) {
            display.remove();
            return;
        }
        float width = steps % 2 == 0 ? 0.96F : 0.68F;
        Quaternionf rotation = new Quaternionf().rotationY((float) -angle);
        Vector3f offset = new Vector3f(-width / 2, 0, -width / 2).rotate(rotation);
        display.setTransformation(new Transformation(offset, rotation, new Vector3f(width, DISPLAY_THICKNESS, width),
                new Quaternionf()));

        Pad pad = new Pad(cell, frame, display, angle, speed);
        byFrame.put(id, pad);
        byFloor.put(cell, pad);
        frame.getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, (byte) 1);
        frame.setVisibleByDefault(false);
        updateVisibility();
    }

    private static BlockDisplay createDisplay(Block support) {
        Location center = support.getLocation().add(0.5, 1 + DISPLAY_GAP, 0.5);
        Directional tile = (Directional) Material.MAGENTA_GLAZED_TERRACOTTA.createBlockData();
        tile.setFacing(BlockFace.SOUTH);
        return support.getWorld().spawn(center, BlockDisplay.class, display -> {
            display.setBlock(tile);
            display.setPersistent(false);
            display.setGravity(false);
        });
    }

    private void showFrame(ItemFrame frame) {
        frame.setVisibleByDefault(true);
        for (Player player : frame.getWorld().getPlayers()) {
            player.showEntity(plugin, frame);
        }
    }

    private void remove(UUID id) {
        Pad pad = untrack(id);
        if (pad != null) {
            pad.display().remove();
        }
    }

    private Pad untrack(UUID id) {
        Pad pad = byFrame.remove(id);
        if (pad != null) {
            byFloor.remove(pad.cell(), pad);
        }
        return pad;
    }

    void close() {
        for (Pad pad : List.copyOf(byFrame.values())) {
            showFrame(pad.frame());
            remove(pad.frame().getUniqueId());
        }
    }

    private static boolean registered(ItemFrame frame) {
        return frame.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE);
    }

    private static double speed(Material material) {
        return switch (material) {
            case WOODEN_SPEAR -> 1.44;
            case STONE_SPEAR -> 1.6675;
            case COPPER_SPEAR -> 1.87;
            case IRON_SPEAR -> 2.0475;
            case GOLDEN_SPEAR -> 2.20;
            case DIAMOND_SPEAR -> 2.3275;
            case NETHERITE_SPEAR -> 2.43;
            default -> 0;
        };
    }

    private record Cell(World world, int x, int y, int z) {
        static Cell under(Location location) {
            return new Cell(
                    location.getWorld(), location.getBlockX(), Location.locToBlock(location.getY() - FLOOR_TOLERANCE),
                    location.getBlockZ());
        }
    }

    private record Pad(Cell cell, ItemFrame frame, BlockDisplay display, double angle, double speed) { }
}
