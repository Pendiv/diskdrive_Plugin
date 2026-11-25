package local.disc;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

final class DiscEditor implements BasicCommand, Listener {
    private final DiscPlugin plugin;
    private final DiscMatch match;
    private final Set<UUID> editors = new HashSet<>();
    private final Map<UUID, FieldSession> fieldEditors = new HashMap<>();
    private final Map<UUID, String> preparingFields = new HashMap<>();
    private final Set<UUID> closingFields = new HashSet<>();
    private final Map<Material, Predicate<Location>> placements;
    private final DiscMechanisms mechanisms;
    private final MatchBoxLayout boxLayout;

    DiscEditor(
            DiscPlugin plugin, ItemBoxes boxes, DiscMatch match, DiscMechanisms mechanisms, MatchBoxLayout boxLayout) {
        this.plugin = plugin;
        this.match = match;
        this.mechanisms = mechanisms;
        this.boxLayout = boxLayout;
        placements = Map.of(Material.CHEST, ground -> {
            if (boxes.hasBoxAt(ground)) {
                return false;
            }
            boxes.place(ground, player -> List.of());
            return true;
        });
    }

    @Override
    public String permission() {
        return "disc.edit";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("編集モードはゲーム内から使ってください。");
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("box")) {
            editBox(player, args[1].toLowerCase(Locale.ROOT));
            return;
        }
        if (args.length >= 1 && args.length <= 2 && args[0].equalsIgnoreCase("field")) {
            openField(player, args.length == 2 ? args[1] : null);
            return;
        }
        String action = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "start" -> {
                editors.add(player.getUniqueId());
                player.sendMessage("編集モードを開始しました。チェスト、縦向きの鎖、ピストン、音符ブロック、避雷針、TNT、ドラゴンの卵を特殊ブロックとして設置できます。");
            }
            case "end" -> {
                if (!leaveField(player)) {
                    editors.remove(player.getUniqueId());
                    player.sendMessage("編集モードを終了しました。");
                }
            }
            default -> player.sendMessage(fieldUsage() + "\n/edit start | end\n/edit box outer | center | remove");
        }
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("box")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("outer", "center", "remove").stream().filter(action -> action.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("field")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return match.fieldTargets().stream().filter(target -> target.startsWith(prefix)).toList();
        }
        if (args.length >= 2) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return List.of("field", "start", "end", "box").stream().filter(action -> action.startsWith(prefix)).toList();
    }

    private String fieldUsage() {
        return "/edit field [" + String.join(" | ", match.fieldTargets()) + "]";
    }

    private void editBox(Player player, String action) {
        if (!isFieldEditing(player) || !DiscArena.isArena(player.getWorld())) {
            player.sendMessage("/edit field で試合場に入り、編集するボックスに照準を合わせてください。");
            return;
        }
        MatchBoxLayout.Kind kind = switch (action) {
            case "outer" -> MatchBoxLayout.Kind.OUTER;
            case "center" -> MatchBoxLayout.Kind.CENTER;
            default -> null;
        };
        if (kind == null && !action.equals("remove")) {
            player.sendMessage("/edit box outer | center | remove");
            return;
        }
        UUID target = boxLayout.aimedPreview(player);
        if (target == null) {
            player.sendMessage("編集するボックスに照準を合わせてください。");
            return;
        }
        try {
            boolean changed = kind == null ? boxLayout.remove(player.getWorld(), target)
                    : boxLayout.setKind(player.getWorld(), target, kind);
            if (!changed) {
                player.sendMessage("ボックスが見つかりませんでした。");
            } else if (kind == null) {
                player.sendMessage("ボックスを試合場から削除しました。");
            } else {
                player.sendMessage(kind.displayName() + "に設定しました。");
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not change a Disc match box", error);
            player.sendMessage("ボックスを編集できませんでした。");
        }
    }

    boolean hasFieldEditors(String canonicalTarget) {
        return preparingFields.containsValue(canonicalTarget) || fieldEditors.values().stream()
                .anyMatch(session -> session.world().getKey().getKey().equals(canonicalTarget));
    }

    boolean isFieldEditing(Player player) {
        return fieldEditors.containsKey(player.getUniqueId());
    }

    private void openField(Player player, String requestedTarget) {
        UUID id = player.getUniqueId();
        if (preparingFields.containsKey(id)) {
            player.sendMessage("フィールドを準備中です。準備が終わってから実行してください。");
            return;
        }
        if (player.isDead()) {
            player.sendMessage("リスポーンしてからフィールドを編集してください。");
            return;
        }
        String target = match.resolveFieldTarget(requestedTarget);
        if (target == null) {
            player.sendMessage(fieldUsage());
            return;
        }
        FieldSession current = fieldEditors.get(id);
        if (current != null) {
            if (current.world().getKey().getKey().equals(target)) {
                player.sendMessage("指定したフィールドを編集中です。退出は /edit end です。");
                return;
            }
            finishField(player, true, true);
            if (fieldEditors.containsKey(id) || !player.isOnline() || player.isDead()) {
                return;
            }
        }

        preparingFields.put(id, target);
        try {
            Location destination = match.fieldForEdit(player, target);
            if (destination == null || !plugin.isEnabled() || !player.isOnline() || player.isDead()) {
                return;
            }

            plugin.returnDiscs(player);
            MatchPlayerState previous = MatchPlayerState.capture(player);
            previous.save(player);
            fieldEditors.put(id, new FieldSession(destination.getWorld(), previous));

            if (!player.teleport(destination) || !player.isOnline() || player.getWorld() != destination.getWorld()) {
                throw new IllegalStateException("Field edit teleport failed");
            }
            player.setGameMode(GameMode.CREATIVE);
            if (!player.isOnline() || player.getGameMode() != GameMode.CREATIVE
                    || player.getWorld() != destination.getWorld()) {
                throw new IllegalStateException("Field edit game mode change was cancelled");
            }
            player.setAllowFlight(true);
            player.setFlying(true);
            player.setFallDistance(0);
            editors.remove(id);
            boxLayout.showPreviews(destination.getWorld());
            player.sendMessage("フィールドを編集しています。/edit start で特殊ブロックを有効にし、/edit end で元の場所へ戻ります。");
        } catch (RuntimeException error) {
            plugin.getLogger().log(
                    Level.WARNING, "Could not open the Disc field editor for " + player.getName(), error);
            if (player.isOnline()) {
                player.sendMessage("フィールドの編集を開始できませんでした。");
                finishField(player, false, false);
            }
        } finally {
            preparingFields.remove(id);
        }
    }

    boolean leaveField(Player player) {
        if (preparingFields.containsKey(player.getUniqueId())) {
            player.sendMessage("フィールドを準備中です。準備が終わってから実行してください。");
            return true;
        }
        return finishField(player, true, true);
    }

    private boolean finishField(Player player, boolean save, boolean notify) {
        UUID id = player.getUniqueId();
        FieldSession session = fieldEditors.get(id);
        if (session == null) {
            return false;
        }
        if (!closingFields.add(id)) {
            return true;
        }

        if (notify) {
            editors.remove(id);
        }
        try {
            plugin.returnDiscs(player);
            if (session.previous().restore(player)) {
                fieldEditors.remove(id, session);
                hideBoxPreviewsIfIdle(session.world());
                if (save) {
                    saveField(player, session.world());
                }
                if (notify) {
                    player.sendMessage("フィールドの編集を終了しました。");
                }
            } else if (player.isOnline()) {
                player.sendMessage("元の状態に戻れませんでした。/edit end で再試行できます。");
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not restore Disc field editor " + player.getName(), error);
            if (player.isOnline()) {
                player.sendMessage("元の状態に戻れませんでした。/edit end で再試行できます。");
            }
        } finally {
            closingFields.remove(id);
            if (fieldEditors.get(id) == session) {
                hideBoxPreviewsIfIdle(session.world());
            }
        }
        return true;
    }

    private void saveField(Player player, World world) {
        try {
            world.save();
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not save the Disc arena after editing", error);
            player.sendMessage("フィールドを保存できませんでした。");
        }
    }

    void close() {
        for (UUID id : List.copyOf(fieldEditors.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                finishField(player, false, false);
            }
        }
        fieldEditors.clear();
        editors.clear();
        boxLayout.hideAllPreviews();
    }

    private void hideBoxPreviewsIfIdle(World world) {
        boolean anyInWorld = fieldEditors.entrySet().stream()
                .filter(entry -> entry.getValue().world() == world)
                .map(entry -> plugin.getServer().getPlayer(entry.getKey()))
                .anyMatch(player -> player != null && player.isOnline() && player.getWorld() == world);
        if (!anyInWorld) {
            boxLayout.hidePreviews(world);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!isEditing(player) || !event.canBuild()) {
            return;
        }
        Block block = event.getBlockPlaced();
        Predicate<Location> place = placements.get(block.getType());
        if (place == null) {
            return;
        }

        event.setCancelled(true);
        try {
            Location ground = block.getLocation().add(0.5, 0, 0.5);
            if (isFieldEditing(player) && DiscArena.isArena(block.getWorld())) {
                if (boxLayout.addOuter(ground)) {
                    player.sendMessage("外周ボックスを登録しました。種類の変更は /edit box center でできます。");
                } else {
                    player.sendMessage("この位置にはすでにアイテムボックスがあります。");
                }
            } else if (place.test(ground)) {
                player.sendMessage("アイテムボックスを設置しました。");
            } else {
                player.sendMessage("この位置にはすでにアイテムボックスがあります。");
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            player.sendMessage("この位置にアイテムボックスを設置できませんでした。");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaced(BlockPlaceEvent event) {
        if (event.canBuild() && mechanisms.updatePlacement(event.getBlockPlaced(), isEditing(event.getPlayer()))) {
            event.getPlayer().sendMessage("特殊ブロックを登録しました。");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block chain = event.getClickedBlock();
        EquipmentSlot hand = event.getHand();
        if (!isEditing(player)
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getBlockFace() != BlockFace.DOWN
                || chain == null || hand == null || !mechanisms.isChain(chain)) {
            return;
        }
        ItemStack held = player.getInventory().getItem(hand);
        if (held.isEmpty() || DiscMechanisms.isChainItem(held.getType())) {
            return;
        }
        event.setCancelled(true);
        if (!mechanisms.mount(chain, held.asOne())) {
            player.sendMessage("鎖の下にアイテムを吊るせませんでした。");
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.getInventory().setItem(hand, held.getAmount() == 1 ? null : held.asQuantity(held.getAmount() - 1));
        }
        player.sendMessage("鎖にアイテムを吊るしました。");
    }

    boolean isEditing(Player player) {
        return editors.contains(player.getUniqueId()) && player.hasPermission(permission());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        editors.remove(id);
        FieldSession session = fieldEditors.remove(id);
        if (session != null) {
            hideBoxPreviewsIfIdle(session.world());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        FieldSession session = fieldEditors.get(id);
        if (session != null && event.getFrom() == session.world() && player.getWorld() != session.world()
                && !preparingFields.containsKey(id)) {
            finishField(player, false, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        if (!preparingFields.containsKey(player.getUniqueId())) {
            finishField(player, false, true);
        }
    }

    private record FieldSession(World world, MatchPlayerState previous) { }
}
