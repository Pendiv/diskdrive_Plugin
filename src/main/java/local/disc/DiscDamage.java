package local.disc;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.keys.DamageTypeKeys;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;

enum DiscDamage {
    THROWN(DamageTypeKeys.THROWN, "disc:throw"),
    SONIC_BOOM(DamageTypeKeys.SONIC_BOOM, "disc:sonic_boom"),
    BURN(DamageTypeKeys.ON_FIRE, "disc:burn");

    final TypedKey<DamageType> vanilla;
    final TypedKey<DamageType> withoutCooldown;

    DiscDamage(TypedKey<DamageType> vanilla, String key) {
        this.vanilla = vanilla;
        this.withoutCooldown = TypedKey.create(RegistryKey.DAMAGE_TYPE, key);
    }

    DamageSource.Builder source(boolean ignoreCooldown) {
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE);
        return DamageSource.builder(registry.getOrThrow(ignoreCooldown ? withoutCooldown : vanilla));
    }

    boolean matches(DamageType type) {
        return vanilla.key().equals(type.getKey()) || withoutCooldown.key().equals(type.getKey());
    }
}
