package com.autyism.printer.printer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.Tilt;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * 侦测器放下去、以及它以后亮的那一下，会不会动到周围的红石（只读世界里现在的方块，没有任何副作用）。
 * <p>
 * 照原版的规则算：侦测器面前那一格一变，它 2 tick 后亮 2 tick。亮着时只往身后那一格送信号（强充能，身后是实心方块的话
 * 那一格再充能它周围），亮起、熄灭时给身后那一格和它周围的格子发方块更新；它自己的状态变化会被盯着它的侦测器看到。
 * 放下的那一刻给周围六格发方块更新、更新形状。所以“没影响”要同时满足：
 * <ul>
 *     <li>没有侦测器盯着这一格，也没有侦测器盯着会因为它改变形状的邻居；</li>
 *     <li>收到这些方块更新的格子，按侦测器亮着时、熄灭后的信号都不会变：活塞推不推、发射器 / 投掷器 / 合成器发不发、
 *     灯、门、活板门、栅栏门、漏斗、铁轨、中继器、比较器、火把、TNT……都照原版的判断算（包括活塞、发射器的准连接）；</li>
 *     <li>没收到更新、但会被准连接“上膛”的活塞、发射器也不行（这两 tick 里旁边随便放一块就会触发）；</li>
 *     <li>带着信号的红石线不会因为连到侦测器而改走向（原来指着的元件会断电）。</li>
 * </ul>
 * 拿不准的都算“有影响”。
 */
public final class ObserverImpact {
    private ObserverImpact() {
    }

    /**
     * 投影里侦测器面前那一格（input）的方块现在放不下，只是因为缺了这个侦测器：它贴靠的那一格（见 supportOf）就是侦测器，
     * 世界里放不下、在投影世界里（侦测器在）放得下。
     */
    public static boolean dependsOnObserver(Level schematic, Level level, BlockPos input, BlockPos observer) {
        BlockState want = schematic.getBlockState(input);
        if (want.isAir() || !level.getBlockState(input).canBeReplaced()) return false;
        if (!observer.equals(supportOf(want, input))) return false;
        if (!level.getBlockState(observer).canBeReplaced()) return false;
        return !want.canSurvive(level, input) && want.canSurvive(schematic, input);
    }

    /**
     * 这个方块放在 pos 时贴靠的那一格（要靠它才放得住）：放在地面上的看下面，贴墙的看背后，挂着的看上面；
     * 不需要贴靠、或者不认识的返回 null（不会被当成“要靠侦测器”）
     */
    @Nullable
    public static BlockPos supportOf(BlockState s, BlockPos pos) {
        Block b = s.getBlock();
        if (b instanceof FaceAttachedHorizontalDirectionalBlock && s.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            return switch (s.getValue(BlockStateProperties.ATTACH_FACE)) {
                case FLOOR -> pos.below();
                case CEILING -> pos.above();
                case WALL -> pos.relative(s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
            };
        }
        if (b instanceof WallTorchBlock || b instanceof RedstoneWallTorchBlock || b instanceof WallSignBlock || b instanceof WallBannerBlock
                || b instanceof LadderBlock || b instanceof TripWireHookBlock) {
            return pos.relative(s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        }
        if (b instanceof LanternBlock) return s.getValue(LanternBlock.HANGING) ? pos.above() : pos.below();
        if (b instanceof CeilingHangingSignBlock) return pos.above();
        if (b instanceof DiodeBlock || b instanceof RedStoneWireBlock || b instanceof BaseRailBlock || b instanceof CarpetBlock
                || b instanceof BasePressurePlateBlock || b instanceof BaseTorchBlock || b instanceof StandingSignBlock || b instanceof BannerBlock
                || b instanceof SnowLayerBlock || b instanceof FlowerPotBlock || b instanceof CandleBlock) {
            return pos.below();
        }
        if (b instanceof DoorBlock) {
            return s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.below() : null;
        }
        return null;
    }

    /** 现在在 pos 放下一个朝 facing 的侦测器，会不会动到周围的红石；null = 没影响，否则是原因（调试用） */
    @Nullable
    public static String placementImpact(Level level, BlockPos pos, Direction facing) {
        SignalGetter placed = new With(level, pos, observer(facing, false));
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState s = level.getBlockState(n);
            if (watches(s, n, pos)) return "observer at " + n.toShortString() + " watches this spot";
            // 放下时给周围六格的方块更新（发更新的是侦测器，算信号源）
            String r = updateReaction(placed, level, n, s, true);
            if (r != null) return "update next to the observer: " + r;
            // 会连过来、改变形状的邻居被别的侦测器盯着
            if (changesShape(s, d.getOpposite()) && watched(level, n, pos)) return n.toShortString() + " changes shape and is watched";
        }
        // 身后的红石线会连到侦测器上（只有侦测器身后对着它时才连）：带着信号的线改了走向，原来指着的元件会断电
        // （形状变化不发方块更新，那个元件留在 BUD 状态）。十字形的线连上一边以后会变成直线，也算
        BlockPos back = pos.relative(facing.getOpposite());
        BlockState wire = level.getBlockState(back);
        if (wire.getBlock() instanceof RedStoneWireBlock && facing.getAxis().isHorizontal() && wire.getValue(RedStoneWireBlock.POWER) > 0) {
            boolean cross = true;
            for (Direction h : Direction.Plane.HORIZONTAL) cross &= wire.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(h)).isConnected();
            if (cross || !wire.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(facing)).isConnected()) {
                return "powered wire at " + back.toShortString() + " would change direction";
            }
        }
        // 面前那一格会因为侦测器放下而改变形状（墙、栅栏连过来）：侦测器一放下就看到变化，马上亮一下
        BlockPos input = pos.relative(facing);
        if (changesShape(level.getBlockState(input), facing.getOpposite())) {
            String pulse = pulseImpact(level, pos, facing);
            if (pulse != null) return "the input changes shape, the observer fires at once: " + pulse;
        }
        return null;
    }

    /** pos 上朝 facing 的侦测器以后亮一下（面前那一格放上方块、或者被点了一下时），会不会动到周围的红石；null = 没影响 */
    @Nullable
    public static String pulseImpact(Level level, BlockPos pos, Direction facing) {
        // 它自己亮、灭会被盯着它的侦测器看到
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (watches(level.getBlockState(n), n, pos)) return "observer at " + n.toShortString() + " watches this observer";
        }
        SignalGetter off = new With(level, pos, observer(facing, false));
        SignalGetter lit = new With(level, pos, observer(facing, true));
        BlockPos back = pos.relative(facing.getOpposite());
        boolean conductor = level.getBlockState(back).isRedstoneConductor(level, back);
        // 亮起、熄灭时收到方块更新的：身后那一格，和它周围除了侦测器自己的五格
        for (int i = -1; i < 6; i++) {
            BlockPos y = i < 0 ? back : back.relative(Direction.values()[i]);
            if (y.equals(pos)) continue;
            BlockState s = level.getBlockState(y);
            String where = y.equals(back) ? "output " : "next to the output: ";
            // 亮着时收到更新会不会变；熄灭时收到更新会不会变（现在就对不上的 BUD 状态）
            String r = updateReaction(lit, off, y, s, true);
            if (r == null) r = updateReaction(off, off, y, s, true);
            if (r != null) return where + r;
            // 红石线：身后那一格的线直接收到 15；身后是实心方块时，它周围的线从这个强充能的方块收到 15
            if (s.getBlock() instanceof RedStoneWireBlock && s.getValue(RedStoneWireBlock.POWER) < 15 && (y.equals(back) || conductor)) {
                return where + "wire at " + y.toShortString() + " would be powered";
            }
        }
        // 准连接：身后是实心方块时，它斜下方、正下方第二格的活塞 / 发射器 / 投掷器读得到它的充能，但收不到这两次方块更新，
        // 会“上膛”两 tick（这期间旁边随便有个方块更新就会触发）
        if (conductor) {
            for (Direction d : Direction.values()) {
                if (d == Direction.UP) continue;
                BlockPos y = back.relative(d).below();
                if (y.equals(pos)) continue;
                BlockState s = level.getBlockState(y);
                if (s.getBlock() instanceof PistonBaseBlock || s.getBlock() instanceof DispenserBlock) {
                    String r = updateReaction(lit, off, y, s, true);
                    if (r != null) return "quasi-connectivity: " + r;
                }
            }
        }
        return null;
    }

    private static BlockState observer(Direction facing, boolean powered) {
        return Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, facing).setValue(ObserverBlock.POWERED, powered);
    }

    /** 世界里 pos 那一格换成 state（其余照读世界）：原版算信号的那些方法拿它来算“侦测器在这里 / 亮着时”的信号 */
    private record With(Level level, BlockPos pos, BlockState state) implements SignalGetter {
        @Override
        public BlockState getBlockState(BlockPos p) {
            return p.equals(pos) ? state : level.getBlockState(p);
        }

        @Override
        public FluidState getFluidState(BlockPos p) {
            return p.equals(pos) ? state.getFluidState() : level.getFluidState(p);
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos p) {
            return p.equals(pos) ? null : level.getBlockEntity(p);
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinY() {
            return level.getMinY();
        }
    }

    /** s 是侦测器（在 at），正盯着 target */
    private static boolean watches(BlockState s, BlockPos at, BlockPos target) {
        return s.getBlock() instanceof ObserverBlock && at.relative(s.getValue(ObserverBlock.FACING)).equals(target);
    }

    /** 有侦测器盯着 pos（不算在 except 的那个） */
    private static boolean watched(Level level, BlockPos pos, BlockPos except) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (n.equals(except)) continue;
            if (watches(level.getBlockState(n), n, pos)) return true;
        }
        return false;
    }

    /**
     * toObserver 那一面放上一个实心方块（侦测器），s 会不会改变形状：墙、栅栏、玻璃板 / 铁栏杆、红石线连过来，
     * 音符盒换音色（看下面那一格），脚手架下面有了支撑
     */
    private static boolean changesShape(BlockState s, Direction toObserver) {
        Block b = s.getBlock();
        if (b instanceof CrossCollisionBlock || b instanceof RedStoneWireBlock) return toObserver.getAxis().isHorizontal();
        if (b instanceof WallBlock) return toObserver != Direction.DOWN;
        if (b instanceof NoteBlock) return toObserver.getAxis().isVertical();
        if (b instanceof ScaffoldingBlock) return toObserver == Direction.DOWN;
        return false;
    }

    /**
     * y 上的方块 s 收到一次方块更新（信号按 view 算）会不会变；null = 不会。都照原版各方块收到更新时的判断：
     * 状态里记着“有没有充能”的（活塞伸没伸出、发射器 / 合成器、灯、门、漏斗、铁轨、钟、音符盒……）看和 view 的信号对不对得上；
     * 比较器、命令方块这类状态里看不出输入的，看输入和 base（没有这一下信号时）比有没有变。
     * fromSignalSource：发更新的是信号源（侦测器）——普通铁轨的丁字路口收到这种更新会重新选方向。
     */
    @Nullable
    private static String updateReaction(SignalGetter view, SignalGetter base, BlockPos y, BlockState s, boolean fromSignalSource) {
        Block b = s.getBlock();
        String at = " at " + y.toShortString();
        if (b instanceof MovingPistonBlock) return "moving piston" + at;
        if (b instanceof PistonBaseBlock) {
            return pistonPowered(view, y, s.getValue(BlockStateProperties.FACING)) != s.getValue(PistonBaseBlock.EXTENDED) ? "piston" + at + " would move" : null;
        }
        if (b instanceof DispenserBlock) {
            // 发射器、投掷器有准连接：上面那一格有信号也算
            boolean powered = view.hasNeighborSignal(y) || view.hasNeighborSignal(y.above());
            return powered != s.getValue(BlockStateProperties.TRIGGERED) ? b + at + " would fire" : null;
        }
        if (b instanceof CrafterBlock) {
            return view.hasNeighborSignal(y) != s.getValue(BlockStateProperties.TRIGGERED) ? "crafter" + at + " would craft" : null;
        }
        if (b instanceof TntBlock) return view.hasNeighborSignal(y) ? "TNT" + at + " would be lit" : null;
        if (b instanceof RepeaterBlock) {
            Direction f = s.getValue(BlockStateProperties.HORIZONTAL_FACING);
            // 被侧面的中继器 / 比较器锁住的不动
            if (diodeSide(view, y, f, true) > 0) return null;
            return (diodeInput(view, y, f) > 0) != s.getValue(BlockStateProperties.POWERED) ? "repeater" + at + " would switch" : null;
        }
        if (b instanceof ComparatorBlock) {
            Direction f = s.getValue(BlockStateProperties.HORIZONTAL_FACING);
            // 后面那一格是容器之类的（读数）时，比较器不看信号；读数客户端不一定知道，但这一下信号改变不了它
            boolean rear = !view.getBlockState(y.relative(f)).hasAnalogOutputSignal() && diodeInput(view, y, f) != diodeInput(base, y, f);
            return rear || diodeSide(view, y, f, false) != diodeSide(base, y, f, false) ? "comparator" + at + " input would change" : null;
        }
        if (b instanceof RedstoneWallTorchBlock) {
            Direction toward = s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            return view.hasSignal(y.relative(toward), toward) == s.getValue(BlockStateProperties.LIT) ? "wall torch" + at + " would switch" : null;
        }
        if (b instanceof RedstoneTorchBlock) {
            return view.hasSignal(y.below(), Direction.DOWN) == s.getValue(BlockStateProperties.LIT) ? "torch" + at + " would switch" : null;
        }
        if (b instanceof RedstoneLampBlock) return view.hasNeighborSignal(y) != s.getValue(BlockStateProperties.LIT) ? "lamp" + at + " would switch" : null;
        if (b instanceof DoorBlock) {
            // 门的两半一起算
            Direction other = s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN;
            boolean powered = view.hasNeighborSignal(y) || view.hasNeighborSignal(y.relative(other));
            return powered != s.getValue(BlockStateProperties.POWERED) ? "door" + at + " would open/close" : null;
        }
        if (b instanceof HopperBlock) return view.hasNeighborSignal(y) == s.getValue(HopperBlock.ENABLED) ? "hopper" + at + " would lock/unlock" : null;
        if (b instanceof BaseRailBlock rail) {
            if (railUnsupported(view, y, s.getValue(rail.getShapeProperty()))) return "rail" + at + " would break";
            if (b instanceof PoweredRailBlock) {
                boolean powered = view.hasNeighborSignal(y) || railChain(view, y, s, true, 0) || railChain(view, y, s, false, 0);
                return powered != s.getValue(PoweredRailBlock.POWERED) ? "powered rail" + at + " would switch" : null;
            }
            // 普通铁轨的丁字路口：信号源发来更新时按有没有信号重新选方向（照着投影精确放的方向会被改掉）
            if (b instanceof RailBlock && fromSignalSource && railConnections(view, y) == 3) return "rail junction" + at + " may switch";
            return null;
        }
        if (b instanceof BigDripleafBlock) {
            return s.getValue(BlockStateProperties.TILT) != Tilt.NONE && view.hasNeighborSignal(y) ? "dripleaf" + at + " would reset" : null;
        }
        if (b instanceof CommandBlock || b instanceof StructureBlock || b instanceof CalibratedSculkSensorBlock) {
            return view.hasNeighborSignal(y) != base.hasNeighborSignal(y) ? b + at + " input would change" : null;
        }
        // 其余带 POWERED 的方块（钟、音符盒、活板门、栅栏门、铜灯、头颅、架子……）：POWERED 就是“旁边有没有信号”。
        // 按钮、拉杆、绊线、避雷针、侦测器、中继器、压力板、讲台、探测铁轨的 POWERED 是它们自己发出的信号，不看旁边
        if (s.hasProperty(BlockStateProperties.POWERED) && !poweredBySelf(b)) {
            return view.hasNeighborSignal(y) != s.getValue(BlockStateProperties.POWERED) ? b + at + " would switch" : null;
        }
        return null;
    }

    private static boolean poweredBySelf(Block b) {
        return b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof TripWireBlock || b instanceof TripWireHookBlock
                || b instanceof LightningRodBlock || b instanceof ObserverBlock || b instanceof DiodeBlock || b instanceof BasePressurePlateBlock
                || b instanceof LecternBlock || b instanceof DetectorRailBlock;
    }

    /** 和原版活塞判断有没有被充能一样：除了推出去的那一面以外的五面，再加上方那一格（准连接） */
    private static boolean pistonPowered(SignalGetter view, BlockPos pos, Direction facing) {
        for (Direction d : Direction.values()) {
            if (d != facing && view.hasSignal(pos.relative(d), d)) return true;
        }
        if (view.hasSignal(pos, Direction.DOWN)) return true;
        BlockPos up = pos.above();
        for (Direction d : Direction.values()) {
            if (d != Direction.DOWN && view.hasSignal(up.relative(d), d)) return true;
        }
        return false;
    }

    /** 原版中继器 / 比较器后面的输入（红石线按它自己的强度算） */
    private static int diodeInput(SignalGetter view, BlockPos pos, Direction facing) {
        BlockPos in = pos.relative(facing);
        int i = view.getSignal(in, facing);
        if (i >= 15) return i;
        BlockState s = view.getBlockState(in);
        return Math.max(i, s.getBlock() instanceof RedStoneWireBlock ? s.getValue(RedStoneWireBlock.POWER) : 0);
    }

    /** 原版中继器 / 比较器两侧的输入（中继器只认中继器、比较器） */
    private static int diodeSide(SignalGetter view, BlockPos pos, Direction facing, boolean diodesOnly) {
        Direction cw = facing.getClockWise(), ccw = facing.getCounterClockWise();
        return Math.max(view.getControlInputSignal(pos.relative(cw), cw, diodesOnly), view.getControlInputSignal(pos.relative(ccw), ccw, diodesOnly));
    }

    /** 原版铁轨收到方块更新时会不会因为没有支撑而掉落 */
    private static boolean railUnsupported(SignalGetter view, BlockPos pos, RailShape shape) {
        if (!Block.canSupportRigidBlock(view, pos.below())) return true;
        return switch (shape) {
            case ASCENDING_EAST -> !Block.canSupportRigidBlock(view, pos.east());
            case ASCENDING_WEST -> !Block.canSupportRigidBlock(view, pos.west());
            case ASCENDING_NORTH -> !Block.canSupportRigidBlock(view, pos.north());
            case ASCENDING_SOUTH -> !Block.canSupportRigidBlock(view, pos.south());
            default -> false;
        };
    }

    /** 原版 RailState.countPotentialConnections：四周（含上下一格）有几个方向有铁轨 */
    private static int railConnections(SignalGetter view, BlockPos pos) {
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = pos.relative(d);
            if (BaseRailBlock.isRail(view.getBlockState(p)) || BaseRailBlock.isRail(view.getBlockState(p.above()))
                    || BaseRailBlock.isRail(view.getBlockState(p.below()))) {
                n++;
            }
        }
        return n;
    }

    /** 原版 PoweredRailBlock.findPoweredRailSignal：沿着铁轨往一个方向找有信号的同种铁轨（最多 8 节） */
    private static boolean railChain(SignalGetter view, BlockPos pos, BlockState state, boolean forward, int depth) {
        if (depth >= 8) return false;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        boolean alsoBelow = true;
        RailShape shape = state.getValue(PoweredRailBlock.SHAPE);
        switch (shape) {
            case NORTH_SOUTH -> z += forward ? 1 : -1;
            case EAST_WEST -> x += forward ? -1 : 1;
            case ASCENDING_EAST -> {
                if (forward) {
                    x--;
                } else {
                    x++;
                    y++;
                    alsoBelow = false;
                }
                shape = RailShape.EAST_WEST;
            }
            case ASCENDING_WEST -> {
                if (forward) {
                    x--;
                    y++;
                    alsoBelow = false;
                } else {
                    x++;
                }
                shape = RailShape.EAST_WEST;
            }
            case ASCENDING_NORTH -> {
                if (forward) {
                    z++;
                } else {
                    z--;
                    y++;
                    alsoBelow = false;
                }
                shape = RailShape.NORTH_SOUTH;
            }
            case ASCENDING_SOUTH -> {
                if (forward) {
                    z++;
                    y++;
                    alsoBelow = false;
                } else {
                    z--;
                }
                shape = RailShape.NORTH_SOUTH;
            }
            default -> {
            }
        }
        Block block = state.getBlock();
        return sameRailWithPower(view, new BlockPos(x, y, z), block, forward, depth, shape)
                || alsoBelow && sameRailWithPower(view, new BlockPos(x, y - 1, z), block, forward, depth, shape);
    }

    private static boolean sameRailWithPower(SignalGetter view, BlockPos pos, Block block, boolean forward, int depth, RailShape shape) {
        BlockState s = view.getBlockState(pos);
        if (!s.is(block)) return false;
        RailShape other = s.getValue(PoweredRailBlock.SHAPE);
        if (shape == RailShape.EAST_WEST && (other == RailShape.NORTH_SOUTH || other == RailShape.ASCENDING_NORTH || other == RailShape.ASCENDING_SOUTH)) {
            return false;
        }
        if (shape == RailShape.NORTH_SOUTH && (other == RailShape.EAST_WEST || other == RailShape.ASCENDING_EAST || other == RailShape.ASCENDING_WEST)) {
            return false;
        }
        if (!s.getValue(PoweredRailBlock.POWERED)) return false;
        return view.hasNeighborSignal(pos) || railChain(view, pos, s, forward, depth + 1);
    }
}
