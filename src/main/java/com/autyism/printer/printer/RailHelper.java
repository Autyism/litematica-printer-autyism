package com.autyism.printer.printer;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 铁轨放置顺序（照原版 RailState.place 的规则推算）。
 * <p>
 * 新放的铁轨会看四周的铁轨：一个邻居“还能连”（它真正连上的铁轨不到 2 条，或者它本来就指向这里）才算数。
 * 只有横向的邻居算数时铁轨会变成横的；两个方向都算数或者都不算数时，按玩家朝向决定。
 * 所以：横向有“还能连”的铁轨、纵向（想要的方向）还没有、而投影里纵向确实还有铁轨要放时，先等纵向那一节放好。
 */
public final class RailHelper {
    /** 一个格子最多等多少 tick（防止奇怪的布局永远等下去） */
    private static final int MAX_WAIT_TICKS = 200;
    private static final Long2IntOpenHashMap firstWait = new Long2IntOpenHashMap();

    private RailHelper() {
    }

    /** 现在放这节铁轨会不会被旁边的铁轨拉成错误的方向（并且等一等能解决） */
    public static boolean shouldWait(BlockGetter world, BlockGetter schematic, BlockPos pos, RailShape wanted, int tickNow) {
        Direction.Axis axis = axisOf(wanted);
        if (axis == null) return false; // 弯轨不处理
        boolean axisConnectable = false, perpConnectable = false, axisPending = false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(d);
            boolean connectable = isConnectable(world, n, pos);
            if (d.getAxis() == axis) {
                axisConnectable |= connectable;
                if (!connectable && railAround(schematic, n) != null && railAround(world, n) == null) axisPending = true;
            } else {
                perpConnectable |= connectable;
            }
        }
        long key = pos.asLong();
        if (!(perpConnectable && !axisConnectable && axisPending)) {
            firstWait.remove(key);
            return false;
        }
        if (!firstWait.containsKey(key)) firstWait.put(key, tickNow);
        if (tickNow - firstWait.get(key) > MAX_WAIT_TICKS) return false;
        if (firstWait.size() > 4096) firstWait.clear();
        return true;
    }

    @Nullable
    private static Direction.Axis axisOf(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> Direction.Axis.Z;
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> Direction.Axis.X;
            default -> null;
        };
    }

    /** pos 处（或上下各一格）的铁轨位置；没有返回 null —— 与原版 RailState.getRail 一致 */
    @Nullable
    private static BlockPos railAround(BlockGetter world, BlockPos pos) {
        if (BaseRailBlock.isRail(world.getBlockState(pos))) return pos;
        if (BaseRailBlock.isRail(world.getBlockState(pos.above()))) return pos.above();
        if (BaseRailBlock.isRail(world.getBlockState(pos.below()))) return pos.below();
        return null;
    }

    /** 原版 hasNeighborRail：邻居铁轨去掉“软连接”后，连接数不是 2，或者已经指向 target */
    private static boolean isConnectable(BlockGetter world, BlockPos neighbor, BlockPos target) {
        BlockPos railPos = railAround(world, neighbor);
        if (railPos == null) return false;
        List<BlockPos> hard = new ArrayList<>();
        for (BlockPos c : connections(railPos, shapeOf(world.getBlockState(railPos)))) {
            BlockPos other = railAround(world, c);
            if (other != null && pointsTo(world, other, railPos)) hard.add(c);
        }
        for (BlockPos c : connections(railPos, shapeOf(world.getBlockState(railPos)))) {
            if (c.getX() == target.getX() && c.getZ() == target.getZ()) return true;
        }
        return hard.size() != 2;
    }

    private static boolean pointsTo(BlockGetter world, BlockPos rail, BlockPos target) {
        for (BlockPos c : connections(rail, shapeOf(world.getBlockState(rail)))) {
            if (c.getX() == target.getX() && c.getZ() == target.getZ()) return true;
        }
        return false;
    }

    private static RailShape shapeOf(BlockState state) {
        return state.getValue(((BaseRailBlock) state.getBlock()).getShapeProperty());
    }

    private static List<BlockPos> connections(BlockPos pos, RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH -> List.of(pos.north(), pos.south());
            case EAST_WEST -> List.of(pos.west(), pos.east());
            case ASCENDING_EAST -> List.of(pos.west(), pos.east().above());
            case ASCENDING_WEST -> List.of(pos.west().above(), pos.east());
            case ASCENDING_NORTH -> List.of(pos.north().above(), pos.south());
            case ASCENDING_SOUTH -> List.of(pos.north(), pos.south().above());
            case SOUTH_EAST -> List.of(pos.east(), pos.south());
            case SOUTH_WEST -> List.of(pos.west(), pos.south());
            case NORTH_WEST -> List.of(pos.west(), pos.north());
            case NORTH_EAST -> List.of(pos.east(), pos.north());
        };
    }
}
