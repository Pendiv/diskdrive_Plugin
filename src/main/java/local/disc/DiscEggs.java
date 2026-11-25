package local.disc;

import static org.bukkit.persistence.PersistentDataType.INTEGER_ARRAY;
import static org.bukkit.persistence.PersistentDataType.STRING;

import java.util.Comparator;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.persistence.PersistentDataContainer;

final class DiscEggs {
    private static final NamespacedKey ORIGIN = new NamespacedKey("disc", "egg_origin");
    private static final NamespacedKey ID = new NamespacedKey("disc", "egg_id");
    private static final double NEARBY_RADIUS = 5;
    private static final int EGG_TELEPORT_RADIUS = 18;
    private static final int PLAYER_TELEPORT_RADIUS = 64;
    private static final int MAX_RETURN_CHOICES = 24;

    static void register(Block block, PersistentDataContainer state) {
        state.set(ORIGIN, INTEGER_ARRAY, new int[] {block.getX(), block.getY(), block.getZ()});
        state.set(ID, STRING, UUID.randomUUID().toString());
    }

    static UUID id(PersistentDataContainer state) {
        String value = state == null ? null : state.get(ID, STRING);
        return value == null ? null : UUID.fromString(value);
    }

    static Player nearbyPlayer(Block block) {
        Location center = block.getLocation().toCenterLocation();
        return block.getWorld().getNearbyPlayers(center, NEARBY_RADIUS).stream()
                .filter(player -> !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                        && player.getLocation().distanceSquared(center) <= NEARBY_RADIUS * NEARBY_RADIUS)
                .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(center))).orElse(null);
    }

    static Block move(Block source, PersistentDataContainer state, Player companion) {
        int[] origin = state.get(ORIGIN, INTEGER_ARRAY);
        if (origin == null || origin.length != 3) {
            return null;
        }
        Home home = new Home(origin[0], origin[1], origin[2]);
        World world = source.getWorld();
        boolean arena = DiscArena.isArena(world);
        DiscLanding search = new DiscLanding(
                source.getLocation().toCenterLocation(),
                arena ? DiscArena.ground(world) : source.getY(),
                arena ? 0 : 6);
        double distanceFromHome = Math.sqrt(home.distanceSquared(source));
        int returnChoices = Math.min(MAX_RETURN_CHOICES, 1 + (int) Math.ceil(distanceFromHome * 1.5));
        Block target = search.closestOf(EGG_TELEPORT_RADIUS, returnChoices, home::distanceSquared);
        if (target == null) {
            return null;
        }

        if (companion != null) {
            Block stand = search.random(0, PLAYER_TELEPORT_RADIUS, target);
            if (stand != null) {
                Location destination = stand.getLocation().add(0.5, 0, 0.5);
                destination.setYaw(companion.getYaw());
                destination.setPitch(companion.getPitch());
                if (companion.teleport(destination, TeleportCause.PLUGIN)) {
                    companion.setFallDistance(0);
                }
            }
        }
        if (source.getType() != Material.DRAGON_EGG || !DiscLanding.safe(target)) {
            return null;
        }
        target.setBlockData(source.getBlockData(), false);
        source.setType(Material.AIR, false);
        signal(source);
        signal(target);
        return target;
    }

    private static void signal(Block block) {
        Location center = block.getLocation().toCenterLocation();
        block.getWorld().spawnParticle(Particle.PORTAL, center, 18, 0.3, 0.35, 0.3, 0.2);
        block.getWorld().playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 0.65F, 1.2F);
    }

    private record Home(int x, int y, int z) {
        double distanceSquared(Block block) {
            double dx = block.getX() - x;
            double dy = block.getY() - y;
            double dz = block.getZ() - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
