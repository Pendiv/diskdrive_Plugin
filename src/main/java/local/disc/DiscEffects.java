package local.disc;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.pointer.Pointer;
import net.kyori.adventure.util.TriState;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

final class DiscEffects implements Listener {
    static final Pointer<DiscType> DISC_TYPE = Pointer.pointer(DiscType.class, Key.key("disc", "type"));
    static final Pointer<Vector> SWAP_DIRECTION = Pointer.pointer(Vector.class, Key.key("disc", "swap_direction"));

    private static final int BURN_DURATION = 80;
    private static final int BURN_INTERVAL = 20;
    private static final int MAX_BURN_DAMAGE = 3;
    private static final int ENDER_MIN_DISTANCE = 6;
    private static final int ENDER_MAX_DISTANCE = 18;
    private static final int ENDER_HEIGHT_SEARCH = 6;

    private final Map<UUID, Burn> burns = new HashMap<>();
    private final Map<UUID, SwapLaunch> swaps = new HashMap<>();
    private final Set<LivingEntity> enderHits = new LinkedHashSet<>();
    private long currentTick;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        DamageSource source = event.getDamageSource();
        if (!(event.getEntity() instanceof LivingEntity target) || !isEffectTarget(target) || !dealtDamage(event)
                || !(source.getCausingEntity() instanceof Player attacker)
                || DiscDamage.BURN.matches(source.getDamageType())) {
            return;
        }

        DiscType type = source.getDamageContext().get(DISC_TYPE).orElse(null);
        if (type != null) {
            switch (type) {
                case LEVITATION -> target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 12, 11));
                case GLOWING -> target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0));
                case DARKNESS -> target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0));
                case SWAP -> source.getDamageContext().get(SWAP_DIRECTION)
                        .ifPresent(direction -> swaps.put(target.getUniqueId(), new SwapLaunch(target, direction)));
                case ENDER -> enderHits.add(target);
                default -> { }
            }
        }

        if (target.isInWater()) {
            extinguish(target);
            return;
        }

        Burn burn = burns.get(target.getUniqueId());
        if (burn != null) {
            burn.damage = Math.min(burn.damage + 1, MAX_BURN_DAMAGE);
        }
        if (type == DiscType.MAGMA) {
            if (burn == null) {
                burn = new Burn(target, currentTick + BURN_INTERVAL);
                burns.put(target.getUniqueId(), burn);
                // Visual fire leaves the vanilla fire timer independent of these damage ticks.
                target.setVisualFire(TriState.TRUE);
            }
            burn.attacker = attacker;
            burn.throwId = source.getDamageContext().get(DiscThrow.THROW_ID).orElse(null);
            burn.expiresAt = currentTick + BURN_DURATION;
        }
    }

    @SuppressWarnings("deprecation")
    static boolean dealtDamage(EntityDamageEvent event) {
        return event.getFinalDamage() > 0 || (event.isApplicable(DamageModifier.ABSORPTION)
                && event.getDamage(DamageModifier.ABSORPTION) < 0);
    }

    void tick() {
        currentTick++;
        for (Burn burn : List.copyOf(burns.values())) {
            LivingEntity target = burn.target;
            if (!target.isValid() || target.isDead() || target.isInWater() || !isEffectTarget(target)
                    || !burn.attacker.isOnline() || burn.attacker.getWorld() != target.getWorld()) {
                extinguish(target);
                continue;
            }

            if (currentTick >= burn.nextDamageAt) {
                burn.nextDamageAt += BURN_INTERVAL;
                DamageSource.Builder builder = DiscDamage.BURN.source(true)
                        .withDirectEntity(burn.attacker)
                        .withCausingEntity(burn.attacker);
                if (burn.throwId != null) {
                    builder.withDamageContext(context -> context.withStatic(DiscThrow.THROW_ID, burn.throwId));
                }
                target.damage(burn.damage, builder.build());
            }
            if (currentTick >= burn.expiresAt && burns.get(target.getUniqueId()) == burn) {
                extinguish(target);
            }
        }
        swaps.values().removeIf(swap -> !swap.tick());
        List<LivingEntity> teleports = List.copyOf(enderHits);
        enderHits.clear();
        teleports.forEach(DiscEffects::enderTeleport);
    }

    private static void enderTeleport(LivingEntity target) {
        if (!target.isValid() || target.isDead() || !isEffectTarget(target)) {
            return;
        }
        Location from = target.getLocation();
        var world = from.getWorld();
        int baseY = DiscArena.isArena(world) ? DiscArena.ground(world) : from.getBlockY();
        DiscLanding search = new DiscLanding(from, baseY, ENDER_HEIGHT_SEARCH);
        Block stand = search.random(ENDER_MIN_DISTANCE, ENDER_MAX_DISTANCE, null);
        if (stand == null) {
            return;
        }
        Location destination = stand.getLocation().add(0.5, 0, 0.5);
        destination.setYaw(from.getYaw());
        destination.setPitch(from.getPitch());
        if (target.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            target.setVelocity(new Vector());
            target.setFallDistance(0);
            teleportSignal(from);
            teleportSignal(target.getLocation());
        }
    }

    private static void teleportSignal(Location location) {
        location.getWorld().spawnParticle(Particle.PORTAL, location.clone().add(0, 0.8, 0), 18, 0.3, 0.45, 0.3, 0.2);
        location.getWorld().playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8F, 1.2F);
    }

    private static boolean isEffectTarget(LivingEntity target) {
        if (target instanceof Player player) {
            return player.isOnline() && (player.getGameMode() == GameMode.SURVIVAL
                    || player.getGameMode() == GameMode.ADVENTURE);
        }
        return DiscDummies.isDummy(target);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        extinguish(event.getEntity());
        stopSwap(event.getEntity());
        enderHits.remove(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        extinguish(event.getPlayer());
        stopSwap(event.getPlayer());
        enderHits.remove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        extinguish(event.getPlayer());
        stopSwap(event.getPlayer());
        enderHits.remove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        stopSwap(event.getPlayer());
    }

    void clear() {
        for (Burn burn : burns.values()) {
            restoreVisualFire(burn);
        }
        burns.clear();
        swaps.values().forEach(SwapLaunch::stop);
        swaps.clear();
        enderHits.clear();
    }

    private void stopSwap(LivingEntity target) {
        SwapLaunch swap = swaps.remove(target.getUniqueId());
        if (swap != null) {
            swap.stop();
        }
    }

    private void extinguish(LivingEntity target) {
        Burn burn = burns.remove(target.getUniqueId());
        if (burn != null) {
            restoreVisualFire(burn);
        }
    }

    private void restoreVisualFire(Burn burn) {
        if (burn.target.getVisualFire() == TriState.TRUE) {
            burn.target.setVisualFire(burn.previousVisualFire);
        }
    }

    private static final class Burn {
        private final LivingEntity target;
        private final TriState previousVisualFire;
        private Player attacker;
        private UUID throwId;
        private long nextDamageAt;
        private long expiresAt;
        private int damage = 1;

        private Burn(LivingEntity target, long nextDamageAt) {
            this.target = target;
            this.previousVisualFire = target.getVisualFire();
            this.nextDamageAt = nextDamageAt;
        }
    }
}
