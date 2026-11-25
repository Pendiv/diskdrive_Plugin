package local.disc;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LingeringPotion;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

final class DiscPayloads implements Listener {
    private static final double IMPACT_RADIUS = 3;
    private static final double IMPACT_DAMAGE = 6;

    private final Map<UUID, Anvil> anvils = new HashMap<>();

    void drop(Location location, ItemStack item, Player attacker, UUID throwId) {
        World world = location.getWorld();
        switch (item.getType()) {
            case LINGERING_POTION -> world.spawn(location, LingeringPotion.class, potion -> {
                potion.setItem(item);
                potion.setShooter(attacker);
                potion.setVelocity(new Vector(0, -0.12, 0));
            });
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> {
                FallingBlock anvil = world.spawnFallingBlock(location, item.getType().createBlockData());
                anvil.setHurtEntities(false);
                anvil.setDropItem(false);
                anvil.setPersistent(false);
                if (anvil.isValid()) {
                    anvils.put(anvil.getUniqueId(), new Anvil(anvil, attacker, throwId));
                }
            }
            default -> dropItem(location, item);
        }
    }

    static void dropItem(Location location, ItemStack item) {
        location.getWorld().dropItem(location, item, drop -> drop.setVelocity(new Vector(0, -0.10, 0)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilLanding(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fallingBlock)) {
            return;
        }
        Anvil anvil = anvils.remove(fallingBlock.getUniqueId());
        if (anvil == null) {
            return;
        }
        event.setCancelled(true);
        Location impact = event.getBlock().getLocation().add(0.5, 0.1, 0.5);
        World world = impact.getWorld();
        world.playSound(impact, Sound.BLOCK_ANVIL_LAND, 1.0F, 0.85F);
        world.spawnParticle(Particle.CLOUD, impact, 30, 1.3, 0.08, 1.3, 0.06);
        if (!Boolean.TRUE.equals(world.getGameRuleValue(GameRules.PVP))) {
            return;
        }

        Player attacker = anvil.attacker();
        if (!attacker.isOnline() || attacker.getWorld() != world) {
            return;
        }
        DamageSource damage = DamageSource.builder(DamageType.FALLING_ANVIL)
                .withDirectEntity(fallingBlock)
                .withCausingEntity(attacker)
                .withDamageLocation(impact)
                .withDamageContext(context -> context.withStatic(DiscThrow.THROW_ID, anvil.throwId()))
                .build();
        for (Player target : world.getNearbyPlayers(impact, IMPACT_RADIUS)) {
            if (target.getLocation().distanceSquared(impact) <= IMPACT_RADIUS * IMPACT_RADIUS) {
                target.damage(IMPACT_DAMAGE, damage);
            }
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof FallingBlock) {
            anvils.remove(event.getEntity().getUniqueId());
        }
    }

    void discardIn(World world) {
        for (Anvil anvil : List.copyOf(anvils.values())) {
            FallingBlock entity = anvil.entity();
            if (entity.getWorld() == world && anvils.remove(entity.getUniqueId(), anvil)) {
                entity.remove();
            }
        }
    }

    void clear() {
        for (Anvil anvil : List.copyOf(anvils.values())) {
            anvil.entity().remove();
        }
        anvils.clear();
    }

    private record Anvil(FallingBlock entity, Player attacker, UUID throwId) { }
}
