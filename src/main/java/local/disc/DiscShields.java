package local.disc;

import static io.papermc.paper.datacomponent.DataComponentTypes.BLOCKS_ATTACKS;
import static io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE;
import static io.papermc.paper.datacomponent.DataComponentTypes.MAX_DAMAGE;

import io.papermc.paper.datacomponent.item.BlocksAttacks;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class DiscShields implements Listener {
    private static final int DURABILITY = 6;
    private static final int FREE_REFLECTION_TICKS = 10;
    private static final int REFLECTION_COOLDOWN = 20;
    private static final ThreadLocal<Player> DISC_DAMAGE_TARGET = new ThreadLocal<>();

    static ItemStack create() {
        ItemStack shield = new ItemStack(Material.SHIELD);
        normalize(shield);
        return shield;
    }

    static void applyIncomingDamage(LivingEntity target, double damage, DamageSource source) {
        if (!(target instanceof Player player) || !isGameWorld(player.getWorld())) {
            target.damage(damage, source);
            return;
        }
        Player previous = DISC_DAMAGE_TARGET.get();
        DISC_DAMAGE_TARGET.set(player);
        try {
            target.damage(damage, source);
        } finally {
            if (previous == null) {
                DISC_DAMAGE_TARGET.remove();
            } else {
                DISC_DAMAGE_TARGET.set(previous);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativeShieldWear(PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        if (player == DISC_DAMAGE_TARGET.get() && isShield(event.getItem()) && isShield(player.getActiveItem())) {
            event.setCancelled(true);
        }
    }

    static void reflect(Player player) {
        if (!isGameWorld(player.getWorld()) || !player.hasActiveItem()
                || player.getActiveItemUsedTime() <= FREE_REFLECTION_TICKS) {
            return;
        }

        EquipmentSlot hand = player.getActiveItemHand();
        PlayerInventory inventory = player.getInventory();
        ItemStack shield = inventory.getItem(hand);
        if (!isShield(shield)) {
            return;
        }

        normalize(shield);
        int damage = Objects.requireNonNullElse(shield.getData(DAMAGE), 0) + 1;
        player.setCooldown(shield, Math.max(player.getCooldown(shield), REFLECTION_COOLDOWN));
        player.clearActiveItem();
        if (damage >= DURABILITY) {
            new PlayerItemBreakEvent(player, shield.clone()).callEvent();
            player.broadcastSlotBreak(hand);
            inventory.setItem(hand, null);
        } else {
            shield.setData(DAMAGE, damage);
            inventory.setItem(hand, shield);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onShieldUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        ItemStack item = event.getItem();
        if ((action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)
                && isGameWorld(event.getPlayer().getWorld()) && isShield(item)) {
            // Paper exposes the live hand stack before native use starts.
            normalize(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        normalizeInventory(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        normalizeInventory(event.getPlayer());
    }

    static void normalizeInventory(Player player) {
        if (!isGameWorld(player.getWorld())) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack shield = inventory.getItem(slot);
            if (isShield(shield) && normalize(shield)) {
                inventory.setItem(slot, shield);
            }
        }
    }

    private static boolean normalize(ItemStack shield) {
        Integer originalMaximum = shield.getData(MAX_DAMAGE);
        boolean changed = !Objects.equals(originalMaximum, DURABILITY);
        if (changed) {
            int previousDamage = Objects.requireNonNullElse(shield.getData(DAMAGE), 0);
            int maximum = originalMaximum == null || originalMaximum <= 0
                    ? Material.SHIELD.asItemType().getDefaultData(MAX_DAMAGE) : originalMaximum;
            shield.setData(
                    DAMAGE,
                    Math.clamp(Math.round(previousDamage * (float) DURABILITY / maximum), 0, DURABILITY - 1));
            shield.setData(MAX_DAMAGE, DURABILITY);
        }

        BlocksAttacks blocking = shield.getData(BLOCKS_ATTACKS);
        if (blocking == null) {
            blocking = Material.SHIELD.asItemType().getDefaultData(BLOCKS_ATTACKS);
            shield.setData(BLOCKS_ATTACKS, withoutDelay(blocking));
            return true;
        }
        if (blocking.blockDelaySeconds() != 0) {
            shield.setData(BLOCKS_ATTACKS, withoutDelay(blocking));
            return true;
        }
        return changed;
    }

    private static BlocksAttacks withoutDelay(BlocksAttacks source) {
        BlocksAttacks.Builder builder = BlocksAttacks.blocksAttacks().blockDelaySeconds(0)
                .disableCooldownScale(source.disableCooldownScale())
                .damageReductions(source.damageReductions()).itemDamage(source.itemDamage());
        if (source.bypassedBy() != null) {
            builder.bypassedBy(source.bypassedBy());
        }
        if (source.blockSound() != null) {
            builder.blockSound(source.blockSound());
        }
        if (source.disableSound() != null) {
            builder.disableSound(source.disableSound());
        }
        return builder.build();
    }

    private static boolean isShield(ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.SHIELD;
    }

    private static boolean isGameWorld(World world) {
        return DiscArena.isArena(world) || DiscLobby.isLobby(world);
    }
}
