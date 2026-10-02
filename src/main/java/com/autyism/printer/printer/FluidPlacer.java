package com.autyism.printer.printer;

import com.autyism.printer.config.Configs;
import com.autyism.printer.utils.PlayerUtils;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

/**
 * 用桶打印流体：
 * <ul>
 *   <li>投影里的水源 / 岩浆源：手持水桶 / 岩浆桶，看向相邻方块朝向目标格的那个面，发 UseItem（服务端按包里的视角做射线，和玩家自己用桶完全一样）；</li>
 *   <li>投影里装满水 / 岩浆 / 细雪的炼药锅：世界里是空炼药锅时，用对应的桶右键炼药锅。</li>
 * </ul>
 * 生存模式每桶只能放一格（用完变空桶）；没有桶时不处理，交给原来的“破冰放水”等逻辑。
 */
public final class FluidPlacer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final boolean DEBUG = Boolean.getBoolean("ale.debuglook");

    public enum Kind { SOURCE, CAULDRON }

    /** 一次待执行的流体操作 */
    public record Plan(Kind kind, Item bucket, BlockPos target, @Nullable BlockHitResult hit, float yaw, float pitch) {
    }

    private FluidPlacer() {
    }

    private static void dbg(BlockPos pos, String msg) {
        if (DEBUG && (pos.getX() + pos.getZ()) % 7 == 0) System.out.println("[fluid-plan] " + pos.toShortString() + " " + msg);
    }

    @Nullable
    private static Item bucketForSource(BlockState required) {
        if (!(required.getBlock() instanceof LiquidBlock) || !required.getFluidState().isSource()) return null;
        Fluid f = required.getFluidState().getType();
        if (f == Fluids.WATER) return Items.WATER_BUCKET;
        if (f == Fluids.LAVA) return Items.LAVA_BUCKET;
        return null;
    }

    @Nullable
    private static Item bucketForCauldron(BlockState required) {
        if (required.is(Blocks.WATER_CAULDRON) && required.getValue(LayeredCauldronBlock.LEVEL) == 3) return Items.WATER_BUCKET;
        if (required.is(Blocks.LAVA_CAULDRON)) return Items.LAVA_BUCKET;
        if (required.is(Blocks.POWDER_SNOW_CAULDRON) && required.getValue(LayeredCauldronBlock.LEVEL) == 3) return Items.POWDER_SNOW_BUCKET;
        return null;
    }

    private static boolean hasItem(LocalPlayer player, Item item) {
        if (PlayerUtils.getAbilities(player).instabuild) return true;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }

    /** 这个格子能不能（现在）用桶处理；能的话返回计划 */
    @Nullable
    public static Plan plan(ClientLevel level, Level schematic, LocalPlayer player, BlockPos pos, BlockState required, BlockState current) {
        if (!Configs.Print.PRINT_FLUIDS_WITH_BUCKET.getBooleanValue()) return null;
        Item bucket = bucketForSource(required);
        if (bucket != null) {
            if (current.getFluidState().isSource() && current.getFluidState().getType() == required.getFluidState().getType()) return null;
            boolean empty = current.isAir() || (current.getBlock() instanceof LiquidBlock && current.getFluidState().getType().isSame(required.getFluidState().getType()));
            if (!empty || !hasItem(player, bucket)) {
                dbg(pos, "empty=" + empty + " hasItem=" + hasItem(player, bucket));
                return null;
            }
            if (!contained(level, schematic, pos)) {
                dbg(pos, "not contained");
                return null;
            }
            Plan p = planSource(level, player, pos, bucket);
            if (p == null) dbg(pos, "no face");
            return p;
        }
        bucket = bucketForCauldron(required);
        if (bucket != null && current.is(Blocks.CAULDRON) && hasItem(player, bucket)) {
            Vec3 eye = player.getEyePosition();
            Vec3 hitVec = Vec3.atCenterOf(pos).add(0, 0.5, 0);
            if (eye.distanceTo(hitVec) > player.blockInteractionRange()) return null;
            float[] rot = rotation(eye, hitVec);
            return new Plan(Kind.CAULDRON, bucket, pos, new BlockHitResult(hitVec, Direction.UP, pos, false), rot[0], rot[1]);
        }
        return null;
    }

    private static final int MAX_BODY = 4096;
    private static final Long2BooleanOpenHashMap CONTAINED_CACHE = new Long2BooleanOpenHashMap();
    private static long cacheTick = Long.MIN_VALUE;
    @Nullable
    private static ClientLevel cacheLevel;

    /**
     * 流体要被“装住”才放：洪泛搜索同一片投影流体（含流动的水，只往水平和下方走），
     * 整片水体四周、下方本应是实心方块的位置都必须已经放好。
     * 只看目标格自己四周不够：水会流到同一水体里墙还没放好的格子再漏出去。
     */
    private static boolean contained(ClientLevel level, Level schematic, BlockPos pos) {
        // 客户端的 gameTime 不一定每 tick 递增，用玩家自己的 tickCount
        long tick = mc.player == null ? 0 : mc.player.tickCount;
        if (tick != cacheTick || level != cacheLevel) {
            CONTAINED_CACHE.clear();
            cacheTick = tick;
            cacheLevel = level;
        }
        // 注意：fastutil 的 get() 找不到时返回 false，不能拿来判断“有没有缓存”
        if (CONTAINED_CACHE.containsKey(pos.asLong())) return CONTAINED_CACHE.get(pos.asLong());
        Fluid fluid = schematic.getBlockState(pos).getFluidState().getType();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        queue.add(pos);
        seen.add(pos.asLong());
        boolean ok = true;
        while (!queue.isEmpty() && ok) {
            BlockPos cur = queue.poll();
            for (Direction d : Direction.values()) {
                if (d == Direction.UP) continue;
                BlockPos n = cur.relative(d);
                BlockState want = schematic.getBlockState(n);
                if (want.getBlock() instanceof LiquidBlock && want.getFluidState().getType().isSame(fluid)) {
                    if (seen.size() < MAX_BODY && Math.abs(n.getX() - pos.getX()) + Math.abs(n.getZ() - pos.getZ()) <= 16 && seen.add(n.asLong())) {
                        queue.add(n);
                    }
                    continue;
                }
                if (want.isAir() || want.getBlock() instanceof LiquidBlock) continue;
                if (level.getBlockState(n).getBlock() != want.getBlock()) {
                    dbg(pos, "leak at " + n.toShortString() + " want " + want + " have " + level.getBlockState(n));
                    ok = false;
                    break;
                }
            }
        }
        for (long l : seen) CONTAINED_CACHE.put(l, ok);
        return ok;
    }

    private static final double[] SAMPLES = {0, -0.3, 0.3, -0.42, 0.42};

    /** 面内的偏移（u、v 是面上两个轴方向的坐标，-0.5~0.5） */
    private static Vec3 offsetOnFace(Direction d, double u, double v) {
        return switch (d.getAxis()) {
            case X -> new Vec3(0, u, v);
            case Y -> new Vec3(u, 0, v);
            case Z -> new Vec3(u, v, 0);
        };
    }

    /** 找一个相邻方块的面：从眼睛看过去第一个碰到的就是它，并且是朝向目标格的那一面 */
    @Nullable
    private static Plan planSource(ClientLevel level, LocalPlayer player, BlockPos pos, Item bucket) {
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        Direction[] order = Direction.orderedByNearest(player);
        for (int pass = 0; pass < 2; pass++) {
            for (Direction d : order) {
                // 先试“从玩家这边看得到朝向目标格那一面”的相邻方块（通常是地板 / 墙），第二轮试其余的
                if ((pass == 0) != (d == Direction.DOWN || isFacingAway(eye, pos, d))) continue;
                BlockPos neighbor = pos.relative(d);
                BlockState ns = level.getBlockState(neighbor);
                if (ns.isAir() || ns.getBlock() instanceof LiquidBlock) continue;
                // 含水方块会被水桶“装水”而不是在目标格放水
                if (bucket == Items.WATER_BUCKET && ns.getBlock() instanceof LiquidBlockContainer) continue;
                if (ns.getShape(level, neighbor).isEmpty()) continue;
                Vec3 faceCenter = Vec3.atCenterOf(pos).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
                // 面上取 5x5 个点：斜着看时面中心可能被旁边的方块（比如台阶）挡住，但面的另一侧还能看到
                for (double u : SAMPLES) {
                    for (double v : SAMPLES) {
                        Vec3 face = faceCenter.add(offsetOnFace(d, u, v));
                        if (eye.distanceTo(face) > reach - 0.05) continue;
                        Vec3 end = face.add(d.getStepX() * 0.1, d.getStepY() * 0.1, d.getStepZ() * 0.1);
                        Vec3 dir = end.subtract(eye).normalize();
                        Vec3 far = eye.add(dir.scale(reach));
                        BlockHitResult hit = level.clip(new ClipContext(eye, far, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(neighbor) || hit.getDirection() != d.getOpposite()) continue;
                        float[] rot = rotation(eye, end);
                        return new Plan(Kind.SOURCE, bucket, pos, hit, rot[0], rot[1]);
                    }
                }
            }
        }
        return null;
    }

    /** 相邻方块在目标格“远离玩家”的那一侧（从玩家这边能看到它朝向目标格的面） */
    private static boolean isFacingAway(Vec3 eye, BlockPos pos, Direction d) {
        Vec3 c = Vec3.atCenterOf(pos);
        return (eye.x - c.x) * d.getStepX() + (eye.y - c.y) * d.getStepY() + (eye.z - c.z) * d.getStepZ() < 0;
    }

    private static float[] rotation(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        return new float[]{yaw, pitch};
    }

    /** 手里已经是对应的桶时执行；返回是否发出了操作 */
    public static boolean execute(LocalPlayer player, Plan plan) {
        if (mc.gameMode == null || !player.getMainHandItem().is(plan.bucket())) return false;
        float oldYaw = player.getYRot(), oldPitch = player.getXRot();
        ContainerGuard.beginPrinterInteraction();
        try {
            if (plan.kind() == Kind.CAULDRON) {
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, plan.hit());
            } else {
                // UseItem 包带着视角：服务端先把玩家转到这个视角再用桶做射线
                player.setYRot(plan.yaw());
                player.setXRot(plan.pitch());
                mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
            ContainerGuard.endPrinterInteraction();
        }
        return true;
    }
}
