package local.disc;

import com.destroystokyo.paper.MaterialTags;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

final class BotBrain {
    private static final double SPEED = 0.255;
    private static final double SCAN_DISTANCE = 30;
    private static final double SNIPER_SCAN_DISTANCE = 60;
    private static final double MORE_THROWER_SCAN_DISTANCE = 46;
    private static final double GLOWING_SCAN_DISTANCE = 60;
    private static final double TRACK_DISTANCE = 18;
    private static final double VIEW_DOT = 0.55;
    private static final double FINISH_HEALTH = 6;
    private static final double BURST_FINISH_HEALTH = 9;
    private static final double STRIKER_ATTACK_RANGE = 6;
    private static final double STRIKER_FINISH_RANGE = 9;
    private static final double STRIKER_BOOST_RANGE = 8;
    private static final double FRAGMENT_BURST_RANGE = 22;
    private static final int MAX_FRAGMENTS_PER_BURST = 2;
    private static final double RETREAT_HEALTH = 8;
    private static final double RETREAT_BANK_DISTANCE = 8;
    private static final int HOLD_AIM_TICKS = 16;
    private static final int RETREAT_MEMORY_TICKS = 30;
    private static final int ICHOR_DISENGAGE_TICKS = 38;
    private static final int FOLLOW_UP_TICKS = 12;
    private static final double AIM_ERROR_SQUARED = 0.30 * 0.30;
    private static final float TURN_YAW = 12;
    private static final float TURN_PITCH = 9;
    private static final float[] SCAN_PITCHES = {0, -35, 0, 35};

    private final DiscPlugin plugin;
    private final MatchLoot loot;
    private final MatchBots.Body body;
    private final Player player;
    private final MatchRole role;
    private final boolean jumper;
    private final boolean ichor;
    private final boolean moreThrower;
    private final double directSight;
    private final Random random = new Random();
    private final Deque<Location> route = new ArrayDeque<>();
    private final Map<UUID, MotionSample> motionSamples = new HashMap<>();
    private Target lastSeen;
    private long lastSeenAt;
    private Target lastMarked;
    private long lastMarkedAt;
    private BankPlan bankPlan;
    private Location heldAim;
    private long nextScan;
    private long nextPath;
    private long nextJump;
    private long nextEscape;
    private long nextRoleMove;
    private long nextApproachBoost;
    private long disengageUntil;
    private UUID followUpTarget;
    private IncomingDisc incomingDisc;
    private long followUpUntil;
    private Location roam;
    private Location retreatGoal;
    private Location jumpWaypoint;
    private Location approachBoost;
    private boolean retreatUsingBoost;
    private boolean retreatUrgent;
    private Location pathGoal;
    private Engagement engagement;
    private float searchYaw;
    private float searchPitch;
    private int scanPitchIndex;
    private double verticalVelocity;
    private Vector impulse = new Vector();
    private Vector lastMotion = new Vector();

    BotBrain(DiscPlugin plugin, MatchLoot loot, MatchBots.Body body, MatchRole role) {
        this.plugin = plugin;
        this.loot = loot;
        this.body = body;
        this.player = body.player;
        this.role = role;
        this.jumper = role == MatchRole.JUMPER;
        this.ichor = role == MatchRole.ICHOR;
        this.moreThrower = role == MatchRole.MORE_THROWER;
        this.directSight = role == MatchRole.SNIPER ? SNIPER_SCAN_DISTANCE
                : moreThrower ? MORE_THROWER_SCAN_DISTANCE : SCAN_DISTANCE;
        this.searchYaw = player.getLocation().getYaw();
    }

    void tick(long tick, Collection<Player> fighters) {
        Location from = player.getLocation();
        List<Target> observed = fighters.stream().filter(other -> other != player)
                .map(other -> observe(other, tick)).filter(target -> target != null)
                .sorted(Comparator.comparingDouble(Target::distance)).toList();
        List<Target> visible = observed.stream().filter(Target::visible).toList();
        motionSamples.keySet().removeIf(id -> visible.stream().noneMatch(target -> target.id.equals(id)));
        Target closest = visible.isEmpty() ? null : visible.getFirst();
        Target threat = visible.stream().filter(Target::aiming).findFirst().orElse(closest);
        Location launch = DiscFlight.launchOrigin(player);
        double health = player.getHealth();
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        boolean fullHealth = maxHealth != null && health >= maxHealth.getValue() - 0.01;
        boolean reconstructor = role == MatchRole.RECONSTRUCTOR;
        int fragments = reconstructor ? plugin.botFragmentCount(player) : 0;
        if (fragments > 0) {
            plugin.equipBotFragments(player);
        }
        double reserveThreshold = reconstructor ? Math.min(4, fragments * 0.7) : 0;
        boolean lowHealth = health <= (ichor ? 10 : RETREAT_HEALTH + reserveThreshold);
        boolean recentlySeen = lastSeen != null && tick - lastSeenAt <= HOLD_AIM_TICKS;
        Target closeThreat = lowHealth ? closeRetreatThreat(from, observed, recentlySeen) : null;
        Target marked = observed.stream()
                .filter(target -> target.glowing && BotBankShot.inRange(target.distance))
                .findFirst().orElse(null);
        if (marked != null) {
            lastMarked = marked;
            lastMarkedAt = tick;
        } else if (tick - lastMarkedAt <= HOLD_AIM_TICKS) {
            marked = lastMarked;
        }
        boolean rememberThreat = lowHealth && lastSeen != null && tick - lastSeenAt <= RETREAT_MEMORY_TICKS;
        if (threat == null && rememberThreat && clearBetween(lastSeen.eye, launch)) {
            threat = lastSeen;
        }
        if (threat == null && ichor && tick < disengageUntil && lastSeen != null) {
            threat = lastSeen;
        }
        double healthGap = reconstructor ? (fragments >= 3 ? 3 : 4)
                : role == MatchRole.STRIKER && closest != null && closest.distance <= STRIKER_ATTACK_RANGE ? 8 : 5;
        boolean disadvantaged = visible.stream().anyMatch(other -> other.health - health >= healthGap)
                || visible.size() >= 2 && health < 15;
        Location explosive = nearestLitTnt(from);
        FragmentAttack fragmentAttack = fragments > 0 && explosive == null && health > RETREAT_HEALTH
                && (visible.size() < 2 || health >= 15) ? fragmentAttack(launch, visible, fragments, tick) : null;
        boolean disengaging = ichor && threat != null && (tick < disengageUntil
                || threat.distance < 7 && (threat.aiming || !fullHealth));
        boolean flee = explosive != null || fragmentAttack == null
                && (closeThreat != null || threat != null && (lowHealth || disadvantaged || disengaging));
        boolean singer = role == MatchRole.SINGER;
        boolean striker = role == MatchRole.STRIKER;
        boolean wantsEvolution = singer && !plugin.botHasDisc(player, DiscType.WARDEN);
        SonicAttack sonic = singer && explosive == null && plugin.canAttemptThrow(player)
                ? sonicFinisher(launch, observed) : null;
        Target healer = !flee && role == MatchRole.DEMENTOR ? observed.stream()
                .filter(target -> target.healing && target.distance <= 24)
                .findFirst().orElse(null) : null;
        Target finish = flee ? null : finishingTarget(visible);
        boolean followingUp = !flee && moreThrower && tick < followUpUntil;
        Target followUp = followingUp
                ? visible.stream()
                        .filter(target -> target.id.equals(followUpTarget)
                                && !target.shieldFacing
                                && plugin.botDiscSlot(player, target.distance, null) >= 0)
                        .findFirst()
                        .orElse(null)
                : null;
        Target peek = heldAim != null && recentlySeen
                ? visible.stream().filter(target -> target.id.equals(lastSeen.id)).findFirst().orElse(null)
                : null;

        Target focus = fragmentAttack != null ? fragmentAttack.target
                : lowHealth && closest != null ? threat
                : sonic != null ? sonic.target
                : healer != null ? healer
                : followUp != null ? followUp
                : finish != null ? finish
                : peek != null ? peek
                : closest;

        if (focus != null && focus.visible) {
            lastSeen = focus;
            lastSeenAt = tick;
            heldAim = null;
        } else if (focus == null && recentlySeen && tick - lastSeenAt == 1 && !lowHealth && !flee) {
            heldAim = watchOpening(launch, lastSeen);
        }
        boolean watching = focus == null && heldAim != null && recentlySeen && !lowHealth && !flee
                && (!striker || from.distanceSquared(lastSeen.position) <= STRIKER_ATTACK_RANGE * STRIKER_ATTACK_RANGE);
        boolean heal = !flee && !watching && health < 14 && focus == null && tick - lastSeenAt > 12;
        double guardDistance = role == MatchRole.SHIFTER ? 2.5 : 4;
        boolean guard = !flee && !heal && sonic == null && fragmentAttack == null && threat != null
                && threat.aiming && threat.distance > guardDistance
                && (!striker || threat.distance <= STRIKER_ATTACK_RANGE);
        boolean mobileRecovery = ichor && flee && explosive == null;
        player.setSneaking(heal || mobileRecovery);
        int discSlot = sonic != null ? sonic.slot : fragmentAttack != null ? fragmentAttack.slot : -1;
        Location shot = sonic != null ? sonic.aim : fragmentAttack != null ? fragmentAttack.aim : null;
        Target shotTarget = sonic != null ? sonic.target : fragmentAttack != null ? fragmentAttack.target : null;
        Target offensiveBank = followingUp ? null : healer != null ? healer.visible ? null : healer
                : (role == MatchRole.SNIPER || moreThrower) && focus != null ? null : marked;
        Target bankTarget = closeThreat != null ? closeThreat : !flee ? offensiveBank : null;
        if (striker && bankTarget != null && bankTarget.distance > STRIKER_ATTACK_RANGE) {
            bankTarget = null;
        }
        boolean bank = false;
        boolean releaseWithoutTurning = false;
        if (discSlot < 0 && !heal && !mobileRecovery && explosive == null && (bankTarget != null || focus != null)
                && plugin.canAttemptThrow(player)) {
            if (bankTarget != null) {
                DiscType preference = bankTarget == healer ? DiscType.ENDER : wantsEvolution ? DiscType.DARKNESS : null;
                int bankSlot = plugin.botBankDiscSlot(player, bankTarget.distance, preference);
                if (bankSlot >= 0 && closeThreat != null) {
                    releaseWithoutTurning = bankHits(launch, launch.getDirection(), closeThreat);
                    if (releaseWithoutTurning) {
                        discSlot = bankSlot;
                        shotTarget = bankTarget;
                    }
                } else if (bankSlot >= 0 && !lowHealth) {
                    Vector aim = bankAim(launch, bankTarget, tick);
                    if (aim != null) {
                        discSlot = bankSlot;
                        shot = launch.clone().add(aim);
                        shotTarget = bankTarget;
                        bank = true;
                    }
                }
            }
            if (discSlot < 0 && focus != null && !lowHealth) {
                Target attackTarget = striker && finish == null ? closest
                        : healer != null && healer.visible ? healer : followUp != null ? followUp
                        : finish != null ? finish : peek != null ? peek : visible.stream()
                                .filter(target -> target.distance <= (flee ? 14 : directSight))
                                .min(Comparator.comparingDouble(target -> target.distance + 0.35 * target.health))
                                .orElse(null);
                if (attackTarget != null && !(followingUp && attackTarget.shieldFacing)) {
                    DiscType preferred = attackTarget == healer ? DiscType.ENDER
                            : role == MatchRole.SNIPER && attackTarget.distance >= 20 ? DiscType.SNIPE
                            : role == MatchRole.MATRIX ? DiscType.LASER
                            : wantsEvolution ? DiscType.DARKNESS : null;
                    discSlot = plugin.botDiscSlot(player, attackTarget.distance, preferred, !singer);
                    if (discSlot >= 0) {
                        DiscType type = plugin.botDiscType(player, discSlot);
                        double range = Math.min(directSight, DiscFlight.directRange(type));
                        if (!striker || strikerShouldThrow(attackTarget, type)) {
                            shot = shotPoint(launch, attackTarget, range, DiscFlight.launchSpeed(type, role),
                                    role == MatchRole.MATRIX && type == DiscType.LASER);
                            shotTarget = attackTarget;
                        }
                    }
                }
            }
        }
        boolean readyToAttack = shot != null || releaseWithoutTurning;
        int shieldTime = readyToAttack ? 6 : fullHealth ? 10 : 19;
        boolean shield = guard && !(peek != null && readyToAttack)
                && tick % 32 < shieldTime && player.getCooldown(Material.SHIELD) == 0
                && player.getInventory().getItemInOffHand().getType() == Material.SHIELD;
        updateShield(shield);

        Location goal = null;
        Location gaze = null;
        boolean combatMovement = false;
        if (flee) {
            approachBoost = null;
            if (closeThreat != null && explosive == null) {
                goal = retreat(from, closeThreat.position, tick, true);
                combatMovement = retreatUsingBoost;
                if (!retreatUsingBoost) {
                    Location cover = combatPosition(
                            from, closeThreat, closeThreat, visible, tick, fullHealth, Tactic.RETREAT);
                    if (cover != null) {
                        goal = cover;
                    }
                }
            } else if (explosive == null) {
                goal = combatPosition(from, threat, threat, visible, tick, fullHealth, Tactic.RETREAT);
            }
            if (goal == null || mobileRecovery && goal.distanceSquared(from) < 1) {
                Location danger = explosive != null ? explosive
                        : closeThreat != null ? closeThreat.position
                        : threat.position;
                goal = retreat(from, danger, tick, false);
            }
            gaze = goal;
        } else if (watching) {
            gaze = heldAim;
            combatMovement = true;
        } else if (!heal) {
            retreatGoal = null;
            boolean holdingPosition = closest == null && lastSeen != null && engagement != null
                    && engagement.position != null && tick - lastSeenAt <= 24;
            Location box = null;
            if (reconstructor) {
                if (fragmentAttack == null) {
                    box = safeSupplyBox(from, observed);
                }
            } else if (finish == null && !prioritizeFight(focus, healer) && health >= 12 && !guard) {
                box = loot.nearestBox(player);
            }
            boolean restock = reconstructor && box != null && fragments < 5;
            if (restock || preferBox(from, box, closest, visible, health, holdingPosition)) {
                engagement = null;
                goal = box;
                gaze = box.clone().add(0, 0.6, 0);
                loot.openNearbyBox(player);
            } else if (focus != null) {
                gaze = aimPoint(focus);
                Tactic tactic = striker || finish != null || followUp != null
                        || healer != null || fragmentAttack != null
                        ? Tactic.CHASE
                        : guard && (!fullHealth || !readyToAttack) ? Tactic.GUARD
                        : Tactic.FIGHT;
                goal = striker ? strikerApproachBoost(from, focus, tick) : null;
                if (goal == null) {
                    goal = combatPosition(from, focus, threat, visible, tick, fullHealth, tactic);
                }
                combatMovement = true;
            } else if (rememberThreat) {
                gaze = aimPoint(lastSeen);
            } else if (lastSeen != null && tick - lastSeenAt <= 60) {
                gaze = aimPoint(lastSeen);
                goal = holdingPosition && tick < engagement.replanAt
                        ? engagement.position
                        : combatPosition(from, lastSeen, null, visible, tick, fullHealth, Tactic.FIGHT);
                combatMovement = true;
            } else {
                engagement = null;
                goal = distantPosition(fighters, from);
            }
            if (goal == null && !rememberThreat) {
                goal = roam(from, tick);
            }
        }

        if (shot != null && !shield) {
            gaze = shot;
        }
        if (gaze == null && tick >= nextScan) {
            searchYaw = from.getYaw() + (random.nextBoolean() ? 1 : -1) * (health < 14 ? 80 : 50);
            searchPitch = SCAN_PITCHES[scanPitchIndex];
            scanPitchIndex = (scanPitchIndex + 1) % SCAN_PITCHES.length;
            nextScan = tick + (health < 14 ? 6 : 12);
        }
        if (gaze == null) {
            Location heading = from.clone();
            heading.setYaw(searchYaw);
            heading.setPitch(searchPitch);
            gaze = player.getEyeLocation().add(heading.getDirection().multiply(4));
        }
        if (!releaseWithoutTurning) {
            lookAt(gaze, shot != null && !shield ? launch : player.getEyeLocation());
        }
        if (readyToAttack && !heal && !shield
                && (sonic != null
                        ? canReleaseSonic(launch, sonic.target)
                        : releaseWithoutTurning || (bank
                                ? bankHits(launch, player.getEyeLocation().getDirection(), bankTarget)
                                : canRelease(launch, shot)))) {
            DiscType firedType = reconstructor ? plugin.botDiscType(player, discSlot) : null;
            EquipmentSlot hand = EquipmentSlot.OFF_HAND;
            if (discSlot != DiscItem.OFF_HAND_SLOT) {
                hand = EquipmentSlot.HAND;
                equipForThrow(discSlot);
            }
            if (plugin.throwDisc(player, hand)) {
                if (firedType != null && firedType.returnsToOwner() && shotTarget != null
                        && !bank && !releaseWithoutTurning) {
                    double damage = estimatedDamage(shotTarget, firedType);
                    long arrival = tick + (long) Math.ceil(shotTarget.distance / DiscFlight.launchSpeed(firedType));
                    incomingDisc = new IncomingDisc(
                            shotTarget.id, shotTarget.effectiveHealth(), damage, arrival + 5, false);
                } else if (firedType == DiscType.FRAGMENT && shotTarget != null && hasIncomingDisc(shotTarget, tick)) {
                    incomingDisc = incomingDisc.withFollowUp();
                }
                if (ichor) {
                    retreatGoal = null;
                    disengageUntil = tick + ICHOR_DISENGAGE_TICKS;
                }
                if (moreThrower && shotTarget != null) {
                    boolean completed = tick < followUpUntil && shotTarget.id.equals(followUpTarget);
                    followUpTarget = completed ? null : shotTarget.id;
                    followUpUntil = completed ? tick : tick + FOLLOW_UP_TICKS;
                }
            }
        }

        boolean roleMove = !shield && !heal && !watching
                && (jumper
                        ? goal != null
                                && (flee || combatMovement && focus != null
                                        && focus.distance >= 4 && focus.distance <= 16)
                        : flee ? role == MatchRole.DEMENTOR
                        : combatMovement && canClose(from, goal, focus));
        if (jumper && roleMove && !flee && threat != null && threat.aiming && player.isOnGround()
                && tick >= nextRoleMove && goal.distanceSquared(from) < 4) {
            Vector toward = horizontal(threat.position.toVector().subtract(from.toVector()));
            Vector flank = new Vector(-toward.getZ(), 0, toward.getX()).multiply(random.nextBoolean() ? 5 : -5);
            goal = bounded(from.clone().add(flank));
        }
        move(goal, tick, shield || heal, combatMovement, roleMove, mobileRecovery);
    }

    private FragmentAttack fragmentAttack(Location launch, List<Target> visible, int fragments, long tick) {
        if (!plugin.canAttemptThrow(player)) {
            return null;
        }
        int fragmentSlot = plugin.botFragmentSlot(player);
        if (fragmentSlot < 0) {
            return null;
        }
        FragmentAttack best = null;
        int bestPriority = Integer.MAX_VALUE;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Target target : visible) {
            if (target.shieldFacing || target.distance > FRAGMENT_BURST_RANGE) {
                continue;
            }
            boolean followUp = hasIncomingDisc(target, tick) && !incomingDisc.followedUp;
            double pending = incomingDamage(target, tick);
            double remaining = target.effectiveHealth() - pending;
            if (remaining <= 0) {
                continue;
            }
            int reusableSlot = plugin.botDiscSlot(player, target.distance, null);
            double reusableDamage = reusableSlot < 0
                    ? 0
                    : estimatedDamage(target, plugin.botDiscType(player, reusableSlot));
            double fragmentDamage = estimatedDamage(target, DiscType.FRAGMENT);
            int budget = Math.min(fragments, MAX_FRAGMENTS_PER_BURST);
            if (!followUp && remaining > reusableDamage + fragmentDamage * budget) {
                continue;
            }
            int slot = followUp || reusableSlot < 0 || remaining <= fragmentDamage && remaining > reusableDamage
                    ? fragmentSlot : reusableSlot;
            DiscType type = plugin.botDiscType(player, slot);
            Location aim = shotPoint(launch, target, DiscFlight.directRange(type), DiscFlight.launchSpeed(type), false);
            boolean lethal = remaining <= (slot == fragmentSlot ? fragmentDamage : reusableDamage);
            int priority = lethal ? 0 : followUp ? 1 : 2;
            double score = target.distance * 0.18 + remaining + (slot == fragmentSlot ? 1.5 : 0);
            if (aim != null && (priority < bestPriority || priority == bestPriority && score < bestScore)) {
                bestPriority = priority;
                bestScore = score;
                best = new FragmentAttack(target, aim, slot);
            }
        }
        return best;
    }

    private boolean hasIncomingDisc(Target target, long tick) {
        return incomingDisc != null && tick <= incomingDisc.validUntil && target.id.equals(incomingDisc.target);
    }

    private double incomingDamage(Target target, long tick) {
        if (!hasIncomingDisc(target, tick) || target.effectiveHealth() < incomingDisc.healthAtLaunch - 0.5) {
            return 0;
        }
        return incomingDisc.damage;
    }

    private double estimatedDamage(Target target, DiscType type) {
        return target.defense.landed(type, plugin.botBaseDamage(player, type));
    }

    private Location safeSupplyBox(Location from, List<Target> observed) {
        return loot.nearestBox(player, box -> observed.stream().noneMatch(enemy -> {
            double targetDistance = box.distanceSquared(enemy.position);
            return targetDistance < 49 || enemy.aiming && targetDistance + 9 < from.distanceSquared(enemy.position)
                    && clearBetween(enemy.eye, box.clone().add(0, 1, 0));
        }));
    }

    private SonicAttack sonicFinisher(Location launch, List<Target> observed) {
        int slot = plugin.botWardenSlot(player);
        if (slot < 0) {
            return null;
        }
        SonicAttack best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double maxDistance = DiscFlight.directRange(DiscType.WARDEN) + DiscFlight.shockwaveRadius(role);
        for (Target target : observed) {
            if (target.effectiveHealth() > plugin.botBaseDamage(player, DiscType.WARDEN)
                    || target.distance > maxDistance) {
                continue;
            }
            Location aim = target.bounds.getCenter().toLocation(launch.getWorld());
            BotSonicShot pulse = BotSonicShot.trace(launch, aim.toVector().subtract(launch.toVector()), role);
            if (pulse == null || !pulse.reaches(target.bounds)) {
                continue;
            }
            long others = observed.stream().filter(other -> other != target && pulse.reaches(other.bounds)).count();
            double score = -target.effectiveHealth() - 0.14 * target.distance + 0.55 * Math.min(3, others)
                    + (BotSonicShot.cramped(target.position) ? 0.65 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = new SonicAttack(target, aim, slot);
            }
        }
        return best;
    }

    private boolean canReleaseSonic(Location launch, Target target) {
        BotSonicShot pulse = BotSonicShot.trace(launch, player.getEyeLocation().getDirection(), role);
        return pulse != null && pulse.reaches(target.bounds);
    }

    private void equipForThrow(int slot) {
        var inventory = player.getInventory();
        if (slot < 9) {
            inventory.setHeldItemSlot(slot);
            return;
        }
        int hand = inventory.getHeldItemSlot();
        var disc = inventory.getItem(slot);
        inventory.setItem(slot, inventory.getItem(hand));
        inventory.setItem(hand, disc);
    }

    private static boolean strikerShouldThrow(Target target, DiscType type) {
        return target.distance <= STRIKER_ATTACK_RANGE || target.distance <= STRIKER_FINISH_RANGE
                && target.effectiveHealth()
                        <= type.damage() * MatchRole.strikerDamageMultiplier(target.distance) * 0.75;
    }

    private Location strikerApproachBoost(Location from, Target target, long tick) {
        if (target.distance <= STRIKER_BOOST_RANGE) {
            approachBoost = null;
            return null;
        }
        if (approachBoost != null && sameFooting(from, approachBoost)) {
            approachBoost = null;
            nextApproachBoost = tick + 16;
        }
        if (tick >= nextApproachBoost) {
            Vector toward = horizontal(target.position.toVector().subtract(from.toVector()));
            Location boost = plugin.escapeBoost(from, toward, false);
            approachBoost = boost != null && !sameFooting(from, boost)
                    && boost.distanceSquared(target.position) < from.distanceSquared(target.position) + 4
                    ? boost
                    : null;
            nextApproachBoost = tick + 12;
        }
        return approachBoost;
    }

    private static boolean sameFooting(Location first, Location second) {
        return first.getBlockX() == second.getBlockX() && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private Target finishingTarget(List<Target> visible) {
        double threshold = moreThrower ? BURST_FINISH_HEALTH : FINISH_HEALTH;
        double range = role == MatchRole.STRIKER ? STRIKER_FINISH_RANGE : directSight;
        var candidates = visible.stream().filter(target -> target.health <= threshold && target.distance <= range);
        return (moreThrower
                ? candidates.min(Comparator.comparingDouble(target -> target.health + 0.14 * target.distance))
                : candidates.findFirst()
        ).orElse(null);
    }

    private boolean prioritizeFight(Target focus, Target healer) {
        if (jumper) {
            return focus != null && focus.distance <= 14;
        }
        return focus != null && switch (role) {
            case SNIPER -> true;
            case STRIKER -> true;
            case SHIFTER -> focus.distance <= 18;
            case DEMENTOR -> healer != null || focus.distance <= 15;
            default -> false;
        };
    }

    private boolean canClose(Location from, Location goal, Target focus) {
        if (goal == null || focus == null || focus.distance < 7
                || goal.distanceSquared(focus.position) + 4 >= from.distanceSquared(focus.position)) {
            return false;
        }
        return role == MatchRole.SHIFTER && focus.distance <= 17
                || role == MatchRole.DEMENTOR && focus.distance <= 20;
    }

    private Target closeRetreatThreat(Location from, List<Target> observed, boolean recentlySeen) {
        if (!observed.isEmpty() && observed.getFirst().distance <= RETREAT_BANK_DISTANCE) {
            return observed.getFirst();
        }
        return recentlySeen && from.distanceSquared(lastSeen.position) <= RETREAT_BANK_DISTANCE * RETREAT_BANK_DISTANCE
                ? lastSeen : null;
    }

    private Vector bankAim(Location launch, Target target, long tick) {
        if (bankPlan == null || !bankPlan.target.equals(target.id) || tick >= bankPlan.replanAt) {
            Vector aim = BotBankShot.aim(launch, player.getBoundingBox().expand(0.2), target.bounds);
            bankPlan = new BankPlan(target.id, aim, tick + 4);
        }
        return bankPlan.aim;
    }

    private boolean bankHits(Location launch, Vector direction, Target target) {
        return BotBankShot.hits(launch, direction, player.getBoundingBox().expand(0.2), target.bounds);
    }

    private static boolean preferBox(Location from, Location box, Target closest, List<Target> visible,
                                     double health, boolean holdingPosition) {
        if (box == null || closest != null && closest.distance < 7) {
            return false;
        }
        double boxScore = (health >= 16 ? 15 : 13) - 0.45 * from.distance(box) - crowdPenalty(box, visible);
        double combatScore = closest == null ? (holdingPosition ? 5 : 0)
                : 10 + Math.max(0, 13 - closest.distance);
        return boxScore > combatScore;
    }

    private static double crowdPenalty(Location position, List<Target> visible) {
        double density = 0;
        for (Target target : visible) {
            density += Math.max(0, 1 - position.distanceSquared(target.position) / 81);
        }
        return Math.clamp(0.8 * (density - 1), 0, 1.6);
    }

    private Target observe(Player other, long tick) {
        Location eye = player.getEyeLocation();
        Location position = other.getLocation();
        Location otherEye = other.getEyeLocation();
        Vector ray = otherEye.toVector().subtract(eye.toVector());
        double distance = ray.length();
        boolean glowing = other.isGlowing() || other.hasPotionEffect(PotionEffectType.GLOWING);
        double viewDistance = player.hasPotionEffect(PotionEffectType.BLINDNESS) ? 6
                : player.hasPotionEffect(PotionEffectType.DARKNESS) ? 12
                : glowing ? GLOWING_SCAN_DISTANCE : directSight;
        if (distance < 0.01 || distance > viewDistance) {
            return null;
        }
        Vector direction = ray.multiply(1 / distance);
        if (eye.getDirection().dot(direction) < VIEW_DOT) {
            return null;
        }
        boolean visible = clear(eye, direction, distance);
        if (!visible && !glowing) {
            return null;
        }
        double facing = otherEye.getDirection().dot(direction.clone().multiply(-1));
        boolean aiming = visible && facing > 0.88;
        boolean shieldFacing = (moreThrower || role == MatchRole.RECONSTRUCTOR)
                && visible && facing > 0 && other.isBlocking();
        MotionSample motion = visible ? trackMotion(other.getUniqueId(), position, tick) : null;
        BotDamageEstimate defense = role == MatchRole.RECONSTRUCTOR ? BotDamageEstimate.of(other) : null;

        return new Target(
                other.getUniqueId(), position, otherEye, distance,
                other.getHealth(), other.getAbsorptionAmount(), aiming, shieldFacing,
                motion, visible, glowing, role == MatchRole.DEMENTOR && plugin.isHealing(other),
                other.getBoundingBox(), defense);
    }

    private static boolean clear(Location eye, Vector direction, double distance) {
        return eye.getWorld().rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true,
                block -> !MaterialTags.GLASS.isTagged(block) && !MaterialTags.GLASS_PANES.isTagged(block)) == null;
    }

    private Location distantPosition(Collection<Player> fighters, Location from) {
        Player distant = fighters.stream().filter(other -> other != player && from.distanceSquared(other.getLocation())
                        >= TRACK_DISTANCE * TRACK_DISTANCE)
                .min(Comparator.comparingDouble(other -> from.distanceSquared(other.getLocation()))).orElse(null);
        if (distant == null) {
            return null;
        }
        Location target = distant.getLocation();
        int height = Math.floorDiv(target.getBlockY(), 3) * 3 + 1;
        Location approximate = new Location(from.getWorld(), Math.floor(target.getX() / 4) * 4 + 2, height,
                Math.floor(target.getZ() / 4) * 4 + 2);
        Location standing = BotPath.standingNear(approximate);
        return standing == null ? approximate : standing;
    }

    private static Location nearestLitTnt(Location from) {
        return from.getWorld().getNearbyEntities(from, 5, 3, 5, entity -> entity instanceof TNTPrimed).stream()
                .map(entity -> entity.getLocation())
                .min(Comparator.comparingDouble(from::distanceSquared))
                .orElse(null);
    }

    private Location retreat(Location from, Location danger, long tick, boolean urgent) {
        if (retreatGoal == null || tick >= nextEscape || retreatUrgent != urgent) {
            Vector away = horizontal(from.toVector().subtract(danger.toVector()));
            Location boost = plugin.escapeBoost(from, away, urgent);
            retreatUsingBoost = boost != null;
            retreatUrgent = urgent;
            retreatGoal = boost == null ? bounded(from.clone().add(away.multiply(12))) : boost;
            nextEscape = tick + 8;
        }
        return retreatGoal;
    }

    private Location combatPosition(Location from, Target target, Target threat, List<Target> visible, long tick,
                                    boolean fullHealth, Tactic tactic) {
        if (engagement != null && engagement.target.equals(target.id) && tick < engagement.replanAt
                && engagement.tactic == tactic && engagement.enemyPosition.distanceSquared(target.position) < 9) {
            return engagement.position;
        }

        Vector forward = horizontal(target.position.toVector().subtract(from.toVector()));
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        CombatRange range = combatRange(from, fullHealth, tactic);
        List<CombatSpot> candidates = new ArrayList<>();
        addCombatSpot(candidates, from, from, target, threat, visible, side, range, tactic);
        for (int sign : new int[]{-1, 1}) {
            for (int distance : new int[]{3, 6}) {
                addCombatSpot(candidates, from, from.clone().add(forward.clone().multiply(sign * distance)),
                        target, threat, visible, side, range, tactic);
                Location flank = from.clone().add(side.clone().multiply(sign * distance));
                addCombatSpot(candidates, from, flank, target, threat, visible, side, range, tactic);
                addCombatSpot(candidates, from, flank.clone().add(forward.clone().multiply(2)),
                        target, threat, visible, side, range, tactic);
                if (tactic == Tactic.RETREAT) {
                    addCombatSpot(candidates, from, flank.clone().subtract(forward.clone().multiply(2)),
                            target, threat, visible, side, range, tactic);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(CombatSpot::score).reversed());
        for (CombatSpot candidate : candidates.stream().limit(tactic == Tactic.RETREAT ? 5 : 3).toList()) {
            Location position = candidate.position;
            List<Location> plan = BotPath.find(from, position);
            if (from.distanceSquared(position) > 0.45
                    && (plan.isEmpty() || plan.getLast().distanceSquared(position) > 0.45)) {
                continue;
            }
            route.clear();
            route.addAll(plan);
            pathGoal = position;
            nextPath = tick + 8;
            engagement = new Engagement(target.id, target.position, position, tactic, tick + 12);
            return position;
        }
        route.clear();
        pathGoal = from;
        nextPath = tick + 7;
        Location fallback = tactic == Tactic.RETREAT ? null : from;
        engagement = new Engagement(target.id, target.position, fallback, tactic, tick + 7);
        return fallback;
    }

    private CombatRange combatRange(Location from, boolean fullHealth, Tactic tactic) {
        boolean chasing = tactic == Tactic.CHASE;
        if (jumper) {
            return chasing ? new CombatRange(3, 8) : new CombatRange(5, 12);
        }
        if (ichor) {
            return new CombatRange(11, 22);
        }
        if (moreThrower) {
            return chasing ? new CombatRange(9, 21) : new CombatRange(13, 27);
        }
        return switch (role) {
            case SNIPER -> DiscArena.isDuel(from.getWorld()) ? new CombatRange(11, 21) : new CombatRange(30, 50);
            case STRIKER -> new CombatRange(1.8, 3.5);
            case SHIFTER -> chasing ? new CombatRange(1.5, 4) : new CombatRange(2, 5);
            case DEMENTOR -> chasing ? new CombatRange(4, 8) : new CombatRange(5, 11);
            default -> chasing ? new CombatRange(4.5, 9)
                    : fullHealth ? new CombatRange(8, 15)
                    : new CombatRange(11, 18);
        };
    }

    private static void addCombatSpot(List<CombatSpot> spots, Location from, Location candidate, Target target,
                                      Target threat, List<Target> visible, Vector side,
                                      CombatRange range, Tactic tactic) {
        Location position = BotPath.standingNear(bounded(candidate.clone()));
        if (position == null || spots.stream().anyMatch(spot -> spot.position.equals(position))) {
            return;
        }
        boolean chasing = tactic == Tactic.CHASE;
        boolean seekCover = tactic == Tactic.GUARD;
        double distance = position.distance(target.position);
        double score = -Math.max(0, range.near - distance) * 1.55
                - Math.max(0, distance - range.far) * 0.75
                - position.distance(from) * 0.17
                - crowdPenalty(position, visible);
        Location shotStart = position.clone().add(0, 1.45, 0);
        Location threatEye = threat == null ? target.eye : threat.eye;
        boolean exposed = clearBetween(threatEye, shotStart);
        if (tactic == Tactic.RETREAT) {
            if (!exposed) {
                long otherSightlines = visible.stream().filter(other -> other != threat)
                        .filter(other -> clearBetween(other.eye, shotStart)).count();
                double safety = -otherSightlines * 12 - position.distance(from) + Math.min(distance, 18) * 0.15;
                spots.add(new CombatSpot(position, safety));
            }
            return;
        }
        boolean firingLine = clearBetween(shotStart, aimPoint(target));
        boolean coverEdge = false;
        for (int sign : new int[]{-1, 1}) {
            Location nearby = BotPath.standingNear(position.clone().add(side.clone().multiply(sign * 1.5)));
            if (nearby != null && clearBetween(threatEye, nearby.clone().add(0, 1.45, 0)) != exposed) {
                coverEdge = true;
                break;
            }
        }
        score += firingLine ? (chasing ? 7.0 : seekCover ? 1.5 : 5.0) : 0;
        score += coverEdge ? (exposed ? 2.8 : 1.7) : 0;
        if (seekCover && !exposed) {
            score += coverEdge ? 7 : 3;
        }
        if (threat != null && threat.aiming && exposed) {
            Vector relative = shotStart.toVector().subtract(threatEye.toVector());
            if (relative.lengthSquared() > 0.0001 && threatEye.getDirection().dot(relative.normalize()) > 0.94) {
                score -= 2.4;
            }
        }
        spots.add(new CombatSpot(position, score));
    }

    private static Location aimPoint(Target target) {
        return target.position.clone().add(0, 1.05, 0);
    }

    private MotionSample trackMotion(UUID id, Location position, long tick) {
        MotionSample previous = motionSamples.get(id);
        if (previous != null && tick - previous.tick < 2) {
            return previous;
        }
        Vector velocity = new Vector();
        int steady = 0;
        if (previous != null && tick - previous.tick <= 3) {
            Vector delta = position.toVector()
                    .subtract(previous.position.toVector())
                    .multiply(1.0 / (tick - previous.tick));
            double speed = delta.length();
            if (speed >= 0.045 && speed <= 0.8) {
                velocity = delta;
                double previousSpeed = previous.velocity.length();
                if (previousSpeed >= speed * 0.65 && previousSpeed <= speed * 1.55
                        && delta.dot(previous.velocity) >= speed * previousSpeed * 0.94) {
                    steady = previous.steady + 1;
                }
            }
        }
        MotionSample sample = new MotionSample(tick, position, velocity, steady);
        motionSamples.put(id, sample);
        return sample;
    }

    private static Location watchOpening(Location origin, Target target) {
        Location edge = target.eye.clone().subtract(0, 0.15, 0);
        if (origin.distanceSquared(edge) > SCAN_DISTANCE * SCAN_DISTANCE || !clearBetween(origin, edge)) {
            return null;
        }
        Vector velocity = target.motion.velocity;
        Location probe = edge.clone();
        for (int step = 0; step < 8 && velocity.lengthSquared() > 0; step++) {
            probe.add(velocity);
            if (!clearBetween(origin, probe)) {
                return edge;
            }
            edge = probe.clone();
        }
        Location lastVisible = target.eye.clone().subtract(0, 0.15, 0);
        for (Vector offset : List.of(new Vector(1, 0, 0), new Vector(-1, 0, 0), new Vector(0, 0, 1),
                new Vector(0, 0, -1), new Vector(0, 1, 0), new Vector(0, -1, 0))) {
            if (!clearBetween(origin, lastVisible.clone().add(offset))) {
                return lastVisible;
            }
        }
        return null;
    }

    private static Location shotPoint(Location origin, Target target, double range, double speed, boolean matrixLaser) {
        Location direct = aimPoint(target);
        if (!clearBetween(origin, direct)) {
            direct = target.eye.clone().subtract(0, 0.15, 0);
        }
        double distanceSquared = origin.distanceSquared(direct);
        if (distanceSquared > range * range || !clearBetween(origin, direct)) {
            return null;
        }
        if (matrixLaser) {
            return target.visible ? matrixLaserShot(origin, direct, target.motion, range, speed) : direct;
        }
        if (target.motion.steady < 2) {
            return direct;
        }
        Vector velocity = target.motion.velocity;
        Vector offset = direct.toVector().subtract(origin.toVector());
        double a = speed * speed - velocity.lengthSquared();
        double b = offset.dot(velocity);
        double time = (b + Math.sqrt(b * b + a * distanceSquared)) / a;
        Location intercept = direct.clone().add(velocity.clone().multiply(time));
        if (origin.distanceSquared(intercept) <= range * range && clearBetween(origin, intercept)
                && direct.getWorld().rayTraceBlocks(direct, velocity, velocity.length() * time,
                        FluidCollisionMode.NEVER, true) == null) {
            return intercept;
        }
        return direct;
    }

    private static Location matrixLaserShot(
            Location origin, Location direct, MotionSample motion, double range, double speed) {
        Vector forward = direct.toVector().subtract(origin.toVector()).setY(0);
        if (motion == null || forward.lengthSquared() < 1e-6) {
            return direct;
        }

        forward.normalize();
        Vector lateral = motion.velocity.clone().setY(0);
        lateral.subtract(forward.multiply(lateral.dot(forward)));
        double lateralSpeed = lateral.length();
        if (lateralSpeed < 0.04) {
            return direct;
        }

        double leadTicks = Math.min(4, 0.65 * origin.distance(direct) / speed);
        double shift = Math.min(0.7, lateralSpeed * leadTicks);
        Location intercept = direct.clone().add(lateral.multiply(shift / lateralSpeed));
        if (origin.distanceSquared(intercept) <= range * range && clearBetween(origin, intercept)
                && direct.getWorld().rayTraceBlocks(direct, lateral, shift, FluidCollisionMode.NEVER, true) == null) {
            return intercept;
        }
        return direct;
    }

    private static boolean clearBetween(Location from, Location to) {
        Vector ray = to.toVector().subtract(from.toVector());
        double distance = ray.length();
        return distance < 0.01 || clear(from, ray.multiply(1 / distance), distance);
    }

    private Location roam(Location from, long tick) {
        if (roam == null || from.distanceSquared(roam) < 2 || tick % 60 == 0) {
            double angle = random.nextDouble() * Math.PI * 2;
            roam = bounded(from.clone().add(Math.cos(angle) * 8, 0, Math.sin(angle) * 8));
        }
        return roam;
    }

    private static Location bounded(Location location) {
        int edge = DiscArena.edge(location.getWorld()) - 1;
        location.setX(Math.clamp(location.getX(), -edge, edge));
        location.setZ(Math.clamp(location.getZ(), -edge, edge));
        return location;
    }

    private void lookAt(Location target, Location origin) {
        Vector difference = target.toVector().subtract(origin.toVector());
        if (difference.lengthSquared() < 0.001) {
            return;
        }
        Location desired = origin.clone().setDirection(difference);
        float yaw = origin.getYaw() + Math.clamp(
                (float) Math.IEEEremainder(desired.getYaw() - origin.getYaw(), 360), -TURN_YAW, TURN_YAW);
        float pitch = origin.getPitch() + Math.clamp(desired.getPitch() - origin.getPitch(), -TURN_PITCH, TURN_PITCH);
        player.setRotation(yaw, pitch);
    }

    private boolean canRelease(Location origin, Location shot) {
        Vector direction = player.getEyeLocation().getDirection();
        Vector offset = shot.toVector().subtract(origin.toVector());
        double along = offset.dot(direction);
        return along >= 0.1 && offset.lengthSquared() - along * along <= AIM_ERROR_SQUARED
                && clear(origin, direction, along);
    }

    private void updateShield(boolean block) {
        if (block && player.getCooldown(Material.SHIELD) == 0) {
            if (!player.hasActiveItem()) {
                player.startUsingItem(EquipmentSlot.OFF_HAND);
            }
        } else if (player.hasActiveItem()) {
            player.clearActiveItem();
        }
    }

    private void move(Location goal, long tick, boolean careful, boolean combatMovement, boolean roleMove,
                      boolean mobileRecovery) {
        Location from = player.getLocation();
        Vector movement = new Vector();
        boolean crouching = mobileRecovery;
        if (mobileRecovery) {
            player.setSprinting(false);
        }
        if (player.isOnGround()) {
            if (jumpWaypoint != null) {
                jumpWaypoint = null;
                nextPath = tick;
                engagement = null;
                nextRoleMove = Math.max(nextRoleMove, tick + 8);
            }
        } else if (jumpWaypoint != null) {
            goal = jumpWaypoint;
        }
        if (jumpWaypoint != null) {
            movement = jumpTravel(from);
        } else if (goal != null) {
            if (tick >= nextPath || pathGoal == null || goal.distanceSquared(pathGoal) > 4) {
                route.clear();
                route.addAll(BotPath.find(from, goal));
                pathGoal = goal;
                nextPath = tick + 7 + random.nextInt(4);
            }
            while (!route.isEmpty() && from.distanceSquared(route.peek()) < 0.36
                    && (approachBoost == null
                            || !sameFooting(route.peek(), approachBoost)
                            || sameFooting(from, approachBoost))) {
                route.remove();
            }
            if (!route.isEmpty()) {
                if (mobileRecovery && route.peek().getY() < from.getY() - 0.5) {
                    crouching = false;
                    player.setSneaking(false);
                }
                double speed = crouching ? crouchedMoveSpeed() : careful ? SPEED * 0.4 : SPEED;
                movement = horizontal(route.peek().toVector().subtract(from.toVector())).multiply(speed);
            }
        } else {
            route.clear();
            pathGoal = null;
            nextPath = tick;
        }
        boolean running = !careful && movement.lengthSquared() > (mobileRecovery ? 0.0025 : 0.01);
        player.setSprinting(running && !crouching);
        boolean usedAbility = jumper ? !careful && (roleMove || jumpWaypoint != null) && tryJumperMove(from, goal, tick)
                : roleMove && running && tryRoleMovement(from, tick);
        if (usedAbility && role == MatchRole.DEMENTOR) {
            route.clear();
            pathGoal = null;
            nextPath = tick;
            lastMotion = player.getVelocity();
            verticalVelocity = 0;
            impulse.zero();
            plugin.botStep(player, from, player.getLocation());
            return;
        }
        Vector momentum = player.getVelocity();
        if (usedAbility) {
            verticalVelocity = momentum.getY();
            if (jumper) {
                impulse.zero();
                movement = jumpTravel(from);
            } else {
                impulse = momentum.clone().setY(0);
                movement.zero();
            }
        } else if (momentum.distanceSquared(lastMotion) > 0.04) {
            impulse = momentum.clone().subtract(lastMotion).setY(0);
            verticalVelocity += momentum.getY() - lastMotion.getY();
        }
        movement.add(impulse);
        impulse.multiply(player.isOnGround() ? 0.66 : 0.91);
        PotionEffect levitation = player.getPotionEffect(PotionEffectType.LEVITATION);
        if (levitation != null) {
            verticalVelocity += (0.05 * (levitation.getAmplifier() + 1) - verticalVelocity) * 0.2;
        } else if (!usedAbility && running && player.isOnGround()
                && (!combatMovement && !mobileRecovery && tick >= nextJump
                        || !route.isEmpty() && route.peek().getBlockY() > from.getBlockY())) {
            verticalVelocity = 0.42;
            nextJump = tick + 13;
        } else if (!usedAbility) {
            verticalVelocity = player.isOnGround() ? Math.max(-0.08, verticalVelocity - 0.08)
                    : Math.max(-1.4, (verticalVelocity - 0.08) * 0.98);
        }
        movement.setY(verticalVelocity);
        body.move(movement);
        lastMotion = player.getVelocity();
        plugin.botStep(player, from, player.getLocation());
    }

    private boolean tryJumperMove(Location from, Location goal, long tick) {
        boolean grounded = player.isOnGround();
        if (tick < nextRoleMove || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || !grounded && (jumpWaypoint == null || verticalVelocity > 0.25)
                || impulse.lengthSquared() > 0.04 || player.getVelocity().distanceSquared(lastMotion) > 0.04) {
            return false;
        }
        if (goal == null || from.toVector().setY(0).distanceSquared(goal.toVector().setY(0)) < (grounded ? 4 : 1)) {
            return false;
        }
        nextRoleMove = tick + 5;
        Location landing = BotJumpPlan.landing(player, from, goal, SPEED, DiscRoleMovement.jumperSpeed(grounded));
        Vector direction = landing == null ? null : horizontal(landing.toVector().subtract(from.toVector()));
        if (direction == null || !plugin.useBotRoleMovement(player, direction)) {
            return false;
        }
        jumpWaypoint = landing;
        return true;
    }

    private Vector jumpTravel(Location from) {
        Vector offset = jumpWaypoint.toVector().subtract(from.toVector()).setY(0);
        double distance = offset.length();
        return distance <= SPEED ? offset : offset.multiply(SPEED / distance);
    }

    private double crouchedMoveSpeed() {
        var movementSpeed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        var sneakSpeed = player.getAttribute(Attribute.SNEAKING_SPEED);
        double walkMultiplier = movementSpeed == null || movementSpeed.getBaseValue() <= 0
                ? 1 : movementSpeed.getValue() / movementSpeed.getBaseValue();
        return SPEED / 1.3 * walkMultiplier * (sneakSpeed == null ? 0.3 : sneakSpeed.getValue());
    }

    private boolean tryRoleMovement(Location from, long tick) {
        if (tick < nextRoleMove || route.size() < 2) {
            return false;
        }
        nextRoleMove = tick + 5;
        var iterator = route.iterator();
        Location first = iterator.next();
        Vector direction = horizontal(iterator.next().toVector().subtract(first.toVector()));
        for (Location point : route) {
            Vector offset = point.toVector().subtract(from.toVector());
            double along = offset.dot(direction);
            if (along <= 0 || Math.abs(offset.getY()) > 0.6 || offset.lengthSquared() - along * along > 0.65 * 0.65) {
                return false;
            }
            if (along >= 4) {
                return plugin.useBotRoleMovement(player, direction);
            }
        }
        return false;
    }

    private static Vector horizontal(Vector vector) {
        vector.setY(0);
        return vector.lengthSquared() > 0.0001 ? vector.normalize() : vector;
    }

    private record MotionSample(long tick, Location position, Vector velocity, int steady) { }

    private record Target(UUID id, Location position, Location eye, double distance, double health, double absorption,
                          boolean aiming, boolean shieldFacing, MotionSample motion, boolean visible, boolean glowing,
                          boolean healing, BoundingBox bounds, BotDamageEstimate defense) {
        double effectiveHealth() {
            return health + absorption;
        }
    }

    private record SonicAttack(Target target, Location aim, int slot) { }

    private record FragmentAttack(Target target, Location aim, int slot) { }

    private record IncomingDisc(
            UUID target, double healthAtLaunch, double damage, long validUntil, boolean followedUp) {
        IncomingDisc withFollowUp() {
            return new IncomingDisc(target, healthAtLaunch, damage, validUntil, true);
        }
    }

    private record BankPlan(UUID target, Vector aim, long replanAt) { }

    private record CombatSpot(Location position, double score) { }

    private record CombatRange(double near, double far) { }

    private enum Tactic { FIGHT, GUARD, CHASE, RETREAT }

    private record Engagement(UUID target, Location enemyPosition, Location position, Tactic tactic, long replanAt) { }
}
