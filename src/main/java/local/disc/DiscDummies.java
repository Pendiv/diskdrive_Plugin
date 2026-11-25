package local.disc;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

final class DiscDummies implements Listener {
    private static final String DUMMY_TAG = "disc_test_dummy";
    private static final String SHIELD_TAG = "disc_test_shield";
    private static final double DUMMY_HEALTH = 200;
    private static final int HEAL_DELAY = 100;

    private final Map<UUID, Dummy> dummies = new HashMap<>();
    private long tick;

    static boolean isDummy(LivingEntity entity) {
        return entity instanceof Mannequin && entity.getScoreboardTags().contains(DUMMY_TAG);
    }

    static boolean tryBlock(LivingEntity target, Vector motion, Player attacker) {
        if (!isDummy(target) || !target.getScoreboardTags().contains(SHIELD_TAG)
                || target.getEquipment().getItemInOffHand().getType() != Material.SHIELD) {
            return false;
        }
        Location facing = target.getLocation();
        facing.setPitch(0);
        if (facing.getDirection().dot(motion) >= 0) {
            return false;
        }
        // Paper applies the shield damage modifier only to players.
        facing.getWorld().playSound(facing, Sound.ITEM_SHIELD_BLOCK, 1.0F, 1.0F);
        attacker.sendActionBar(Component.text("盾で防御"));
        return true;
    }

    void start(Server server) {
        for (World world : server.getWorlds()) {
            world.getEntitiesByClass(Mannequin.class).forEach(this::track);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        track(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Mannequin) {
            dummies.remove(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        if (event.getEntity() instanceof Mannequin entity && entity.hasActiveItem()
                && entity.getScoreboardTags().contains(SHIELD_TAG)) {
            Dummy dummy = dummies.get(entity.getUniqueId());
            if (dummy != null) {
                dummy.refreshShield = true;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Mannequin mannequin) {
            Dummy dummy = dummies.get(mannequin.getUniqueId());
            if (dummy != null) {
                dummy.healAt = tick + HEAL_DELAY;
                reportHit(event);
            }
        }
    }

    void tick() {
        tick++;
        for (Dummy dummy : List.copyOf(dummies.values())) {
            Mannequin entity = dummy.entity;
            if (!entity.isValid() || entity.isDead() || !isDummy(entity)) {
                dummies.remove(entity.getUniqueId(), dummy);
                continue;
            }

            if (!dummy.initialized) {
                entity.setImmovable(false);
                entity.setGravity(true);
                AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealth != null) {
                    maxHealth.setBaseValue(DUMMY_HEALTH);
                }
                AttributeInstance knockback = entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
                if (knockback != null) {
                    knockback.setBaseValue(1);
                }
                dummy.initialized = true;
            }

            if (entity.getScoreboardTags().contains(SHIELD_TAG)) {
                if (dummy.refreshShield) {
                    // Pairing sends the use flag before the equipment. Re-raise after the client receives both.
                    entity.clearActiveItem();
                    dummy.refreshShield = false;
                } else {
                    raiseShield(entity);
                }
            }

            AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
            double maximum = maxHealth == null ? DUMMY_HEALTH : maxHealth.getValue();
            if (tick >= dummy.healAt && entity.getHealth() < maximum) {
                entity.setHealth(maximum);
            }

            double health = entity.getHealth();
            if (health != dummy.shownHealth) {
                entity.setDescription(Component.text(String.format(Locale.ROOT, "HP %.1f / %.1f", health, maximum)));
                dummy.shownHealth = health;
            }
        }
    }

    private void track(Entity entity) {
        if (entity instanceof Mannequin mannequin && isDummy(mannequin)) {
            dummies.put(mannequin.getUniqueId(), new Dummy(mannequin));
        }
    }

    private static void raiseShield(Mannequin entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment.getItemInOffHand().getType() != Material.SHIELD) {
            equipment.setItemInOffHand(new ItemStack(Material.SHIELD), true);
        }
        if (!entity.hasActiveItem() || entity.getActiveItemHand() != EquipmentSlot.OFF_HAND
                || entity.getActiveItem().getType() != Material.SHIELD) {
            entity.clearActiveItem();
            entity.startUsingItem(EquipmentSlot.OFF_HAND);
        }
    }

    @SuppressWarnings("deprecation")
    private static void reportHit(EntityDamageEvent event) {
        DamageSource source = event.getDamageSource();
        if (!(source.getCausingEntity() instanceof Player shooter)) {
            return;
        }
        DiscType type = source.getDamageContext().get(DiscEffects.DISC_TYPE).orElse(null);
        boolean burn = DiscDamage.BURN.matches(source.getDamageType());
        if (type == null && !burn) {
            return;
        }

        double damage = event.getFinalDamage();
        if (damage > 0) {
            String name = burn ? "炎上" : type.displayName();
            shooter.sendActionBar(Component.text(String.format(Locale.ROOT, "%s  %.1fダメージ", name, damage)));
        } else if (event.isApplicable(DamageModifier.BLOCKING) && event.getDamage(DamageModifier.BLOCKING) < 0) {
            shooter.sendActionBar(Component.text("盾で防御"));
        }
    }

    private static final class Dummy {
        private final Mannequin entity;
        private long healAt;
        private boolean initialized;
        private boolean refreshShield;
        private double shownHealth = Double.NaN;

        private Dummy(Mannequin entity) {
            this.entity = entity;
        }
    }
}
