package local.disc;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

final class MatchBots implements Listener {
    private final DiscPlugin plugin;
    private final Map<UUID, Body> bodies = new HashMap<>();
    private NativeAccess access;

    MatchBots(DiscPlugin plugin) {
        this.plugin = plugin;
    }

    Body spawn(UUID id, String name, Location location) {
        if (access == null) {
            access = new NativeAccess();
        }
        Body body = access.create(id, name, location);
        bodies.put(id, body);
        try {
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                access.send(viewer, body.info);
            }
            access.add(body);
            return body;
        } catch (RuntimeException error) {
            remove(body);
            throw error;
        }
    }

    void remove(Body body) {
        UUID id = body.player.getUniqueId();
        if (bodies.remove(id, body)) {
            try {
                access.remove(body);
            } finally {
                Object packet = construct(access.removeInfo, List.of(id));
                for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                    access.send(viewer, packet);
                }
            }
        }
    }

    @EventHandler
    public void onTrack(PlayerTrackEntityEvent event) {
        Body body = bodies.get(event.getEntity().getUniqueId());
        if (body != null && !bodies.containsKey(event.getPlayer().getUniqueId())) {
            access.send(event.getPlayer(), body.info);
        }
    }

    static final class ArenaChunks implements AutoCloseable {
        private final ItemBoxes boxes;
        private final World world;
        private final List<Chunk> held = new ArrayList<>();

        ArenaChunks(ItemBoxes boxes, World world) {
            this.boxes = boxes;
            this.world = world;
            int edge = DiscArena.edge(world);
            try {
                for (int x = -edge >> 4; x <= edge >> 4; x++) {
                    for (int z = -edge >> 4; z <= edge >> 4; z++) {
                        boxes.retainChunk(world, x, z);
                        held.add(new Chunk(x, z));
                    }
                }
            } catch (RuntimeException error) {
                close();
                throw error;
            }
        }

        @Override
        public void close() {
            held.forEach(chunk -> boxes.releaseChunk(world, chunk.x, chunk.z));
            held.clear();
        }

        private record Chunk(int x, int z) { }
    }

    final class Body {
        final Player player;
        private final Object nativePlayer;
        private final Object world;
        private final Object channel;
        private final Object info;
        private boolean added;

        private Body(Player player, Object nativePlayer, Object world, Object channel, Object info) {
            this.player = player;
            this.nativePlayer = nativePlayer;
            this.world = world;
            this.channel = channel;
            this.info = info;
        }

        void move(Vector velocity) {
            access.move(this, velocity);
        }
    }

    // The bot uses a real ServerPlayer so Disc damage, equipment, and game mechanics share the player path.
    private final class NativeAccess {
        private final Class<?> nativePlayer = internal("net.minecraft.server.level.ServerPlayer");
        private final Class<?> nativeWorld = internal("net.minecraft.server.level.ServerLevel");
        private final Class<?> nativeServer = internal("net.minecraft.server.MinecraftServer");
        private final Class<?> profile = internal("com.mojang.authlib.GameProfile");
        private final Class<?> clientInformation = internal("net.minecraft.server.level.ClientInformation");
        private final Class<?> packetFlow = internal("net.minecraft.network.protocol.PacketFlow");
        private final Class<?> connection = internal("net.minecraft.network.Connection");
        private final Class<?> cookie = internal("net.minecraft.server.network.CommonListenerCookie");
        private final Class<?> packetListener = internal("net.minecraft.server.network.ServerGamePacketListenerImpl");
        private final Class<?> loadedPacket =
                internal("net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket");
        private final Class<?> vec = internal("net.minecraft.world.phys.Vec3");
        private final Class<?> mover = internal("net.minecraft.world.entity.MoverType");
        private final Class<?> channel = internal("io.netty.channel.embedded.EmbeddedChannel");

        private final Constructor<?> makePlayer = constructor(
                nativePlayer, nativeServer, nativeWorld, profile, clientInformation);
        private final Constructor<?> makeProfile = constructor(profile, UUID.class, String.class);
        private final Constructor<?> makeConnection = constructor(connection, packetFlow);
        private final Constructor<?> makeCookieListener = constructor(
                packetListener, nativeServer, connection, nativePlayer, cookie);
        private final Constructor<?> makeChannel = constructor(channel);
        private final Constructor<?> makeLoaded = constructor(loadedPacket);
        private final Constructor<?> makeVector = constructor(vec, double.class, double.class, double.class);
        private final Constructor<?> removeInfo = constructor(
                internal("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket"), List.class);

        private final Method createInfo = method(
                internal("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket"),
                "createPlayerInitializing", java.util.Collection.class);
        private final Method defaultClient = method(clientInformation, "createDefault");
        private final Method createCookie = method(cookie, "createInitial", profile, boolean.class);
        private final Method snap = method(
                nativePlayer, "snapTo", double.class, double.class, double.class, float.class, float.class);
        private final Method bukkit = method(nativePlayer, "getBukkitEntity");
        private final Method addPlayer = method(nativeWorld, "addNewPlayer", nativePlayer);
        private final Method removePlayer = method(nativeWorld, "removePlayerImmediately", nativePlayer,
                internal("net.minecraft.world.entity.Entity$RemovalReason"));
        private final Method markLoaded = method(packetListener, "handleAcceptPlayerLoad", loadedPacket);
        private final Method resetPosition = method(packetListener, "resetPosition");
        private final Method nativeMove = method(nativePlayer, "move", mover, vec);
        private final Method setMotion = method(nativePlayer, "setDeltaMovement", vec);
        private final Method doTick = method(nativePlayer, "doTick");
        private final Method send = method(packetListener, "send", internal("net.minecraft.network.protocol.Packet"));
        private final Method readOutbound = method(channel, "readOutbound");
        private final Method closeChannel = method(channel, "finishAndReleaseAll");
        private final Method release = method(internal("io.netty.util.ReferenceCountUtil"), "release", Object.class);

        private final Field playerConnection = field(nativePlayer, "connection");
        private final Field connectionChannel = field(connection, "channel");

        private final Object inbound = constant(packetFlow, "SERVERBOUND");
        private final Object self = constant(mover, "SELF");
        private final Object discarded = constant(
                internal("net.minecraft.world.entity.Entity$RemovalReason"), "DISCARDED");
        private final Object still = construct(makeVector, 0.0, 0.0, 0.0);
        private final Object server = call(method(plugin.getServer().getClass(), "getServer"), plugin.getServer());
        private final Map<UUID, Object> players = loadPlayers();

        @SuppressWarnings("unchecked")
        private Map<UUID, Object> loadPlayers() {
            Object list = call(method(nativeServer, "getPlayerList"), server);
            return (Map<UUID, Object>) call(method(list.getClass(), "getPlayersByUUID"), list);
        }

        private Body create(UUID id, String name, Location location) {
            Object world = call(method(location.getWorld().getClass(), "getHandle"), location.getWorld());
            Object gameProfile = construct(makeProfile, id, name);
            Object player = construct(makePlayer, server, world, gameProfile, call(defaultClient, null));
            Object net = construct(makeConnection, inbound);
            Object sink = construct(makeChannel);
            try {
                put(connectionChannel, net, sink);
                Object listener = construct(
                        makeCookieListener, server, net, player, call(createCookie, null, gameProfile, false));
                put(playerConnection, player, listener);
                call(snap, player,
                        location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                call(resetPosition, listener);
                call(markLoaded, listener, construct(makeLoaded));
                Object info = call(createInfo, null, List.of(player));
                return new Body((Player) call(bukkit, player), player, world, sink, info);
            } catch (RuntimeException error) {
                call(closeChannel, sink);
                throw error;
            }
        }

        private void add(Body body) {
            if (players.putIfAbsent(body.player.getUniqueId(), body.nativePlayer) != null) {
                throw new IllegalStateException("Bot UUID already in use");
            }
            call(addPlayer, body.world, body.nativePlayer);
            body.added = true;
        }

        private void remove(Body body) {
            try {
                if (body.added) {
                    call(removePlayer, body.world, body.nativePlayer, discarded);
                }
            } finally {
                players.remove(body.player.getUniqueId(), body.nativePlayer);
                call(closeChannel, body.channel);
            }
        }

        private void send(Player viewer, Object packet) {
            Object handle = call(method(viewer.getClass(), "getHandle"), viewer);
            Object listener = get(playerConnection, handle);
            if (listener != null) {
                call(send, listener, packet);
            }
        }

        private void move(Body body, Vector velocity) {
            call(setMotion, body.nativePlayer, still);
            call(doTick, body.nativePlayer);
            Object motion = construct(makeVector, velocity.getX(), velocity.getY(), velocity.getZ());
            call(setMotion, body.nativePlayer, motion);
            call(nativeMove, body.nativePlayer, self, motion);
            Object queued;
            while ((queued = call(readOutbound, body.channel)) != null) {
                call(release, null, queued);
            }
        }
    }

    private static Class<?> internal(String name) {
        try {
            return Class.forName(name);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Missing Paper bot type " + name, error);
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... args) {
        try {
            return type.getMethod(name, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Missing Paper bot method " + name, error);
        }
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... args) {
        try {
            return type.getConstructor(args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Missing Paper bot constructor " + type.getSimpleName(), error);
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getField(name);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Missing Paper bot field " + name, error);
        }
    }

    private static Object constant(Class<?> type, String name) {
        return get(field(type, name), null);
    }

    private static Object get(Field field, Object target) {
        try {
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not read Paper bot field " + field.getName(), error);
        }
    }

    private static void put(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not set Paper bot field " + field.getName(), error);
        }
    }

    private static Object construct(Constructor<?> constructor, Object... args) {
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "Could not construct " + constructor.getDeclaringClass().getSimpleName(), error);
        }
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not invoke Paper bot method " + method.getName(), error);
        }
    }
}
