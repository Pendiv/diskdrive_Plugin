package local.disc;

import static org.bukkit.persistence.PersistentDataType.BOOLEAN;
import static org.bukkit.persistence.PersistentDataType.BYTE_ARRAY;
import static org.bukkit.persistence.PersistentDataType.DOUBLE;
import static org.bukkit.persistence.PersistentDataType.FLOAT;
import static org.bukkit.persistence.PersistentDataType.INTEGER;
import static org.bukkit.persistence.PersistentDataType.STRING;
import static org.bukkit.persistence.PersistentDataType.TAG_CONTAINER;
import static org.bukkit.persistence.PersistentDataType.TAG_CONTAINER_ARRAY;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

final class MatchPlayerState {
    private static final NamespacedKey PENDING = key("match_restore");
    private final PersistentDataContainer state;

    private MatchPlayerState(PersistentDataContainer state) {
        this.state = state;
    }

    static MatchPlayerState capture(Player player) {
        player.closeInventory();
        PersistentDataContainer state = player.getPersistentDataContainer()
                .getAdapterContext()
                .newPersistentDataContainer();
        PlayerInventory inventory = player.getInventory();
        state.set(key("items"), BYTE_ARRAY, ItemStack.serializeItemsAsBytes(inventory.getContents()));
        state.set(key("selected_slot"), INTEGER, inventory.getHeldItemSlot());
        state.set(key("mode"), STRING, player.getGameMode().name());
        state.set(key("allow_flight"), BOOLEAN, player.getAllowFlight());
        state.set(key("flying"), BOOLEAN, player.isFlying());

        Location location = player.getLocation();
        state.set(key("world_id"), STRING, location.getWorld().getUID().toString());
        state.set(key("world_name"), STRING, location.getWorld().getName());
        state.set(key("x"), DOUBLE, location.getX());
        state.set(key("y"), DOUBLE, location.getY());
        state.set(key("z"), DOUBLE, location.getZ());
        state.set(key("yaw"), FLOAT, location.getYaw());
        state.set(key("pitch"), FLOAT, location.getPitch());

        state.set(key("health"), DOUBLE, player.getHealth());
        state.set(key("absorption"), DOUBLE, player.getAbsorptionAmount());
        state.set(key("food"), INTEGER, player.getFoodLevel());
        state.set(key("saturation"), FLOAT, player.getSaturation());
        state.set(key("exhaustion"), FLOAT, player.getExhaustion());
        state.set(key("exp_progress"), FLOAT, player.getExp());
        state.set(key("exp_level"), INTEGER, player.getLevel());
        state.set(key("exp_total"), INTEGER, player.getTotalExperience());
        state.set(key("exp_cooldown"), INTEGER, player.getExpCooldown());
        state.set(key("fire_ticks"), INTEGER, player.getFireTicks());
        state.set(key("fall_distance"), FLOAT, player.getFallDistance());
        state.set(key("no_damage_ticks"), INTEGER, player.getNoDamageTicks());
        state.set(key("last_damage"), DOUBLE, player.getLastDamage());
        state.set(key("warden_cooldown"), INTEGER, player.getCooldown(DiscItem.WARDEN_COOLDOWN_GROUP));
        state.set(key("return_cooldown"), INTEGER, player.getCooldown(DiscPlugin.RETURN_COOLDOWN_GROUP));
        state.set(key("fragment_cooldown"), INTEGER, player.getCooldown(DiscPlugin.FRAGMENT_COOLDOWN_GROUP));

        PersistentDataContainer[] effects = player.getActivePotionEffects().stream()
                .map(effect -> encodeEffect(state, effect))
                .toArray(PersistentDataContainer[]::new);
        state.set(key("effects"), TAG_CONTAINER_ARRAY, effects);
        return new MatchPlayerState(state);
    }

    void save(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data.has(PENDING)) {
            throw new IllegalStateException("The player already has a pending match restore.");
        }
        data.set(PENDING, TAG_CONTAINER, state);
        player.saveData();
    }

    static boolean restorePending(Player player) {
        return restorePendingTo(player, null);
    }

    static boolean restorePending(Player player, Location destination) {
        return restorePendingTo(player, Objects.requireNonNull(destination, "destination"));
    }

    private static boolean restorePendingTo(Player player, Location destination) {
        PersistentDataContainer pending = player.getPersistentDataContainer().get(PENDING, TAG_CONTAINER);
        return pending == null || new MatchPlayerState(pending).restoreTo(player, destination);
    }

    static boolean hasPending(Player player) {
        return player.getPersistentDataContainer().has(PENDING);
    }

    boolean restore(Player player) {
        return restoreTo(player, null);
    }

    boolean restore(Player player, Location destination) {
        return restoreTo(player, Objects.requireNonNull(destination, "destination"));
    }

    private boolean restoreTo(Player player, Location destinationOverride) {
        if (!player.isOnline() || player.isDead()) {
            return false;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(read(state, "items", BYTE_ARRAY));
        GameMode mode = GameMode.valueOf(read(state, "mode", STRING));
        Location destination = destinationOverride == null ? returnLocation(player) : destinationOverride;
        int slot = read(state, "selected_slot", INTEGER);
        List<PotionEffect> effects = Arrays.stream(read(state, "effects", TAG_CONTAINER_ARRAY))
                .map(MatchPlayerState::decodeEffect)
                .filter(Objects::nonNull)
                .toList();

        player.closeInventory();
        if (!destination.equals(player.getLocation()) && !player.teleport(destination, TeleportCause.PLUGIN)) {
            return false;
        }
        player.setGameMode(mode);
        if (player.getGameMode() != mode) {
            return false;
        }
        player.setAllowFlight(read(state, "allow_flight", BOOLEAN));
        player.setFlying(player.getAllowFlight() && read(state, "flying", BOOLEAN));
        inventory.setContents(items);
        inventory.setHeldItemSlot(slot);
        player.clearActivePotionEffects();
        player.addPotionEffects(effects);

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double healthLimit = maxHealth == null ? 20 : maxHealth.getValue();
        player.setHealth(Math.clamp(read(state, "health", DOUBLE), 0.01, healthLimit));
        player.setAbsorptionAmount(read(state, "absorption", DOUBLE));
        player.setFoodLevel(read(state, "food", INTEGER));
        player.setSaturation(read(state, "saturation", FLOAT));
        player.setExhaustion(read(state, "exhaustion", FLOAT));
        player.setLevel(read(state, "exp_level", INTEGER));
        player.setExp(read(state, "exp_progress", FLOAT));
        player.setTotalExperience(read(state, "exp_total", INTEGER));
        player.setExpCooldown(read(state, "exp_cooldown", INTEGER));
        player.setFireTicks(read(state, "fire_ticks", INTEGER));
        player.setFallDistance(read(state, "fall_distance", FLOAT));
        player.setLastDamage(read(state, "last_damage", DOUBLE));
        player.setNoDamageTicks(read(state, "no_damage_ticks", INTEGER));
        player.setCooldown(DiscItem.WARDEN_COOLDOWN_GROUP, read(state, "warden_cooldown", INTEGER));
        player.setCooldown(DiscPlugin.RETURN_COOLDOWN_GROUP, state.getOrDefault(key("return_cooldown"), INTEGER, 0));
        player.setCooldown(DiscPlugin.FRAGMENT_COOLDOWN_GROUP,
                state.getOrDefault(key("fragment_cooldown"), INTEGER, 0));

        player.getPersistentDataContainer().remove(PENDING);
        player.saveData();
        return true;
    }

    private Location returnLocation(Player player) {
        Server server = player.getServer();
        World world = server.getWorld(UUID.fromString(read(state, "world_id", STRING)));
        if (world == null) {
            world = server.getWorld(read(state, "world_name", STRING));
        }
        if (world == null) {
            return server.getWorlds().getFirst().getSpawnLocation();
        }
        return new Location(world, read(state, "x", DOUBLE), read(state, "y", DOUBLE), read(state, "z", DOUBLE),
                read(state, "yaw", FLOAT), read(state, "pitch", FLOAT));
    }

    private static PersistentDataContainer encodeEffect(PersistentDataContainer state, PotionEffect effect) {
        PersistentDataContainer encoded = state.getAdapterContext().newPersistentDataContainer();
        encoded.set(key("type"), STRING, effect.getType().getKey().toString());
        encoded.set(key("duration"), INTEGER, effect.getDuration());
        encoded.set(key("amplifier"), INTEGER, effect.getAmplifier());
        encoded.set(key("ambient"), BOOLEAN, effect.isAmbient());
        encoded.set(key("particles"), BOOLEAN, effect.hasParticles());
        encoded.set(key("icon"), BOOLEAN, effect.hasIcon());
        PotionEffect hidden = effect.getHiddenPotionEffect();
        if (hidden != null) {
            encoded.set(key("hidden"), TAG_CONTAINER, encodeEffect(state, hidden));
        }
        return encoded;
    }

    private static PotionEffect decodeEffect(PersistentDataContainer state) {
        NamespacedKey typeKey = NamespacedKey.fromString(read(state, "type", STRING));
        if (typeKey == null) {
            return null;
        }
        PotionEffectType type = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(typeKey);
        if (type == null) {
            return null;
        }
        PersistentDataContainer hidden = state.get(key("hidden"), TAG_CONTAINER);
        return new PotionEffect(type, read(state, "duration", INTEGER), read(state, "amplifier", INTEGER),
                read(state, "ambient", BOOLEAN), read(state, "particles", BOOLEAN), read(state, "icon", BOOLEAN),
                hidden == null ? null : decodeEffect(hidden));
    }

    private static <P, C> C read(PersistentDataContainer state, String name, PersistentDataType<P, C> type) {
        return Objects.requireNonNull(state.get(key(name), type), "Missing match restore field: " + name);
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey("disc", name);
    }
}
