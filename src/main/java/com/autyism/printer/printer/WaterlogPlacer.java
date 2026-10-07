package com.autyism.printer.printer;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.BlockMatchingType;
import com.autyism.printer.utils.LitematicaUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 打印含水方块：投影里含水、世界里已经放好（除了含水以外都和投影一样）的方块，用水桶给它加水。
 * <p>
 * 水会不会流出去完全按原版的流动规则判断（FlowingFluid 里的这些方法都是私有的，这里照着写）：
 * 下方和四个侧面，邻格能装流动的水、并且两边的碰撞箱在这个面上没有把缝堵死，水就会流过去。
 * <ul>
 *   <li>按投影的最终样子看就会流出去（投影自己在这一侧是开着的）：永远不加水，这一格算作完成；</li>
 *   <li>投影里是封住的，但世界里邻格还没打印好（或者打印机刚放的邻格服务端还没确认）：先等；</li>
 *   <li>都封住了：瞄准这个方块用水桶。</li>
 * </ul>
 */
public final class WaterlogPlacer {
    public enum Verdict { NONE, DRY_OK, WAIT, READY }

    private static final Direction[] SPREAD = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private WaterlogPlacer() {
    }

    public static boolean enabled() {
        return Configs.Print.PRINT_WATERLOGGED.getBooleanValue() && !Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue();
    }

    /** 投影里这一格是含水的方块 */
    public static boolean wantsWater(BlockState required) {
        return required.hasProperty(BlockStateProperties.WATERLOGGED) && required.getValue(BlockStateProperties.WATERLOGGED);
    }

    /** 投影要含水，世界里是同一个方块、还没含水；其他方面按打印机自己的规则已经和投影一致 */
    public static boolean isCandidate(BlockState required, BlockState current) {
        if (!wantsWater(required)) return false;
        if (current.getBlock() != required.getBlock() || current.getValue(BlockStateProperties.WATERLOGGED)) return false;
        return BlockMatchingType.get(required, current.setValue(BlockStateProperties.WATERLOGGED, true)) == BlockMatchingType.CORRECT;
    }

    /** 这一格该怎么处理（选项关闭或不相关时 NONE） */
    public static Verdict verdict(ClientLevel level, Level schematic, LocalPlayer player, BlockPos pos, BlockState required, BlockState current) {
        if (!enabled() || !isCandidate(required, current)) return Verdict.NONE;
        // 现在装不了水的状态（例如双层台阶）不处理
        if (!(current.getBlock() instanceof LiquidBlockContainer container)
                //? if <1.20.2 {
                /*|| !container.canPlaceLiquid(level, pos, current, Fluids.WATER)) {
                *///?} else
                || !container.canPlaceLiquid(player, level, pos, current, Fluids.WATER)) {
            return Verdict.NONE;
        }
        // 投影最终的样子（投影范围外的格子按世界里的算）：会流出去就保持不含水
        if (leaks(pos, required, p -> LitematicaUtils.isSchematicBlock(p) ? schematic : level)) return Verdict.DRY_OK;
        if (leaks(pos, current.setValue(BlockStateProperties.WATERLOGGED, true), p -> level)) return Verdict.WAIT;
        return Verdict.READY;
    }

    /** 投影在某一侧是开着的：这一格保持不含水，算作已完成 */
    public static boolean acceptsDry(ClientLevel level, Level schematic, LocalPlayer player, BlockPos pos, BlockState required, BlockState current) {
        return enabled() && verdict(level, schematic, player, pos, required, current) == Verdict.DRY_OK;
    }

    /** 这一格自己或者会影响水流的邻格，有打印机的动作还没被服务端确认 */
    public static boolean actionPending(ClientLevel level, BlockPos pos, int tick) {
        if (ActionConfirm.pending(level, pos, tick)) return true;
        for (Direction d : SPREAD) {
            if (ActionConfirm.pending(level, pos.relative(d), tick)) return true;
        }
        return false;
    }

    private interface Source {
        BlockGetter at(BlockPos pos);
    }

    private static BlockState state(Source src, BlockPos self, BlockState selfState, BlockPos p) {
        return p.equals(self) ? selfState : src.at(p).getBlockState(p);
    }

    /** 这一格含水后，水会不会流到下方或四周的邻格 */
    private static boolean leaks(BlockPos pos, BlockState self, Source src) {
        for (Direction d : SPREAD) {
            BlockPos n = pos.relative(d);
            BlockGetter g = src.at(n);
            BlockState ns = g.getBlockState(n);
            if (isWaterSource(ns.getFluidState())) continue;
            if (!canHoldAnyFluid(ns)) continue;
            if (!canPassThroughWall(d, src.at(pos), pos, self, g, n, ns)) continue;
            if (canHoldSpecificFluid(g, n, ns, newFluid(pos, self, src, n, ns))) return true;
        }
        return false;
    }

    private static boolean isWaterSource(FluidState fluid) {
        return fluid.getType() == Fluids.WATER && fluid.isSource();
    }

    /** 同 FlowingFluid.canHoldAnyFluid */
    private static boolean canHoldAnyFluid(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LiquidBlockContainer) return true;
        //? if >=26.3 {
        /*return state.is(BlockTags.WASHED_AWAY_BY_FLUIDS);
        *///?} else {
        if (state.blocksMotion()) return false;
        return !(block instanceof DoorBlock) && !state.is(BlockTags.SIGNS) && !state.is(Blocks.LADDER) && !state.is(Blocks.SUGAR_CANE)
                && !state.is(Blocks.BUBBLE_COLUMN) && !state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL)
                && !state.is(Blocks.END_GATEWAY) && !state.is(Blocks.STRUCTURE_VOID);
        //?}
    }

    /** 同 FlowingFluid.canHoldSpecificFluid */
    private static boolean canHoldSpecificFluid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        //? if <1.20.2 {
        /*return !(state.getBlock() instanceof LiquidBlockContainer container) || container.canPlaceLiquid(level, pos, state, fluid);
        *///?} else
        return !(state.getBlock() instanceof LiquidBlockContainer container) || container.canPlaceLiquid(null, level, pos, state, fluid);
    }

    /** 同 FlowingFluid.canPassThroughWall：两边的碰撞箱在这个面上合起来没有把缝堵死 */
    private static boolean canPassThroughWall(Direction d, BlockGetter fromLevel, BlockPos from, BlockState fromState,
                                              BlockGetter toLevel, BlockPos to, BlockState toState) {
        VoxelShape toShape = toState.getCollisionShape(toLevel, to);
        if (toShape == Shapes.block()) return false;
        VoxelShape fromShape = fromState.getCollisionShape(fromLevel, from);
        if (fromShape == Shapes.block()) return false;
        if (fromShape == Shapes.empty() && toShape == Shapes.empty()) return true;
        return !Shapes.mergedFaceOccludes(fromShape, toShape, d);
    }

    /**
     * 同 FlowingFluid.getNewLiquid 里水的种类：邻格水平方向有两个以上相通的水源、下面是实心方块或水源时会变成水源
     * （能让旁边不含水的含水方块也含水），否则是流动的水
     */
    private static Fluid newFluid(BlockPos self, BlockState selfState, Source src, BlockPos n, BlockState ns) {
        int sources = 0;
        for (Direction h : Direction.Plane.HORIZONTAL) {
            BlockPos m = n.relative(h);
            BlockState ms = state(src, self, selfState, m);
            if (isWaterSource(ms.getFluidState()) && canPassThroughWall(h, src.at(n), n, ns, src.at(m), m, ms)) sources++;
        }
        if (sources >= 2) {
            BlockPos below = n.below();
            BlockState bs = state(src, self, selfState, below);
            if (bs.isSolid() || isWaterSource(bs.getFluidState())) return Fluids.WATER;
        }
        return Fluids.FLOWING_WATER;
    }

    private static final double[] FACE_POINTS = {0.5, 0.3, 0.7, 0.15, 0.85};

    /**
     * 找一个能直接看到这个方块（第一个碰到的就是它）的瞄准点。优先选正对着的格子装不了水的面：
     * 服务端万一认为这个方块装不了水，水桶会把水倒在被点的面前面那一格，这样也倒不出来
     */
    @Nullable
    public static FluidPlacer.Plan plan(ClientLevel level, LocalPlayer player, BlockPos pos, BlockState current) {
        Vec3 eye = player.getEyePosition();
        //? if <1.20.5 {
        /*double reach = net.minecraft.client.Minecraft.getInstance().gameMode.getPickRange();
        *///?} else
        double reach = player.blockInteractionRange();
        FluidPlacer.Plan fallback = null;
        for (AABB box : current.getShape(level, pos).toAabbs()) {
            AABB b = box.move(pos);
            for (Direction face : Direction.values()) {
                double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? b.max(face.getAxis()) : b.min(face.getAxis());
                double eyeCoord = eye.get(face.getAxis());
                if (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? eyeCoord <= plane : eyeCoord >= plane) continue;
                for (double u : FACE_POINTS) {
                    for (double v : FACE_POINTS) {
                        Vec3 point = pointOnFace(b, face, u, v);
                        if (eye.distanceTo(point) > reach - 0.05) continue;
                        Vec3 dir = point.subtract(eye).normalize();
                        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
                        float[] rot = rotation(eye, point);
                        FluidPlacer.Plan plan = new FluidPlacer.Plan(FluidPlacer.Kind.WATERLOG, Items.WATER_BUCKET, pos, hit, rot[0], rot[1]);
                        if (!frontTakesWater(level, player, pos.relative(hit.getDirection()))) return plan;
                        if (fallback == null) fallback = plan;
                    }
                }
            }
        }
        return fallback;
    }

    /** 方块框某个面上的点（u、v 是面上两个方向的比例） */
    private static Vec3 pointOnFace(AABB b, Direction face, double u, double v) {
        double x = b.minX + (b.maxX - b.minX) * u;
        double y = b.minY + (b.maxY - b.minY) * (face.getAxis() == Direction.Axis.X ? u : v);
        double z = b.minZ + (b.maxZ - b.minZ) * v;
        return switch (face.getAxis()) {
            case X -> new Vec3(face == Direction.EAST ? b.maxX : b.minX, y, z);
            case Y -> new Vec3(x, face == Direction.UP ? b.maxY : b.minY, z);
            case Z -> new Vec3(x, y, face == Direction.SOUTH ? b.maxZ : b.minZ);
        };
    }

    /** 水桶倒向这一格时能不能倒进去（同 BucketItem.emptyContents 的判断） */
    private static boolean frontTakesWater(ClientLevel level, LocalPlayer player, BlockPos front) {
        BlockState s = level.getBlockState(front);
        if (s.isAir() || s.canBeReplaced(Fluids.WATER)) return true;
        //? if <1.20.2 {
        /*return s.getBlock() instanceof LiquidBlockContainer c && c.canPlaceLiquid(level, front, s, Fluids.WATER);
        *///?} else
        return s.getBlock() instanceof LiquidBlockContainer c && c.canPlaceLiquid(player, level, front, s, Fluids.WATER);
    }

    private static float[] rotation(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        return new float[]{(float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0), (float) -Math.toDegrees(Math.atan2(dy, h))};
    }
}
