package local.disc;

import static org.bukkit.persistence.PersistentDataType.BOOLEAN;
import static org.bukkit.persistence.PersistentDataType.INTEGER_ARRAY;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

final class TrapResetSigns {
    private static final NamespacedKey SIGN_TAG = new NamespacedKey("disc", "trap_reset_sign");
    private static final NamespacedKey POSITION = new NamespacedKey("disc", "trap_reset_sign_position");
    private static final int GROUND = DiscArena.GROUND;
    private static final int SEARCH_RADIUS = 5;

    private TrapResetSigns() { }

    static boolean isResetSign(Block block) {
        return block != null && DiscLobby.isLobby(block.getWorld()) && isTaggedSign(block);
    }

    private static boolean isTaggedSign(Block block) {
        return block != null && Tag.ALL_SIGNS.isTagged(block.getType()) && block.getState() instanceof Sign sign
                && sign.getPersistentDataContainer().getOrDefault(SIGN_TAG, BOOLEAN, false);
    }

    static void ensure(World world) {
        Site site = site(world);
        if (site == null) {
            return;
        }
        var data = world.getPersistentDataContainer();
        if (!site.lobby()) {
            for (Block block : candidates(world, site)) {
                if (isTaggedSign(block)) {
                    block.setType(Material.AIR, false);
                }
            }
            data.remove(POSITION);
            return;
        }
        int[] remembered = data.get(POSITION, INTEGER_ARRAY);
        Block previous = remembered != null && remembered.length == 3 && remembered[1] == GROUND
                && site.contains(remembered[0], remembered[2])
                ? world.getBlockAt(remembered[0], GROUND, remembered[2]) : null;
        if (isResetSign(previous)) {
            return;
        }

        List<Block> candidates = candidates(world, site);
        for (Block block : candidates) {
            if (isResetSign(block)) {
                remember(block);
                return;
            }
        }
        if (previous != null && place(previous, site)) {
            remember(previous);
            return;
        }
        for (Block block : candidates) {
            if (place(block, site)) {
                remember(block);
                return;
            }
        }
    }

    private static Site site(World world) {
        if (DiscLobby.isLobby(world)) {
            return new Site(-8, -20, -25, -16, BlockFace.SOUTH, true);
        }
        if (DiscArena.isHall(world)) {
            return new Site(-4, -50, -55, -45, BlockFace.NORTH, false);
        }
        if (DiscArena.isDuel(world)) {
            return new Site(-4, -10, -13, -5, BlockFace.NORTH, false);
        }
        if (DiscArena.isArena(world)) {
            return new Site(-4, -31, -36, -26, BlockFace.NORTH, false);
        }
        return null;
    }

    private static List<Block> candidates(World world, Site site) {
        List<Block> blocks = new ArrayList<>();
        for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = site.x() + dx;
                    int z = site.z() + dz;
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == radius && site.contains(x, z)) {
                        blocks.add(world.getBlockAt(x, GROUND, z));
                    }
                }
            }
        }
        return blocks;
    }

    private static boolean place(Block block, Site site) {
        if (!block.getType().isAir() || !hasSafeFloor(block)) {
            return false;
        }
        Block approach = block.getRelative(site.facing());
        if (!approach.getType().isAir() || !approach.getRelative(BlockFace.UP).getType().isAir()
                || !hasSafeFloor(approach)) {
            return false;
        }

        Rotatable data = (Rotatable) Material.SPRUCE_SIGN.createBlockData();
        data.setRotation(site.facing());
        block.setBlockData(data, false);
        if (!(block.getState() instanceof Sign sign)) {
            return false;
        }
        SignSide front = sign.getSide(Side.FRONT);
        front.setColor(DyeColor.WHITE);
        front.setGlowingText(true);
        front.line(0, Component.text("ギミック"));
        front.line(1, Component.text("リセット"));
        front.line(2, Component.text("右クリック"));
        sign.setWaxed(true);
        sign.getPersistentDataContainer().set(SIGN_TAG, BOOLEAN, true);
        return sign.update(false, false);
    }

    private static boolean hasSafeFloor(Block block) {
        Block floor = block.getRelative(BlockFace.DOWN);
        return floor.getType() != Material.MAGMA_BLOCK && floor.getType() != Material.TNT
                && floor.getBlockData().isFaceSturdy(BlockFace.UP, BlockSupport.FULL);
    }

    private static void remember(Block block) {
        block.getWorld().getPersistentDataContainer().set(POSITION, INTEGER_ARRAY,
                new int[] {block.getX(), block.getY(), block.getZ()});
    }

    private record Site(int x, int z, int minZ, int maxZ, BlockFace facing, boolean lobby) {
        boolean contains(int x, int z) {
            return x <= -2 && Math.abs(this.x - x) <= SEARCH_RADIUS && z >= minZ && z <= maxZ;
        }
    }
}
