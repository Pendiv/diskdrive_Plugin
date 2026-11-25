package local.disc;

import static org.bukkit.persistence.PersistentDataType.INTEGER;
import static org.bukkit.persistence.PersistentDataType.STRING;
import static org.bukkit.persistence.PersistentDataType.TAG_CONTAINER_ARRAY;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.RayTraceResult;

final class MatchBoxLayout {
    private static final NamespacedKey LAYOUT = new NamespacedKey("disc", "match_boxes");
    private static final NamespacedKey X = new NamespacedKey("disc", "x");
    private static final NamespacedKey Y = new NamespacedKey("disc", "y");
    private static final NamespacedKey Z = new NamespacedKey("disc", "z");
    private static final NamespacedKey KIND = new NamespacedKey("disc", "kind");
    private static final String PREVIEW_TAG = "disc_match_box_preview";
    private static final double REACH = 8;
    private static final List<Definition> DEFAULTS = List.of(
            new Definition(-23, 64, 0, Kind.OUTER),
            new Definition(0, 64, -23, Kind.OUTER),
            new Definition(23, 64, 0, Kind.OUTER),
            new Definition(0, 64, 23, Kind.OUTER),
            new Definition(0, 64, 0, Kind.CENTER));
    private static final List<Definition> HALL_DEFAULTS = hallDefaults();
    private static final List<Definition> DUEL_DEFAULTS = List.of(
            new Definition(-9, DiscArena.GROUND, -9, Kind.OUTER),
            new Definition(9, DiscArena.GROUND, -9, Kind.OUTER),
            new Definition(-9, DiscArena.GROUND, 9, Kind.OUTER),
            new Definition(9, DiscArena.GROUND, 9, Kind.OUTER),
            new Definition(0, DiscArena.GROUND, 0, Kind.CENTER));

    private final ItemBoxes boxes;
    private final Map<World, PreviewSession> previewSessions = new IdentityHashMap<>();

    MatchBoxLayout(ItemBoxes boxes) {
        this.boxes = boxes;
    }

    enum Kind {
        OUTER("外周ボックス", NamedTextColor.AQUA),
        CENTER("中央ボックス", NamedTextColor.GOLD);

        private final String name;
        private final NamedTextColor color;

        Kind(String name, NamedTextColor color) {
            this.name = name;
            this.color = color;
        }

        String displayName() {
            return name;
        }
    }

    record Definition(int x, int y, int z, Kind kind) {
        Location location(World world) {
            return new Location(world, x + 0.5, y, z + 0.5);
        }

        boolean atSamePosition(Definition other) {
            return x == other.x && y == other.y && z == other.z;
        }
    }

    List<Definition> definitions(World world) {
        if (!DiscArena.isArena(world)) {
            return List.of();
        }
        PersistentDataContainer data = world.getPersistentDataContainer();
        if (!data.has(LAYOUT)) {
            if (DiscArena.isDuel(world)) {
                return DUEL_DEFAULTS;
            }
            return DiscArena.isHall(world) ? HALL_DEFAULTS : DEFAULTS;
        }
        PersistentDataContainer[] stored = data.get(LAYOUT, TAG_CONTAINER_ARRAY);
        if (stored == null) {
            throw new IllegalStateException("Invalid Disc match box layout");
        }

        List<Definition> definitions = new ArrayList<>(stored.length);
        for (PersistentDataContainer entry : stored) {
            Integer x = entry.get(X, INTEGER);
            Integer y = entry.get(Y, INTEGER);
            Integer z = entry.get(Z, INTEGER);
            String kind = entry.get(KIND, STRING);
            if (x == null || y == null || z == null || kind == null) {
                throw new IllegalStateException("Missing Disc match box layout field");
            }
            definitions.add(new Definition(x, y, z, Kind.valueOf(kind)));
        }
        return List.copyOf(definitions);
    }

    private static List<Definition> hallDefaults() {
        List<Definition> definitions = new ArrayList<>();
        for (int[] point : IndoorArenaGenerator.outerBoxPositions()) {
            definitions.add(new Definition(point[0], point[1], point[2], Kind.OUTER));
        }
        int[] center = IndoorArenaGenerator.centerBoxPosition();
        definitions.add(new Definition(center[0], center[1], center[2], Kind.CENTER));
        return List.copyOf(definitions);
    }

    void showPreviews(World world) {
        if (!DiscArena.isArena(world)) {
            return;
        }
        PreviewSession session = previewSessions.computeIfAbsent(world, ignored -> new PreviewSession());
        if (session.updating) {
            return;
        }

        session.updating = true;
        try {
            session.previews.keySet().removeIf(id -> !boxes.isPlaced(id));
            for (Definition definition : definitions(world)) {
                if (previewSessions.get(world) != session) {
                    return;
                }
                if (!session.previews.containsValue(definition)) {
                    addPreview(world, session, definition);
                }
            }
        } finally {
            session.updating = false;
        }
    }

    void hidePreviews(World world) {
        PreviewSession session = previewSessions.remove(world);
        if (session != null) {
            List<UUID> ids = List.copyOf(session.previews.keySet());
            session.previews.clear();
            ids.forEach(boxes::remove);
        }
    }

    void hideAllPreviews() {
        List.copyOf(previewSessions.keySet()).forEach(this::hidePreviews);
    }

    boolean addOuter(Location ground) {
        Definition definition = new Definition(ground.getBlockX(), ground.getBlockY(), ground.getBlockZ(), Kind.OUTER);
        return update(ground.getWorld(), null, definition);
    }

    UUID aimedPreview(Player player) {
        World world = player.getWorld();
        PreviewSession session = previewSessions.get(world);
        if (session == null) {
            return null;
        }
        Location eye = player.getEyeLocation();
        RayTraceResult hit = world.rayTrace(eye, eye.getDirection(), REACH, FluidCollisionMode.NEVER, true, 0,
                entity -> session.previews.containsKey(entity.getUniqueId()));
        Entity entity = hit == null ? null : hit.getHitEntity();
        return entity == null ? null : entity.getUniqueId();
    }

    boolean setKind(World world, UUID id, Kind kind) {
        PreviewSession session = previewSessions.get(world);
        Definition previous = session == null ? null : session.previews.get(id);
        if (previous == null) {
            return false;
        }
        return update(world, id, new Definition(previous.x(), previous.y(), previous.z(), kind));
    }

    boolean remove(World world, UUID id) {
        return update(world, id, null);
    }

    private boolean update(World world, UUID id, Definition replacement) {
        PreviewSession session = previewSessions.get(world);
        if (session == null || session.updating) {
            throw new IllegalStateException("Disc match box previews are not ready");
        }
        List<Definition> definitions = new ArrayList<>(definitions(world));
        Definition previous = session.previews.get(id);
        if (id != null && !definitions.contains(previous)) {
            return false;
        }
        if (replacement != null && replacement.equals(previous)) {
            return true;
        }
        if (id == null && (definitions.stream().anyMatch(replacement::atSamePosition)
                || boxes.hasBoxAt(replacement.location(world)))) {
            return false;
        }
        if (id == null) {
            definitions.add(replacement);
        } else if (replacement == null) {
            definitions.remove(previous);
        } else {
            definitions.set(definitions.indexOf(previous), replacement);
        }

        session.updating = true;
        UUID newPreview = null;
        try {
            if (replacement != null) {
                newPreview = addPreview(world, session, replacement);
                if (newPreview == null) {
                    return false;
                }
            }
            store(world, definitions);
        } catch (RuntimeException error) {
            removePreview(session, newPreview);
            throw error;
        } finally {
            session.updating = false;
        }
        removePreview(session, id);
        return true;
    }

    private UUID addPreview(World world, PreviewSession session, Definition definition) {
        Kind kind = definition.kind();
        UUID id = boxes.place(definition.location(world), player -> List.of(), player -> false,
                Component.text(kind.name, kind.color));
        if (previewSessions.get(world) != session) {
            boxes.remove(id);
            return null;
        }
        Entity hitbox = world.getEntity(id);
        if (hitbox != null) {
            hitbox.addScoreboardTag(PREVIEW_TAG);
        }
        session.previews.put(id, definition);
        return id;
    }

    private void removePreview(PreviewSession session, UUID id) {
        if (id != null) {
            session.previews.remove(id);
            boxes.remove(id);
        }
    }

    private static final class PreviewSession {
        private final Map<UUID, Definition> previews = new LinkedHashMap<>();
        private boolean updating;
    }

    private static void store(World world, List<Definition> definitions) {
        PersistentDataContainer data = world.getPersistentDataContainer();
        PersistentDataContainer[] encoded = new PersistentDataContainer[definitions.size()];
        for (int index = 0; index < definitions.size(); index++) {
            Definition definition = definitions.get(index);
            PersistentDataContainer entry = data.getAdapterContext().newPersistentDataContainer();
            entry.set(X, INTEGER, definition.x());
            entry.set(Y, INTEGER, definition.y());
            entry.set(Z, INTEGER, definition.z());
            entry.set(KIND, STRING, definition.kind().name());
            encoded[index] = entry;
        }
        data.set(LAYOUT, TAG_CONTAINER_ARRAY, encoded);
    }
}
