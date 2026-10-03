package com.autyism.printer.printer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 铁轨放置顺序规划：同一组互相挨着的铁轨，先在“假想世界”里（投影里的非铁轨方块都当成已经放好，铁轨按顺序一节节放）
 * 用 {@link RailSim} 搜一个能让每一节都放对、且不拉歪任何已放铁轨的顺序；打印时按这个顺序放。
 * 搜不到（原版放置怎么排都做不到）时返回 null，退回到“现在安全才放”的逐个判断。
 */
public final class RailPlanner {
    /** 一组铁轨最多多少节（再大就不规划） */
    private static final int MAX_GROUP = 256;
    /** 搜索最多模拟多少次 */
    private static final int MAX_SIMULATIONS = 40_000;
    private static final boolean DEBUG = Boolean.getBoolean("ale.debugrails");

    /** 已规划的组：组里每个位置 → 这组的计划 */
    private static final Map<BlockPos, Plan> plans = new HashMap<>();

    /** order = 放置顺序（null 表示搜不到） */
    public record Plan(List<BlockPos> members, @Nullable List<BlockPos> order) {
        int indexOf(BlockPos pos) {
            return order == null ? -1 : order.indexOf(pos);
        }
    }

    private RailPlanner() {
    }

    public static void reset() {
        plans.clear();
    }

    /**
     * 这节铁轨现在是否轮到它：计划里排在它前面的铁轨都已经以正确形状存在。没有计划（搜不到）时返回 true，交给逐个判断。
     */
    public static boolean isTurn(BlockGetter level, BlockGetter schematic, BlockPos pos) {
        Plan plan = plans.get(pos);
        if (plan == null) {
            plan = makePlan(level, schematic, pos);
            for (BlockPos m : plan.members()) plans.put(m, plan);
        }
        if (plan.order() == null) return true;
        int idx = plan.indexOf(pos);
        for (int i = 0; i < idx; i++) {
            BlockPos before = plan.order().get(i);
            BlockState have = level.getBlockState(before);
            BlockState want = schematic.getBlockState(before);
            // 前面的还没放好（或者服务端的结果还没传回客户端）：等
            if (!BaseRailBlock.isRail(have) || shape(have) != shape(want)) return false;
        }
        return true;
    }

    private static Plan makePlan(BlockGetter level, BlockGetter schematic, BlockPos start) {
        List<BlockPos> members = group(schematic, start);
        if (members.size() > MAX_GROUP) return new Plan(members, null);
        members.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        // 已经在世界里且形状正确的先算“已放”；形状不对的已有铁轨保持原样
        Map<BlockPos, BlockState> virtual = new HashMap<>();
        BitSet placed = new BitSet(members.size());
        for (int i = 0; i < members.size(); i++) {
            BlockState have = level.getBlockState(members.get(i));
            if (BaseRailBlock.isRail(have)) {
                virtual.put(members.get(i), have);
                placed.set(i);
            }
        }
        Search search = new Search(level, schematic, members);
        List<BlockPos> order = new ArrayList<>();
        for (int i = placed.nextSetBit(0); i >= 0; i = placed.nextSetBit(i + 1)) order.add(members.get(i));
        boolean ok = search.dfs(virtual, placed, order);
        if (DEBUG) {
            System.out.println("[printer-rails] group at " + start.toShortString() + " size=" + members.size() + " planned=" + ok
                    + " simulations=" + search.simulations + (ok ? "" : " stuckAfter=" + search.best));
        }
        return new Plan(members, ok ? order : null);
    }

    /** 投影里和 start 相连的铁轨（东南西北相邻，高度差不超过 1） */
    private static List<BlockPos> group(BlockGetter schematic, BlockPos start) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        while (!queue.isEmpty() && out.size() <= MAX_GROUP) {
            BlockPos p = queue.poll();
            out.add(p);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos n = p.relative(d).above(dy);
                    if (seen.add(n) && BaseRailBlock.isRail(schematic.getBlockState(n))) queue.add(n);
                }
            }
        }
        return out;
    }

    private static RailShape shape(BlockState s) {
        return s.getValue(((BaseRailBlock) s.getBlock()).getShapeProperty());
    }

    /** 深度优先搜索放置顺序 */
    private static final class Search {
        final BlockGetter level;
        final BlockGetter schematic;
        final List<BlockPos> members;
        final Set<BitSet> failed = new HashSet<>();
        int simulations;
        /** 搜索中最多放到了几节（调试用） */
        int best;

        Search(BlockGetter level, BlockGetter schematic, List<BlockPos> members) {
            this.level = level;
            this.schematic = schematic;
            this.members = members;
        }

        boolean dfs(Map<BlockPos, BlockState> virtual, BitSet placed, List<BlockPos> order) {
            best = Math.max(best, placed.cardinality());
            if (placed.cardinality() == members.size()) return true;
            if (simulations > MAX_SIMULATIONS || failed.contains(placed)) return false;
            BlockGetter world = view(virtual);
            for (int i = placed.nextClearBit(0); i < members.size(); i = placed.nextClearBit(i + 1)) {
                BlockPos pos = members.get(i);
                simulations++;
                Map<BlockPos, BlockState> changes = RailSim.trySafe(world, schematic, pos, schematic.getBlockState(pos));
                if (changes == null) continue;
                Map<BlockPos, BlockState> next = new HashMap<>(virtual);
                next.putAll(changes);
                BitSet nextPlaced = (BitSet) placed.clone();
                nextPlaced.set(i);
                order.add(pos);
                if (dfs(next, nextPlaced, order)) return true;
                order.remove(order.size() - 1);
                if (simulations > MAX_SIMULATIONS) return false;
            }
            failed.add((BitSet) placed.clone());
            return false;
        }

        /** 假想世界：模拟中放下的铁轨 + 投影里的非铁轨方块（当作已经放好）+ 世界里已有的方块 */
        BlockGetter view(Map<BlockPos, BlockState> virtual) {
            return new BlockGetter() {
                @Override
                public BlockState getBlockState(BlockPos pos) {
                    BlockState v = virtual.get(pos);
                    if (v != null) return v;
                    BlockState want = schematic.getBlockState(pos);
                    if (BaseRailBlock.isRail(want)) return Blocks.AIR.defaultBlockState();
                    if (!want.isAir()) return want;
                    return level.getBlockState(pos);
                }

                @Override
                public FluidState getFluidState(BlockPos pos) {
                    return getBlockState(pos).getFluidState();
                }

                @Override
                public @Nullable net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
                    return null;
                }

                @Override
                public int getHeight() {
                    return level.getHeight();
                }

                @Override
                public int getMinY() {
                    return level.getMinY();
                }
            };
        }
    }
}
