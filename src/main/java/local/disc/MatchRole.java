package local.disc;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

enum MatchRole {
    NONE("役職なし", "normal", 1,
            "初期装備:通常ディスクx1"),

    MULTI_THROWER("マルチスロワー", "multi", 0.78,
            "初期装備:マルチディスクx2",
            "共通の投擲・回収CTと、",
            "盾・ウォーデンディスクのCTを22%軽減する。"),

    SNIPER("スナイパー", "snipe", 1,
            "初期装備:スナイプディスクx1",
            "ディスクの飛翔速度が往路で60%上昇する。",
            "16m以内では投擲ダメージが減少し、",
            "遠距離では大幅に上昇する。"),

    SHIFTER("シフター", "normal", 1,
            "初期装備:通常ディスクx1、盾x1",
            "復活後も盾を獲得する。",
            "地上でShiftを2回押すと前方にジャンプする。",
            "しゃがむことで、より早くHPを回復できる。"),

    DEMENTOR("ディメンター", "ender", 1,
            "初期装備:エンダーディスクx1",
            "Shiftを2回押すと視点方向へ最大5m TPできる。",
            "ガラスは貫通し、ほかの壁の手前で止まる。"),

    JUMPER("ジャンパー", "normal", 1,
            "初期装備:通常ディスクx1",
            "ジャンプ力が高く、落下ダメージが半減する。",
            "空中でSpaceを押し直すと1回だけ追加ジャンプできる。",
            "追加ジャンプの方向は移動キーで調整できる。"),

    ICHOR("イコル", "normal", 1,
            "初期装備:通常ディスクx1",
            "4秒ごとに0.5ハートを自動回復する。",
            "しゃがみ始めると、移動速度上昇Iが12秒間付く。",
            "再発動は加速を得てから18秒後。"),

    MORE_THROWER("モアスロワー", "normal", 1,
            "初期装備:通常ディスクx2（両手に1枚ずつ）",
            "メインハンドから投げられないときは、",
            "右クリックでオフハンドから投げられる。",
            "投擲と回収のCTは手ごとに独立する。",
            "ウォーデンディスクの固有CTは両手で共有する。"),

    SINGER("シンガー", "darkness", 1,
            "初期装備:暗闇ディスクx1",
            "暗闇ディスクで撃破すると、",
            "そのディスクがウォーデンディスクに変わって戻る。",
            "ウォーデンの衝撃波は基礎11 HP、半径4.25m。",
            "同じ相手にも繰り返し当たる。"),

    STRIKER("ストライカー", "normal", 1,
            "初期装備:通常ディスクx1",
            "3m以内では投擲ダメージが2倍になる。",
            "離れるほどダメージが減り、15m以上では0.2倍になる。"),

    RECONSTRUCTOR("リコンストラクター", "normal", 1,
            "初期装備:通常ディスクx1、ディスクの破片x3",
            "箱を開けるたびに破片を1個、試合中は毎分2個獲得する。",
            "破片のダメージが0.5 HP上昇する。",
            "破片にCTがなく、被弾直後の相手にもダメージが入る。",
            "倒されると所持していた破片をすべて失う。"),

    MATRIX("マトリクス", "laser", 1,
            "初期装備:レーザーディスクx2",
            "レーザーディスクの投擲と回収にかかるCTが33%短くなる。",
            "炎系のダメージを半減する。");

    private static final NamespacedKey SELECTION = new NamespacedKey("disc", "match_role");
    private static final MatchRole[] BOT_ROLES = {MULTI_THROWER, SNIPER, SHIFTER, DEMENTOR, JUMPER, ICHOR, MORE_THROWER,
            SINGER, STRIKER, RECONSTRUCTOR, MATRIX};
    private static final double SNIPER_CLOSE_RANGE = 16;
    private static final double SNIPER_MID_RANGE = 32;
    private static final double SNIPER_FAR_RANGE = 52;
    private static final double STRIKER_CLOSE_RANGE = 3;
    private static final double STRIKER_FAR_RANGE = 15;

    private final String displayName;
    private final String starterDiscId;
    private final List<String> description;
    private final double cooldownMultiplier;

    MatchRole(String displayName, String starterDiscId, double cooldownMultiplier, String... description) {
        this.displayName = displayName;
        this.starterDiscId = starterDiscId;
        this.description = List.of(description);
        this.cooldownMultiplier = cooldownMultiplier;
    }

    static MatchRole byId(String id) {
        for (MatchRole role : values()) {
            if (role.id().equalsIgnoreCase(id)) {
                return role;
            }
        }
        return null;
    }

    static MatchRole selected(Player player) {
        MatchRole role = byId(player.getPersistentDataContainer().get(SELECTION, PersistentDataType.STRING));
        return role == null ? NONE : role;
    }

    static MatchRole randomForBot() {
        return BOT_ROLES[ThreadLocalRandom.current().nextInt(BOT_ROLES.length)];
    }

    void select(Player player) {
        player.getPersistentDataContainer().set(SELECTION, PersistentDataType.STRING, id());
    }

    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    String displayName() {
        return displayName;
    }

    List<String> description() {
        return description;
    }

    DiscType starterDisc() {
        DiscType disc = DiscItem.findType(starterDiscId);
        return disc == null ? DiscType.NORMAL : disc;
    }

    int starterDiscCount() {
        return switch (this) {
            case MULTI_THROWER, MORE_THROWER, MATRIX -> 2;
            default -> 1;
        };
    }

    int cooldownTicks(int baseTicks) {
        return (int) Math.round(baseTicks * cooldownMultiplier);
    }

    int discCooldownTicks(DiscType type, int baseTicks) {
        return this == MATRIX && type == DiscType.LASER ? (int) Math.round(baseTicks * 0.67) : cooldownTicks(baseTicks);
    }

    static double sniperDamageMultiplier(double distance) {
        if (distance <= SNIPER_MID_RANGE) {
            double progress = Math.max(0, (distance - SNIPER_CLOSE_RANGE) / (SNIPER_MID_RANGE - SNIPER_CLOSE_RANGE));
            return 0.6 + 1.4 * progress;
        }
        return 2 + Math.min(1, (distance - SNIPER_MID_RANGE) / (SNIPER_FAR_RANGE - SNIPER_MID_RANGE));
    }

    static double strikerDamageMultiplier(double distance) {
        double progress = Math.clamp(
                (distance - STRIKER_CLOSE_RANGE) / (STRIKER_FAR_RANGE - STRIKER_CLOSE_RANGE), 0, 1);
        return 2 - 1.8 * progress;
    }
}
