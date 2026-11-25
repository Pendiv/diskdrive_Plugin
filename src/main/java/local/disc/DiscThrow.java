package local.disc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.pointer.Pointer;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

final class DiscThrow {
    static final Pointer<UUID> THROW_ID = Pointer.pointer(UUID.class, Key.key("disc", "throw_id"));
    static final Pointer<Vector> ATTACK_ORIGIN = Pointer.pointer(Vector.class, Key.key("disc", "attack_origin"));

    private static final long MAX_FLIGHT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final double SPREAD_ANGLE = Math.toRadians(12);

    private final Player owner;
    private final World world;
    private final int slot;
    private ItemStack item;
    private final DiscType type;
    private final UUID id = UUID.randomUUID();
    private final long launchTime = System.nanoTime();
    private final List<DiscFlight> flights = new ArrayList<>(3);

    DiscThrow(Player owner, EquipmentSlot hand, int slot, ItemStack item, DiscType type, ItemBoxes itemBoxes,
              DiscMechanisms mechanisms, MatchRole role) {
        this.owner = owner;
        this.world = owner.getWorld();
        this.slot = slot;
        this.item = item;
        this.type = type;

        Set<UUID> hitPlayers = new HashSet<>();
        Set<Block> struckRods = new HashSet<>();
        Vector aim = owner.getEyeLocation().getDirection();
        try {
            flights.add(new DiscFlight(
                    owner, hand, item, type, hitPlayers, aim, itemBoxes, mechanisms, struckRods, id, role));
            if (type == DiscType.MULTI) {
                Vector axis = new Vector(-aim.getZ(), 0, aim.getX());
                if (axis.lengthSquared() < 0.0001) {
                    axis = new Vector(1, 0, 0);
                }
                Vector spreadAxis = aim.clone().crossProduct(axis).normalize();
                flights.add(new DiscFlight(
                        owner, hand, item, type, hitPlayers,
                        aim.clone().rotateAroundAxis(spreadAxis, SPREAD_ANGLE),
                        itemBoxes, mechanisms, struckRods, id, role));
                flights.add(new DiscFlight(
                        owner, hand, item, type, hitPlayers,
                        aim.clone().rotateAroundAxis(spreadAxis, -SPREAD_ANGLE),
                        itemBoxes, mechanisms, struckRods, id, role));
            }
        } catch (RuntimeException exception) {
            removeDisplays();
            throw exception;
        }
    }

    Player owner() {
        return owner;
    }

    int slot() {
        return slot;
    }

    UUID id() {
        return id;
    }

    ItemStack item() {
        return item;
    }

    DiscType type() {
        return type;
    }

    void replaceReturnItem(ItemStack item) {
        this.item = item;
    }

    void removeDisplays() {
        for (DiscFlight flight : flights) {
            flight.removeDisplay();
        }
    }

    boolean tick() {
        if (!owner.isOnline() || owner.isDead() || owner.getWorld() != world
                || owner.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (System.nanoTime() - launchTime < MAX_FLIGHT_NANOS && tickFlights()) {
            return true;
        }
        if (type.returnsToOwner()) {
            world.playSound(owner.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.7F, 1.35F);
        }
        return false;
    }

    private boolean tickFlights() {
        var iterator = flights.iterator();
        while (iterator.hasNext()) {
            DiscFlight flight = iterator.next();
            if (flight.tick()) {
                continue;
            }
            if (flight.caught()) {
                return false;
            }
            flight.removeDisplay();
            iterator.remove();
        }
        return !flights.isEmpty() || type == DiscType.WARDEN;
    }
}
