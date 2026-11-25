package local.disc;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.player.PlayerToggleSneakEvent;

final class DiscHealing implements Listener {
    private static final int STARTUP_TICKS = 40;
    private static final int INTERVAL_TICKS = 14;
    private static final int SHIFTER_FIRST_HEAL_TICKS = 30;
    private static final int SHIFTER_INTERVAL_TICKS = 10;
    private static final int ICHOR_INTERVAL_TICKS = 80;
    private static final double HEALTH_PER_STEP = 1;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private long tick;

    boolean canHeal(Player player) {
        return player.isSneaking() && canRecoverHealth(player);
    }

    private static boolean canRecoverHealth(Player player) {
        GameMode mode = player.getGameMode();
        if (player.isDead() || (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE)) {
            return false;
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        return maxHealth != null && player.getHealth() < maxHealth.getValue();
    }

    void tick(
            Collection<? extends Player> players, Predicate<Player> canFight, Function<Player, MatchRole> activeRole) {
        tick++;
        for (Player player : players) {
            if (!canFight.test(player)) {
                stop(player);
                continue;
            }
            MatchRole role = activeRole.apply(player);
            if (role == MatchRole.ICHOR && tick % ICHOR_INTERVAL_TICKS == 0 && canRecoverHealth(player)) {
                player.heal(HEALTH_PER_STEP, RegainReason.CUSTOM);
            }
            if (!canHeal(player)) {
                stop(player);
                continue;
            }
            boolean shifter = role == MatchRole.SHIFTER;
            int firstHeal = shifter ? SHIFTER_FIRST_HEAL_TICKS : STARTUP_TICKS + INTERVAL_TICKS;
            int interval = shifter ? SHIFTER_INTERVAL_TICKS : INTERVAL_TICKS;
            Session session = sessions.computeIfAbsent(player.getUniqueId(), id -> new Session(tick + firstHeal));
            if (tick >= session.nextHealAt) {
                session.nextHealAt += interval;
                player.heal(HEALTH_PER_STEP, RegainReason.CUSTOM);
                if (!canHeal(player)) {
                    stop(player);
                }
            }
        }
    }

    boolean isCharging(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            stop(event.getPlayer());
        }
    }

    void stop(Player player) {
        sessions.remove(player.getUniqueId());
    }

    private static final class Session {
        private long nextHealAt;

        private Session(long nextHealAt) {
            this.nextHealAt = nextHealAt;
        }
    }
}
