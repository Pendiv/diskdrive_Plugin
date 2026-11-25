package local.disc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

final class MatchHud {
    private static final String OBJECTIVE = "disc_match";
    private static final int MAX_ROWS = 15;

    private final Packets packets;
    private final Object addObjective;
    private final Object showObjective;
    private final Object removeObjective;
    private final List<Object> addTeams = new ArrayList<>();
    private final List<Object> removeTeams = new ArrayList<>();
    private final Map<UUID, View> views = new LinkedHashMap<>();

    MatchHud(Server server, List<BotLabel> bots) {
        try {
            packets = new Packets();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not initialize the Disc scoreboard packets", error);
        }

        // Build client packets from a separate board so the server can still use its team rules.
        Scoreboard board = server.getScoreboardManager().getNewScoreboard();
        board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.text("得点 / 与ダメージ", NamedTextColor.GOLD));
        Object handle = packets.handle(board);
        Object objective = call(packets.getObjective, handle, OBJECTIVE);
        addObjective = create(packets.setObjective, objective, 0);
        removeObjective = create(packets.setObjective, objective, 1);
        showObjective = packets.display(objective);

        Map<MatchRole, Team> roleTeams = new EnumMap<>(MatchRole.class);
        for (BotLabel bot : bots) {
            Team team = roleTeams.computeIfAbsent(bot.role, role -> {
                Team label = board.registerNewTeam("disc_role_" + role.ordinal());
                label.suffix(Component.text(" [" + role.displayName() + "]", roleColor(role)));
                return label;
            });
            team.addEntry(bot.name);
        }
        for (Team team : roleTeams.values()) {
            Object nativeTeam = call(packets.getTeam, handle, team.getName());
            addTeams.add(call(packets.addTeam, null, nativeTeam, true));
            removeTeams.add(call(packets.removeTeam, null, nativeTeam));
        }
    }

    void update(List<Player> watchers, List<Standing> standings) {
        List<Standing> ranking = standings.stream().sorted(Comparator.reverseOrder()).toList();
        for (View view : List.copyOf(views.values())) {
            if (!view.player.isOnline() || !watchers.contains(view.player)) {
                hide(view.player);
            }
        }
        for (Player viewer : watchers) {
            if (!viewer.isOnline()) {
                continue;
            }
            View view = views.get(viewer.getUniqueId());
            if (view == null) {
                view = new View(viewer);
                views.put(viewer.getUniqueId(), view);
                packets.send(viewer, addObjective);
                packets.send(viewer, showObjective);
                for (Object team : addTeams) {
                    packets.send(viewer, team);
                }
            }
            render(view, ranking);
        }
    }

    void hide(Player viewer) {
        View view = views.remove(viewer.getUniqueId());
        if (view == null || !viewer.isOnline()) {
            return;
        }
        packets.send(viewer, removeObjective);
        for (Object team : removeTeams) {
            packets.send(viewer, team);
        }
        Object previous = call(packets.getDisplayObjective, packets.handle(viewer.getScoreboard()), packets.sidebar);
        if (previous != null) {
            packets.send(viewer, packets.display(previous));
        }
    }

    void close() {
        for (View view : List.copyOf(views.values())) {
            hide(view.player);
        }
    }

    private void render(View view, List<Standing> ranking) {
        List<Row> rows = new ArrayList<>();
        UUID viewer = view.player.getUniqueId();
        ranking.stream().filter(entry -> entry.id.equals(viewer)).findFirst().ifPresent(self -> {
            int rank = 1 + (int) ranking.stream().filter(entry -> entry.compareTo(self) > 0).count();
            rows.add(new Row(Component.text("自分  " + self.points + "点  (" + rank + "位)", NamedTextColor.AQUA),
                    Component.text(MatchScore.damageText(self.damage), NamedTextColor.GRAY)));
        });
        rows.add(new Row(Component.text("ランキング", NamedTextColor.YELLOW), null));
        Standing previous = null;
        int rank = 0;
        for (int index = 0; index < ranking.size() && rows.size() < MAX_ROWS; index++) {
            Standing entry = ranking.get(index);
            if (previous == null || entry.compareTo(previous) != 0) {
                rank = index + 1;
            }
            previous = entry;
            NamedTextColor color = entry.id.equals(viewer) ? NamedTextColor.AQUA : NamedTextColor.WHITE;
            Component result = Component.text(entry.points + "点", NamedTextColor.GOLD)
                    .append(Component.text(" / " + MatchScore.damageText(entry.damage), NamedTextColor.GRAY));
            rows.add(new Row(Component.text(rank + "位  " + entry.name, color), result));
        }

        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (index >= view.rows.size() || !row.equals(view.rows.get(index))) {
                packets.send(view.player, packets.row(index, row));
            }
        }
        for (int index = rows.size(); index < view.rows.size(); index++) {
            packets.send(view.player, create(packets.resetScore, entryKey(index), OBJECTIVE));
        }
        view.rows = rows;
    }

    private static String entryKey(int index) {
        return "disc_row_" + index;
    }

    private static NamedTextColor roleColor(MatchRole role) {
        return switch (role) {
            case SNIPER -> NamedTextColor.GOLD;
            case DEMENTOR -> NamedTextColor.LIGHT_PURPLE;
            case SHIFTER -> NamedTextColor.AQUA;
            case MULTI_THROWER -> NamedTextColor.GREEN;
            case JUMPER -> NamedTextColor.BLUE;
            case ICHOR -> NamedTextColor.RED;
            case MORE_THROWER -> NamedTextColor.DARK_GREEN;
            case SINGER -> NamedTextColor.DARK_AQUA;
            case STRIKER -> NamedTextColor.DARK_RED;
            case RECONSTRUCTOR -> NamedTextColor.YELLOW;
            case MATRIX -> NamedTextColor.DARK_PURPLE;
            case NONE -> NamedTextColor.GRAY;
        };
    }

    record BotLabel(String name, MatchRole role) { }

    record Standing(UUID id, String name, int points, long damage) implements Comparable<Standing> {
        @Override
        public int compareTo(Standing other) {
            return MatchScore.compare(points, damage, other.points, other.damage);
        }
    }

    private record Row(Component name, Component result) { }

    private static final class View {
        private final Player player;
        private List<Row> rows = List.of();

        private View(Player player) {
            this.player = player;
        }
    }

    private static final class Packets {
        private final Method scoreHandle;
        private final Method playerHandle;
        private final Field connection;
        private final Method send;
        private final Method component;
        private final Method getObjective;
        private final Method getDisplayObjective;
        private final Method getTeam;
        private final Method addTeam;
        private final Method removeTeam;
        private final Constructor<?> setObjective;
        private final Constructor<?> setDisplay;
        private final Constructor<?> setScore;
        private final Constructor<?> resetScore;
        private final Constructor<?> fixedFormat;
        private final Object sidebar;
        private final Object blankFormat;

        private Packets() throws ReflectiveOperationException {
            Class<?> craftBoard = Class.forName("org.bukkit.craftbukkit.scoreboard.CraftScoreboard");
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            Class<?> boardType = nms("world.scores.Scoreboard");
            Class<?> objective = nms("world.scores.Objective");
            Class<?> displaySlot = nms("world.scores.DisplaySlot");
            Class<?> team = nms("world.scores.PlayerTeam");
            Class<?> chat = nms("network.chat.Component");
            Class<?> teamPacket = packet("SetPlayerTeam");
            scoreHandle = craftBoard.getMethod("getHandle");
            playerHandle = craftPlayer.getMethod("getHandle");
            connection = nms("server.level.ServerPlayer").getField("connection");
            send = connection.getType().getMethod("send", nms("network.protocol.Packet"));
            component = Class.forName("io.papermc.paper.adventure.PaperAdventure")
                    .getMethod("asVanilla", Component.class);
            getObjective = boardType.getMethod("getObjective", String.class);
            getDisplayObjective = boardType.getMethod("getDisplayObjective", displaySlot);
            getTeam = boardType.getMethod("getPlayerTeam", String.class);
            addTeam = teamPacket.getMethod("createAddOrModifyPacket", team, boolean.class);
            removeTeam = teamPacket.getMethod("createRemovePacket", team);
            setObjective = packet("SetObjective").getConstructor(objective, int.class);
            setDisplay = packet("SetDisplayObjective").getConstructor(displaySlot, objective);
            setScore = packet("SetScore")
                    .getConstructor(String.class, String.class, int.class, Optional.class, Optional.class);
            resetScore = packet("ResetScore").getConstructor(String.class, String.class);
            fixedFormat = nms("network.chat.numbers.FixedFormat").getConstructor(chat);
            sidebar = displaySlot.getField("SIDEBAR").get(null);
            blankFormat = nms("network.chat.numbers.BlankFormat").getField("INSTANCE").get(null);
        }

        private Object handle(Scoreboard board) {
            return call(scoreHandle, board);
        }

        private Object display(Object objective) {
            return create(setDisplay, sidebar, objective);
        }

        private Object row(int index, Row row) {
            Object number = row.result == null ? blankFormat : create(fixedFormat, call(component, null, row.result));
            return create(setScore, entryKey(index), OBJECTIVE, MAX_ROWS - index,
                    Optional.of(call(component, null, row.name)), Optional.of(number));
        }

        private void send(Player player, Object packet) {
            try {
                call(send, connection.get(call(playerHandle, player)), packet);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Could not send the Disc scoreboard packet", error);
            }
        }

        private static Class<?> packet(String name) throws ClassNotFoundException {
            return nms("network.protocol.game.Clientbound" + name + "Packet");
        }

        private static Class<?> nms(String name) throws ClassNotFoundException {
            return Class.forName("net.minecraft." + name);
        }
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not invoke " + method.getName(), error);
        }
    }

    private static Object create(Constructor<?> constructor, Object... args) {
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "Could not create " + constructor.getDeclaringClass().getSimpleName(), error);
        }
    }
}
