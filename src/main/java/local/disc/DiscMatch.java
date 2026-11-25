package local.disc;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.event.player.PlayerItemGroupCooldownEvent;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.keys.tags.DamageTypeTagKeys;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

final class DiscMatch implements BasicCommand, Listener {
    private static final int COUNTDOWN_TICKS = 20 * 3;
    private static final int RESPAWN_TICKS = 20 * 3;
    private static final int DEATH_CREDIT_TICKS = 20 * 8;
    private static final int NO_THROW_TICKS = 20 * 20;
    private static final int NO_EXCHANGE_TICKS = 20 * 30;
    static final double SUDDEN_DEATH_DAMAGE = 1.5;
    private static final int STARTING_BREAD = 4;
    private static final int RECONSTRUCTOR_STARTING_FRAGMENTS = 3;
    private static final int RECONSTRUCTOR_RESUPPLY_TICKS = 60 * 20;
    private static final int RECONSTRUCTOR_RESUPPLY_FRAGMENTS = 2;
    static final double RECONSTRUCTOR_FRAGMENT_BONUS = 0.5;
    private static final double SNIPER_SNIPE_BONUS = 2.5;
    private static final String ADMIN_PERMISSION = "disc.match.admin";
    private static final String MAP_SETTING = "match.map";
    private static final NamespacedKey SHIELD_COOLDOWN_GROUP = NamespacedKey.minecraft("shield");

    private final DiscPlugin plugin;
    private final Map<DiscArena.Layout, MatchSlot> matches = new EnumMap<>(DiscArena.Layout.class);
    private DiscArena arena;
    private final DiscLobby lobby;
    private final DiscItem discItem;
    private final ItemBoxes itemBoxes;
    private final MatchBoxLayout boxLayout;
    private final MatchLoot lobbyLoot;
    private final DiscMechanisms mechanisms;
    private final MatchBots bots;
    private final Map<UUID, MatchPlayerState> previews = new LinkedHashMap<>();
    private volatile Location lobbyEntry;
    private boolean preparing;
    private long tick;

    DiscMatch(DiscPlugin plugin, DiscItem discItem, ItemBoxes itemBoxes, MatchBoxLayout boxLayout,
              DiscMechanisms mechanisms, MatchBots bots) {
        this.plugin = plugin;
        this.bots = bots;
        this.itemBoxes = itemBoxes;
        for (DiscArena.Layout layout : DiscArena.Layout.values()) {
            matches.put(layout, new MatchSlot(new DiscArena(plugin.getServer(), layout)));
        }
        String selected = plugin.getConfig().getString(MAP_SETTING, DiscArena.Layout.HALL.id());
        DiscArena configured = findArena(selected);
        this.arena = configured == null ? matches.get(DiscArena.Layout.HALL).arena : configured;
        this.lobby = new DiscLobby(plugin.getServer());
        this.discItem = discItem;
        this.mechanisms = mechanisms;
        this.boxLayout = boxLayout;
        this.lobbyLoot = new MatchLoot(itemBoxes, boxLayout, discItem, player -> false);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length > 0 && args[0].equalsIgnoreCase("energy")) {
            setEnergy(sender, args);
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("map")) {
            selectMap(sender, args[1]);
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("role") && sender instanceof Player player) {
            chooseRole(player, args[1]);
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("join") && sender instanceof Player player) {
            MatchSlot slot = findMatch(args[1]);
            if (slot == null) {
                sender.sendMessage("/discmatch join <2|4|8>");
            } else {
                join(player, slot);
            }
            return;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("stop"))) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                sender.sendMessage("試合を管理する権限がありません。");
                return;
            }
            MatchSlot slot = findMatch(args[1]);
            if (slot == null) {
                sender.sendMessage("/discmatch " + args[0] + " <2|4|8>");
            } else if (args[0].equalsIgnoreCase("start")) {
                start(sender, slot, true);
            } else {
                stop(sender, slot.active);
            }
            return;
        }
        if (args.length != 1) {
            sendUsage(sender);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (preparing && !action.equals("status") && !action.equals("perf") && !action.equals("map")) {
            sendPreparing(sender);
            return;
        }
        switch (action) {
            case "lobby" -> {
                if (sender instanceof Player player) {
                    enterLobby(player);
                }
            }
            case "join" -> {
                if (sender instanceof Player player) {
                    join(player, matches.get(arena.layout()));
                }
            }
            case "ready", "wait" -> {
                if (sender instanceof Player player) {
                    chooseQueueAction(player, action.equals("ready"));
                }
            }
            case "role" -> {
                if (sender instanceof Player player) {
                    showRoles(player);
                }
            }
            case "leave" -> {
                if (sender instanceof Player player && !plugin.leaveFieldEditor(player) && !leave(player, true)) {
                    if (MatchPlayerState.hasPending(player)) {
                        restorePending(player);
                    } else {
                        player.sendMessage("参加中のディスク試合や待機列がありません。");
                    }
                }
            }
            case "status" -> showStatus(sender);
            case "perf" -> showPerformance(sender);
            case "map" -> sender.sendMessage("選択中のマップ  " + arena.layout().displayName()
                    + " (" + arena.layout().id() + "、最大" + arena.capacity() + "人)");
            case "preview", "start", "stop" -> administer(sender, action);
            default -> sendUsage(sender);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("map") && source.getSender().hasPermission(ADMIN_PERMISSION)) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return matches.keySet().stream().map(DiscArena.Layout::id).filter(id -> id.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("join")
                || source.getSender().hasPermission(ADMIN_PERMISSION)
                && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("stop")))) {
            return List.of("2", "4", "8").stream().filter(size -> size.startsWith(args[1])).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("role") && source.getSender() instanceof Player) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> choices = new ArrayList<>();
            for (MatchRole role : MatchRole.values()) {
                if (role.id().startsWith(prefix)) {
                    choices.add(role.id());
                }
            }
            return choices;
        }
        if (args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> choices = new ArrayList<>(
                List.of("lobby", "join", "ready", "wait", "leave", "status", "perf", "map", "role"));
        if (source.getSender().hasPermission(ADMIN_PERMISSION)) {
            choices.addAll(List.of("preview", "start", "stop", "energy"));
        }
        return choices.stream().filter(choice -> choice.startsWith(prefix)).toList();
    }

    private void setEnergy(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("試合を管理する権限がありません。");
            return;
        }
        int value;
        try {
            value = args.length == 3 ? Integer.parseInt(args[2]) : -1;
        } catch (NumberFormatException ignored) {
            value = -1;
        }
        if (value < 0 || value > DiscEnergy.MAX) {
            sender.sendMessage("/discmatch energy <プレイヤー> <0-" + DiscEnergy.MAX + ">");
            return;
        }
        Entrant entry = matches.values().stream().filter(slot -> slot.active != null)
                .flatMap(slot -> slot.active.players.values().stream())
                .filter(candidate -> candidate.player.getName().equalsIgnoreCase(args[1]))
                .findFirst().orElse(null);
        if (entry == null) {
            sender.sendMessage("試合中のプレイヤーを指定してください。");
            return;
        }
        entry.energy = value;
        sender.sendMessage(entry.player.getName() + " のエネルギー " + value + "/" + DiscEnergy.MAX);
    }

    int energy(Player player) {
        Entrant entry = entrantInArena(player);
        return entry == null ? -1 : entry.energy;
    }

    private Entrant entrantInArena(Player player) {
        Entrant entry = entrant(player);
        return entry != null && player.getWorld() == entry.round.world ? entry : null;
    }

    private boolean keepsFood(Player player) {
        return DiscLobby.isLobby(player.getWorld()) || entrantInArena(player) != null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && keepsFood(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player player && keepsFood(player)) {
            event.setCancelled(true);
        }
    }

    private void selectMap(CommandSender sender, String id) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("試合を管理する権限がありません。");
            return;
        }
        DiscArena selected = findArena(id);
        if (selected == null) {
            sender.sendMessage(mapUsage());
            return;
        }
        if (preparing) {
            sendPreparing(sender);
            return;
        }
        arena = selected;
        plugin.getConfig().set(MAP_SETTING, selected.layout().id());
        plugin.saveConfig();
        sender.sendMessage(selected.layout().displayName() + "を選択しました。最大 " + selected.capacity() + " 人。");
    }

    private void administer(CommandSender sender, String action) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("試合を管理する権限がありません。");
            return;
        }
        switch (action) {
            case "start" -> start(sender, matches.get(arena.layout()), false);
            case "stop" -> {
                Round active = sender instanceof Player player ? roundIn(player.getWorld()) : null;
                if (active == null) {
                    List<Round> running = matches.values().stream().map(slot -> slot.active)
                            .filter(Objects::nonNull).toList();
                    if (running.size() > 1) {
                        sender.sendMessage("複数の試合が進行中です。/discmatch stop <2|4|8> で指定してください。");
                        return;
                    }
                    active = running.isEmpty() ? null : running.getFirst();
                }
                stop(sender, active);
            }
            case "preview" -> {
                if (sender instanceof Player player) {
                    preview(player);
                }
            }
            default -> throw new IllegalArgumentException(action);
        }
    }

    private void stop(CommandSender sender, Round active) {
        if (preparing) {
            sendPreparing(sender);
        } else if (active == null) {
            sender.sendMessage("進行中のディスク試合がありません。");
        } else {
            finish(active, "試合を終了しました。");
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("/discmatch lobby | join [2|4|8] | ready | wait | leave | status | perf | map | role");
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("/discmatch preview | start [2|4|8] | stop [2|4|8]\n" + mapUsage());
        }
    }

    private void showRoles(Player player) {
        Entrant entry = entrant(player);
        if (entry != null) {
            player.sendMessage("試合中の役職は " + entry.role.displayName() + " です。");
            return;
        }
        if (!DiscLobby.isLobby(player.getWorld())) {
            player.sendMessage("役職はロビーで選べます。");
            return;
        }
        MatchRole selected = MatchRole.selected(player);
        player.sendMessage("次の試合の役職を選んでください。現在 " + selected.displayName());
        for (MatchRole role : MatchRole.values()) {
            player.sendMessage(Component.text(
                            role.displayName(), role == selected ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/discmatch role " + role.id()))
                    .append(Component.text("  " + String.join(" ", role.description()), NamedTextColor.GRAY)));
        }
    }

    private void chooseRole(Player player, String id) {
        if (entrant(player) != null || !DiscLobby.isLobby(player.getWorld())) {
            player.sendMessage("役職はロビーで試合開始前に選んでください。");
            return;
        }
        MatchRole role = MatchRole.byId(id);
        if (role == null) {
            showRoles(player);
            return;
        }
        selectRole(player, role);
    }

    private void selectRole(Player player, MatchRole role) {
        role.select(player);
        player.sendMessage("次の試合の役職を " + role.displayName() + " にしました。人数別の看板から参加できます。");
    }

    private void openRoleMenu(Player player) {
        if (!canQueue(player) || entrant(player) != null) {
            return;
        }
        RoleMenu menu = new RoleMenu();
        MatchRole selected = MatchRole.selected(player);
        for (MatchRole role : menu.roles) {
            Material material = switch (role) {
                case SHIFTER -> Material.SHIELD;
                case JUMPER -> Material.RABBIT_FOOT;
                case ICHOR -> Material.SUGAR;
                case MORE_THROWER -> Material.CROSSBOW;
                case STRIKER -> Material.IRON_SWORD;
                case RECONSTRUCTOR -> DiscType.FRAGMENT.material();
                default -> role.starterDisc().material();
            };
            ItemStack icon = new ItemStack(material);
            icon.editMeta(meta -> {
                meta.customName(Component.text(
                                role.displayName(), role == selected ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
                List<Component> lore = new ArrayList<>();
                for (String line : role.description()) {
                    lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                }
                lore.add(Component.empty());
                lore.add(Component.text(role == selected ? "選択中" : "クリックして選択", NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            });
            menu.inventory.setItem(role.ordinal(), icon);
        }
        player.openInventory(menu.inventory);
    }

    @EventHandler
    public void onRoleMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RoleMenu menu)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (!(event.getWhoClicked() instanceof Player player) || slot < 0 || slot >= menu.roles.length
                || !(event.isLeftClick() || event.isRightClick()) || !canQueue(player) || entrant(player) != null) {
            return;
        }
        selectRole(player, menu.roles[slot]);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory().getTopInventory() == menu.inventory) {
                player.closeInventory();
            }
        });
    }

    @EventHandler
    public void onRoleMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RoleMenu) {
            event.setCancelled(true);
        }
    }

    private String mapUsage() {
        return "/discmatch map " + matches.keySet().stream()
                .map(DiscArena.Layout::id)
                .collect(Collectors.joining(" | "));
    }

    private DiscArena findArena(String id) {
        String canonical = "dual".equalsIgnoreCase(id) ? "duel" : id;
        return matches.values().stream().map(slot -> slot.arena)
                .filter(candidate -> candidate.layout().id().equalsIgnoreCase(canonical))
                .findFirst().orElse(null);
    }

    private MatchSlot findMatch(String size) {
        return switch (size) {
            case "2" -> matches.get(DiscArena.Layout.DUEL);
            case "4" -> matches.get(DiscArena.Layout.OUTDOOR);
            case "8" -> matches.get(DiscArena.Layout.HALL);
            default -> null;
        };
    }

    private static void sendPreparing(CommandSender sender) {
        sender.sendMessage("ワールドを準備中です。準備が終わってから実行してください。");
    }

    private void enterLobby(Player player) {
        if (player.isDead()) {
            player.sendMessage("リスポーンしてからロビーに移動してください。");
            return;
        }

        preparing = true;
        try {
            Location destination = prepareLobby();
            lobbyEntry = destination;
            plugin.leaveFieldEditor(player);
            if (entrant(player) != null || previews.containsKey(player.getUniqueId())) {
                leave(player, true);
            }
            if (MatchPlayerState.hasPending(player)) {
                restorePending(player);
                if (MatchPlayerState.hasPending(player)) {
                    return;
                }
            }
            if (player.isOnline() && player.teleport(destination)) {
                player.sendMessage("ロビーに移動しました。役職を選び、人数別の看板から参加できます。");
            } else if (player.isOnline()) {
                player.sendMessage("ロビーに移動できませんでした。");
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not open the Disc lobby", error);
            player.sendMessage("ロビーに移動できませんでした。");
        } finally {
            preparing = false;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLobbySign(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        DiscLobby.Action action = lobby.signAction(event.getClickedBlock());
        if (action == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Player player = event.getPlayer();
        if (preparing) {
            sendPreparing(player);
            return;
        }
        switch (action) {
            case DUEL -> join(player, matches.get(DiscArena.Layout.DUEL));
            case OUTDOOR -> join(player, matches.get(DiscArena.Layout.OUTDOOR));
            case HALL -> join(player, matches.get(DiscArena.Layout.HALL));
            case ROLE -> openRoleMenu(player);
            case SUPPLY -> plugin.giveMissingDiscs(player);
            case WAIT -> chooseQueueAction(player, false);
            case LEAVE -> {
                if (!removeFromQueue(player.getUniqueId())) {
                    player.sendMessage("参加中の待機列がありません。");
                } else {
                    player.sendMessage("待機列から退出しました。");
                }
            }
            case START -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && DiscLobby.isLobby(player.getWorld())) {
                    chooseQueueAction(player, true);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTrapResetSign(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.useInteractedBlock() == Event.Result.DENY
                || !TrapResetSigns.isResetSign(event.getClickedBlock())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        World world = event.getClickedBlock().getWorld();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getWorld() != world) {
                return;
            }
            try {
                boolean complete = mechanisms.resetTraps(world);
                player.sendMessage(complete ? "このエリアのギミックをリセットしました。"
                        : "復元待ちのギミックがあります。場所が空くと元に戻ります。");
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not reset Disc traps in " + world.getName(), error);
                player.sendMessage("ギミックを復元できませんでした。");
            }
        });
    }

    private void join(Player player, MatchSlot slot) {
        UUID id = player.getUniqueId();
        if (preparing) {
            sendPreparing(player);
            return;
        }
        if (plugin.isFieldEditing(player)) {
            player.sendMessage("/edit end で試合場の編集を終えてから参加してください。");
            return;
        }
        if (!canQueue(player) || entrant(player) != null) {
            player.sendMessage("マッチにはロビーから参加できます。");
            return;
        }
        if (slot.queue.size() >= slot.arena.capacity() && !slot.queue.contains(id)) {
            player.sendMessage(slot.arena.layout().matchName() + "の待機列が満員です。");
            return;
        }
        if (MatchPlayerState.hasPending(player)) {
            restorePending(player, lobbyEntry);
            if (MatchPlayerState.hasPending(player)) {
                return;
            }
        }
        if (!slot.queue.contains(id)) {
            removeFromQueue(id);
            slot.queue.add(id);
            updateMatchSign(slot);
        }
        player.sendMessage(slot.arena.layout().matchName() + "に参加しました。 "
                + slot.queue.size() + " / " + slot.arena.capacity() + "人待機");
        if (slot.active != null) {
            player.sendMessage("現在の試合が終わるまで、ロビーで次の試合を待ちます。");
        }
        player.sendMessage("「この参加者で開始」「他の人を待つ」「待機をやめる」の看板を選べます。");
    }

    private void chooseQueueAction(Player player, boolean ready) {
        MatchSlot slot = queuedMatch(player.getUniqueId());
        if (slot == null || !canQueue(player)) {
            player.sendMessage("人数別の看板でマッチを選んでください。");
            return;
        }
        if (ready) {
            start(player, slot, true);
        } else {
            player.sendMessage(slot.arena.layout().matchName() + "で他の参加者を待ちます。満員になると開始します。");
        }
    }

    private MatchSlot queuedMatch(UUID id) {
        return matches.values().stream().filter(slot -> slot.queue.contains(id)).findFirst().orElse(null);
    }

    private boolean removeFromQueue(UUID id) {
        MatchSlot slot = queuedMatch(id);
        if (slot == null) {
            return false;
        }
        slot.queue.remove(id);
        updateMatchSign(slot);
        return true;
    }

    private boolean canQueue(Player player) {
        return player != null && player.isOnline() && !player.isDead() && DiscLobby.isLobby(player.getWorld())
                && player.getGameMode() != GameMode.SPECTATOR && !plugin.isFieldEditing(player);
    }

    private List<Player> queuedPlayers(MatchSlot slot) {
        return slot.queue.stream().map(plugin.getServer()::getPlayer).filter(this::canQueue).toList();
    }

    private void showStatus(CommandSender sender) {
        Round active = sender instanceof Player player ? roundIn(player.getWorld()) : null;
        if (active != null) {
            sender.sendMessage(scoreLine(active));
            return;
        }
        for (MatchSlot slot : matches.values()) {
            sender.sendMessage(slot.arena.layout().matchName() + "  " + slot.status()
                    + "  " + slot.queue.size() + " / " + slot.arena.capacity() + "人待機");
        }
    }

    private void showPerformance(CommandSender sender) {
        var server = plugin.getServer();
        double[] tps = server.getTPS();
        float tickRate = server.getServerTickManager().getTickRate();
        sender.sendMessage(String.format(
                Locale.ROOT,
                "TPS（1分 / 5分 / 15分） %.1f / %.1f / %.1f",
                Math.min(tps[0], tickRate), Math.min(tps[1], tickRate), Math.min(tps[2], tickRate)));

        long[] tickTimes = Arrays.stream(server.getTickTimes())
                .filter(time -> time > 0)
                .sorted()
                .toArray();
        if (tickTimes.length > 0) {
            double average = Arrays.stream(tickTimes).average().orElseThrow() / 1_000_000;
            int percentile = (tickTimes.length * 95 + 99) / 100 - 1;
            sender.sendMessage(String.format(
                    Locale.ROOT,
                    "MSPT（直近%dティック） 平均 %.1f / 95%%点 %.1f / 最大 %.1f ms",
                    tickTimes.length,
                    average,
                    tickTimes[percentile] / 1_000_000.0,
                    tickTimes[tickTimes.length - 1] / 1_000_000.0));
        } else {
            sender.sendMessage("ティックの処理時間を計測中です。");
        }

        if (sender instanceof Player player) {
            sender.sendMessage("自分のPing 約 " + player.getPing() + " ms");
        }
    }

    private void updateMatchSign(MatchSlot slot) {
        lobby.updateMatchSign(slot.arena.layout(), slot.status(), slot.queue.size(), slot.arena.capacity());
    }

    private void start(CommandSender sender, MatchSlot match, boolean fillWithBots) {
        if (preparing) {
            sendPreparing(sender);
            return;
        }
        if (match.active != null) {
            sender.sendMessage(match.arena.layout().matchName() + "がプレイ中です。終了までロビーで待機できます。");
            return;
        }
        DiscArena targetArena = match.arena;
        if (plugin.hasFieldEditors(targetArena.layout().id())) {
            sender.sendMessage("試合場を編集中です。編集者が /edit end を実行すると開始できます。");
            return;
        }
        List<Player> players = queuedPlayers(match);
        if (!fillWithBots && players.size() < 2) {
            sender.sendMessage("生存中の参加者が2人以上必要です。各自 /discmatch join で参加できます。");
            return;
        }
        int total = fillWithBots ? targetArena.capacity() : players.size();
        Round next = new Round(match, new MatchLoot(itemBoxes, boxLayout, discItem,
                player -> canOpenMatchLoot(player, match.active)));
        preparing = true;
        try {
            next.world = prepareArena(next.arena);
            if (total > players.size()) {
                next.botChunks = new MatchBots.ArenaChunks(itemBoxes, next.world);
            }
            for (int slot = 0; slot < players.size(); slot++) {
                Player player = players.get(slot);
                if (!player.isOnline() || player.isDead()) {
                    throw new IllegalStateException("Entrant left during setup: " + player.getName());
                }
                MatchPlayerState state = capture(player);
                Entrant entry = new Entrant(
                        next, player, state, next.arena.armorColor(slot), MatchRole.selected(player));
                next.players.put(player.getUniqueId(), entry);
                if (!player.teleport(next.arena.spawn(slot))) {
                    throw new IllegalStateException("Entry teleport failed for " + player.getName());
                }
                equip(entry, false);
                player.setCooldown(DiscItem.WARDEN_COOLDOWN_GROUP, 0);
                player.setCooldown(DiscPlugin.RETURN_COOLDOWN_GROUP, 0);
                player.setCooldown(DiscItem.OFF_HAND_WARDEN_GROUP, 0);
                player.setCooldown(DiscPlugin.OFF_HAND_COOLDOWN_GROUP, 0);
                player.setCooldown(DiscPlugin.FRAGMENT_COOLDOWN_GROUP, 0);
            }
            if (players.stream().anyMatch(player -> !player.isOnline())) {
                throw new IllegalStateException("An entrant disconnected during setup");
            }
            for (int slot = players.size(); slot < total; slot++) {
                String name = "DiscBOT" + targetArena.capacity() + "_" + (slot + 1);
                MatchBots.Body body = bots.spawn(UUID.randomUUID(), name, next.arena.spawn(slot));
                Entrant entry = new Entrant(
                        next, body.player, null, next.arena.armorColor(slot), MatchRole.randomForBot());
                entry.body = body;
                next.players.put(body.player.getUniqueId(), entry);
                entry.brain = new BotBrain(plugin, next.loot, body, entry.role);
                equip(entry, false);
            }
            next.hud = new MatchHud(
                    plugin.getServer(),
                    next.players.values().stream()
                            .filter(entry -> entry.body != null)
                            .map(entry -> new MatchHud.BotLabel(entry.player.getName(), entry.role))
                            .toList());
            mechanisms.beginArenaRound(next.world);
        } catch (RuntimeException error) {
            next.players.values().forEach(entry -> finishEntrant(entry, lobbyEntry));
            closeBotChunks(next);
            match.autoStartAt = tick + 100;
            plugin.getLogger().log(Level.WARNING, "Could not start the Disc match", error);
            sender.sendMessage("試合場への移動に失敗しました。");
            return;
        } finally {
            preparing = false;
        }

        players.forEach(player -> match.queue.remove(player.getUniqueId()));
        int roundMinutes = switch (next.arena.layout()) {
            case DUEL -> 3;
            case OUTDOOR -> 4;
            case HALL -> 5;
        };
        next.startsAt = tick + COUNTDOWN_TICKS;
        next.endsAt = next.startsAt + roundMinutes * 60L * 20;
        next.lastThrowAt = next.startsAt;
        next.lastExchangeAt = next.startsAt;
        match.active = next;
        updateMatchSign(match);
        watchers(next).forEach(player -> player.showBossBar(next.board));
        String mode = next.arena.layout() == DiscArena.Layout.DUEL ? "ディスク1on1" : "ディスクFFA";
        announce(next, mode + "を開始します。" + roundMinutes + "分間の得点戦です。同点なら与ダメージで決まります。");
        updateBoard(next);
    }

    private void preview(Player player) {
        UUID id = player.getUniqueId();
        if (plugin.isFieldEditing(player)) {
            player.sendMessage("/edit end で試合場の編集を終えてください。");
            return;
        }
        if (previews.containsKey(id) || entrant(player) != null || player.isDead()) {
            player.sendMessage("観戦・参加中です。退出するには /discmatch leave を使ってください。");
            return;
        }
        MatchPlayerState before = null;
        MatchSlot match = matches.get(arena.layout());
        preparing = true;
        try {
            prepareArena(match.arena);
            Location destination = match.arena.overlook();
            before = capture(player);
            if (!player.teleport(destination)) {
                throw new IllegalStateException("Preview teleport failed for " + player.getName());
            }
            player.setGameMode(GameMode.SPECTATOR);
            previews.put(id, before);
            removeFromQueue(id);
            if (match.active != null) {
                player.showBossBar(match.active.board);
                updateBoard(match.active);
            }
            player.sendMessage("試合場を観戦しています。/discmatch leave で元の場所へ戻ります。");
        } catch (RuntimeException error) {
            if (before != null) {
                restore(player, before);
            }
            plugin.getLogger().log(Level.WARNING, "Could not open the Disc arena preview", error);
            player.sendMessage("試合場に移動できませんでした。");
        } finally {
            preparing = false;
        }
    }

    private MatchPlayerState capture(Player player) {
        plugin.returnDiscs(player);
        MatchPlayerState state = MatchPlayerState.capture(player);
        state.save(player);
        return state;
    }

    List<String> fieldTargets() {
        List<String> targets = new ArrayList<>();
        targets.add("lobby");
        matches.keySet().forEach(layout -> targets.add(layout.id()));
        return List.copyOf(targets);
    }

    String resolveFieldTarget(String requested) {
        if (requested == null) {
            return arena.layout().id();
        }
        if (requested.equalsIgnoreCase("lobby")) {
            return "lobby";
        }
        DiscArena target = findArena(requested);
        return target == null ? null : target.layout().id();
    }

    Location fieldForEdit(Player player, String canonicalTarget) {
        if (preparing) {
            sendPreparing(player);
            return null;
        }
        if (player.isDead()) {
            player.sendMessage("リスポーンしてから編集してください。");
            return null;
        }
        boolean editingLobby = "lobby".equals(canonicalTarget);
        DiscArena target = editingLobby ? null : findArena(canonicalTarget);
        if (!editingLobby && target == null) {
            player.sendMessage("編集先を指定してください。/edit field " + String.join(" | ", fieldTargets()));
            return null;
        }
        if (entrant(player) != null || target != null && matches.get(target.layout()).active != null) {
            player.sendMessage("試合終了後にこのエリアを編集できます。");
            return null;
        }
        if (previews.containsKey(player.getUniqueId())) {
            leave(player, true);
        }
        if (MatchPlayerState.hasPending(player)) {
            restorePending(player);
            if (MatchPlayerState.hasPending(player)) {
                return null;
            }
        }

        preparing = true;
        try {
            Location destination;
            if (editingLobby) {
                destination = prepareLobby();
                lobbyEntry = destination;
            } else {
                prepareArena(target);
                destination = target.overlook();
            }
            removeFromQueue(player.getUniqueId());
            return destination;
        } finally {
            preparing = false;
        }
    }

    private World prepareArena(DiscArena selected) {
        World world = selected.world();
        mechanisms.prepareArena(world);
        ensureTrapResetSign(world);
        return world;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        try {
            lobbyEntry = prepareLobby();
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not pre-load the Disc lobby", error);
        }
    }

    private Location prepareLobby() {
        Location destination = lobby.spawn();
        matches.values().forEach(this::updateMatchSign);
        World world = destination.getWorld();
        try {
            mechanisms.prepareLobby(world);
            ensureTrapResetSign(world);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not prepare Disc lobby trap recovery", error);
        }
        try {
            lobbyLoot.showLobby(world);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not place loot-box examples in the Disc lobby", error);
        }
        return destination;
    }

    private void ensureTrapResetSign(World world) {
        try {
            TrapResetSigns.ensure(world);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not update trap reset signs in " + world.getName(), error);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        Location destination = lobbyEntry;
        if (destination != null) {
            event.setSpawnLocation(destination.clone());
        }
    }

    private void equip(Entrant entry, boolean respawning) {
        Player player = entry.player;
        player.setGameMode(GameMode.ADVENTURE);
        PlayerInventory inventory = player.getInventory();
        inventory.setContents(new ItemStack[inventory.getSize()]);
        inventory.setHelmet(leatherArmor(Material.LEATHER_HELMET, entry.armorColor));
        inventory.setChestplate(leatherArmor(Material.LEATHER_CHESTPLATE, entry.armorColor));
        inventory.setLeggings(leatherArmor(Material.LEATHER_LEGGINGS, entry.armorColor));
        inventory.setBoots(leatherArmor(Material.LEATHER_BOOTS, entry.armorColor));
        player.clearActivePotionEffects();
        player.setAbsorptionAmount(0);
        player.setHealth(Math.min(20, player.getAttribute(Attribute.MAX_HEALTH).getValue()));
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setExhaustion(0);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setNoDamageTicks(0);
        if (entry.role == MatchRole.SHIFTER) {
            inventory.setItemInOffHand(DiscShields.create());
        }
        if (entry.role == MatchRole.RECONSTRUCTOR) {
            player.setCooldown(DiscPlugin.FRAGMENT_COOLDOWN_GROUP, 0);
        }
        if (respawning) {
            restoreMatchDiscs(entry);
        } else {
            for (int index = 0; index < entry.role.starterDiscCount(); index++) {
                inventory.setItem(starterDiscSlot(entry.role, index), discItem.create(entry.role.starterDisc()));
            }
            inventory.setItem(1, new ItemStack(Material.BREAD, STARTING_BREAD));
            if (entry.role == MatchRole.RECONSTRUCTOR) {
                int slot = entry.body == null ? 2 : DiscItem.OFF_HAND_SLOT;
                inventory.setItem(
                        slot, discItem.create(DiscType.FRAGMENT).asQuantity(RECONSTRUCTOR_STARTING_FRAGMENTS));
            }
            inventory.setHeldItemSlot(0);
        }
    }

    private void restoreMatchDiscs(Entrant entry) {
        Player player = entry.player;
        PlayerInventory inventory = player.getInventory();
        DiscType starter = entry.role.starterDisc();
        int starters = 0;
        for (RetainedDisc disc : entry.discsAtDeath) {
            plugin.restoreItem(player, disc.slot(), disc.item());
            if (discItem.type(disc.item()) == starter) {
                starters += disc.item().getAmount();
            }
        }
        entry.discsAtDeath = List.of();

        if (entry.body != null) {
            int wardenTicks = (int) Math.max(0, entry.wardenReadyAt - tick);
            player.setCooldown(DiscItem.WARDEN_COOLDOWN_GROUP, wardenTicks);
        }
        for (RetainedFlight flight : entry.flightsAtDeath) {
            plugin.restoreItem(player, flight.slot(), flight.item());
            DiscType type = discItem.type(flight.item());
            if (type == starter) {
                starters += flight.item().getAmount();
            }
            plugin.startReturnCooldown(player, flight.launchedType(), flight.slot());
        }
        entry.flightsAtDeath.clear();
        for (int index = starters; index < entry.role.starterDiscCount(); index++) {
            plugin.restoreItem(player, starterDiscSlot(entry.role, index), discItem.create(starter));
        }
        plugin.restoreItem(player, 1, new ItemStack(Material.BREAD, STARTING_BREAD));
        int previous = entry.selectedSlotAtDeath;
        inventory.setHeldItemSlot(discItem.matches(inventory.getItem(previous)) ? previous : 0);
    }

    private static int starterDiscSlot(MatchRole role, int index) {
        return index == 0 ? 0 : role == MatchRole.MORE_THROWER ? DiscItem.OFF_HAND_SLOT : 2;
    }

    boolean keepFlightForRespawn(DiscThrow flight) {
        Entrant entry = entrantInArena(flight.owner());
        if (entry == null) {
            return false;
        }
        entry.flightsAtDeath.add(new RetainedFlight(flight.slot(), flight.item(), flight.type()));
        return true;
    }

    private void saveMatchDiscs(Entrant entry) {
        Player player = entry.player;
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        boolean loseFragments = entry.role == MatchRole.RECONSTRUCTOR;
        List<RetainedDisc> discs = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            DiscType type = discItem.type(contents[slot]);
            if (loseFragments && type == DiscType.FRAGMENT) {
                inventory.setItem(slot, null);
            } else if (type != null) {
                discs.add(new RetainedDisc(slot, contents[slot].clone()));
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        DiscType cursorType = discItem.type(cursor);
        if (cursorType != null) {
            if (!loseFragments || cursorType != DiscType.FRAGMENT) {
                discs.add(new RetainedDisc(-1, cursor.clone()));
            }
            player.setItemOnCursor(ItemStack.empty());
        }
        entry.discsAtDeath = List.copyOf(discs);
        entry.selectedSlotAtDeath = inventory.getHeldItemSlot();
    }

    private static ItemStack leatherArmor(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        item.editMeta(LeatherArmorMeta.class, meta -> meta.setColor(color));
        return item;
    }

    boolean canThrow(Player player) {
        return !DiscArena.isArena(player.getWorld()) || isFighting(entrant(player));
    }

    boolean isSuddenDeath(Player player) {
        Entrant entry = entrant(player);
        return isFighting(entry) && entry.round.suddenDeath;
    }

    int cooldownTicks(Player player, int baseTicks) {
        return activeRole(player).cooldownTicks(baseTicks);
    }

    MatchRole activeRole(Player player) {
        Entrant entry = entrant(player);
        return isFighting(entry) ? entry.role : MatchRole.NONE;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShieldCooldown(PlayerItemGroupCooldownEvent event) {
        if (SHIELD_COOLDOWN_GROUP.equals(event.getCooldownGroup())) {
            event.setCooldown(cooldownTicks(event.getPlayer(), event.getCooldown()));
        }
    }

    void recordThrow(Player player) {
        Entrant entry = entrant(player);
        if (isFighting(entry)) {
            entry.round.lastThrowAt = tick;
        }
    }

    void recordWardenCooldown(Player player, int ticks) {
        Entrant entry = entrantInArena(player);
        if (entry != null) {
            entry.wardenReadyAt = tick + ticks;
        }
    }

    private boolean isFighting(Entrant entry) {
        return entry != null && entry.round.match.active == entry.round && entry.player.getWorld() == entry.round.world
                && tick >= entry.round.startsAt && entry.respawnAt == 0 && !entry.player.isDead();
    }

    private boolean canOpenMatchLoot(Player player, Round active) {
        Entrant entry = entrant(player);
        return isFighting(entry) && entry.round == active;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !DiscArena.isArena(player.getWorld())) {
            return;
        }
        Entrant victim = entrant(player);
        if (!isFighting(victim)) {
            event.setCancelled(true);
            return;
        }
        DamageSource source = event.getDamageSource();
        double damage = event.getDamage();
        double multiplier = victim.round.suddenDeath && isDiscDamage(source) ? SUDDEN_DEATH_DAMAGE : 1;
        if (victim.role == MatchRole.JUMPER && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            multiplier *= 0.5;
        }
        if (victim.role == MatchRole.MATRIX && isFireDamage(source)) {
            multiplier *= 0.5;
        }
        if (source.getCausingEntity() instanceof Player attacker) {
            Entrant attacking = entrant(attacker);
            if (attacking == null || attacking.round != victim.round
                    || !isFighting(attacking) && source.getDamageContext().get(DiscThrow.THROW_ID).isEmpty()) {
                event.setCancelled(true);
                return;
            }
            DiscType disc = source.getDamageContext().get(DiscEffects.DISC_TYPE).orElse(null);
            if (attacking.role == MatchRole.RECONSTRUCTOR && disc == DiscType.FRAGMENT) {
                damage += RECONSTRUCTOR_FRAGMENT_BONUS;
            }
            if (attacking.role == MatchRole.SNIPER || attacking.role == MatchRole.STRIKER) {
                Vector origin = source.getDamageContext().get(DiscThrow.ATTACK_ORIGIN).orElse(null);
                if (origin != null) {
                    double distance = player.getEyeLocation().toVector().distance(origin);
                    if (attacking.role == MatchRole.SNIPER) {
                        if (disc == DiscType.SNIPE) {
                            damage += SNIPER_SNIPE_BONUS;
                        }
                        multiplier *= MatchRole.sniperDamageMultiplier(distance);
                    } else {
                        multiplier *= MatchRole.strikerDamageMultiplier(distance);
                    }
                }
            }
        }
        double adjusted = damage * multiplier;
        if (adjusted != event.getDamage()) {
            event.setDamage(adjusted);
        }
    }

    private static boolean isFireDamage(DamageSource source) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).getTag(DamageTypeTagKeys.IS_FIRE)
                .contains(TypedKey.create(RegistryKey.DAMAGE_TYPE, source.getDamageType().getKey()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageResolved(EntityDamageEvent event) {
        DamageSource source = event.getDamageSource();
        if (!(event.getEntity() instanceof Player target)
                || !(source.getCausingEntity() instanceof Player attacker) || attacker == target) {
            return;
        }
        Entrant victim = entrant(target);
        Entrant attacking = entrant(attacker);
        if (!isFighting(victim) || attacking == null || attacking.round != victim.round) {
            return;
        }
        if (DiscEffects.dealtDamage(event)) {
            victim.recentAttackers.putLast(attacker.getUniqueId(), tick);
            attacking.score.addDamage(damageDealt(target, event));
        }
        if (!victim.round.suddenDeath && isFighting(attacking) && isDiscDamage(source)
                && (event.getFinalDamage() > 0 || blockedByShield(event))) {
            victim.round.lastExchangeAt = tick;
        }
    }

    @SuppressWarnings("deprecation")
    private static double damageDealt(Player target, EntityDamageEvent event) {
        double health = Math.min(target.getHealth(), Math.max(0, event.getFinalDamage()));
        double absorption = event.isApplicable(DamageModifier.ABSORPTION)
                ? Math.min(target.getAbsorptionAmount(), Math.max(0, -event.getDamage(DamageModifier.ABSORPTION))) : 0;
        return health + absorption;
    }

    private static boolean isDiscDamage(DamageSource source) {
        return source.getDamageContext().get(DiscEffects.DISC_TYPE).isPresent()
                || DiscDamage.BURN.matches(source.getDamageType())
                        && source.getDamageContext().get(DiscThrow.THROW_ID).isPresent();
    }

    @SuppressWarnings("deprecation")
    private static boolean blockedByShield(EntityDamageEvent event) {
        return event.isApplicable(DamageModifier.BLOCKING) && event.getDamage(DamageModifier.BLOCKING) < 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (protectTerrain(event.getEntity().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (protectTerrain(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (protectTerrain(event.getEntity().getWorld())) {
            event.setFire(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionIgnite(BlockIgniteEvent event) {
        if (event.getCause() == BlockIgniteEvent.IgniteCause.EXPLOSION && protectTerrain(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (protectTerrain(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    private static boolean protectTerrain(World world) {
        return DiscArena.isArena(world) || DiscLobby.isLobby(world);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Entrant entry = entrant(victim);
        if (entry == null || entry.respawnAt != 0) {
            return;
        }
        saveMatchDiscs(entry);
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepLevel(true);
        event.deathMessage(null);
        entry.respawnAt = tick + RESPAWN_TICKS;

        DamageSource source = event.getDamageSource();
        Round active = entry.round;
        Entrant scorer = source.getCausingEntity() instanceof Player attacker && attacker != victim
                ? entrant(attacker)
                : null;
        if (scorer != null && scorer.round != active) {
            scorer = null;
        }
        UUID throwId = scorer == null ? null : source.getDamageContext().get(DiscThrow.THROW_ID).orElse(null);
        if (scorer == null) {
            scorer = recentScorer(entry);
        }
        entry.recentAttackers.clear();
        if (tick >= active.startsAt && scorer != null) {
            if (scorer.role == MatchRole.SINGER && throwId != null
                    && source.getDamageContext().get(DiscEffects.DISC_TYPE).orElse(null) == DiscType.DARKNESS) {
                plugin.upgradeDarknessThrow(scorer.player, throwId);
            }
            MatchScore.Award award = scorer.score.record(victim.getUniqueId(), throwId, tick);
            announce(active, scorer.player.getName() + " が " + victim.getName() + " を撃破 +" + award.points()
                    + "点" + award.label());
        }
        victim.sendMessage("3秒後に復活します。");
        updateBoard(active);
    }

    private Entrant recentScorer(Entrant victim) {
        for (var hit : victim.recentAttackers.reversed().entrySet()) {
            if (tick - hit.getValue() > DEATH_CREDIT_TICKS) {
                break;
            }
            Entrant attacker = victim.round.players.get(hit.getKey());
            if (attacker != null) {
                return attacker;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Entrant entry = entrant(event.getPlayer());
        if (entry != null && entry.respawnAt != 0) {
            event.setRespawnLocation(entry.round.arena.overlook());
        } else if (entry == null && MatchPlayerState.hasPending(event.getPlayer()) && lobbyEntry != null) {
            event.setRespawnLocation(lobbyEntry.clone());
        }
    }

    @EventHandler
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        Entrant entry = entrant(player);
        if (entry != null && entry.respawnAt != 0) {
            player.setGameMode(GameMode.SPECTATOR);
        } else if (entry == null && !plugin.isFieldEditing(player) && MatchPlayerState.hasPending(player)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> restorePending(player, lobbyEntry));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Entrant entry = entrantInArena(event.getPlayer());
        if (entry != null && tick < entry.round.startsAt && event.hasChangedPosition()) {
            Location from = event.getFrom().clone();
            from.setYaw(event.getTo().getYaw());
            from.setPitch(event.getTo().getPitch());
            event.setTo(from);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (entrant(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> enterAfterLogin(player));
    }

    private void enterAfterLogin(Player player) {
        if (!player.isOnline() || entrant(player) != null || previews.containsKey(player.getUniqueId())
                || plugin.isFieldEditing(player)) {
            return;
        }
        try {
            Location destination = lobbyEntry;
            if (destination == null) {
                destination = prepareLobby();
                lobbyEntry = destination;
            }
            if (player.isDead() && MatchPlayerState.hasPending(player)) {
                player.spigot().respawn();
            } else if (MatchPlayerState.hasPending(player)) {
                restorePending(player, destination);
            } else if (!DiscLobby.isLobby(player.getWorld())) {
                player.teleport(destination.clone());
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not move " + player.getName() + " to the lobby", error);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        leave(event.getPlayer(), false);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (DiscArena.isArena(event.getFrom())) {
            leave(player, true);
        } else if (!preparing && DiscLobby.isLobby(event.getFrom())) {
            removeFromQueue(player.getUniqueId());
        }
    }

    void tick() {
        tick++;
        mechanisms.tickArenaRound();
        for (MatchSlot slot : matches.values()) {
            if (slot.active != null) {
                tickRound(slot.active);
            } else if (!preparing && tick % 20 == 0 && tick >= slot.autoStartAt
                    && slot.queue.size() == slot.arena.capacity()
                    && !plugin.hasFieldEditors(slot.arena.layout().id())
                    && queuedPlayers(slot).size() == slot.arena.capacity()) {
                start(plugin.getServer().getConsoleSender(), slot, true);
            }
        }
    }

    private void tickRound(Round active) {
        if (tick >= active.endsAt) {
            List<MatchHud.Standing> ranking = standings(active).stream().sorted(Comparator.reverseOrder()).toList();
            MatchHud.Standing leader = ranking.getFirst();
            String damage = MatchScore.damageText(leader.damage());
            finish(active, ranking.size() > 1 && leader.compareTo(ranking.get(1)) == 0
                    ? "引き分け。首位は " + leader.points() + "点・与ダメージ " + damage + "でした。"
                    : leader.name() + " が " + leader.points() + "点・与ダメージ " + damage + "で勝利！");
            return;
        }
        if (tick == active.startsAt) {
            announce(active, "試合開始！");
            try {
                active.loot.begin(active.world, tick);
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not prepare this round's Disc loot boxes", error);
            }
        }
        active.loot.tick(tick);
        for (Entrant entry : List.copyOf(active.players.values())) {
            if (active.match.active != active) {
                return;
            }
            if (entry.respawnAt == 0 || !entry.player.isOnline()) {
                continue;
            }
            if (entry.body != null) {
                if (tick >= entry.respawnAt) {
                    respawnBot(active, entry);
                }
            } else if (entry.player.isDead()) {
                entry.player.spigot().respawn();
            } else if (tick >= entry.respawnAt) {
                respawn(active, entry);
            }
        }
        if (tick >= active.startsAt && active.match.active == active) {
            List<Player> fighters = active.players.values().stream().filter(this::isFighting)
                    .map(entry -> entry.player).toList();
            for (Entrant entry : List.copyOf(active.players.values())) {
                if (!isFighting(entry)) {
                    continue;
                }
                if (entry.role == MatchRole.RECONSTRUCTOR) {
                    resupplyFragments(active, entry);
                }
                if (entry.brain != null && tick >= entry.retryAt) {
                    try {
                        entry.brain.tick(tick, fighters);
                    } catch (RuntimeException error) {
                        entry.retryAt = tick + 100;
                        plugin.getLogger().log(
                                Level.WARNING, "Disc BOT behavior failed for " + entry.player.getName(), error);
                    }
                }
            }
        }
        if (active.match.active == active) {
            checkSuddenDeath(active);
        }
        if (tick % 20 == 0 && active.match.active == active) {
            updateBoard(active);
        }
    }

    private void resupplyFragments(Round active, Entrant entry) {
        int intervals = (int) ((tick - active.startsAt) / RECONSTRUCTOR_RESUPPLY_TICKS);
        if (intervals > entry.fragmentResupplies) {
            int amount = (intervals - entry.fragmentResupplies) * RECONSTRUCTOR_RESUPPLY_FRAGMENTS;
            entry.fragmentResupplies = intervals;
            plugin.restoreItem(entry.player, -1, discItem.create(DiscType.FRAGMENT).asQuantity(amount));
        }
    }

    private void checkSuddenDeath(Round active) {
        if (active.suddenDeath || tick < active.startsAt
                || tick - active.lastThrowAt < NO_THROW_TICKS && tick - active.lastExchangeAt < NO_EXCHANGE_TICKS
                || active.players.values().stream().filter(this::isFighting).limit(2).count() < 2) {
            return;
        }
        active.suddenDeath = true;
        for (Entrant entry : List.copyOf(active.players.values())) {
            Player player = entry.player;
            if (!player.isOnline() || player.getWorld() != active.world) {
                continue;
            }
            clearSharedCooldown(player, entry.wardenReadyAt - tick, false);
            if (entry.role == MatchRole.MORE_THROWER) {
                clearSharedCooldown(player, entry.wardenReadyAt - tick, true);
            }
        }
        active.board.color(BossBar.Color.RED);
        updateBoard(active);
        announce(active, "サドンデス！ディスクの威力が1.5倍になりました。全員が発光し、帰還直後から再投擲できます。");
    }

    private static void clearSharedCooldown(Player player, long wardenRemaining, boolean offHand) {
        NamespacedKey sharedGroup = DiscPlugin.throwCooldownGroup(offHand);
        int shared = player.getCooldown(sharedGroup);
        if (shared <= 0) {
            return;
        }
        player.setCooldown(sharedGroup, 0);
        NamespacedKey wardenGroup = DiscItem.wardenGroup(offHand);
        int warden = player.getCooldown(wardenGroup);
        if (warden > 0 && warden <= shared) {
            player.setCooldown(wardenGroup, (int) Math.clamp(wardenRemaining, 0L, warden));
        }
    }

    private void respawn(Round active, Entrant entry) {
        Player player = entry.player;
        for (int slot = 0; slot < active.arena.capacity(); slot++) {
            Location spawn = active.arena.spawn(slot);
            boolean occupied = active.players.values().stream().anyMatch(other -> other != entry && isFighting(other)
                    && other.player.getWorld() == spawn.getWorld()
                    && spawn.distanceSquared(other.player.getLocation()) < 36);
            if (!occupied && player.teleport(spawn)) {
                entry.respawnAt = 0;
                equip(entry, true);
                return;
            }
        }
        if (player.teleport(active.arena.spawn(0))) {
            entry.respawnAt = 0;
            equip(entry, true);
        }
    }

    private void respawnBot(Round active, Entrant entry) {
        Player previous = entry.player;
        UUID id = previous.getUniqueId();
        String name = previous.getName();
        try {
            plugin.stopBot(previous);
            bots.remove(entry.body);
            Location spawn = active.arena.spawn(active.spawnCursor++ % active.arena.capacity());
            entry.body = bots.spawn(id, name, spawn);
            entry.player = entry.body.player;
            entry.brain = new BotBrain(plugin, active.loot, entry.body, entry.role);
            entry.respawnAt = 0;
            equip(entry, true);
        } catch (RuntimeException error) {
            active.players.remove(id);
            finishEntrant(entry, null);
            plugin.getLogger().log(Level.WARNING, "Could not respawn " + name, error);
            if (active.players.size() < 2) {
                finish(active, "参加者が足りないため、試合を終了しました。");
            }
        }
    }

    List<Player> botPlayers() {
        return matches.values().stream().filter(slot -> slot.active != null)
                .flatMap(slot -> slot.active.players.values().stream())
                .filter(entry -> entry.body != null).map(entry -> entry.player).toList();
    }

    private boolean leave(Player player, boolean restoreNow) {
        UUID id = player.getUniqueId();
        if (removeFromQueue(id)) {
            player.sendMessage("待機列から退出しました。");
            return true;
        }
        MatchPlayerState preview = previews.remove(id);
        if (preview != null) {
            for (MatchSlot slot : matches.values()) {
                if (slot.active != null) {
                    player.hideBossBar(slot.active.board);
                    slot.active.hud.hide(player);
                }
            }
            if (restoreNow) {
                restore(player, preview);
            }
            return true;
        }
        Entrant entry = entrant(player);
        if (entry == null) {
            return false;
        }
        Round active = entry.round;
        active.players.remove(id);

        player.hideBossBar(active.board);
        active.hud.hide(player);
        if (restoreNow) {
            restore(player, entry.original, lobbyEntry);
        }
        announce(active, player.getName() + " が試合から退出しました。");
        if (active.players.size() < 2) {
            finish(active, "残りの参加者が2人に満たないため、試合を終了しました。");
        } else {
            updateBoard(active);
        }
        return true;
    }

    private void finish(Round active, String result) {
        active.match.active = null;
        active.loot.end();
        announce(active, result);
        announce(active, scoreLine(active));
        watchers(active).forEach(player -> player.hideBossBar(active.board));
        active.hud.close();
        active.players.values().forEach(entry -> finishEntrant(entry, lobbyEntry));
        try {
            mechanisms.finishArenaRound(active.world);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not restore Disc arena traps at round end", error);
        } finally {
            closeBotChunks(active);
            updateMatchSign(active.match);
        }
    }

    private static void closeBotChunks(Round ending) {
        if (ending.botChunks != null) {
            ending.botChunks.close();
        }
    }

    private void finishEntrant(Entrant entry, Location destination) {
        if (entry.body == null) {
            restore(entry.player, entry.original, destination);
        } else {
            try {
                plugin.stopBot(entry.player);
            } finally {
                bots.remove(entry.body);
            }
        }
    }

    void close() {
        for (MatchSlot slot : matches.values()) {
            slot.queue.clear();
            if (slot.active != null) {
                finish(slot.active, "サーバー停止のため試合を終了しました。");
            }
        }
        List<UUID> spectators = List.copyOf(previews.keySet());
        for (UUID id : spectators) {
            Player player = plugin.getServer().getPlayer(id);
            MatchPlayerState state = previews.remove(id);
            if (player != null) {
                restore(player, state);
            }
        }
    }

    private void restore(Player player, MatchPlayerState state) {
        restore(player, state, null);
    }

    private void restore(Player player, MatchPlayerState state, Location destination) {
        plugin.clearPlayerEffects(player);
        plugin.returnDiscs(player);
        if (player.isOnline() && !player.isDead()) {
            try {
                boolean restored = destination == null
                        ? state.restore(player)
                        : state.restore(player, destination.clone());
                if (!restored) {
                    player.sendMessage("参加前の状態に戻れませんでした。/discmatch leave で再試行できます。");
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not restore match data for " + player.getName(), error);
            }
        }
    }

    private void restorePending(Player player) {
        restorePending(player, null);
    }

    private void restorePending(Player player, Location destination) {
        if (player.isOnline() && !player.isDead()
                && entrant(player) == null && !previews.containsKey(player.getUniqueId())) {
            try {
                boolean restored = destination == null ? MatchPlayerState.restorePending(player)
                        : MatchPlayerState.restorePending(player, destination.clone());
                if (!restored) {
                    player.sendMessage("参加前の状態に戻れませんでした。/discmatch leave で再試行できます。");
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not recover match data for " + player.getName(), error);
            }
        }
    }

    private Entrant entrant(Player player) {
        UUID id = player.getUniqueId();
        for (MatchSlot slot : matches.values()) {
            if (slot.active != null) {
                Entrant entry = slot.active.players.get(id);
                if (entry != null) {
                    return entry;
                }
            }
        }
        return null;
    }

    private Round roundIn(World world) {
        for (MatchSlot slot : matches.values()) {
            if (slot.active != null && slot.active.world == world) {
                return slot.active;
            }
        }
        return null;
    }

    private void updateBoard(Round active) {
        long remaining = Math.max(0, active.endsAt - Math.max(tick, active.startsAt));
        active.board.progress((float) remaining / (active.endsAt - active.startsAt));
        active.board.name(Component.text(tick < active.startsAt
                ? "開始まで " + (active.startsAt - tick + 19) / 20 + "秒"
                : remainingTime(active)));
        active.hud.update(watchers(active), standings(active));
    }

    private static List<MatchHud.Standing> standings(Round active) {
        return active.players.values().stream().map(entry -> new MatchHud.Standing(entry.player.getUniqueId(),
                entry.player.getName(), entry.score.points(), entry.score.damage())).toList();
    }

    private String scoreLine(Round active) {
        String results = standings(active).stream().sorted(Comparator.reverseOrder())
                .map(entry -> entry.name() + " " + entry.points() + "点 / " + MatchScore.damageText(entry.damage()))
                .collect(Collectors.joining("\n"));
        return remainingTime(active) + "  |  与ダメージ\n" + results;
    }

    private String remainingTime(Round active) {
        long seconds = (Math.max(0, active.endsAt - Math.max(tick, active.startsAt)) + 19) / 20;
        String time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        return active.suddenDeath ? "サドンデス  |  " + time : time;
    }

    private void announce(Round active, String message) {
        watchers(active).forEach(player -> player.sendMessage(message));
    }

    private List<Player> watchers(Round active) {
        List<Player> watchers = new ArrayList<>();
        active.players.values().stream()
                .filter(entry -> entry.body == null)
                .forEach(entry -> watchers.add(entry.player));
        previews.keySet().stream().map(plugin.getServer()::getPlayer)
                .filter(player -> player != null && player.getWorld() == active.world)
                .forEach(watchers::add);
        return watchers;
    }

    private final class RoleMenu implements InventoryHolder {
        private final MatchRole[] roles = MatchRole.values();
        private final Inventory inventory = plugin.getServer().createInventory(this, (roles.length + 8) / 9 * 9,
                Component.text("役職を選ぶ"));

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class MatchSlot {
        private final DiscArena arena;
        private final Set<UUID> queue = new LinkedHashSet<>();
        private Round active;
        private long autoStartAt;

        private MatchSlot(DiscArena arena) {
            this.arena = arena;
        }

        private String status() {
            if (active != null) {
                return "プレイ中";
            }
            return queue.isEmpty() ? "空" : "マッチ待機中";
        }
    }

    private static final class Round {
        private final MatchSlot match;
        private final DiscArena arena;
        private final MatchLoot loot;
        private final Map<UUID, Entrant> players = new LinkedHashMap<>();
        private final BossBar board = BossBar.bossBar(
                Component.empty(), 1, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        private MatchHud hud;
        private World world;
        private MatchBots.ArenaChunks botChunks;
        private long startsAt;
        private long endsAt;
        private long lastThrowAt;
        private long lastExchangeAt;
        private boolean suddenDeath;
        private int spawnCursor;

        private Round(MatchSlot match, MatchLoot loot) {
            this.match = match;
            this.arena = match.arena;
            this.loot = loot;
        }
    }

    private static final class Entrant {
        private final Round round;
        private Player player;
        private final MatchPlayerState original;
        private final Color armorColor;
        private final MatchRole role;
        private int energy = DiscEnergy.MAX;
        private int fragmentResupplies;
        private final MatchScore score = new MatchScore();
        private final LinkedHashMap<UUID, Long> recentAttackers = new LinkedHashMap<>();
        private long respawnAt;
        private long wardenReadyAt;
        private long retryAt;
        private List<RetainedDisc> discsAtDeath = List.of();
        private final List<RetainedFlight> flightsAtDeath = new ArrayList<>();
        private int selectedSlotAtDeath;
        private MatchBots.Body body;
        private BotBrain brain;

        private Entrant(Round round, Player player, MatchPlayerState original, Color armorColor, MatchRole role) {
            this.round = round;
            this.player = player;
            this.original = original;
            this.armorColor = armorColor;
            this.role = role;
        }
    }

    private record RetainedDisc(int slot, ItemStack item) { }

    private record RetainedFlight(int slot, ItemStack item, DiscType launchedType) { }
}
