package com.autyism.printer.printer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 铁轨安全放置：在客户端把“现在放这节铁轨”按原版规则完整模拟一遍（逐行移植自原版 {@code RailState} 与
 * {@code BaseRailBlock.getStateForPlacement/updateDir}），只有模拟结果满足下面全部条件才放：
 * <ul>
 *   <li>这节铁轨的形状和投影一致；</li>
 *   <li>周围被它连上、因此改了形状的铁轨，改完仍和投影一致（不会把已经放好的铁轨拉歪 / 串错）；</li>
 *   <li>不会出现没有支撑的上坡铁轨（原版会让它直接掉落成物品）。</li>
 * </ul>
 * 否则先不放，等别的铁轨放好后再试；怎么排都放不对的就一直不放（不靠“先放错再敲掉”的办法）。
 * <p>
 * 放下一节后，在服务端结果回到客户端、和模拟一致之前，附近的铁轨都先等着，避免客户端延迟导致按旧状态判断。
 */
public final class RailSim {
    /** 已经发出、还没在客户端看到结果的铁轨放置：位置 → 预期的各格形状 */
    private static final List<Pending> pending = new ArrayList<>();
    private static final int PENDING_TIMEOUT_TICKS = 40;

    private record Pending(BlockPos pos, Map<BlockPos, RailShape> expected, int tick) {
    }

    private RailSim() {
    }

    /**
     * 找一个能安全放这节铁轨的朝向（玩家水平朝向决定铁轨的初始方向：朝东/西 = 东西向，否则南北向）。
     *
     * @return 应该看的方向；null = 现在不能放
     */
    @Nullable
    public static Direction findSafeLook(BlockGetter level, BlockGetter schematic, BlockPos pos, BlockState required, int tickNow) {
        if (!(required.getBlock() instanceof BaseRailBlock)) return null;
        if (waitingNear(level, pos, tickNow)) return null;
        Safe safe = findSafe(level, schematic, pos, required);
        if (safe == null) return null;
        Map<BlockPos, RailShape> expected = new HashMap<>();
        for (var e : safe.changes().entrySet()) {
            if (BaseRailBlock.isRail(e.getValue())) expected.put(e.getKey(), shapeOf(e.getValue()));
        }
        pending.add(new Pending(pos.immutable(), expected, tickNow));
        return safe.look();
    }

    /** 规划用：在给定世界里现在放这节是否安全；安全返回放下后的变化，否则 null */
    @Nullable
    static Map<BlockPos, BlockState> trySafe(BlockGetter world, BlockGetter schematic, BlockPos pos, BlockState required) {
        Safe safe = findSafe(world, schematic, pos, required);
        return safe == null ? null : safe.changes();
    }

    private record Safe(Direction look, Map<BlockPos, BlockState> changes) {
    }

    @Nullable
    private static Safe findSafe(BlockGetter level, BlockGetter schematic, BlockPos pos, BlockState required) {
        if (!(required.getBlock() instanceof BaseRailBlock rail)) return null;
        RailShape wanted = required.getValue(rail.getShapeProperty());
        boolean wantEastWest = wanted == RailShape.EAST_WEST || wanted == RailShape.ASCENDING_EAST || wanted == RailShape.ASCENDING_WEST;
        Direction[] looks = wantEastWest ? new Direction[]{Direction.EAST, Direction.NORTH} : new Direction[]{Direction.NORTH, Direction.EAST};
        for (Direction look : looks) {
            RailShape initial = look == Direction.EAST ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
            BlockState placed = rail.defaultBlockState().setValue(rail.getShapeProperty(), initial);
            // 红石信号只影响普通铁轨在丁字路口时拐向哪边：有没有信号都要安全才放
            Map<BlockPos, BlockState> withSignal = simulate(level, pos, placed, true);
            Map<BlockPos, BlockState> noSignal = simulate(level, pos, placed, false);
            if (!clean(level, schematic, pos, wanted, withSignal) || !clean(level, schematic, pos, wanted, noSignal)) continue;
            // 用轻松放置协议时，服务端可能直接把初始形状设成投影里的形状（也可能不管铁轨）：两种都要安全
            if (com.autyism.printer.utils.LitematicaUtils.usePrecisionPlacement(pos, required) != null) {
                BlockState asWanted = rail.defaultBlockState().setValue(rail.getShapeProperty(), wanted);
                if (!clean(level, schematic, pos, wanted, simulate(level, pos, asWanted, true))
                        || !clean(level, schematic, pos, wanted, simulate(level, pos, asWanted, false))) continue;
            }
            return new Safe(look, noSignal);
        }
        return null;
    }

    /** 附近有刚放的铁轨、客户端还没看到服务端给的结果：先等 */
    private static boolean waitingNear(BlockGetter level, BlockPos pos, int tickNow) {
        boolean wait = false;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            boolean arrived = true;
            for (var e : p.expected().entrySet()) {
                BlockState now = level.getBlockState(e.getKey());
                if (!BaseRailBlock.isRail(now) || shapeOf(now) != e.getValue()) {
                    arrived = false;
                    break;
                }
            }
            if (arrived || tickNow - p.tick() > PENDING_TIMEOUT_TICKS || tickNow < p.tick()) {
                it.remove();
                continue;
            }
            if (Math.abs(p.pos().getX() - pos.getX()) <= 3 && Math.abs(p.pos().getZ() - pos.getZ()) <= 3
                    && Math.abs(p.pos().getY() - pos.getY()) <= 3) {
                wait = true;
            }
        }
        return wait;
    }

    /** 换世界 / 重新开始时清掉 */
    public static void reset() {
        pending.clear();
        RailPlanner.reset();
    }

    /** 模拟结果是否安全：见类说明 */
    private static boolean clean(BlockGetter level, BlockGetter schematic, BlockPos pos, RailShape wanted, Map<BlockPos, BlockState> changes) {
        BlockState self = changes.get(pos);
        if (self == null || !BaseRailBlock.isRail(self) || shapeOf(self) != wanted) return false;
        for (var e : changes.entrySet()) {
            BlockPos p = e.getKey();
            RailShape got = shapeOf(e.getValue());
            if (!p.equals(pos)) {
                BlockState before = level.getBlockState(p);
                RailShape had = BaseRailBlock.isRail(before) ? shapeOf(before) : null;
                BlockState want = schematic.getBlockState(p);
                RailShape should = BaseRailBlock.isRail(want) ? shapeOf(want) : null;
                // 改形状只允许改成投影里的样子
                if (got != had && got != should) return false;
            }
            //? if <1.21 {
            /*if (got.isAscending() && !slopeSupported(level, p, got)) return false;
            *///?} else
            if (got.isSlope() && !slopeSupported(level, p, got)) return false;
        }
        return true;
    }

    /** 原版 BaseRailBlock.shouldBeRemoved：上坡铁轨高的那一侧必须有能支撑的方块，否则会掉落 */
    private static boolean slopeSupported(BlockGetter level, BlockPos pos, RailShape shape) {
        Direction high = switch (shape) {
            case ASCENDING_EAST -> Direction.EAST;
            case ASCENDING_WEST -> Direction.WEST;
            case ASCENDING_NORTH -> Direction.NORTH;
            default -> Direction.SOUTH;
        };
        return Block.canSupportRigidBlock(level, pos.relative(high));
    }

    private static RailShape shapeOf(BlockState state) {
        return state.getValue(((BaseRailBlock) state.getBlock()).getShapeProperty());
    }

    // ------------------------------------------------------------------ 原版逻辑的模拟

    /** 在客户端世界的副本上放下 placed（初始形状由朝向决定），返回所有被改变的格子（含这一格） */
    static Map<BlockPos, BlockState> simulate(BlockGetter level, BlockPos pos, BlockState placed, boolean signal) {
        Overlay world = new Overlay(level);
        world.set(pos, placed);
        RailState state = new RailState(world, pos.immutable(), placed);
        state.place(signal, true, shapeOf(placed));
        return world.changes;
    }

    /** 客户端世界 + 模拟中改过的格子 */
    private static final class Overlay {
        final BlockGetter base;
        final Map<BlockPos, BlockState> changes = new HashMap<>();

        Overlay(BlockGetter base) {
            this.base = base;
        }

        BlockState get(BlockPos pos) {
            BlockState s = changes.get(pos);
            return s != null ? s : base.getBlockState(pos);
        }

        void set(BlockPos pos, BlockState state) {
            changes.put(pos.immutable(), state);
        }

        boolean isRail(BlockPos pos) {
            return BaseRailBlock.isRail(get(pos));
        }
    }

    /** 原版 net.minecraft.world.level.block.RailState 的逐行移植（世界换成 Overlay） */
    private static final class RailState {
        private final Overlay level;
        private final BlockPos pos;
        private BlockState state;
        private final BaseRailBlock block;
        private final boolean isStraight;
        private final List<BlockPos> connections = new ArrayList<>();

        RailState(Overlay level, BlockPos pos, BlockState state) {
            this.level = level;
            this.pos = pos;
            this.state = state;
            this.block = (BaseRailBlock) state.getBlock();
            this.isStraight = this.block.isStraight();
            this.updateConnections(state.getValue(this.block.getShapeProperty()));
        }

        private void updateConnections(RailShape shape) {
            this.connections.clear();
            switch (shape) {
                case NORTH_SOUTH -> {
                    connections.add(pos.north());
                    connections.add(pos.south());
                }
                case EAST_WEST -> {
                    connections.add(pos.west());
                    connections.add(pos.east());
                }
                case ASCENDING_EAST -> {
                    connections.add(pos.west());
                    connections.add(pos.east().above());
                }
                case ASCENDING_WEST -> {
                    connections.add(pos.west().above());
                    connections.add(pos.east());
                }
                case ASCENDING_NORTH -> {
                    connections.add(pos.north().above());
                    connections.add(pos.south());
                }
                case ASCENDING_SOUTH -> {
                    connections.add(pos.north());
                    connections.add(pos.south().above());
                }
                case SOUTH_EAST -> {
                    connections.add(pos.east());
                    connections.add(pos.south());
                }
                case SOUTH_WEST -> {
                    connections.add(pos.west());
                    connections.add(pos.south());
                }
                case NORTH_WEST -> {
                    connections.add(pos.west());
                    connections.add(pos.north());
                }
                case NORTH_EAST -> {
                    connections.add(pos.east());
                    connections.add(pos.north());
                }
            }
        }

        private void removeSoftConnections() {
            for (int i = 0; i < connections.size(); i++) {
                RailState rail = getRail(connections.get(i));
                if (rail != null && rail.connectsTo(this)) {
                    connections.set(i, rail.pos);
                } else {
                    connections.remove(i--);
                }
            }
        }

        private boolean hasRail(BlockPos p) {
            return level.isRail(p) || level.isRail(p.above()) || level.isRail(p.below());
        }

        @Nullable
        private RailState getRail(BlockPos p) {
            BlockState s = level.get(p);
            if (BaseRailBlock.isRail(s)) return new RailState(level, p, s);
            BlockPos up = p.above();
            s = level.get(up);
            if (BaseRailBlock.isRail(s)) return new RailState(level, up, s);
            BlockPos down = p.below();
            s = level.get(down);
            return BaseRailBlock.isRail(s) ? new RailState(level, down, s) : null;
        }

        private boolean connectsTo(RailState other) {
            return hasConnection(other.pos);
        }

        private boolean hasConnection(BlockPos p) {
            for (BlockPos c : connections) {
                if (c.getX() == p.getX() && c.getZ() == p.getZ()) return true;
            }
            return false;
        }

        @SuppressWarnings("unused")
        int countPotentialConnections() {
            int i = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (hasRail(pos.relative(d))) i++;
            }
            return i;
        }

        private boolean canConnectTo(RailState other) {
            return connectsTo(other) || connections.size() != 2;
        }

        private void connectTo(RailState other) {
            connections.add(other.pos);
            BlockPos n = pos.north(), s = pos.south(), w = pos.west(), e = pos.east();
            boolean bn = hasConnection(n), bs = hasConnection(s), bw = hasConnection(w), be = hasConnection(e);
            RailShape shape = null;
            if (bn || bs) shape = RailShape.NORTH_SOUTH;
            if (bw || be) shape = RailShape.EAST_WEST;
            if (!isStraight) {
                if (bs && be && !bn && !bw) shape = RailShape.SOUTH_EAST;
                if (bs && bw && !bn && !be) shape = RailShape.SOUTH_WEST;
                if (bn && bw && !bs && !be) shape = RailShape.NORTH_WEST;
                if (bn && be && !bs && !bw) shape = RailShape.NORTH_EAST;
            }
            if (shape == RailShape.NORTH_SOUTH) {
                if (level.isRail(n.above())) shape = RailShape.ASCENDING_NORTH;
                if (level.isRail(s.above())) shape = RailShape.ASCENDING_SOUTH;
            }
            if (shape == RailShape.EAST_WEST) {
                if (level.isRail(e.above())) shape = RailShape.ASCENDING_EAST;
                if (level.isRail(w.above())) shape = RailShape.ASCENDING_WEST;
            }
            if (shape == null) shape = RailShape.NORTH_SOUTH;
            state = state.setValue(block.getShapeProperty(), shape);
            level.set(pos, state);
        }

        private boolean hasNeighborRail(BlockPos p) {
            RailState rail = getRail(p);
            if (rail == null) return false;
            rail.removeSoftConnections();
            return rail.canConnectTo(this);
        }

        void place(boolean signal, boolean always, RailShape placedShape) {
            BlockPos n = pos.north(), s = pos.south(), w = pos.west(), e = pos.east();
            boolean hn = hasNeighborRail(n), hs = hasNeighborRail(s), hw = hasNeighborRail(w), he = hasNeighborRail(e);
            RailShape shape = null;
            boolean ns = hn || hs;
            boolean ew = hw || he;
            if (ns && !ew) shape = RailShape.NORTH_SOUTH;
            if (ew && !ns) shape = RailShape.EAST_WEST;
            boolean se = hs && he, sw = hs && hw, ne = hn && he, nw = hn && hw;
            if (!isStraight) {
                if (se && !hn && !hw) shape = RailShape.SOUTH_EAST;
                if (sw && !hn && !he) shape = RailShape.SOUTH_WEST;
                if (nw && !hs && !he) shape = RailShape.NORTH_WEST;
                if (ne && !hs && !hw) shape = RailShape.NORTH_EAST;
            }
            if (shape == null) {
                if (ns && ew) shape = placedShape;
                else if (ns) shape = RailShape.NORTH_SOUTH;
                else if (ew) shape = RailShape.EAST_WEST;
                if (!isStraight) {
                    if (signal) {
                        if (se) shape = RailShape.SOUTH_EAST;
                        if (sw) shape = RailShape.SOUTH_WEST;
                        if (ne) shape = RailShape.NORTH_EAST;
                        if (nw) shape = RailShape.NORTH_WEST;
                    } else {
                        if (nw) shape = RailShape.NORTH_WEST;
                        if (ne) shape = RailShape.NORTH_EAST;
                        if (sw) shape = RailShape.SOUTH_WEST;
                        if (se) shape = RailShape.SOUTH_EAST;
                    }
                }
            }
            if (shape == RailShape.NORTH_SOUTH) {
                if (level.isRail(n.above())) shape = RailShape.ASCENDING_NORTH;
                if (level.isRail(s.above())) shape = RailShape.ASCENDING_SOUTH;
            }
            if (shape == RailShape.EAST_WEST) {
                if (level.isRail(e.above())) shape = RailShape.ASCENDING_EAST;
                if (level.isRail(w.above())) shape = RailShape.ASCENDING_WEST;
            }
            if (shape == null) shape = placedShape;
            updateConnections(shape);
            state = state.setValue(block.getShapeProperty(), shape);
            if (always || level.get(pos) != state) {
                level.set(pos, state);
                for (int i = 0; i < connections.size(); i++) {
                    RailState rail = getRail(connections.get(i));
                    if (rail != null) {
                        rail.removeSoftConnections();
                        if (rail.canConnectTo(this)) rail.connectTo(this);
                    }
                }
            }
        }
    }
}
