package local.disc;

import java.util.List;
import java.util.Locale;
import org.bukkit.inventory.meta.ItemMeta;

enum DiscSkin {
    COPPER("銅"),
    IRON("鉄"),
    GOLD("金"),
    DIAMOND("ダイヤモンド"),
    NETHERITE("ネザライト");

    private final String materialName;

    DiscSkin(String materialName) {
        this.materialName = materialName;
    }

    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    String displayName() {
        return materialName + "の投擲ディスク";
    }

    void apply(ItemMeta meta) {
        applyModelData(meta, "disc:" + id());
    }

    static void applyModelData(ItemMeta meta, String model) {
        var modelData = meta.getCustomModelDataComponent();
        modelData.setStrings(List.of(model));
        meta.setCustomModelDataComponent(modelData);
    }

    static DiscSkin find(String id) {
        for (DiscSkin skin : values()) {
            if (skin.id().equalsIgnoreCase(id)) {
                return skin;
            }
        }
        return null;
    }
}
