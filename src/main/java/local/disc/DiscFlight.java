package local.disc;

import com.destroystokyo.paper.MaterialTags;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class DiscFlight {
    private static final double OUTBOUND_SPEED = 1.25;
    private static final double LASER_SPEED = 3.75;
    private static final double SNIPER_SPEED_MULTIPLIER = 1.6;
    private static final double RETURN_SPEED = 1.65;
    private static final double BASE_RANGE = 34;
    static final double BOUNCE_RANGE = 6.8;
    private static final double SNIPE_RANGE = 72;
    private static final double SNIPE_BOUNCE_RANGE = 0.5;
    private static final double SNIPE_DAMAGE_STEP_DISTANCE = 5;
    private static final double SNIPE_DAMAGE_STEP = 0.5;
    private static final double WARDEN_RANGE = 20;
    private static final double WARDEN_BOUNCE_RANGE = 24;
    private static final double WARDEN_MAX_BOUNCE_BONUS = 48;
    static final double SHOCKWAVE_RADIUS = 3.25;
    private static final int SHOCKWAVE_INTERVAL = 3;
    private static final double DIRECT_HIT_MARGIN = 0.20;
    private static final double REFLECTION_TARGET_RADIUS = 6;
    private static final int MAX_BOUNCES = 15;
    static final double EDGE_OFFSET = 0.001;

    private final Player owner;
    private final EquipmentSlot hand;
    private final ItemStack item;
    private final DiscType type;
    private final MatchRole role;
    private final double outboundMultiplier;
    private final UUID throwId;
    private final ItemBoxes itemBoxes;
    private final DiscMechanisms mechanisms;
    private final ItemDisplay display;
    private final Set<UUID> hitTargets;
    private final Map<UUID, Integer> shockwaveHitTicks;
    private final Set<Block> struckRods;
    private final World world;
    private final OwnerCatch ownerCatch = new OwnerCatch();

    private Player attacker;
    private Vector attackOrigin;
    private Location position;
    private Vector direction;
    private double range;
    private double wardenBounceBudget = WARDEN_MAX_BOUNCE_BONUS;
    private int age;
    private int bounces;
    private boolean caught;

    DiscFlight(Player owner, EquipmentSlot hand, ItemStack item, DiscType type, Set<UUID> hitTargets, Vector aim,
               ItemBoxes itemBoxes, DiscMechanisms mechanisms, Set<Block> struckRods, UUID throwId,
               MatchRole role) {
        this.owner = owner;
        this.attacker = owner;
        this.hand = hand;
        this.item = item;
        this.type = type;
        this.role = role;
        this.outboundMultiplier = speedMultiplier(role);
        this.throwId = throwId;
        this.itemBoxes = itemBoxes;
        this.mechanisms = mechanisms;
        this.hitTargets = hitTargets;
        this.shockwaveHitTicks = role == MatchRole.SINGER && type == DiscType.WARDEN ? new HashMap<>() : null;
        this.struckRods = struckRods;
        this.world = owner.getWorld();
        this.range = directRange(type);
        this.position = launchOrigin(owner);
        this.attackOrigin = position.toVector();
        this.direction = aim;
        this.display = world.spawn(position, ItemDisplay.class, entity -> {
            entity.setItemStack(item.asOne());
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setRotation(0, 0);
            entity.setTeleportDuration(1);
            entity.setInterpolationDuration(1);
            entity.setTransformation(pose(0));
        });
        position.setYaw(0);
        position.setPitch(0);
    }

    static double directRange(DiscType type) {
        return type == DiscType.SNIPE ? SNIPE_RANGE : type == DiscType.WARDEN ? WARDEN_RANGE : BASE_RANGE;
    }

    static double launchSpeed(DiscType type) {
        return type == DiscType.LASER ? LASER_SPEED : OUTBOUND_SPEED;
    }

    static double launchSpeed(DiscType type, MatchRole role) {
        return launchSpeed(type) * speedMultiplier(role);
    }

    private static double speedMultiplier(MatchRole role) {
        return role == MatchRole.SNIPER ? SNIPER_SPEED_MULTIPLIER : 1;
    }

    static Location launchOrigin(Player owner) {
        return owner.getEyeLocation().subtract(0, 0.17, 0);
    }

    void removeDisplay() {
        display.remove();
    }

    boolean caught() {
        return caught;
    }

    boolean tick() {
        if (!display.isValid()) {
            return false;
        }

        if (range > EDGE_OFFSET) {
            flyOut();
        } else if (type == DiscType.WARDEN || !type.returnsToOwner() || !flyBack()) {
            return false;
        }

        if (caught || !display.isValid() || !type.returnsToOwner() && range <= EDGE_OFFSET) {
            return false;
        }
        display.setInterpolationDelay(0);
        display.setTransformation(pose(++age));
        if (type == DiscType.WARDEN && range > EDGE_OFFSET && age % SHOCKWAVE_INTERVAL == 1) {
            world.spawnParticle(Particle.SONIC_BOOM, position, 1);
        }
        return display.teleport(position);
    }

    private void flyOut() {
        double remainingTime = 1;
        while (range > EDGE_OFFSET) {
            double speed = (bounces == 0 ? launchSpeed(type) : OUTBOUND_SPEED) * outboundMultiplier;
            double step = remainingTime * speed;
            if (step <= EDGE_OFFSET) {
                return;
            }
            double distance = Math.min(step, range);
            if (!chunksAreLoaded(direction, distance)) {
                range = 0;
                return;
            }

            RayTraceResult wall = traceBlocks(direction, distance, true);
            double traveled = wall == null ? distance : wall.getHitPosition().distance(position.toVector());
            double hitDistance = wall == null || type == DiscType.WARDEN ? traveled : traveled - EDGE_OFFSET;
            if (hitEntities(direction, hitDistance, false)) {
                return;
            }
            range -= traveled;
            if (wall == null) {
                position.add(direction.clone().multiply(distance));
                return;
            }

            remainingTime -= traveled / speed;
            if (hitMechanism(wall)) {
                position = wall.getHitPosition().toLocation(world);
                continue;
            }
            if (!type.returnsToOwner() || wall.getHitBlockFace() == null) {
                range = 0;
                return;
            }

            Vector normal = wall.getHitBlockFace().getDirection();
            position = wall.getHitPosition().add(normal.clone().multiply(EDGE_OFFSET)).toLocation(world);
            if (bounces >= MAX_BOUNCES) {
                range = 0;
                return;
            }
            direction = reflect(direction, normal);
            correctReflection(normal);
            bounces++;
            if (type == DiscType.WARDEN) {
                double bonus = Math.min(WARDEN_BOUNCE_RANGE, wardenBounceBudget);
                wardenBounceBudget -= bonus;
                range += bonus;
            } else {
                range += type == DiscType.SNIPE ? SNIPE_BOUNCE_RANGE : BOUNCE_RANGE;
            }
            world.playSound(position, Sound.BLOCK_COPPER_HIT, 0.9F, 1.65F);
        }
    }

    private boolean flyBack() {
        Location eye = owner.getEyeLocation();
        boolean right = (owner.getMainHand() == MainHand.RIGHT) == (hand == EquipmentSlot.HAND);
        Vector side = new Vector(1, 0, 0).rotateAroundY(-Math.toRadians(eye.getYaw()));
        Vector home = eye.subtract(0, 0.27, 0).add(side.multiply(right ? -0.27 : 0.27)).toVector();
        Vector back = home.subtract(position.toVector());
        double distance = back.length();
        if (distance <= EDGE_OFFSET) {
            return false;
        }

        back.multiply(1 / distance);
        double step = Math.min(distance, RETURN_SPEED);
        if (!chunksAreLoaded(back, step)) {
            return false;
        }
        while (step > EDGE_OFFSET) {
            RayTraceResult wall = traceBlocks(back, step, true);
            double traveled = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            if (hitEntities(back, wall == null ? traveled : traveled - EDGE_OFFSET, true)) {
                return true;
            }
            if (wall != null && hitMechanism(wall)) {
                position = wall.getHitPosition().toLocation(world);
                step -= traveled;
                continue;
            }
            position.add(back.multiply(step));
            if (ownerCatch.entered(owner.getBoundingBox().expand(DIRECT_HIT_MARGIN), position.toVector())) {
                caught = true;
                return false;
            }
            break;
        }
        return distance > RETURN_SPEED;
    }

    private RayTraceResult traceBlocks(Vector motion, double distance, boolean detectWalls) {
        RayTraceResult wall = detectWalls ? traceWall(position, motion, distance) : null;
        double chainDistance = wall == null ? distance : wall.getHitPosition().distance(position.toVector());
        RayTraceResult chain = mechanisms.trace(position, motion, chainDistance);
        return chain == null ? wall : chain;
    }

    static Vector reflect(Vector direction, Vector normal) {
        return direction.clone().subtract(normal.clone().multiply(2 * direction.dot(normal))).normalize();
    }

    static RayTraceResult traceWall(Location from, Vector direction, double distance) {
        return from.getWorld().rayTraceBlocks(from, direction, distance, FluidCollisionMode.NEVER, true,
                DiscFlight::isSolidObstacle);
    }

    private static boolean isSolidObstacle(Block block) {
        return !MaterialTags.GLASS.isTagged(block) && !MaterialTags.GLASS_PANES.isTagged(block)
                && !DiscMechanisms.isChain(block);
    }

    private boolean hitMechanism(RayTraceResult hit) {
        return mechanisms.hitBlock(hit.getHitBlock(), attacker, type, throwId, struckRods, hitTargets);
    }

    private boolean chunksAreLoaded(Vector motion, double distance) {
        Location end = position.clone().add(motion.clone().multiply(distance));
        int startX = position.getBlockX() >> 4;
        int startZ = position.getBlockZ() >> 4;
        int endX = end.getBlockX() >> 4;
        int endZ = end.getBlockZ() >> 4;
        return world.isChunkLoaded(startX, startZ) && world.isChunkLoaded(endX, endZ)
                && world.isChunkLoaded(startX, endZ) && world.isChunkLoaded(endX, startZ);
    }

    private boolean hitEntities(Vector motion, double distance, boolean returning) {
        if (distance <= 0) {
            return false;
        }
        Vector start = position.toVector();
        BoundingBox ownerBox = owner.getBoundingBox().expand(DIRECT_HIT_MARGIN);
        double contact = ownerCatch.contactDistance(ownerBox, start, motion, distance);
        boolean crossesOwner = contact >= 0 && (returning || bounces > 0 && attacker == owner)
                && canReachHitbox(owner, start.clone().add(motion.clone().multiply(contact)));
        double beforeCatch = crossesOwner ? Math.max(0, contact - EDGE_OFFSET) : distance;
        if (beforeCatch > 0 && attacker.isOnline() && attacker.getWorld() == world
                && hitOtherEntities(motion, beforeCatch, returning)) {
            ownerCatch.advance(ownerBox, position.toVector());
            return true;
        }
        if (crossesOwner) {
            caught = true;
            return true;
        }
        ownerCatch.advance(ownerBox, start.add(motion.clone().multiply(distance)));
        return false;
    }

    private boolean hitOtherEntities(Vector motion, double distance, boolean returning) {
        if (type == DiscType.WARDEN) {
            itemBoxes.openAlong(position, motion, distance, attacker);
            if (!display.isValid()) {
                return true;
            }
            RayTraceResult hit;
            while ((hit = world.rayTraceEntities(position, motion, distance,
                    entity -> DiscMechanisms.canPushTnt(entity, hitTargets))) != null) {
                DiscMechanisms.pushTnt((TNTPrimed) hit.getHitEntity(), motion, hitTargets);
            }
            return hitShockwave(motion, distance, returning);
        }
        DamageSource source = null;
        long boxRevision = itemBoxes.placementRevision();
        Set<UUID> obstructed = new HashSet<>();
        while (true) {
            RayTraceResult hit = world.rayTraceEntities(position, motion, distance, DIRECT_HIT_MARGIN,
                    entity -> !obstructed.contains(entity.getUniqueId())
                            && (itemBoxes.canOpen(entity, boxRevision, attacker)
                                    || DiscMechanisms.canPushTnt(entity, hitTargets)
                                    || entity instanceof LivingEntity target && canHit(target, returning)));
            if (hit == null) {
                return false;
            }
            Entity entity = hit.getHitEntity();
            if (entity == null) {
                return false;
            }
            if (!canReachHitbox(entity, hit.getHitPosition())) {
                obstructed.add(entity.getUniqueId());
                continue;
            }
            if (entity instanceof TNTPrimed tnt) {
                DiscMechanisms.pushTnt(tnt, motion, hitTargets);
                continue;
            }
            if (itemBoxes.open(entity, attacker)) {
                if (!display.isValid()) {
                    return true;
                }
                continue;
            }
            if (!(entity instanceof LivingEntity target)) {
                return false;
            }
            if (DiscDummies.tryBlock(target, motion, attacker)) {
                hitTargets.add(target.getUniqueId());
                if (bounces < MAX_BOUNCES) {
                    deflect(target, hit.getHitPosition(), motion);
                    return true;
                }
                continue;
            }
            if (source == null) {
                source = createDamageSource(motion);
            }
            double damage = type.damage();
            if (type == DiscType.SNIPE) {
                double distanceToAttacker = attacker.getLocation().distance(target.getLocation());
                damage += Math.floor(distanceToAttacker / SNIPE_DAMAGE_STEP_DISTANCE) * SNIPE_DAMAGE_STEP;
            }
            DiscShields.applyIncomingDamage(target, damage, source);
            if (!display.isValid()) {
                return true;
            }
            if (target instanceof Player player && bounces < MAX_BOUNCES && blockedByShield(target, source)) {
                deflect(target, hit.getHitPosition(), motion);
                DiscShields.reflect(player);
                return true;
            }
            hitTargets.add(target.getUniqueId());
        }
    }

    private boolean canReachHitbox(Entity entity, Vector hitPosition) {
        // The margin can extend through a thin cover block. Check the real hitbox before applying the hit.
        BoundingBox box = entity.getBoundingBox();
        Vector contact = new Vector(Math.clamp(hitPosition.getX(), box.getMinX(), box.getMaxX()),
                Math.clamp(hitPosition.getY(), box.getMinY(), box.getMaxY()),
                Math.clamp(hitPosition.getZ(), box.getMinZ(), box.getMaxZ()));
        Vector offset = contact.subtract(position.toVector());
        double length = offset.length();
        if (length <= EDGE_OFFSET) {
            return true;
        }
        Vector direction = offset.multiply(1 / length);
        return chunksAreLoaded(direction, length) && traceWall(position, direction, length - EDGE_OFFSET) == null;
    }

    static double shockwaveRadius(MatchRole role) {
        return SHOCKWAVE_RADIUS + (role == MatchRole.SINGER ? 1 : 0);
    }

    static double shockwaveDamage(MatchRole role) {
        return DiscType.WARDEN.damage() + (role == MatchRole.SINGER ? 1 : 0);
    }

    private boolean hitShockwave(Vector motion, double distance, boolean returning) {
        double radius = shockwaveRadius(role);
        Vector start = position.toVector();
        Vector end = start.clone().add(motion.clone().multiply(distance));
        BoundingBox area = BoundingBox.of(start, end).expand(radius);
        DamageSource source = null;
        for (Entity entity : world.getNearbyEntities(area, candidate -> candidate instanceof LivingEntity)) {
            LivingEntity target = (LivingEntity) entity;
            UUID targetId = target.getUniqueId();
            if (!canHit(target, returning) || shockwaveHitTicks != null
                    && age - shockwaveHitTicks.getOrDefault(targetId, -SHOCKWAVE_INTERVAL) < SHOCKWAVE_INTERVAL) {
                continue;
            }
            Vector offset = target.getBoundingBox().getCenter().subtract(start);
            double alongPath = Math.clamp(offset.dot(motion), 0, distance);
            double distanceSquared = offset.subtract(motion.clone().multiply(alongPath)).lengthSquared();
            if (distanceSquared > radius * radius) {
                continue;
            }
            if (source == null) {
                source = createDamageSource(motion);
            }
            if (shockwaveHitTicks == null) {
                hitTargets.add(targetId);
            } else {
                shockwaveHitTicks.put(targetId, age);
            }
            target.damage(shockwaveDamage(role), source);
            if (!display.isValid()) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private boolean blockedByShield(LivingEntity target, DamageSource source) {
        return target.getLastDamageCause() instanceof EntityDamageByEntityEvent event
                && event.getDamager() == source.getDirectEntity() && !event.isCancelled()
                && event.isApplicable(DamageModifier.BLOCKING) && event.getDamage(DamageModifier.BLOCKING) < 0
                && event.getFinalDamage() <= 0;
    }

    private void deflect(LivingEntity defender, Vector hitPosition, Vector motion) {
        if (!type.returnsToOwner()) {
            range = 0;
            return;
        }
        Location facing = defender.getLocation();
        facing.setPitch(0);
        Vector normal = facing.getDirection();
        double dot = motion.dot(normal);
        direction = dot < 0 ? motion.clone().subtract(normal.clone().multiply(2 * dot)) : normal;
        if (defender instanceof Player player && attacker != player) {
            attacker = player;
            attackOrigin = hitPosition.clone();
        }
        range = type == DiscType.SNIPE
                ? Math.max(0, range - hitPosition.distance(position.toVector())) + SNIPE_BOUNCE_RANGE
                : BASE_RANGE;
        position = hitPosition.add(direction.clone().multiply(EDGE_OFFSET)).toLocation(world);
        correctReflection(normal);
        bounces++;
    }

    private void correctReflection(Vector normal) {
        if (type != DiscType.HOMING) {
            return;
        }
        Vector origin = position.toVector();
        double targetDistanceSquared = REFLECTION_TARGET_RADIUS * REFLECTION_TARGET_RADIUS;
        Vector corrected = null;
        for (LivingEntity target : world.getNearbyLivingEntities(position, REFLECTION_TARGET_RADIUS,
                entity -> canHit(entity, false))) {
            Vector offset = target.getBoundingBox().getCenter().subtract(origin);
            double distanceSquared = offset.lengthSquared();
            if (distanceSquared > targetDistanceSquared || offset.dot(normal) <= EDGE_OFFSET) {
                continue;
            }
            double distance = Math.sqrt(distanceSquared);
            Vector aim = offset.multiply(1 / distance);
            if (chunksAreLoaded(aim, distance) && traceBlocks(aim, distance, true) == null) {
                targetDistanceSquared = distanceSquared;
                corrected = aim;
            }
        }
        if (corrected != null) {
            direction = corrected;
        }
    }

    private boolean canHit(LivingEntity target, boolean returning) {
        if (target == attacker || (returning && target == owner) || target.isDead()
                || hitTargets.contains(target.getUniqueId())) {
            return false;
        }
        if (!(target instanceof Player player)) {
            return DiscDummies.isDummy(target);
        }
        if (DiscLobby.isLobby(world) || !Boolean.TRUE.equals(world.getGameRuleValue(GameRules.PVP))
                || (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)
                || !attacker.canSee(player)) {
            return false;
        }
        Team team = attacker.getScoreboard().getEntryTeam(attacker.getName());
        return team == null || team.allowFriendlyFire() || !team.hasEntry(player.getName());
    }

    private DamageSource createDamageSource(Vector motion) {
        // A projectile handle gives Paper the damager and direction without a second visible entity.
        Snowball projectile = world.createEntity(position, Snowball.class);
        projectile.setItem(item);
        projectile.setShooter(attacker);
        projectile.setVelocity(motion);
        DiscDamage damage = type == DiscType.WARDEN ? DiscDamage.SONIC_BOOM : DiscDamage.THROWN;
        Vector origin = attackOrigin.clone();
        return damage.source(true)
                .withCausingEntity(attacker)
                .withDirectEntity(projectile)
                .withDamageLocation(position)
                .withDamageContext(context -> {
                    context.withStatic(DiscEffects.DISC_TYPE, type).withStatic(DiscThrow.THROW_ID, throwId)
                            .withDynamic(DiscThrow.ATTACK_ORIGIN, origin::clone);
                    if (type == DiscType.SWAP) {
                        context.withStatic(DiscEffects.SWAP_DIRECTION, motion.clone());
                    }
                })
                .build();
    }

    private static final class OwnerCatch {
        private boolean armed;

        double contactDistance(BoundingBox bounds, Vector start, Vector direction, double distance) {
            advance(bounds, start);
            if (!armed) {
                return -1;
            }
            if (bounds.contains(start)) {
                return 0;
            }
            RayTraceResult hit = bounds.rayTrace(start, direction, distance);
            return hit == null ? -1 : hit.getHitPosition().distance(start);
        }

        void advance(BoundingBox bounds, Vector point) {
            // The launch begins inside the owner; only a later entry can be a catch.
            armed |= !bounds.contains(point);
        }

        boolean entered(BoundingBox bounds, Vector point) {
            return armed && bounds.contains(point);
        }
    }

    private static Transformation pose(int age) {
        Quaternionf rotation = new Quaternionf().rotateX((float) (Math.PI / 2))
                .rotateZ(age * 0.76F);
        return new Transformation(new Vector3f(), rotation, new Vector3f(0.9F), new Quaternionf());
    }
}
