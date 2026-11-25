package local.disc;

import java.util.Locale;
import org.bukkit.Material;

enum DiscType {
    NORMAL(Material.MUSIC_DISC_13, "投擲ディスク", 5),
    MAGMA(Material.MUSIC_DISC_PIGSTEP, "マグマの投擲ディスク", 2),
    LEVITATION(Material.MUSIC_DISC_TEARS, "浮遊の投擲ディスク", 3.5),
    GLOWING(Material.MUSIC_DISC_BOUNCE, "発光の投擲ディスク", 4.5),
    WARDEN(Material.MUSIC_DISC_5, "ウォーデンの投擲ディスク", 10),
    DARKNESS(Material.MUSIC_DISC_STAL, "暗闇の投擲ディスク", 4.5),
    MULTI(Material.MUSIC_DISC_PRECIPICE, "マルチディスク", 3.5),
    SWAP(Material.MUSIC_DISC_CREATOR, "スワップディスク", 4),
    HOMING(Material.MUSIC_DISC_LAVA_CHICKEN, "ホーミングディスク", 4.5),
    SNIPE(Material.MUSIC_DISC_FAR, "スナイプディスク", 4),
    ENDER(Material.MUSIC_DISC_MALL, "エンダーディスク", 3.5),
    LASER(Material.MUSIC_DISC_CHIRP, "レーザーディスク", 4.5),
    FRAGMENT(Material.DISC_FRAGMENT_5, "ディスクの破片", 3),
    MEGA(Material.MUSIC_DISC_OTHERSIDE, "MegaDisc", 5);

    private final Material material;
    private final String displayName;
    private final double damage;

    DiscType(Material material, String displayName, double damage) {
        this.material = material;
        this.displayName = displayName;
        this.damage = damage;
    }

    Material material() {
        return material;
    }

    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    String displayName() {
        return displayName;
    }

    double damage() {
        return damage;
    }

    boolean returnsToOwner() {
        return this != FRAGMENT;
    }
}
