package local.disc;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class MatchScore {
    private static final int STREAK_TICKS = 100;
    private static final int SIMULTANEOUS_TICKS = 15;

    private final Deque<Kill> recent = new ArrayDeque<>();
    private final Map<UUID, Set<UUID>> victimsByThrow = new HashMap<>();
    private int points;
    private long damage;

    int points() {
        return points;
    }

    long damage() {
        return damage;
    }

    void addDamage(double healthLost) {
        damage += Math.round(healthLost * 100);
    }

    static int compare(int points, long damage, int otherPoints, long otherDamage) {
        int byPoints = Integer.compare(points, otherPoints);
        return byPoints != 0 ? byPoints : Long.compare(damage, otherDamage);
    }

    static String damageText(long damage) {
        long whole = damage / 100;
        long fraction = damage % 100;
        if (fraction == 0) {
            return whole + " HP";
        }
        return fraction % 10 == 0 ? whole + "." + fraction / 10 + " HP"
                : String.format(Locale.ROOT, "%d.%02d HP", whole, fraction);
    }

    Award record(UUID victim, UUID throwId, long tick) {
        while (!recent.isEmpty() && tick - recent.peekFirst().tick() > STREAK_TICKS) {
            recent.removeFirst();
        }
        Kill previous = null;
        for (Kill kill : recent.reversed()) {
            if (!kill.victim().equals(victim)) {
                previous = kill;
                break;
            }
        }

        Award award = Award.KILL;
        if (previous != null) {
            award = tick - previous.tick() <= SIMULTANEOUS_TICKS ? Award.SIMULTANEOUS : Award.STREAK;
        }
        if (throwId != null) {
            Set<UUID> victims = victimsByThrow.computeIfAbsent(throwId, ignored -> new HashSet<>());
            if (victims.stream().anyMatch(other -> !other.equals(victim))) {
                award = Award.SAME_THROW;
            }
            victims.add(victim);
        }

        recent.addLast(new Kill(victim, tick));
        points += award.points();
        return award;
    }

    enum Award {
        KILL(1, ""),
        STREAK(2, "・連続撃破"),
        SIMULTANEOUS(3, "・同時撃破"),
        SAME_THROW(4, "・一投で複数撃破");

        private final int points;
        private final String label;

        Award(int points, String label) {
            this.points = points;
            this.label = label;
        }

        int points() {
            return points;
        }

        String label() {
            return label;
        }
    }

    private record Kill(UUID victim, long tick) { }
}
