package local.disc;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class DiscPlugin extends JavaPlugin implements Listener {
    static final NamespacedKey RETURN_COOLDOWN_GROUP = new NamespacedKey("disc", "return");
    static final NamespacedKey OFF_HAND_COOLDOWN_GROUP = new NamespacedKey("disc", "off_hand_return");
    static final NamespacedKey FRAGMENT_COOLDOWN_GROUP = new NamespacedKey("disc", "fragment");
    static final int THROW_COOLDOWN_TICKS = 40;
    static final int FRAGMENT_COOLDOWN_TICKS = 20;
    private static final NamespacedKey TEMPORARY_GLOW = new NamespacedKey("disc", "temporary_glow");
    private static final int RETURN_COOLDOWN_TICKS = 20;
    private static final int WARDEN_COOLDOWN_TICKS = 600;
    private static final int ENERGY_HUD_REFRESH_TICKS = 20;

    private final Set<DiscThrow> flights = new LinkedHashSet<>();
    private final Map<UUID, ShownHud> shownHud = new HashMap<>();
    private final Map<UUID, Integer> lastDualThrowInput = new HashMap<>();
    private final Set<UUID> cooldownMetadataChanges = new HashSet<>();
    private final DiscEffects effects = new DiscEffects();
    private final DiscDummies dummies = new DiscDummies();
    private final DiscShields shields = new DiscShields();
    private final DiscPayloads payloads = new DiscPayloads();
    private final DiscMechanisms mechanisms = new DiscMechanisms(this, payloads);
    private final DiscHealing healing = new DiscHealing();
    private final MatchBots bots = new MatchBots(this);
    private DiscEditor editor;
    private DashPads dashPads;
    private DiscItem discItem;
    private ItemBoxes itemBoxes;
    private DiscMatch match;
    private DiscRoleMovement roleMovement;

    @Override
    public void onEnable() {
        discItem = new DiscItem(this);
        itemBoxes = new ItemBoxes(this, discItem);
        MatchBoxLayout matchBoxLayout = new MatchBoxLayout(itemBoxes);
        match = new DiscMatch(this, discItem, itemBoxes, matchBoxLayout, mechanisms, bots);
        roleMovement = new DiscRoleMovement(match::activeRole);
        editor = new DiscEditor(this, itemBoxes, match, mechanisms, matchBoxLayout);
        dashPads = new DashPads(this, editor::isEditing);
        registerCommand("disc", "投擲ディスクを受け取る", discItem);
        registerCommand("discmatch", "投擲ディスクの試合", match);
        registerCommand("edit", "試合場と特殊ブロックの編集", editor);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(effects, this);
        getServer().getPluginManager().registerEvents(dummies, this);
        getServer().getPluginManager().registerEvents(shields, this);
        getServer().getPluginManager().registerEvents(itemBoxes, this);
        getServer().getPluginManager().registerEvents(editor, this);
        getServer().getPluginManager().registerEvents(dashPads, this);
        getServer().getPluginManager().registerEvents(mechanisms, this);
        getServer().getPluginManager().registerEvents(payloads, this);
        getServer().getPluginManager().registerEvents(healing, this);
        getServer().getPluginManager().registerEvents(roleMovement, this);
        getServer().getPluginManager().registerEvents(bots, this);
        getServer().getPluginManager().registerEvents(match, this);
        dummies.start(getServer());
        dashPads.start();
        getServer().getScheduler().runTaskTimer(this, this::tickFlights, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, dashPads::updateVisibility, 1L, 5L);
        getServer().getScheduler().runTaskTimer(this, this::showPlayerHud, 1L, 2L);
    }

    @Override
    public void onDisable() {
        if (editor != null) {
            editor.close();
        }
        if (match != null) {
            match.close();
        }
        mechanisms.finishLobby();
        for (DiscThrow flight : List.copyOf(flights)) {
            returnFlight(flight);
        }
        effects.clear();
        itemBoxes.clear();
        if (dashPads != null) {
            dashPads.close();
        }
        payloads.clear();
        for (Player player : getServer().getOnlinePlayers()) {
            clearPlayerEffects(player);
            clearHud(player);
        }
    }

    boolean hasFieldEditors(String canonicalTarget) {
        return editor != null && editor.hasFieldEditors(canonicalTarget);
    }

    boolean isFieldEditing(Player player) {
        return editor != null && editor.isFieldEditing(player);
    }

    boolean receivesBoxFragment(Player player) {
        return match.activeRole(player) == MatchRole.RECONSTRUCTOR;
    }

    boolean leaveFieldEditor(Player player) {
        return editor != null && editor.leaveField(player);
    }

    /** Places a box above the given floor position on the server thread. The provider supplies the opener's rewards. */
    public UUID placeItemBox(Location ground, Function<Player, List<ItemStack>> rewards) {
        requireActiveServerThread();
        return itemBoxes.place(ground, rewards);
    }

    /** Removes an unopened box on the server thread. */
    public boolean removeItemBox(UUID id) {
        requireActiveServerThread();
        return itemBoxes.remove(id);
    }

    private void requireActiveServerThread() {
        if (!isEnabled() || !getServer().isPrimaryThread()) {
            throw new IllegalStateException(
                    "Item box placement and removal require the enabled plugin's server thread");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if ((action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
                || event.useItemInHand() == Event.Result.DENY || !discItem.matches(event.getItem())) {
            return;
        }
        if (event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.JUKEBOX) {
            return;
        }

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        if (hand == null || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        event.setCancelled(true);
        throwFromInput(player, hand);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemBoxInteract(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();
        if (!itemBoxes.contains(event.getRightClicked()) || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        EquipmentSlot hand = event.getHand();
        if (hand == EquipmentSlot.HAND && !discItem.matches(inventory.getItemInMainHand())) {
            // The box can consume the main-hand interaction before the client tries the off hand.
            hand = EquipmentSlot.OFF_HAND;
        }
        if (discItem.matches(inventory.getItem(hand))) {
            event.setCancelled(true);
            throwFromInput(player, hand);
        }
    }

    private void throwFromInput(Player player, EquipmentSlot hand) {
        int tick = getServer().getCurrentTick();
        boolean reconstructor = match.activeRole(player) == MatchRole.RECONSTRUCTOR;
        Integer last = lastDualThrowInput.get(player.getUniqueId());
        if (last != null && tick - last >= 0 && tick - last <= (reconstructor ? 0 : 1)) {
            return;
        }
        boolean paired = match.activeRole(player) == MatchRole.MORE_THROWER || hasFragmentPair(player.getInventory());
        boolean thrown = throwDisc(player, hand)
                || paired && hand == EquipmentSlot.HAND && throwDisc(player, EquipmentSlot.OFF_HAND);
        if (paired && thrown) {
            lastDualThrowInput.put(player.getUniqueId(), tick);
        }
    }

    private boolean hasFragmentPair(PlayerInventory inventory) {
        DiscType main = discItem.type(inventory.getItemInMainHand());
        DiscType off = discItem.type(inventory.getItemInOffHand());
        return main != null && off != null && main != off && (main == DiscType.FRAGMENT || off == DiscType.FRAGMENT);
    }

    static NamespacedKey throwCooldownGroup(boolean offHand) {
        return offHand ? OFF_HAND_COOLDOWN_GROUP : RETURN_COOLDOWN_GROUP;
    }

    static NamespacedKey throwCooldownGroup(DiscType type, boolean offHand) {
        return type == DiscType.FRAGMENT ? FRAGMENT_COOLDOWN_GROUP : throwCooldownGroup(offHand);
    }

    boolean canAttemptThrow(Player player) {
        return match.canThrow(player) && !healing.canHeal(player);
    }

    private boolean throwReady(Player player, DiscType type, boolean offHand) {
        return type == DiscType.FRAGMENT && match.activeRole(player) == MatchRole.RECONSTRUCTOR
                || player.getCooldown(throwCooldownGroup(type, offHand)) == 0;
    }

    boolean throwDisc(Player player, EquipmentSlot hand) {
        MatchRole role = match.activeRole(player);
        boolean moreThrower = role == MatchRole.MORE_THROWER;
        boolean offHandCooldown = moreThrower && hand == EquipmentSlot.OFF_HAND;
        if (!canAttemptThrow(player)) {
            return false;
        }

        PlayerInventory inventory = player.getInventory();
        DiscType type = discItem.type(inventory.getItem(hand));
        if (type == null || !throwReady(player, type, offHandCooldown)) {
            return false;
        }
        if (type == DiscType.WARDEN && wardenUnavailable(player, offHandCooldown)) {
            return false;
        }
        discItem.refreshMetadata(player, moreThrower);
        ItemStack held = inventory.getItem(hand);
        int slot = hand == EquipmentSlot.OFF_HAND ? DiscItem.OFF_HAND_SLOT : inventory.getHeldItemSlot();
        DiscThrow flight = new DiscThrow(player, hand, slot, held.asOne(), type, itemBoxes, mechanisms, role);
        inventory.setItem(slot, held.getAmount() == 1 ? null : held.asQuantity(held.getAmount() - 1));
        flights.add(flight);
        if (type == DiscType.FRAGMENT) {
            if (role == MatchRole.RECONSTRUCTOR) {
                player.setCooldown(FRAGMENT_COOLDOWN_GROUP, 0);
            } else {
                raiseCooldown(player, FRAGMENT_COOLDOWN_GROUP, FRAGMENT_COOLDOWN_TICKS);
            }
        } else {
            startSharedCooldown(player, type, offHandCooldown, THROW_COOLDOWN_TICKS);
        }
        match.recordThrow(player);
        player.swingHand(hand);
        if (type == DiscType.WARDEN) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.9F, 1.0F);
        } else {
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.8F, 1.35F);
        }
        return true;
    }

    private boolean wardenUnavailable(Player player, boolean offHandCooldown) {
        return player.getCooldown(DiscItem.wardenGroup(offHandCooldown)) > 0 || flights.stream()
                .anyMatch(flight -> flight.owner() == player && flight.type() == DiscType.WARDEN);
    }

    int botDiscSlot(Player player, double distance, DiscType preferred) {
        return botDiscSlot(player, distance, preferred, true);
    }

    int botDiscSlot(Player player, double distance, DiscType preferred, boolean allowWarden) {
        return botDiscSlot(player, distance, false, preferred, allowWarden);
    }

    int botBankDiscSlot(Player player, double distance, DiscType preferred) {
        return botDiscSlot(player, distance, true, preferred, false);
    }

    private int botDiscSlot(Player player, double distance, boolean bank, DiscType preferred, boolean allowWarden) {
        if (!canAttemptThrow(player)) {
            return -1;
        }
        int slot = -1;
        double best = -1;
        PlayerInventory inventory = player.getInventory();
        MatchRole role = match.activeRole(player);
        boolean dual = role == MatchRole.MORE_THROWER;
        for (int index = 0; index < 9; index++) {
            DiscType type = discItem.type(inventory.getItem(index));
            if ((!allowWarden && type == DiscType.WARDEN)
                    || (role == MatchRole.RECONSTRUCTOR && type == DiscType.FRAGMENT)
                    || !botCanUseDisc(player, type, distance, bank, false)) {
                continue;
            }
            if (type == preferred) {
                return index;
            }
            double utility = type.damage() + (type == DiscType.LEVITATION && distance < 9 ? 2 : 0);
            if (utility > best) {
                best = utility;
                slot = index;
            }
        }
        if (slot < 0) {
            DiscType type = discItem.type(inventory.getItemInOffHand());
            if ((dual || type == DiscType.FRAGMENT && role != MatchRole.RECONSTRUCTOR)
                    && (allowWarden || type != DiscType.WARDEN)
                    && botCanUseDisc(player, type, distance, bank, dual)) {
                return DiscItem.OFF_HAND_SLOT;
            }
        }
        return slot;
    }

    boolean botHasDisc(Player player, DiscType type) {
        return botFindDisc(player, type) >= 0 || ownedFlights(player).stream()
                .anyMatch(flight -> discItem.type(flight.item()) == type);
    }

    int botFragmentCount(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack offHand = inventory.getItemInOffHand();
        int count = discItem.type(offHand) == DiscType.FRAGMENT ? offHand.getAmount() : 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (discItem.type(item) == DiscType.FRAGMENT) {
                count += item.getAmount();
            }
        }
        return count;
    }

    int botFragmentSlot(Player player) {
        return throwReady(player, DiscType.FRAGMENT, false)
                && discItem.type(player.getInventory().getItemInOffHand()) == DiscType.FRAGMENT
                ? DiscItem.OFF_HAND_SLOT : -1;
    }

    void equipBotFragments(Player player) {
        int slot = botFindDisc(player, DiscType.FRAGMENT);
        if (slot < 0 || slot == DiscItem.OFF_HAND_SLOT) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack fragments = inventory.getItem(slot);
        if (player.hasActiveItem() && player.getActiveItemHand() == EquipmentSlot.OFF_HAND) {
            player.clearActiveItem();
        }
        inventory.setItem(slot, inventory.getItemInOffHand());
        inventory.setItemInOffHand(fragments);
    }

    double botBaseDamage(Player player, DiscType type) {
        MatchRole role = match.activeRole(player);
        double raw = type == DiscType.WARDEN ? DiscFlight.shockwaveDamage(role) : type.damage();
        if (type == DiscType.FRAGMENT && role == MatchRole.RECONSTRUCTOR) {
            raw += DiscMatch.RECONSTRUCTOR_FRAGMENT_BONUS;
        }
        return match.isSuddenDeath(player) ? raw * DiscMatch.SUDDEN_DEATH_DAMAGE : raw;
    }

    int botWardenSlot(Player player) {
        return !throwReady(player, DiscType.WARDEN, false) || wardenUnavailable(player, false)
                ? -1 : botFindDisc(player, DiscType.WARDEN);
    }

    private int botFindDisc(Player player, DiscType type) {
        PlayerInventory inventory = player.getInventory();
        if (discItem.type(inventory.getItemInOffHand()) == type) {
            return DiscItem.OFF_HAND_SLOT;
        }
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            if (discItem.type(storage[slot]) == type) {
                return slot;
            }
        }
        return -1;
    }

    private boolean botCanUseDisc(Player player, DiscType type, double distance, boolean bank, boolean offHand) {
        if (type == null || type == DiscType.MAGMA || !throwReady(player, type, offHand)) {
            return false;
        }
        return bank
                ? type.returnsToOwner()
                        && type != DiscType.WARDEN && type != DiscType.HOMING && type != DiscType.SNIPE
                : distance <= DiscFlight.directRange(type)
                        && (type != DiscType.WARDEN || !wardenUnavailable(player, offHand));
    }

    DiscType botDiscType(Player player, int slot) {
        return discItem.type(player.getInventory().getItem(slot));
    }

    boolean isHealing(Player player) {
        return healing.isCharging(player);
    }

    boolean useBotRoleMovement(Player player, Vector direction) {
        return roleMovement.useForBot(player, direction);
    }

    Location escapeBoost(Location origin, Vector away, boolean urgent) {
        Location pad = dashPads.escapePad(origin, away, urgent);
        if (reachableBoost(origin, pad)) {
            return pad;
        }
        Location plate = mechanisms.escapePlate(origin, away, urgent);
        return reachableBoost(origin, plate) ? plate : null;
    }

    private static boolean reachableBoost(Location from, Location goal) {
        if (goal == null) {
            return false;
        }
        if (from.distanceSquared(goal) < 0.5) {
            return true;
        }
        List<Location> path = BotPath.find(from, goal);
        return !path.isEmpty() && path.getLast().distanceSquared(goal) < 0.5;
    }

    void botStep(Player player, Location from, Location to) {
        dashPads.step(player, from, to);
    }

    void stopBot(Player player) {
        returnDiscs(player);
        clearPlayerEffects(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMelee(PrePlayerAttackEntityEvent event) {
        if (!itemBoxes.contains(event.getAttacked())
                && discItem.matches(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        clearPlayerEffects(event.getEntity());
        for (DiscThrow flight : ownedFlights(event.getEntity())) {
            if (!removeFlight(flight) || !flight.type().returnsToOwner() || match.keepFlightForRespawn(flight)) {
                continue;
            }
            if (event.getKeepInventory()) {
                restoreItem(flight.owner(), flight.slot(), flight.item());
            } else {
                event.getDrops().add(flight.item());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shownHud.remove(event.getPlayer().getUniqueId());
        cooldownMetadataChanges.remove(event.getPlayer().getUniqueId());
        clearPlayerEffects(event.getPlayer());
        returnDiscs(event.getPlayer());
    }

    private void showPlayerHud() {
        int tick = getServer().getCurrentTick();
        for (Player player : getServer().getOnlinePlayers()) {
            boolean moreThrower = match.activeRole(player) == MatchRole.MORE_THROWER;
            if (cooldownMetadataChanges.remove(player.getUniqueId())) {
                discItem.refreshMetadata(player, moreThrower);
            } else if (moreThrower) {
                discItem.refreshOffHandMetadata(player, true);
            }
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                clearHud(player);
                continue;
            }
            int main = heldCooldown(player, EquipmentSlot.HAND, false);
            PlayerInventory inventory = player.getInventory();
            boolean showOff = moreThrower || discItem.type(inventory.getItemInOffHand()) == DiscType.FRAGMENT
                    || hasFragmentPair(inventory);
            int off = showOff ? heldCooldown(player, EquipmentSlot.OFF_HAND, moreThrower) : 0;
            int energy = match.energy(player);
            Component hud = energy < 0 ? null : DiscEnergy.display(energy, player.hasResourcePack());
            if (main > 0 || off > 0) {
                String text = showOff ? "メイン " + cooldownText(main) + "  |  オフ " + cooldownText(off)
                        : "CT " + cooldownText(main);
                Component cooldown = Component.text(text, NamedTextColor.GRAY);
                hud = hud == null ? cooldown : Component.empty().append(hud).append(Component.space()).append(cooldown);
            }
            if (hud == null) {
                clearHud(player);
                continue;
            }
            UUID id = player.getUniqueId();
            ShownHud previous = shownHud.get(id);
            if (previous == null || !hud.equals(previous.content())
                    || energy >= 0 && tick - previous.sentAt() >= ENERGY_HUD_REFRESH_TICKS) {
                player.sendActionBar(hud);
                shownHud.put(id, new ShownHud(hud, tick));
            }
        }
    }

    private int heldCooldown(Player player, EquipmentSlot hand, boolean offHandCooldown) {
        DiscType type = discItem.type(player.getInventory().getItem(hand));
        if (type == null) {
            return 0;
        }
        int ticks = player.getCooldown(throwCooldownGroup(type, offHandCooldown));
        return type == DiscType.WARDEN
                ? Math.max(ticks, player.getCooldown(DiscItem.wardenGroup(offHandCooldown)))
                : ticks;
    }

    private static String cooldownText(int ticks) {
        int tenths = (ticks + 1) / 2;
        return tenths / 10 + "." + tenths % 10 + "秒";
    }

    @EventHandler
    public void onInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        if (discItem.needsMetadataRefresh(event.getNewItemStack())) {
            cooldownMetadataChanges.add(event.getPlayer().getUniqueId());
        }
    }

    private void clearHud(Player player) {
        if (shownHud.remove(player.getUniqueId()) != null) {
            player.sendActionBar(Component.empty());
        }
    }

    private record ShownHud(Component content, int sentAt) { }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clearPlayerEffects(event.getPlayer());
        returnDiscs(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                discItem.refreshMetadata(player, match.activeRole(player) == MatchRole.MORE_THROWER);
            }
        });
    }

    private void tickFlights() {
        match.tick();
        List<Player> healingPlayers = new ArrayList<>(getServer().getOnlinePlayers());
        healingPlayers.addAll(match.botPlayers());
        healing.tick(healingPlayers, match::canThrow, match::activeRole);
        healingPlayers.forEach(player -> roleMovement.tick(player, match.activeRole(player)));
        healingPlayers.forEach(this::updateGlow);
        dummies.tick();
        itemBoxes.tick();
        for (DiscThrow flight : List.copyOf(flights)) {
            if (flights.contains(flight) && !flight.tick()) {
                returnFlight(flight);
            }
        }
        effects.tick();
    }

    private void updateGlow(Player player) {
        if (healing.isCharging(player) || match.isSuddenDeath(player)) {
            // Keep timed Glowing potion effects independent and clear our flag when play ends.
            if (!player.isGlowing()) {
                player.getPersistentDataContainer().set(TEMPORARY_GLOW, PersistentDataType.BYTE, (byte) 1);
                player.setGlowing(true);
            }
        } else {
            clearGlow(player);
        }
    }

    void clearPlayerEffects(Player player) {
        healing.stop(player);
        lastDualThrowInput.remove(player.getUniqueId());
        if (roleMovement != null) {
            roleMovement.clear(player);
        }
        clearGlow(player);
    }

    private static void clearGlow(Player player) {
        var data = player.getPersistentDataContainer();
        if (data.has(TEMPORARY_GLOW, PersistentDataType.BYTE)) {
            data.remove(TEMPORARY_GLOW);
            player.setGlowing(false);
        }
    }

    void giveMissingDiscs(Player player) {
        discItem.giveMissing(player, ownedFlights(player));
    }

    private List<DiscThrow> ownedFlights(Player owner) {
        return flights.stream().filter(flight -> flight.owner() == owner).toList();
    }

    void upgradeDarknessThrow(Player owner, UUID throwId) {
        for (DiscThrow flight : flights) {
            if (flight.owner() == owner && flight.id().equals(throwId)
                    && discItem.type(flight.item()) == DiscType.DARKNESS) {
                flight.replaceReturnItem(discItem.create(DiscType.WARDEN));
                owner.sendMessage("暗闇DiscがウォーデンDiscに進化しました。");
                return;
            }
        }
    }

    void returnDiscs(Player owner) {
        for (DiscThrow flight : ownedFlights(owner)) {
            returnFlight(flight);
        }
    }

    private void returnFlight(DiscThrow flight) {
        if (removeFlight(flight) && flight.type().returnsToOwner()) {
            restoreItem(flight.owner(), flight.slot(), flight.item());
            startReturnCooldown(flight.owner(), flight.type(), flight.slot());
        }
    }

    private boolean removeFlight(DiscThrow flight) {
        if (!flights.remove(flight)) {
            return false;
        }
        flight.removeDisplays();
        return true;
    }

    void startReturnCooldown(Player player, DiscType type, int slot) {
        if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        boolean moreThrower = match.activeRole(player) == MatchRole.MORE_THROWER;
        discItem.refreshMetadata(player, moreThrower);
        if (type == DiscType.WARDEN) {
            int ticks = match.cooldownTicks(player, WARDEN_COOLDOWN_TICKS);
            player.setCooldown(DiscItem.WARDEN_COOLDOWN_GROUP, ticks);
            if (moreThrower) {
                player.setCooldown(DiscItem.OFF_HAND_WARDEN_GROUP, ticks);
            }
            match.recordWardenCooldown(player, ticks);
        }
        startSharedCooldown(player, type, moreThrower && slot == DiscItem.OFF_HAND_SLOT, RETURN_COOLDOWN_TICKS);
    }

    private void startSharedCooldown(Player player, DiscType type, boolean offHand, int baseTicks) {
        if (match.isSuddenDeath(player)) {
            return;
        }
        int ticks = match.activeRole(player).discCooldownTicks(type, baseTicks);
        raiseCooldown(player, throwCooldownGroup(offHand), ticks);
        raiseCooldown(player, DiscItem.wardenGroup(offHand), ticks);
    }

    private static void raiseCooldown(Player player, NamespacedKey group, int ticks) {
        if (player.getCooldown(group) < ticks) {
            player.setCooldown(group, ticks);
        }
    }

    void restoreItem(Player owner, int slot, ItemStack item) {
        PlayerInventory inventory = owner.getInventory();
        if (slot >= 0) {
            ItemStack previousSlot = inventory.getItem(slot);
            if (previousSlot == null || previousSlot.isEmpty()) {
                inventory.setItem(slot, item);
                return;
            }
        }
        for (ItemStack remaining : inventory.addItem(item).values()) {
            owner.getWorld().dropItem(owner.getLocation(), remaining, drop -> drop.setOwner(owner.getUniqueId()));
        }
    }
}
