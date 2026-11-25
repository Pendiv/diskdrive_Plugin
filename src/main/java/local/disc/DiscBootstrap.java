package local.disc;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.tags.DamageTypeTagKeys;
import java.util.List;
import org.bukkit.damage.DamageScaling;

public final class DiscBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        var lifecycle = context.getLifecycleManager();
        lifecycle.registerEventHandler(RegistryEvents.DAMAGE_TYPE.compose(), event -> {
            for (DiscDamage damage : DiscDamage.values()) {
                event.registry().registerWith(damage.withoutCooldown, factory -> {
                    var builder = factory.copyFrom(damage.vanilla);
                    if (damage == DiscDamage.SONIC_BOOM) {
                        builder.damageScaling(DamageScaling.NEVER);
                    }
                });
            }
        });
        lifecycle.registerEventHandler(LifecycleEvents.TAGS.postFlatten(RegistryKey.DAMAGE_TYPE), event -> {
            var registrar = event.registrar();
            for (DiscDamage damage : DiscDamage.values()) {
                var inheritedTags = registrar.getAllTags().entrySet().stream()
                        .filter(entry -> entry.getValue().contains(damage.vanilla))
                        .map(entry -> entry.getKey())
                        .toList();
                for (var tag : inheritedTags) {
                    if (!tag.equals(DamageTypeTagKeys.BYPASSES_COOLDOWN)) {
                        registrar.addToTag(tag, List.of(damage.withoutCooldown));
                    }
                }
                registrar.addToTag(DamageTypeTagKeys.BYPASSES_COOLDOWN, List.of(damage.withoutCooldown));
            }
        });
    }
}
