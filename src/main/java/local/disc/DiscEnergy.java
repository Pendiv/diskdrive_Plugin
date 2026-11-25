package local.disc;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class DiscEnergy {
    static final int MAX = 100;
    private static final int FIRST_GLYPH = 0xe100;
    private static final Key FONT = Key.key("artism", "energy");

    private DiscEnergy() {}

    static Component display(int energy, boolean packLoaded) {
        if (!packLoaded) {
            return Component.text("EN " + energy + "/" + MAX, NamedTextColor.LIGHT_PURPLE);
        }
        int level = (energy + 9) / 10;
        return Component.text(Character.toString(FIRST_GLYPH + level)).font(FONT);
    }
}
