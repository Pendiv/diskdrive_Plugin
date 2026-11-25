package local.disc;

import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

record BotDamageEstimate(int armor, double toughness, int protection, double resistanceMultiplier) {
    static BotDamageEstimate of(Player player) {
        int armor = (int) Math.floor(attribute(player, Attribute.ARMOR));
        double toughness = attribute(player, Attribute.ARMOR_TOUGHNESS);
        int protection = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece != null && !piece.isEmpty()) {
                protection += piece.getEnchantmentLevel(Enchantment.PROTECTION)
                        + 2 * piece.getEnchantmentLevel(Enchantment.PROJECTILE_PROTECTION);
            }
        }
        var resistance = player.getPotionEffect(PotionEffectType.RESISTANCE);
        double resistanceMultiplier = resistance == null ? 1 : Math.max(0, 1 - 0.2 * (resistance.getAmplifier() + 1));
        return new BotDamageEstimate(armor, toughness, Math.min(protection, 20), resistanceMultiplier);
    }

    double landed(DiscType type, double raw) {
        if (raw <= 0) {
            return 0;
        }
        if (type == DiscType.WARDEN) {
            return raw * resistanceMultiplier;
        }
        double effectiveArmor = Math.min(20, Math.max(armor * 0.2, armor - raw / (2 + toughness / 4)));
        return raw * (1 - effectiveArmor / 25) * resistanceMultiplier * (1 - protection / 25.0);
    }

    private static double attribute(Player player, Attribute attribute) {
        var value = player.getAttribute(attribute);
        return value == null ? 0 : value.getValue();
    }
}
