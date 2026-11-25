package local.disc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class MatchLoot {
    private static final int OUTER_DELAY = 200;
    private static final int OUTER_REFILL = 700;
    private static final int CENTER_DELAY = 900;
    private static final int RETRY_DELAY = 40;
    private static final List<DiscType> OUTER_DISCS = List.of(DiscType.LEVITATION, DiscType.GLOWING,
            DiscType.DARKNESS, DiscType.MULTI, DiscType.SWAP, DiscType.HOMING, DiscType.SNIPE, DiscType.ENDER,
            DiscType.LASER, DiscType.FRAGMENT, DiscType.MEGA);

    private final ItemBoxes boxes;
    private final MatchBoxLayout layout;
    private final DiscItem discItem;
    private final Predicate<Player> canOpen;
    private List<BoxSlot> roundSlots = List.of();
    private long currentTick;

    private World lobbyWorld;
    private UUID outerPreview;
    private UUID centerPreview;

    MatchLoot(ItemBoxes boxes, MatchBoxLayout layout, DiscItem discItem, Predicate<Player> canOpen) {
        this.boxes = boxes;
        this.layout = layout;
        this.discItem = discItem;
        this.canOpen = canOpen;
    }

    void begin(World world, long tick) {
        end();
        currentTick = tick;
        List<MatchBoxLayout.Definition> definitions = layout.definitions(world);
        List<MatchBoxLayout.Definition> outer = new ArrayList<>();
        List<MatchBoxLayout.Definition> centers = new ArrayList<>();
        for (MatchBoxLayout.Definition definition : definitions) {
            (definition.kind() == MatchBoxLayout.Kind.OUTER ? outer : centers).add(definition);
        }
        Collections.shuffle(outer);
        Collections.shuffle(centers);

        List<BoxSlot> slots = new ArrayList<>();
        for (int offset = 0; offset < outer.size();) {
            List<Supplier<List<ItemStack>>> rewards = outerRewards(discItem);
            for (Supplier<List<ItemStack>> reward : rewards) {
                if (offset == outer.size()) {
                    break;
                }
                slots.add(new BoxSlot(outer.get(offset++).location(world), reward, true, tick + OUTER_DELAY));
            }
        }
        if (!centers.isEmpty()) {
            slots.add(new BoxSlot(centers.getFirst().location(world), discReward(discItem, DiscType.WARDEN), false,
                    tick + CENTER_DELAY));
        }
        roundSlots = slots;
    }

    static List<ItemStack> rollOuterReward(DiscItem discItem) {
        List<Supplier<List<ItemStack>>> rewards = outerRewards(discItem);
        return rewards.get(ThreadLocalRandom.current().nextInt(rewards.size())).get();
    }

    private static List<Supplier<List<ItemStack>>> outerRewards(DiscItem discItem) {
        List<DiscType> choices = new ArrayList<>(OUTER_DISCS);
        Collections.shuffle(choices);
        return List.of(MatchLoot::basicSupplies,
                discReward(discItem, DiscType.MAGMA), discReward(discItem, choices.get(0)),
                discReward(discItem, choices.get(1)));
    }

    private static List<ItemStack> basicSupplies() {
        return List.of(DiscShields.create(), new ItemStack(Material.BREAD, 2));
    }

    private static Supplier<List<ItemStack>> discReward(DiscItem discItem, DiscType type) {
        int count = type == DiscType.FRAGMENT ? 3 : 1;
        return () -> List.of(discItem.create(type).asQuantity(count));
    }

    void tick(long tick) {
        currentTick = tick;
        List<BoxSlot> active = roundSlots;
        for (BoxSlot slot : active) {
            if (active != roundSlots) {
                return;
            }
            if (slot.id != null) {
                if (boxes.isPlaced(slot.id)) {
                    continue;
                }
                slot.id = null;
                slot.readyAt = tick + RETRY_DELAY;
            }
            if (tick >= slot.readyAt) {
                spawn(active, slot, tick);
            }
        }
    }

    private void spawn(List<BoxSlot> active, BoxSlot slot, long tick) {
        try {
            UUID placed = boxes.place(slot.location, player -> open(active, slot),
                    player -> active == roundSlots && canOpen.test(player), null);
            if (active == roundSlots) {
                slot.id = placed;
                slot.warned = false;
            } else {
                boxes.remove(placed);
            }
        } catch (RuntimeException error) {
            slot.readyAt = tick + RETRY_DELAY;
            if (!slot.warned) {
                slot.warned = true;
                Bukkit.getLogger().log(
                        Level.WARNING, "Disc could not place a round item box at " + slot.location, error);
            }
        }
    }

    private List<ItemStack> open(List<BoxSlot> active, BoxSlot slot) {
        if (active != roundSlots) {
            return List.of();
        }
        slot.id = null;
        slot.readyAt = currentTick + RETRY_DELAY;
        List<ItemStack> rewards = slot.reward.get();
        slot.readyAt = slot.refills ? currentTick + OUTER_REFILL : Long.MAX_VALUE;
        return rewards;
    }

    void end() {
        List<BoxSlot> ending = roundSlots;
        roundSlots = List.of();
        for (BoxSlot slot : ending) {
            if (slot.id != null) {
                boxes.remove(slot.id);
            }
        }
    }

    Location nearestBox(Player player) {
        return nearestBox(player, location -> true);
    }

    Location nearestBox(Player player, Predicate<Location> eligible) {
        return roundSlots.stream()
                .filter(slot -> slot.id != null && boxes.isPlaced(slot.id)
                        && slot.location.getWorld() == player.getWorld())
                .filter(slot -> eligible.test(slot.location))
                .min(java.util.Comparator.comparingDouble(slot -> slot.location.distanceSquared(player.getLocation())))
                .map(slot -> slot.location.clone())
                .orElse(null);
    }

    void openNearbyBox(Player player) {
        Location eye = player.getEyeLocation();
        for (BoxSlot slot : roundSlots) {
            if (slot.id == null || slot.location.getWorld() != eye.getWorld()) {
                continue;
            }
            var ray = slot.location.clone().add(0, 0.7, 0).toVector().subtract(eye.toVector());
            double distance = ray.length();
            if (distance > 0.01 && distance < 2.7
                    && eye.getWorld().rayTraceBlocks(
                            eye, ray, distance, org.bukkit.FluidCollisionMode.NEVER, true) == null
                    && boxes.open(slot.id, player)) {
                player.swingMainHand();
                return;
            }
        }
    }

    void showLobby(World world) {
        if (lobbyWorld != world) {
            if (outerPreview != null) {
                boxes.remove(outerPreview);
            }
            if (centerPreview != null) {
                boxes.remove(centerPreview);
            }
            outerPreview = null;
            centerPreview = null;
            lobbyWorld = world;
        }
        outerPreview = preview(outerPreview, new Location(world, -2.5, 64, -4.5), "外周ボックス");
        centerPreview = preview(centerPreview, new Location(world, -0.5, 64, -4.5), "中央ボックス");
    }

    private UUID preview(UUID existing, Location location, String label) {
        if (existing != null && boxes.isPlaced(existing)) {
            return existing;
        }
        try {
            return boxes.place(location, player -> List.of(), player -> false, Component.text(label));
        } catch (RuntimeException error) {
            Bukkit.getLogger().log(Level.WARNING, "Disc could not place a lobby item box at " + location, error);
            return null;
        }
    }

    private static final class BoxSlot {
        private final Location location;
        private final Supplier<List<ItemStack>> reward;
        private final boolean refills;
        private long readyAt;
        private UUID id;
        private boolean warned;

        private BoxSlot(Location location, Supplier<List<ItemStack>> reward, boolean refills, long readyAt) {
            this.location = location;
            this.reward = reward;
            this.refills = refills;
            this.readyAt = readyAt;
        }
    }
}
