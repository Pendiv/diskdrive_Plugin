package local.disc;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;

final class DiscItem implements BasicCommand {
    static final NamespacedKey WARDEN_COOLDOWN_GROUP = new NamespacedKey("disc", "warden");
    static final NamespacedKey OFF_HAND_WARDEN_GROUP = new NamespacedKey("disc", "off_hand_warden");
    static final int OFF_HAND_SLOT = 40;

    private final DiscPlugin plugin;
    private final NamespacedKey key;

    DiscItem(DiscPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "throwing_disc");
    }

    boolean matches(ItemStack item) {
        return type(item) != null;
    }

    DiscType type(ItemStack item) {
        if (item == null || item.isEmpty()
                || !item.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            return null;
        }
        for (DiscType type : DiscType.values()) {
            if (item.getType() == type.material()) {
                return type;
            }
        }
        return null;
    }

    ItemStack create(DiscType type) {
        return create(type, null);
    }

    private ItemStack create(DiscType type, DiscSkin skin) {
        ItemStack item = new ItemStack(type.material());
        item.editMeta(meta -> {
            meta.displayName(Component.text(skin == null ? type.displayName() : skin.displayName())
                    .decoration(TextDecoration.ITALIC, false));
            if (type == DiscType.NORMAL) {
                (skin == null ? DiscSkin.IRON : skin).apply(meta);
            } else if (type == DiscType.MEGA) {
                DiscSkin.applyModelData(meta, "disc:mega");
            }
            String use = type.returnsToOwner() ? "右クリックで投げる" : "右クリックで投げる（消費）";
            meta.lore(List.of(Component.text(use).decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            applyCooldownMetadata(meta, type, false);
        });
        return item;
    }

    void giveMissing(Player player, List<DiscThrow> flights) {
        PlayerInventory inventory = player.getInventory();
        EnumSet<DiscType> owned = EnumSet.noneOf(DiscType.class);
        for (ItemStack item : inventory.getContents()) {
            DiscType held = type(item);
            if (held != null) {
                owned.add(held);
            }
        }
        DiscType cursor = type(player.getItemOnCursor());
        if (cursor != null) {
            owned.add(cursor);
        }
        ItemStack[] storage = inventory.getStorageContents();
        int available = emptyStorageSlots(storage);
        boolean offHandReserved = !isEmpty(inventory.getItemInOffHand());
        for (DiscThrow flight : flights) {
            if (!flight.type().returnsToOwner()) {
                continue;
            }
            owned.add(type(flight.item()));
            if (flight.slot() < storage.length || offHandReserved) {
                available--;
            } else {
                offHandReserved = true;
            }
        }

        EnumSet<DiscType> missing = EnumSet.complementOf(owned);
        if (missing.isEmpty()) {
            player.sendMessage("投擲ディスクは全種類そろっています。");
            return;
        }
        int given = 0;
        for (DiscType type : missing) {
            if (given >= available || !inventory.addItem(create(type)).isEmpty()) {
                break;
            }
            given++;
        }
        if (given == 0) {
            player.sendMessage("インベントリに空きがありません。");
        } else {
            int remaining = missing.size() - given;
            String note = remaining > 0 ? " 残り" + remaining + "種を受け取るには空きが必要です。" : "";
            player.sendMessage("不足していた投擲ディスクを" + given + "種受け取りました。" + note);
        }
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.isEmpty();
    }

    private static int emptyStorageSlots(ItemStack[] storage) {
        return (int) Arrays.stream(storage).filter(DiscItem::isEmpty).count();
    }

    void refreshMetadata(Player player, boolean moreThrower) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            DiscType type = type(item);
            if (type == null) {
                continue;
            }

            ItemMeta meta = item.getItemMeta();
            boolean cooldownChanged = applyCooldownMetadata(meta, type, moreThrower && slot == OFF_HAND_SLOT);
            boolean nameChanged = renameLegacyDisc(meta, type);
            boolean skinChanged = applyDefaultSkin(meta, type);
            if ((cooldownChanged || nameChanged || skinChanged) && item.setItemMeta(meta)) {
                inventory.setItem(slot, item);
            }
        }
    }

    void refreshOffHandMetadata(Player player, boolean moreThrower) {
        PlayerInventory inventory = player.getInventory();
        ItemStack item = inventory.getItemInOffHand();
        DiscType type = type(item);
        if (type != null) {
            ItemMeta meta = item.getItemMeta();
            boolean cooldownChanged = applyCooldownMetadata(meta, type, moreThrower);
            boolean skinChanged = applyDefaultSkin(meta, type);
            if ((cooldownChanged || skinChanged) && item.setItemMeta(meta)) {
                inventory.setItemInOffHand(item);
            }
        }
    }

    boolean needsMetadataRefresh(ItemStack item) {
        DiscType type = type(item);
        if (type == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (needsDefaultSkin(meta, type)) {
            return true;
        }
        if (!meta.hasUseCooldown()) {
            return type == DiscType.FRAGMENT;
        }
        UseCooldownComponent cooldown = meta.getUseCooldown();
        NamespacedKey group = cooldown.getCooldownGroup();
        if (type == DiscType.FRAGMENT) {
            return !DiscPlugin.FRAGMENT_COOLDOWN_GROUP.equals(group)
                    || cooldown.getCooldownSeconds() != DiscPlugin.FRAGMENT_COOLDOWN_TICKS / 20F;
        }
        return DiscPlugin.OFF_HAND_COOLDOWN_GROUP.equals(group) || OFF_HAND_WARDEN_GROUP.equals(group);
    }

    private static boolean needsDefaultSkin(ItemMeta meta, DiscType type) {
        return type == DiscType.NORMAL
                && !meta.hasItemModel()
                && meta.getCustomModelDataComponent().getStrings().isEmpty();
    }

    private static boolean applyDefaultSkin(ItemMeta meta, DiscType type) {
        if (!needsDefaultSkin(meta, type)) {
            return false;
        }
        DiscSkin.IRON.apply(meta);
        return true;
    }

    private static boolean renameLegacyDisc(ItemMeta meta, DiscType type) {
        String oldName = switch (type) {
            case SWAP -> "クリエイターの投擲ディスク";
            case HOMING -> "ラバチキンの投擲ディスク";
            default -> null;
        };
        if (oldName == null || !Component.text(oldName)
                .decoration(TextDecoration.ITALIC, false)
                .equals(meta.displayName())) {
            return false;
        }
        meta.displayName(Component.text(type.displayName()).decoration(TextDecoration.ITALIC, false));
        return true;
    }

    static NamespacedKey wardenGroup(boolean offHand) {
        return offHand ? OFF_HAND_WARDEN_GROUP : WARDEN_COOLDOWN_GROUP;
    }

    private static boolean applyCooldownMetadata(ItemMeta meta, DiscType type, boolean offHand) {
        boolean warden = type == DiscType.WARDEN;
        NamespacedKey group = warden ? wardenGroup(offHand) : DiscPlugin.throwCooldownGroup(type, offHand);
        float seconds = switch (type) {
            case WARDEN -> 30F;
            case FRAGMENT -> DiscPlugin.FRAGMENT_COOLDOWN_TICKS / 20F;
            default -> DiscPlugin.THROW_COOLDOWN_TICKS / 20F;
        };
        UseCooldownComponent cooldown = meta.getUseCooldown();
        if (meta.hasUseCooldown()
                && group.equals(cooldown.getCooldownGroup())
                && seconds == cooldown.getCooldownSeconds()) {
            return false;
        }
        cooldown.setCooldownGroup(group);
        cooldown.setCooldownSeconds(seconds);
        meta.setUseCooldown(cooldown);
        return true;
    }

    @Override
    public String permission() {
        return "disc.give";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length != 0) {
            boolean handled = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> give(sender, args);
                case "kit" -> giveKit(sender, args);
                case "box" -> placeBox(source, args);
                default -> false;
            };
            if (handled) {
                return;
            }
        }
        sender.sendMessage(
                "/disc give [プレイヤー または 種類]\n/disc give <プレイヤー> <種類>\n/disc kit [プレイヤー]\n/disc box <x> <y> <z>");
    }

    private boolean placeBox(CommandSourceStack source, String[] args) {
        if (args.length != 4) {
            return false;
        }
        try {
            Location ground = new Location(source.getLocation().getWorld(), Double.parseDouble(args[1]),
                    Double.parseDouble(args[2]), Double.parseDouble(args[3]));
            plugin.placeItemBox(ground, player -> List.of());
            source.getSender().sendMessage("アイテムボックスを設置しました。");
        } catch (IllegalArgumentException error) {
            source.getSender().sendMessage("設置できる座標を指定してください。");
        } catch (IllegalStateException error) {
            source.getSender().sendMessage("アイテムボックスを設置できませんでした。");
        }
        return true;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (args.length > 3) {
            return false;
        }
        String requestedItem = "normal";
        Player target = null;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayerExact(args[1]);
            if (args.length == 3) {
                if (target == null) {
                    sender.sendMessage("オンラインのプレイヤーが見つかりません。");
                    return true;
                }
                requestedItem = args[2];
            } else if (target == null) {
                requestedItem = args[1];
            }
        }
        DiscSkin skin = DiscSkin.find(requestedItem);
        DiscType type = skin == null ? findType(requestedItem) : DiscType.NORMAL;
        if (type == null) {
            sender.sendMessage(args.length == 3
                    ? "その種類の投擲ディスクはありません。"
                    : "オンラインのプレイヤーまたは投擲ディスクの種類が見つかりません。");
            return true;
        }
        if (target == null) {
            target = resolveTarget(sender, null);
            if (target == null) {
                return true;
            }
        }

        if (!target.getInventory().addItem(create(type, skin)).isEmpty()) {
            sender.sendMessage(target.getName() + "のインベントリに空きがありません。");
            return true;
        }
        reportGive(sender, target, skin == null ? type.displayName() : skin.displayName());
        return true;
    }

    private boolean giveKit(CommandSender sender, String[] args) {
        if (args.length > 2) {
            return false;
        }
        Player target = resolveTarget(sender, args.length == 2 ? args[1] : null);
        if (target == null) {
            return true;
        }

        PlayerInventory inventory = target.getInventory();
        DiscType[] types = DiscType.values();
        int emptySlots = emptyStorageSlots(inventory.getStorageContents());
        if (emptySlots < types.length) {
            sender.sendMessage(target.getName() + "のインベントリに空きが" + types.length + "枠必要です。");
            return true;
        }
        inventory.addItem(Arrays.stream(types).map(this::create).toArray(ItemStack[]::new));
        reportGive(sender, target, "投擲ディスク全" + types.length + "種");
        return true;
    }

    private Player resolveTarget(CommandSender sender, String name) {
        if (name != null) {
            Player player = plugin.getServer().getPlayerExact(name);
            if (player == null) {
                sender.sendMessage("オンラインのプレイヤーが見つかりません。");
            }
            return player;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage("配布先のプレイヤー名を指定してください。");
        return null;
    }

    static DiscType findType(String id) {
        if (id.equalsIgnoreCase("creator")) {
            return DiscType.SWAP;
        }
        if (id.equalsIgnoreCase("lava_chicken")) {
            return DiscType.HOMING;
        }
        if (id.equalsIgnoreCase("far")) {
            return DiscType.SNIPE;
        }
        if (id.equalsIgnoreCase("mall")) {
            return DiscType.ENDER;
        }
        if (id.equalsIgnoreCase("chirp")) {
            return DiscType.LASER;
        }
        if (id.equalsIgnoreCase("disc_fragment_5")) {
            return DiscType.FRAGMENT;
        }
        if (id.equalsIgnoreCase("megadisc") || id.equalsIgnoreCase("megadisk") || id.equalsIgnoreCase("otherside")) {
            return DiscType.MEGA;
        }
        for (DiscType type : DiscType.values()) {
            if (type.id().equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }

    private static void reportGive(CommandSender sender, Player target, String name) {
        target.sendMessage(name + "を受け取りました。");
        if (sender != target) {
            sender.sendMessage(target.getName() + "に" + name + "を渡しました。");
        }
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            return List.of("give", "kit", "box");
        }
        List<String> candidates = new ArrayList<>();
        boolean give = args[0].equalsIgnoreCase("give");
        boolean kit = args[0].equalsIgnoreCase("kit");
        if (args.length == 1) {
            candidates.addAll(List.of("give", "kit", "box"));
        }
        if (args.length == 2 && (give || kit)) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (!(sender instanceof Player viewer) || viewer.canSee(player)) {
                    candidates.add(player.getName());
                }
            }
        }
        if (give && (args.length == 3 || args.length == 2 && sender instanceof Player)) {
            for (DiscType type : DiscType.values()) {
                candidates.add(type.id());
            }
            for (DiscSkin skin : DiscSkin.values()) {
                candidates.add(skin.id());
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().distinct()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
