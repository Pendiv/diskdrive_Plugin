package local.disc;

import static org.bukkit.persistence.PersistentDataType.BOOLEAN;
import static org.bukkit.persistence.PersistentDataType.BYTE_ARRAY;
import static org.bukkit.persistence.PersistentDataType.INTEGER;
import static org.bukkit.persistence.PersistentDataType.STRING;
import static org.bukkit.persistence.PersistentDataType.TAG_CONTAINER;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Axis;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.block.data.type.Piston;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityKnockbackByEntityEvent;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class DiscMechanisms implements Listener {
    private static final NamespacedKey PAYLOAD = key("payload");
    private static final NamespacedKey DISPLAY_ID = key("payload_display");
    private static final NamespacedKey ANCHOR_Y = key("anchor_y");
    private static final NamespacedKey TRACKED_TNT = key("tracked_tnt");
    private static final double TARGET_PADDING = 0.28;
    private static final int PISTON_WINDOW_TICKS = 5;
    private static final double PISTON_STRENGTH = 1.25;
    private static final double HORIZONTAL_PISTON_LIFT = 0.33;
    private static final double HORIZONTAL_PISTON_JUMP_SPEED = 0.29;
    private static final double UPWARD_PISTON_JUMP_SPEED = 0.10;

    private final DiscPlugin plugin;
    private final DiscPayloads payloads;
    private final ArenaTrapRecovery recovery;
    private final Set<Block> primingTraps = new HashSet<>();
    private final Map<Block, PistonPulse> pistonPulses = new HashMap<>();
    private final Map<UUID, Movement> movement = new HashMap<>();
    private World lobbyWorld;

    DiscMechanisms(DiscPlugin plugin, DiscPayloads payloads) {
        this.plugin = plugin;
        this.payloads = payloads;
        recovery = new ArenaTrapRecovery(this::restoreTraps, plugin.getLogger());
    }

    void prepareArena(World world) {
        if (DiscArena.isArena(world)) {
            recovery.prepare(world);
        }
    }

    void prepareLobby(World world) {
        if (!DiscLobby.isLobby(world)) {
            throw new IllegalArgumentException("Trap recovery requires the Disc lobby");
        }
        recovery.beginContinuous(world);
        lobbyWorld = world;
    }

    void beginArenaRound(World world) {
        if (!DiscArena.isArena(world)) {
            throw new IllegalArgumentException("Trap recovery requires the Disc arena");
        }
        recovery.begin(world);
    }

    void tickArenaRound() {
        primingTraps.clear();
        recovery.tick();
        tickPistons();
    }

    void finishArenaRound(World world) {
        discardTransientTraps(world, true);
        recovery.end(world, true);
    }

    void finishLobby() {
        World world = lobbyWorld;
        lobbyWorld = null;
        if (world != null) {
            discardTransientTraps(world, false);
            recovery.end(world, false);
        }
    }

    boolean resetTraps(World world) {
        if (!DiscLobby.isLobby(world) && !DiscArena.isArena(world)) {
            return false;
        }
        discardTransientTraps(world, false);
        return recovery.reset(world);
    }

    private void discardTransientTraps(World world, boolean allTnt) {
        primingTraps.removeIf(block -> block.getWorld() == world);
        pistonPulses.keySet().removeIf(block -> block.getWorld() == world);
        if (plugin.getServer().getWorld(world.getUID()) == world) {
            for (TNTPrimed tnt : world.getEntitiesByClass(TNTPrimed.class)) {
                if (allTnt || tnt.getPersistentDataContainer().has(TRACKED_TNT, BOOLEAN)) {
                    tnt.remove();
                }
            }
        }
        payloads.discardIn(world);
    }

    private boolean tracksTraps(World world) {
        return recovery.isTracked(world);
    }

    boolean updatePlacement(Block block, boolean editing) {
        forget(block);
        if (!editing || !canRegister(block)) {
            return false;
        }
        register(block);
        return true;
    }

    private static PersistentDataContainer register(Block block) {
        var chunk = block.getChunk().getPersistentDataContainer();
        var state = chunk.getAdapterContext().newPersistentDataContainer();
        if (block.getType() == Material.DRAGON_EGG) {
            DiscEggs.register(block, state);
        }
        chunk.set(blockKey(block), TAG_CONTAINER, state);
        return state;
    }

    private static boolean canRegister(Block block) {
        return switch (block.getType()) {
            case NOTE_BLOCK, TNT, PISTON, STICKY_PISTON, DRAGON_EGG -> true;
            default -> Tag.LIGHTNING_RODS.isTagged(block.getType()) || isVerticalChain(block);
        };
    }

    private static boolean isRegisteredLightningRod(Block block) {
        return Tag.LIGHTNING_RODS.isTagged(block.getType()) && data(block) != null;
    }

    static boolean isChainItem(Material material) {
        return material == Material.IRON_CHAIN || material.name().endsWith("COPPER_CHAIN");
    }

    private static boolean isVerticalChain(Block block) {
        return isChainItem(block.getType())
                && block.getBlockData() instanceof Orientable chain
                && chain.getAxis() == Axis.Y;
    }

    static boolean isChain(Block block) {
        return isVerticalChain(block) && data(block) != null;
    }

    boolean mount(Block chain, ItemStack item) {
        var state = data(chain);
        if (!isVerticalChain(chain) || state == null || state.has(PAYLOAD, BYTE_ARRAY)
                || !chain.getRelative(BlockFace.DOWN).isPassable()) {
            return false;
        }
        return attach(chain, state, item, item.serializeAsBytes());
    }

    private static boolean attach(Block chain, PersistentDataContainer state, ItemStack item, byte[] storedItem) {
        Location location = hangingPosition(chain);
        ItemDisplay display = chain.getWorld().spawn(location, ItemDisplay.class, entity -> {
            entity.setItemStack(item);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            entity.setGravity(false);
            entity.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(0.65F), new Quaternionf()));
            entity.getPersistentDataContainer().set(ANCHOR_Y, INTEGER, chain.getY());
        });
        if (!display.isValid()) {
            return false;
        }
        try {
            state.set(PAYLOAD, BYTE_ARRAY, storedItem);
            state.set(DISPLAY_ID, STRING, display.getUniqueId().toString());
            chain.getChunk().getPersistentDataContainer().set(blockKey(chain), TAG_CONTAINER, state);
        } catch (RuntimeException error) {
            display.remove();
            throw error;
        }
        return true;
    }

    RayTraceResult trace(Location from, Vector motion, double distance) {
        World world = from.getWorld();
        Vector start = from.toVector();
        RayTraceResult nearest = world.rayTraceBlocks(from, motion, distance, FluidCollisionMode.NEVER, false,
                block -> registeredEgg(block) != null);
        double limit = nearest == null ? distance : nearest.getHitPosition().distance(start);
        Location end = from.clone().add(motion.clone().multiply(limit));
        int minY = Math.max(world.getMinHeight(), Math.min(from.getBlockY(), end.getBlockY()));
        int maxY = Math.min(world.getMaxHeight() - 1, Math.max(from.getBlockY(), end.getBlockY()));
        for (int x = Math.min(from.getBlockX(), end.getBlockX());
                x <= Math.max(from.getBlockX(), end.getBlockX()); x++) {
            for (int z = Math.min(from.getBlockZ(), end.getBlockZ());
                    z <= Math.max(from.getBlockZ(), end.getBlockZ()); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!isChain(block) && !isRegisteredLightningRod(block)) {
                        continue;
                    }
                    BoundingBox shape = block.getBoundingBox()
                            .expand(TARGET_PADDING)
                            .intersection(BoundingBox.of(block));
                    RayTraceResult hit = shape.rayTrace(start, motion, limit);
                    if (hit != null) {
                        limit = hit.getHitPosition().distance(start);
                        nearest = new RayTraceResult(hit.getHitPosition(), block, hit.getHitBlockFace());
                    }
                }
            }
        }
        return nearest;
    }

    boolean hitBlock(
            Block block, Player attacker, DiscType type, UUID throwId,
            Set<Block> struckRods, Set<UUID> hitTargets) {
        if (block == null) {
            return false;
        }
        if (isChain(block)) {
            return cut(block, attacker, throwId);
        }
        PersistentDataContainer egg = registeredEgg(block);
        if (egg != null && hitTargets.add(DiscEggs.id(egg))) {
            return moveEgg(block, egg, DiscEggs.nearbyPlayer(block));
        }
        if (isRegisteredLightningRod(block) && struckRods.add(block)) {
            Location strike = block.getLocation().add(0.5, 1, 0.5);
            block.getWorld().strikeLightning(strike);
        }
        if (type == DiscType.MAGMA && block.getType() == Material.TNT && data(block) != null) {
            return prime(block, attacker, hitTargets);
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggAttack(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || block == null
                || event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        PersistentDataContainer egg = registeredEgg(block);
        if (egg != null) {
            event.setCancelled(true);
            moveEgg(block, egg, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaEggTeleport(BlockFromToEvent event) {
        if (registeredEgg(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggFall(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock
                && event.getTo().isAir() && registeredEgg(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    private static PersistentDataContainer registeredEgg(Block block) {
        if (block.getType() != Material.DRAGON_EGG) {
            return null;
        }
        PersistentDataContainer state = data(block);
        return DiscEggs.id(state) == null ? null : state;
    }

    private static boolean moveEgg(Block source, PersistentDataContainer state, Player companion) {
        Block target = DiscEggs.move(source, state, companion);
        if (target == null) {
            return false;
        }
        source.getChunk().getPersistentDataContainer().remove(blockKey(source));
        target.getChunk().getPersistentDataContainer().set(blockKey(target), TAG_CONTAINER, state);
        return true;
    }

    private boolean cut(Block block, Player attacker, UUID throwId) {
        World world = block.getWorld();
        Location cut = block.getLocation().toCenterLocation();
        Sound breakSound = block.getBlockData().getSoundGroup().getBreakSound();
        List<Block> segments = new ArrayList<>();
        for (Block segment = block; segment.getY() >= world.getMinHeight() && isChain(segment);
             segment = segment.getRelative(BlockFace.DOWN)) {
            segments.add(segment);
        }
        if (tracksTraps(world)) {
            try {
                recovery.remember(world, segments.stream().map(DiscMechanisms::snapshot).toList());
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not record Disc chain before cutting", error);
                return false;
            }
            if (!tracksTraps(world)) {
                return false;
            }
        }
        for (Block segment : segments) {
            ItemStack payload = detach(segment);
            segment.setType(Material.AIR, false);
            if (payload != null) {
                payloads.drop(hangingPosition(segment), payload, attacker, throwId);
            }
        }
        world.playSound(cut, breakSound, 1.0F, 1.2F);
        world.spawnParticle(Particle.CRIT, cut, 8, 0.13, 0.15, 0.13, 0.04);
        return true;
    }

    private boolean prime(Block block, Player attacker, Set<UUID> hitTargets) {
        World world = block.getWorld();
        boolean trackedTrap = tracksTraps(world);
        if (!Boolean.TRUE.equals(world.getGameRuleValue(GameRules.TNT_EXPLODES))
                || !new TNTPrimeEvent(block, TNTPrimeEvent.PrimeCause.PROJECTILE, attacker, null).callEvent()
                || block.getType() != Material.TNT || trackedTrap && !tracksTraps(world)) {
            return false;
        }
        Location location = block.getLocation().add(0.5, 0, 0.5);
        TNTPrimed tnt = world.spawn(location, TNTPrimed.class, entity -> {
            entity.setSource(attacker);
            entity.setFuseTicks(80);
        });
        if (!tnt.isValid()) {
            primingTraps.remove(block);
            return false;
        }
        hitTargets.add(tnt.getUniqueId());
        detach(block);
        block.setType(Material.AIR);
        world.playSound(location, Sound.ENTITY_TNT_PRIMED, 1, 1);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();
        if (!tracksTraps(world) || block.getType() != Material.TNT || data(block) == null) {
            return;
        }
        try {
            recovery.remember(world, List.of(snapshot(block)));
            if (!tracksTraps(world)) {
                event.setCancelled(true);
            } else {
                primingTraps.add(block);
            }
        } catch (RuntimeException error) {
            event.setCancelled(true);
            plugin.getLogger().log(Level.WARNING, "Could not record Disc TNT before priming", error);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTntAdd(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) {
            return;
        }
        boolean trackedTrap = !primingTraps.isEmpty() && primingTraps.remove(tnt.getLocation().getBlock());
        if (trackedTrap) {
            tnt.getPersistentDataContainer().set(TRACKED_TNT, BOOLEAN, true);
        }
        if (trackedTrap || DiscArena.isArena(event.getWorld()) && recovery.isTracked(event.getWorld())) {
            tnt.setPersistent(false);
        }
    }

    private static ArenaTrapRecovery.Trap snapshot(Block block) {
        var state = data(block);
        byte[] payload = state == null ? null : state.get(PAYLOAD, BYTE_ARRAY);
        return new ArenaTrapRecovery.Trap(block.getX(), block.getY(), block.getZ(),
                block.getBlockData().getAsString(), payload);
    }

    private boolean restoreTraps(World world, List<ArenaTrapRecovery.Trap> traps) {
        List<TrapRestore> targets = new ArrayList<>(traps.size());
        for (ArenaTrapRecovery.Trap trap : traps) {
            Block block = world.getBlockAt(trap.x(), trap.y(), trap.z());
            BlockData original = plugin.getServer().createBlockData(trap.blockData());
            boolean chain = isChainItem(original.getMaterial()) && original instanceof Orientable orientable
                    && orientable.getAxis() == Axis.Y;
            byte[] storedItem = trap.payload();
            if (!chain && (original.getMaterial() != Material.TNT || storedItem != null)) {
                throw new IllegalArgumentException("Unexpected block in Disc arena trap recovery");
            }
            Material current = block.getType();
            if (current != original.getMaterial() && !current.isAir()
                    && current != Material.WATER && current != Material.LAVA
                    && current != Material.FIRE && current != Material.SOUL_FIRE) {
                return false;
            }
            ItemStack item = storedItem == null ? null : ItemStack.deserializeBytes(storedItem);
            if (item != null && item.isEmpty()) {
                throw new IllegalArgumentException("Empty mount in Disc arena trap recovery");
            }
            targets.add(new TrapRestore(block, original, item, storedItem));
        }

        boolean complete = true;
        for (TrapRestore target : targets) {
            if (!target.block().getBlockData().equals(target.original())) {
                target.block().setBlockData(target.original(), false);
            }
            if (!restoreMount(target.block(), target.item(), target.storedItem())) {
                complete = false;
            }
        }
        return complete;
    }

    private static boolean restoreMount(Block block, ItemStack item, byte[] storedItem) {
        var state = data(block);
        if (state != null) {
            byte[] currentItem = state.get(PAYLOAD, BYTE_ARRAY);
            ItemDisplay display = mountedDisplay(block, state);
            if (item == null && currentItem == null && !state.has(DISPLAY_ID, STRING)) {
                return true;
            }
            if (item != null && Arrays.equals(storedItem, currentItem) && display != null && display.isValid()
                    && item.equals(display.getItemStack())) {
                return true;
            }
            if (display != null) {
                display.remove();
            }
        }
        var registration = register(block);
        return item == null || attach(block, registration, item, storedItem);
    }

    static boolean canPushTnt(Entity entity, Set<UUID> hitTargets) {
        return entity instanceof TNTPrimed tnt && tnt.getFuseTicks() > 0 && !hitTargets.contains(tnt.getUniqueId());
    }

    static void pushTnt(TNTPrimed tnt, Vector motion, Set<UUID> hitTargets) {
        hitTargets.add(tnt.getUniqueId());
        Vector velocity = motion.clone().multiply(0.85);
        if (Math.abs(velocity.getY()) < 0.1) {
            velocity.setY(0.20);
        }
        tnt.setVelocity(velocity);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStep(PlayerMoveEvent event) {
        if (!event.hasChangedPosition() || event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (event.getFrom().getWorld() == event.getTo().getWorld()) {
            UUID id = event.getPlayer().getUniqueId();
            int tick = plugin.getServer().getCurrentTick();
            Vector displacement = event.getTo().toVector().subtract(event.getFrom().toVector());
            Movement previous = movement.get(id);
            if (previous != null && previous.world() == event.getTo().getWorld()) {
                int elapsed = tick - previous.tick();
                if (elapsed == 0) {
                    displacement.add(previous.displacement());
                } else if (elapsed <= 3) {
                    displacement.multiply(1.0 / elapsed);
                }
            }
            movement.put(id, new Movement(event.getTo().getWorld(), tick, displacement));
        }
        Block under = event.getTo().clone().subtract(0, 0.02, 0).getBlock();
        if (under.getType() != Material.NOTE_BLOCK || Math.abs(event.getTo().getY() - under.getY() - 1) > 0.02
                || under.equals(event.getFrom().clone().subtract(0, 0.02, 0).getBlock()) || data(under) == null) {
            return;
        }
        NoteBlock note = (NoteBlock) under.getBlockData();
        under.getWorld().playNote(under.getLocation().toCenterLocation(), note.getInstrument(), note.getNote());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        Block piston = event.getBlock();
        for (Block moved : event.getBlocks()) {
            forget(moved);
        }
        if (!(piston.getBlockData() instanceof Piston) || data(piston) == null) {
            return;
        }
        BlockFace face = event.getDirection();
        Vector forward = face.getDirection();
        BoundingBox area = BoundingBox.of(piston.getRelative(face))
                .expandDirectional(forward.clone().multiply(1.2)).expand(0.15);
        Vector impulse = forward.multiply(PISTON_STRENGTH);
        if (face.getModY() == 0) {
            impulse.setY(HORIZONTAL_PISTON_LIFT);
        }
        pistonPulses.put(piston, new PistonPulse(face, plugin.getServer().getCurrentTick() + 1,
                area, impulse, new HashSet<>()));
    }

    Location escapePlate(Location from, Vector away, boolean urgent) {
        Location best = null;
        int radius = urgent ? 11 : 7;
        double distance = (radius + 1) * (radius + 1);
        double alignment = urgent ? 0.15 : 0.45;
        World world = from.getWorld();
        int y = from.getBlockY();
        for (int x = from.getBlockX() - radius; x <= from.getBlockX() + radius; x++) {
            for (int z = from.getBlockZ() - radius; z <= from.getBlockZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Block plate = world.getBlockAt(x, y, z);
                if (!plate.getType().name().endsWith("_PRESSURE_PLATE")) {
                    continue;
                }
                Location position = plate.getLocation().add(0.5, 0, 0.5);
                double candidate = from.distanceSquared(position);
                if (candidate < distance && hasEscapePiston(plate, away, alignment)) {
                    distance = candidate;
                    best = position;
                }
            }
        }
        return best;
    }

    private static boolean hasEscapePiston(Block plate, Vector away, double alignment) {
        for (BlockFace direction : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
            BlockFace facing = direction.getOppositeFace();
            if (facing.getDirection().dot(away) <= alignment) {
                continue;
            }
            for (int distance = 1; distance <= 2; distance++) {
                Block piston = plate.getRelative(direction, distance);
                if (piston.getWorld().isChunkLoaded(piston.getX() >> 4, piston.getZ() >> 4)
                        && piston.getBlockData() instanceof Piston state
                        && state.getFacing() == facing
                        && data(piston) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private void tickPistons() {
        int tick = plugin.getServer().getCurrentTick();
        pistonPulses.entrySet().removeIf(entry -> {
            Block piston = entry.getKey();
            PistonPulse pulse = entry.getValue();
            int age = tick - pulse.startTick();
            World world = piston.getWorld();
            if (age >= PISTON_WINDOW_TICKS || !world.isChunkLoaded(piston.getX() >> 4, piston.getZ() >> 4)
                    || !(piston.getBlockData() instanceof Piston state) || state.getFacing() != pulse.face()
                    || data(piston) == null) {
                return true;
            }
            if (age >= 0) {
                for (Entity entity : world.getNearbyEntities(pulse.area())) {
                    if (entity instanceof Player player
                            && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                            && pulse.launched().add(player.getUniqueId())) {
                        launchFromPiston(player, pulse);
                    }
                }
            }
            return false;
        });
    }

    private void launchFromPiston(Player player, PistonPulse pulse) {
        Vector velocity = momentum(player);
        if (pulse.face() != BlockFace.DOWN) {
            // A stronger vertical impulse needs less inherited upward speed for the same extra height.
            double maxJumpSpeed = pulse.face() == BlockFace.UP
                    ? UPWARD_PISTON_JUMP_SPEED
                    : HORIZONTAL_PISTON_JUMP_SPEED;
            velocity.setY(Math.min(velocity.getY(), maxJumpSpeed));
        }
        player.setVelocity(velocity.add(pulse.impulse()));
    }

    @SuppressWarnings("removal")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTntKnockback(EntityKnockbackByEntityEvent event) {
        if (event.getCause() != EntityKnockbackEvent.KnockbackCause.EXPLOSION
                || !(event.getSourceEntity() instanceof TNTPrimed) || !(event.getEntity() instanceof Player player)
                || !DiscLobby.isLobby(player.getWorld()) && !DiscArena.isArena(player.getWorld())) {
            return;
        }
        Vector current = player.getVelocity();
        Vector velocity = momentum(player);
        if (velocity.distanceSquared(current) < 0.000001) {
            return;
        }
        Vector impulse = event.getFinalKnockback().subtract(current);
        if (impulse.lengthSquared() > 0.000001) {
            // The explosion packet adds a delta client-side; send an absolute velocity instead of adding momentum twice.
            event.setCancelled(true);
            player.setVelocity(velocity.add(impulse));
        }
    }

    private Vector momentum(Player player) {
        Vector current = player.getVelocity();
        Movement sample = movement.get(player.getUniqueId());
        if (sample != null && sample.world() == player.getWorld()
                && plugin.getServer().getCurrentTick() - sample.tick() <= 2
                && sample.displacement().lengthSquared() > current.lengthSquared()) {
            return sample.displacement().clone();
        }
        return current;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        movement.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        movement.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        movement.remove(event.getPlayer().getUniqueId());
    }

    private record Movement(World world, int tick, Vector displacement) { }

    private record PistonPulse(BlockFace face, int startTick, BoundingBox area, Vector impulse, Set<UUID> launched) { }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        event.getBlocks().forEach(this::forget);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        forget(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        forget(event.blockList());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof ItemDisplay display)) {
                continue;
            }
            Integer y = display.getPersistentDataContainer().get(ANCHOR_Y, INTEGER);
            if (y == null) {
                continue;
            }
            Location location = display.getLocation();
            Block chain = location.getWorld().getBlockAt(location.getBlockX(), y, location.getBlockZ());
            var state = data(chain);
            if (!isVerticalChain(chain) || state == null
                    || !display.getUniqueId().toString().equals(state.get(DISPLAY_ID, STRING))) {
                display.remove();
            }
        }
    }

    private void forget(List<Block> blocks) {
        blocks.forEach(this::forget);
    }

    private void forget(Block block) {
        ItemStack payload = detach(block);
        if (payload != null) {
            DiscPayloads.dropItem(hangingPosition(block), payload);
        }
    }

    private static ItemStack detach(Block block) {
        var chunk = block.getChunk().getPersistentDataContainer();
        var key = blockKey(block);
        var previous = chunk.get(key, TAG_CONTAINER);
        if (previous == null) {
            return null;
        }
        chunk.remove(key);
        ItemDisplay display = mountedDisplay(block, previous);
        if (display != null) {
            display.remove();
        }
        byte[] bytes = previous.get(PAYLOAD, BYTE_ARRAY);
        return bytes == null ? null : ItemStack.deserializeBytes(bytes);
    }

    private static ItemDisplay mountedDisplay(Block block, PersistentDataContainer state) {
        String id = state.get(DISPLAY_ID, STRING);
        return id != null && block.getWorld().getEntity(UUID.fromString(id)) instanceof ItemDisplay display
                ? display
                : null;
    }

    private static PersistentDataContainer data(Block block) {
        return block.getChunk().getPersistentDataContainer().get(blockKey(block), TAG_CONTAINER);
    }

    private static Location hangingPosition(Block block) {
        return block.getLocation().add(0.5, -0.40, 0.5);
    }

    private static NamespacedKey blockKey(Block block) {
        return key("trap/" + (block.getX() & 15) + "_" + block.getY() + "_" + (block.getZ() & 15));
    }

    private static NamespacedKey key(String value) {
        return new NamespacedKey("disc", value);
    }

    private record TrapRestore(Block block, BlockData original, ItemStack item, byte[] storedItem) { }
}
