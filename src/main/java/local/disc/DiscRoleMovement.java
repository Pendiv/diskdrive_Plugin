package local.disc;

import com.destroystokyo.paper.MaterialTags;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

final class DiscRoleMovement implements Listener {
    private static final int TAP_WINDOW_TICKS = 7;
    private static final int COOLDOWN_TICKS = 30;
    private static final double FORWARD_SPEED = 0.95;
    private static final double UPWARD_SPEED = 0.55;
    private static final double BLINK_DISTANCE = 5;
    private static final double BLINK_STEP = 0.25;
    private static final double HIGH_JUMP_SPEED = 0.84;
    private static final double AIR_JUMP_SPEED = 0.76;
    private static final double JUMPER_INPUT_SPEED = 0.22;
    private static final int ICHOR_SPEED_TICKS = 12 * 20;
    private static final int ICHOR_COOLDOWN_TICKS = 18 * 20;
    private static final NamespacedKey JUMPER_JUMP = new NamespacedKey("disc", "jumper_jump");
    private static final AttributeModifier JUMPER_JUMP_BOOST =
            new AttributeModifier(JUMPER_JUMP, HIGH_JUMP_SPEED - 0.42, AttributeModifier.Operation.ADD_NUMBER);

    private final Function<Player, MatchRole> activeRole;
    private final Map<UUID, Gesture> gestures = new HashMap<>();
    private final Set<UUID> jumperBoosted = new HashSet<>();
    private final Set<UUID> airJumpUsed = new HashSet<>();
    private final Map<UUID, Integer> ichorReadyAt = new HashMap<>();
    private final Set<UUID> ichorSneaking = new HashSet<>();

    DiscRoleMovement(Function<Player, MatchRole> activeRole) {
        this.activeRole = activeRole;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        MatchRole role = activeRole.apply(player);
        if (role != MatchRole.SHIFTER && role != MatchRole.DEMENTOR) {
            gestures.remove(player.getUniqueId());
            if (role == MatchRole.ICHOR && !event.isCancelled()) {
                updateIchorSneak(player, event.isSneaking());
            }
            return;
        }
        if (event.isCancelled() || !event.isSneaking()) {
            return;
        }

        UUID id = player.getUniqueId();
        int now = Bukkit.getCurrentTick();
        Gesture previous = gestures.get(id);
        int elapsed = previous == null ? 0 : now - previous.tick();
        if (coolingDown(previous, now)) {
            return;
        }
        if (previous == null || previous.cooldown() || elapsed < 0 || elapsed > TAP_WINDOW_TICKS) {
            gestures.put(id, new Gesture(now, false));
            return;
        }

        gestures.remove(id);
        activate(player, role, now, player.getEyeLocation().getDirection(), false);
    }

    @EventHandler
    public void onJumpInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        Input input = event.getInput();
        if (activeRole.apply(player) != MatchRole.JUMPER || player.isOnGround()
                || !input.isJump() || player.getCurrentInput().isJump()) {
            return;
        }
        jumperJump(player, horizontalInput(input, player.getYaw()));
    }

    private static Vector horizontalInput(Input input, float yaw) {
        int forward = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
        int left = (input.isLeft() ? 1 : 0) - (input.isRight() ? 1 : 0);
        if (forward == 0 && left == 0) {
            return new Vector();
        }
        double angle = Math.toRadians(yaw);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        return new Vector(-forward * sin + left * cos, 0, forward * cos + left * sin).normalize();
    }

    private boolean jumperJump(Player player, Vector inputDirection) {
        if (player.isInsideVehicle() || player.isInWater() || player.isInLava()) {
            return false;
        }
        boolean grounded = player.isOnGround();
        UUID id = player.getUniqueId();
        if (grounded) {
            airJumpUsed.remove(id);
        } else if (!airJumpUsed.add(id)) {
            return false;
        }
        Vector velocity = player.getVelocity();
        if (inputDirection.lengthSquared() > 0) {
            double speed = Math.max(JUMPER_INPUT_SPEED, Math.hypot(velocity.getX(), velocity.getZ()));
            velocity.setX(inputDirection.getX() * speed).setZ(inputDirection.getZ() * speed);
        }
        player.setVelocity(velocity.setY(jumperSpeed(grounded)));
        return true;
    }

    static double jumperSpeed(boolean grounded) {
        return grounded ? HIGH_JUMP_SPEED : AIR_JUMP_SPEED;
    }

    void tick(Player player, MatchRole role) {
        UUID id = player.getUniqueId();
        if (role == MatchRole.JUMPER) {
            if (jumperBoosted.add(id)) {
                player.getAttribute(Attribute.JUMP_STRENGTH).addTransientModifier(JUMPER_JUMP_BOOST);
            }
            if (player.isOnGround()) {
                airJumpUsed.remove(id);
            }
        } else {
            clearJumper(player);
        }
        if (role == MatchRole.ICHOR) {
            updateIchorSneak(player, player.isSneaking());
        } else {
            clearIchor(player);
        }
    }

    private void clearJumper(Player player) {
        UUID id = player.getUniqueId();
        airJumpUsed.remove(id);
        if (jumperBoosted.remove(id)) {
            player.getAttribute(Attribute.JUMP_STRENGTH).removeModifier(JUMPER_JUMP);
        }
    }

    private void updateIchorSneak(Player player, boolean sneaking) {
        UUID id = player.getUniqueId();
        if (!sneaking) {
            ichorSneaking.remove(id);
            return;
        }
        int now = Bukkit.getCurrentTick();
        if (ichorSneaking.add(id) && now >= ichorReadyAt.getOrDefault(id, now)
                && player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ICHOR_SPEED_TICKS, 0))) {
            ichorReadyAt.put(id, now + ICHOR_COOLDOWN_TICKS);
        }
    }

    private void clearIchor(Player player) {
        UUID id = player.getUniqueId();
        ichorSneaking.remove(id);
        ichorReadyAt.remove(id);
    }

    boolean useForBot(Player player, Vector direction) {
        MatchRole role = activeRole.apply(player);
        if (role == MatchRole.JUMPER) {
            return jumperJump(player, new Vector());
        }
        int now = Bukkit.getCurrentTick();
        return !coolingDown(gestures.get(player.getUniqueId()), now)
                && activate(player, role, now, direction, true);
    }

    private static boolean coolingDown(Gesture previous, int now) {
        return previous != null && previous.cooldown() && now - previous.tick() >= 0
                && now - previous.tick() < COOLDOWN_TICKS;
    }

    private boolean activate(Player player, MatchRole role, int now, Vector direction, boolean bot) {
        boolean moved = switch (role) {
            case SHIFTER -> jump(player, direction);
            case DEMENTOR -> blink(player, direction, bot);
            default -> false;
        };
        if (moved) {
            gestures.put(player.getUniqueId(), new Gesture(now, true));
        }
        return moved;
    }

    private static boolean jump(Player player, Vector direction) {
        if (!player.isOnGround()) {
            return false;
        }
        Vector flat = direction.clone().setY(0);
        if (flat.lengthSquared() < 1e-6) {
            Location facing = player.getLocation();
            facing.setPitch(0);
            flat = facing.getDirection();
        }
        player.setVelocity(flat.normalize().multiply(FORWARD_SPEED).setY(UPWARD_SPEED));
        return true;
    }

    private static boolean blink(Player player, Vector direction, boolean bot) {
        Location origin = player.getLocation();
        Location destination = blinkDestination(player, origin, direction);
        if (destination == null || (bot && !hasSafeBotLanding(origin, destination))
                || !player.teleport(destination, TeleportCause.PLUGIN)) {
            return false;
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8F, 1.15F);
        return true;
    }

    private static boolean hasSafeBotLanding(Location origin, Location destination) {
        Location footing = BotPath.standingNear(destination);
        return footing != null && footing.getBlockY() == destination.getBlockY()
                && origin.distanceSquared(destination) >= 4;
    }

    private static Location blinkDestination(Player player, Location origin, Vector direction) {
        World world = origin.getWorld();
        var border = world.getWorldBorder();
        double height = player.getHeight();
        BoundingBox body = player.getBoundingBox();
        Location destination = null;
        for (double distance = BLINK_STEP; distance <= BLINK_DISTANCE; distance += BLINK_STEP) {
            Vector offset = direction.clone().multiply(distance);
            Location next = origin.clone().add(offset);
            if (next.getY() < world.getMinHeight() || next.getY() + height > world.getMaxHeight()
                    || !world.isChunkLoaded(next.getBlockX() >> 4, next.getBlockZ() >> 4)
                    || !border.isInside(next)) {
                break;
            }
            if (!player.collidesAt(next)) {
                destination = next;
            } else if (!hitsOnlyGlass(world, body.clone().shift(offset))) {
                break;
            }
        }
        return destination;
    }

    private static boolean hitsOnlyGlass(World world, BoundingBox body) {
        // Some shapes, such as fences, extend into the neighboring block.
        int minY = Math.max(world.getMinHeight(), Location.locToBlock(body.getMinY()) - 1);
        int maxY = Math.min(world.getMaxHeight() - 1, Location.locToBlock(body.getMaxY()) + 1);
        boolean hitGlass = false;
        for (int x = Location.locToBlock(body.getMinX()) - 1; x <= Location.locToBlock(body.getMaxX()) + 1; x++) {
            for (int z = Location.locToBlock(body.getMinZ()) - 1; z <= Location.locToBlock(body.getMaxZ()) + 1; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return false;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()
                            || !block.getCollisionShape().overlaps(body.clone().shift(-x, -y, -z))) {
                        continue;
                    }
                    if (!MaterialTags.GLASS.isTagged(block) && !MaterialTags.GLASS_PANES.isTagged(block)) {
                        return false;
                    }
                    hitGlass = true;
                }
            }
        }
        return hitGlass;
    }

    void clear(Player player) {
        gestures.remove(player.getUniqueId());
        clearJumper(player);
        clearIchor(player);
    }

    private record Gesture(int tick, boolean cooldown) { }
}
