package com.autyism.printer.printer;

import com.autyism.printer.I18n;
import com.autyism.printer.Reference;
import com.autyism.printer.printer.action.Action;
import com.autyism.printer.handler.ModuleManager;
import com.autyism.printer.printer.action.ClickAction;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.BlockMatchingType;
import com.autyism.printer.utils.*;
//? if <26.3
import net.fabricmc.fabric.mixin.content.registry.AxeItemAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("IfCanBeSwitch")
public class PlacementGuide {
    /** 最近决定要放的门（位置 → 决定时的 tick），见 DOOR */
    private static final java.util.Map<BlockPos, Integer> RECENT_DOORS = new java.util.HashMap<>();

    protected static final Map<Block, Block> STRIPPED_LOGS = BlockUtils.getStrippedBlocksMap();
    protected static List<String> compostWhitelistCache = new ArrayList<>();      // 缓存堆肥桶白名单的字符串列表（用于判断是否修改）
    protected static Item[] whitelistItemsCache = new Item[0];    // 缓存过滤后的可堆肥物品列表（避免重复计算）
    protected final @NotNull Minecraft mc;
    protected final AtomicReference<Boolean> skip = new AtomicReference<>(false);


    public PlacementGuide(@NotNull Minecraft client) {
        this.mc = client;
    }

    public @Nullable Action getAction(SchematicBlockContext ctx) {
        BlockMatchingType state = BlockMatchingType.get(ctx);
        if (state == BlockMatchingType.CORRECT) return null;
        // canSurvive 只阻拦放置（MISSING），不阻拦破坏（ERROR_BLOCK 走 BREAK_WRONG_BLOCK）
        if (state == BlockMatchingType.MISSING_BLOCK && !ctx.requiredState.canSurvive(ctx.level, ctx.blockPos)) return null;
        // 双格方块（玫瑰丛等）：MISSING 时上半部分由下半部分自动生成，不独立放置
        if (state == BlockMatchingType.MISSING_BLOCK
                && ctx.requiredState.getBlock() instanceof DoublePlantBlock
                && ctx.requiredState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return null;
        }
        for (ClassHook hook : ClassHook.values()) {
            for (Class<?> clazz : hook.classes) {
                if (clazz != null && clazz.isInstance(ctx.requiredState.getBlock())) {
                    skip.set(false);
                    @Nullable Action action = buildAction(ctx, hook, state, skip);
                    if (action == null && skip.get()) {   // hook 不处理该方块, 继续尝试下一个
                        continue;
                    }
                    return action;
                }
            }
        }
        return buildAction(ctx, ClassHook.DEFAULT, state, skip);    // 兜底处理
    }

    @SuppressWarnings("EnhancedSwitchMigration")
    private @Nullable Action buildAction(SchematicBlockContext ctx, ClassHook requiredType, BlockMatchingType state, AtomicReference<Boolean> skip) {
        // 跳过含水方块
        if (Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue() && (BlockUtils.needsWater(ctx.requiredState) || BlockUtils.isLiveCoral(ctx.requiredState))) {
            return null;
        }
        if (Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                && BlockUtils.needsWater(ctx.requiredState)) {
            boolean canGenerateWater = mc.gameMode != null && !mc.gameMode.getPlayerMode().isCreative();
            switch (IceForWaterFlow.decideBuildAction(
                    true,
                    BlockUtils.isWaterSource(ctx.currentState) || BlockUtils.isWaterlogged(ctx.currentState),
                    ctx.currentState.getBlock() instanceof IceBlock,
                    state == BlockMatchingType.MISSING_BLOCK,
                    iceDownCheck(ctx),
                    canGenerateWater)) {
                case PLACE_ICE -> {
                    return new Action().setItem(Items.ICE);
                }
                case PLACE_BLOCK -> {
                    return buildActionMissingBlock(ctx, requiredType, skip);
                }
                case QUEUE_ICE_BREAK -> {
                    if (!BreakUtils.INSTANCE.inQueue(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx.blockPos);
                    return new Action().setItem(Items.ICE);
                }
                case SKIP -> {
                    // 创造模式：提示后跳过
                    if (mc.gameMode != null && mc.gameMode.getPlayerMode().isCreative()
                            && BlockUtils.needsWater(ctx.requiredState)) {
                        MessageUtils.setOverlayMessage(I18n.ICE_CREATIVE_MODE.getName());
                    }
                    return null;
                }
            }
        }
        Action action;
        switch (state) {
            case MISSING_BLOCK:
                action = buildActionMissingBlock(ctx, requiredType, skip);
                break;
            case ERROR_BLOCK:
                // 已有方块要拆 / 要改：这一格上一次的动作还没被服务端确认时，客户端看到的只是预测
                // （例如用假视角放的方块，客户端按玩家真实视角预测了朝向），先不动，免得把放对了的方块拆掉或点乱
                if (ActionConfirm.pending(ctx.level, ctx.blockPos, Reference.MINECRAFT.player.tickCount)) return null;
                action = buildActionErrorBlock(ctx, requiredType, skip);
                break;
            case ERROR_BLOCK_STATE:
                // 点一下调整（音符盒、中继器、栅栏门开关……）或再放一次（雪层、蜡烛……）：上一次的点击被服务端确认、
                // 客户端看到真实结果之后才点下一次，否则有延迟时会按旧状态多点（音符盒点过头、栅栏门来回开关）
                if (ActionConfirm.pending(ctx.level, ctx.blockPos, Reference.MINECRAFT.player.tickCount)) return null;
                action = buildActionErrorBlockState(ctx, requiredType, skip);
                break;
            default:
                action = null;
                break;
        }
        return action;
    }

    private boolean iceDownCheck(SchematicBlockContext ctx) {
        Block downBlockState = ctx.level.getBlockState(ctx.blockPos.below()).getBlock();
        return downBlockState == Blocks.COBWEB
                || downBlockState == Blocks.BAMBOO_SAPLING
                || downBlockState instanceof LiquidBlock;
    }

    /*** 缺失方块：实际位置为空，或当前方块在可替换列表中且启用了替换功能 ***/
    private @Nullable Action buildActionMissingBlock(SchematicBlockContext ctx, ClassHook requiredType, AtomicReference<Boolean> skip) {
        switch (requiredType) {
            case TORCH -> {
                Direction lookDirection = ctx.getRequiredStateProperty(WallTorchBlock.FACING).orElse(Direction.UP).getOpposite();
                return new Action().setSides(lookDirection).setLookDirection(lookDirection).setRequiresSupport();
            }
            case AMETHYST -> {
                Direction lookDirection = ctx.getRequiredStateProperty(AmethystClusterBlock.FACING).orElse(Direction.UP).getOpposite();
                return new Action().setSides(lookDirection).setRequiresSupport();
            }
            case SLAB -> {
                Map<Direction, Vec3> slabSides = BlockUtils.getSlabSides(ctx.level, ctx.blockPos, ctx.requiredState.getValue(SlabBlock.TYPE));
                return new Action().setSides(slabSides);
            }
            case STAIR -> {
                Direction facing = ctx.requiredState.getValue(StairBlock.FACING);
                Half half = ctx.requiredState.getValue(StairBlock.HALF);
                Map<Direction, Vec3> sides = new java.util.EnumMap<>(Direction.class);
                if (half == Half.BOTTOM) {
                    sides.put(Direction.DOWN, new Vec3(0, 0, 0));
                    sides.put(facing, new Vec3(0, 0, 0));
                } else {
                    sides.put(Direction.UP, new Vec3(0, 0.75, 0));
                    sides.put(facing.getOpposite(), new Vec3(0, 0.75, 0));
                }
                return new Action().setSides(sides).setLookDirection(facing);
            }
            case TRAPDOOR -> {
                Half half = ctx.requiredState.getValue(TrapDoorBlock.HALF);
                Direction side = half == Half.TOP ? Direction.UP : Direction.DOWN;
                Direction facing = ctx.requiredState.getValue(TrapDoorBlock.FACING);
                return new Action()
                        .setSides(side)
                        .setLookDirection(facing.getOpposite());
            }
            case STRIP_LOG -> {
                Action action = new Action().setSides(ctx.requiredState.getValue(RotatedPillarBlock.AXIS));
                Item[] items = {ctx.requiredState.getBlock().asItem()};
                if (Configs.Print.STRIP_LOGS.getBooleanValue()) {
                    for (Map.Entry<Block, Block> entry : STRIPPED_LOGS.entrySet()) {
                        if (ctx.requiredState.getBlock() == entry.getValue()) {
                            items = new Item[]{entry.getValue().asItem(), entry.getKey().asItem()};
                            break;
                        }
                    }
                }
                action.setItems(items);
                return action;
            }
            case ANVIL -> {
                return new Action().setLookDirection(ctx.requiredState.getValue(AnvilBlock.FACING).getCounterClockWise());
            }
            case HOPPER -> {
                Direction facing = ctx.requiredState.getValue(HopperBlock.FACING);
                return new Action().setSides(facing);
            }
            case NETHER_PORTAL -> {
                boolean canCreatePortal = PortalShape.findEmptyPortalShape(ctx.level, ctx.blockPos, Direction.Axis.X).isPresent();
                if (canCreatePortal) {
                    return new Action().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                }
            }
            case COCOA -> {
                return new Action().setSides(ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING));
            }
            case CRAFTER -> {
                FrontAndTop frontAndTop = ctx.requiredState.getValue(BlockStateProperties.ORIENTATION);
                Direction facing = frontAndTop.front().getOpposite();
                Direction rotation = frontAndTop.top().getOpposite();
                if (facing == Direction.UP) {
                    return new Action().setLookDirection(rotation, Direction.UP).setNeedWaitModifyLook(true);
                } else if (facing == Direction.DOWN) {
                    return new Action().setLookDirection(rotation.getOpposite(), Direction.DOWN).setNeedWaitModifyLook(true);
                } else {
                    return new Action().setLookDirection(facing, facing).setNeedWaitModifyLook(true);
                }
            }
            case CHEST -> {
                Direction facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
                ChestType type = ctx.requiredState.getValue(BlockStateProperties.CHEST_TYPE);
                Map<Direction, Vec3> noChestSides = new java.util.EnumMap<>(Direction.class);

                for (Direction side : Direction.values()) {
                    if (ctx.level.getBlockState(ctx.blockPos.relative(side)).getBlock() instanceof ChestBlock) {
                        continue;
                    }
                    noChestSides.put(side, Vec3.ZERO);
                }

                if (type == ChestType.SINGLE) {
                    for (Direction side : BlockStateProperties.HORIZONTAL_FACING.getPossibleValues()) {
                        if (!noChestSides.containsKey(side)) {
                            return new Action().setLookDirection(facing).setShift();
                        }
                        return new Action().setSides(noChestSides).setLookDirection(facing);
                    }
                } else {
                    Direction chestFacing = facing;
                    if (type == ChestType.LEFT) {
                        chestFacing = facing.getCounterClockWise();
                    } else if (type == ChestType.RIGHT) {
                        chestFacing = facing.getClockWise();
                    }
                    if (ctx.level.getBlockState(ctx.blockPos.relative(chestFacing)).getBlock() instanceof ChestBlock) {
                        return new Action().setSides(Map.of(chestFacing, Vec3.ZERO)).setLookDirection(facing).setShift(false);
                    } else if (type == ChestType.RIGHT) {
                        // 大箱子固定先放左半、右半等左半真的出现在世界里再放（不潜行才会合并）。
                        // 有的环境客户端预测有延迟，两半同一轮放时右半看不到左半，会被当成单箱潜行放下，再也合不上
                        return null;
                    } else {
                        return new Action().setSides(noChestSides).setLookDirection(facing).setShift();
                    }
                }
            }
            case BED -> {
                if (ctx.requiredState.getValue(BedBlock.PART) == BedPart.FOOT)
                    return new Action().setLookDirection(ctx.requiredState.getValue(BedBlock.FACING));
            }
            case BELL -> {
                Direction side;
                switch (ctx.requiredState.getValue(BellBlock.ATTACHMENT)) {
                    case FLOOR -> side = Direction.DOWN;
                    case CEILING -> side = Direction.UP;
                    default -> side = ctx.requiredState.getValue(BellBlock.FACING);
                }

                Direction look = ctx.requiredState.getValue(BellBlock.ATTACHMENT) != BellAttachType.SINGLE_WALL && ctx.requiredState.getValue(BellBlock.ATTACHMENT) != BellAttachType.DOUBLE_WALL ? ctx.requiredState.getValue(BellBlock.FACING) : null;

                return new Action().setSides(side).setLookDirection(look);
            }
            case DOOR -> {
                Direction facing = ctx.requiredState.getValue(DoorBlock.FACING);
                DoorHingeSide hinge = ctx.requiredState.getValue(DoorBlock.HINGE);
                BlockPos upperPos = ctx.blockPos.above();

                // 原版 DoorBlock.getHinge：左右两边都没有决定性方块时按点击位置决定门轴——
                // 点在门朝向的逆时针一侧（例如朝南的门点在东半边）= 左门轴，顺时针一侧 = 右门轴
                Direction hingeSide = hinge == DoorHingeSide.LEFT ? facing.getCounterClockWise() : facing.getClockWise();
                Vec3 hingeVec = new Vec3(hingeSide.getStepX() * 0.25, 0, hingeSide.getStepZ() * 0.25);

                // 只点脚下的方块（门本来就必须放在方块上），点击位置往门轴一侧偏：
                // 以前还会点“门轴那一侧”的面，但那一面的点击坐标正好落在另一边，门轴会反
                Map<Direction, Vec3> sides = new java.util.EnumMap<>(Direction.class);
                sides.put(Direction.DOWN, hingeVec);

                // 获取左右方块状态
                Direction left = facing.getCounterClockWise();
                Direction right = facing.getClockWise();
                // 原版按左右（上下两格）的邻居决定门轴：这些邻居在投影里有方块的，必须先放好、并且客户端已经看到，
                // 否则服务端已经有墙 / 另一扇门而打印机还没看到时，会按点击位置放、被服务端改成另一侧
                // （只看同一层：上一层分层模式下一定比门晚放；投影里的另一扇门不用等它放好（两扇门会互相等成死锁），但刚决定要放的那扇要等客户端看到）
                int tickNow = Reference.MINECRAFT.player.tickCount;
                for (BlockPos n : new BlockPos[]{ctx.blockPos.relative(left), ctx.blockPos.relative(right)}) {
                    BlockState want = ctx.schematic.getBlockState(n);
                    if (!want.isAir() && !(want.getBlock() instanceof DoorBlock) && ctx.level.getBlockState(n).getBlock() != want.getBlock()) return null;
                    // 旁边那扇门刚决定要放、客户端还没看到：服务端可能已经有了，会决定这扇门的门轴 —— 等客户端看到再算
                    // （真实实例里出现过：门轴在中间的双开门，第二扇被服务端放成了另一侧门轴）
                    Integer decided = RECENT_DOORS.get(n);
                    if (decided != null && tickNow - decided >= 0 && tickNow - decided < 40
                            && !(ctx.level.getBlockState(n).getBlock() instanceof DoorBlock)) return null;
                }
                BlockState leftState = ctx.level.getBlockState(ctx.blockPos.relative(left));
                BlockState leftUpperState = ctx.level.getBlockState(upperPos.relative(left));
                BlockState rightState = ctx.level.getBlockState(ctx.blockPos.relative(right));
                BlockState rightUpperState = ctx.level.getBlockState(upperPos.relative(right));

                int occupancy = (leftState.isCollisionShapeFullBlock(ctx.level, ctx.blockPos.relative(left)) ? -1 : 0) + (leftUpperState.isCollisionShapeFullBlock(ctx.level, upperPos.relative(left)) ? -1 : 0) + (rightState.isCollisionShapeFullBlock(ctx.level, ctx.blockPos.relative(right)) ? 1 : 0) + (rightUpperState.isCollisionShapeFullBlock(ctx.level, upperPos.relative(right)) ? 1 : 0);

                boolean isLeftDoor = leftState.getBlock() instanceof DoorBlock && leftState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
                boolean isRightDoor = rightState.getBlock() instanceof DoorBlock && rightState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;

                boolean condition = (hinge == DoorHingeSide.RIGHT && ((isLeftDoor && !isRightDoor) || occupancy > 0)) || (hinge == DoorHingeSide.LEFT && ((isRightDoor && !isLeftDoor) || occupancy < 0)) || (occupancy == 0 && (isLeftDoor == isRightDoor));
                if (condition) {
                    if (RECENT_DOORS.size() > 256) RECENT_DOORS.clear();
                    RECENT_DOORS.put(ctx.blockPos.immutable(), tickNow);
                    return new Action().setSides(sides).setLookDirection(facing).setRequiresSupport();
                }
            }
            case DIRT_PATH, FARMLAND -> {
                return new Action().setItems(Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.MYCELIUM, Items.PODZOL);
            }
            case BIG_DRIPLEAF_STEM -> {
                return new Action().setItem(Items.BIG_DRIPLEAF);
            }
            case CAVE_VINES -> {
                return new Action().setItem(Items.GLOW_BERRIES).setRequiresSupport();
            }
            case WEEPING_VINES -> {
                return new Action().setItem(Items.WEEPING_VINES).setRequiresSupport();
            }
            case TWISTING_VINES -> {
                return new Action().setItem(Items.TWISTING_VINES).setRequiresSupport();
            }
            case FLOWER_POT -> {
                return new Action().setItem(Items.FLOWER_POT);
            }
            case VINES, GLOW_LICHEN -> {
                // 原版藤蔓 / 发光地衣贴哪一面由玩家“看的方向”决定（getNearestLookingDirections），不是点击的面：
                // 必须同时朝那个方向看，否则会贴到别的面上
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN && ctx.requiredState.getBlock() == Blocks.VINE) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        // 支撑方块还没放时原版会贴到别的面：先等支撑
                        return new Action().setSides(direction).setLookDirection(direction).setRequiresSupport();
                    }
                }
            }
            case FIRE -> {
                if (ctx.requiredState.getBlock() instanceof SoulFireBlock)
                    return new Action().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        return new Action().setSides(direction).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                    }
                }
                return new Action().setSides(Direction.DOWN).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
            }
            case OBSERVER -> {
                @Nullable
                Direction facing = ctx.getRequiredStateProperty(ObserverBlock.FACING).orElse(null);
                if (facing == null) {
                    return null;
                }

                SchematicBlockContext input = ctx.offset(facing);
                SchematicBlockContext output = ctx.offset(facing.getOpposite());

                if (Configs.Print.SAFELY_OBSERVER.getBooleanValue()) {
                    List<Property<?>> inputPropertiesToIgnore = new ArrayList<>();
                    if (input.requiredState.getBlock() instanceof WallBlock) {
                        BlockUtils.getWallFacingProperty(facing.getOpposite())
                                .ifPresent(inputPropertiesToIgnore::add);
                    }
                    if (output.requiredState.getBlock() instanceof CrossCollisionBlock) {
                        BlockUtils.getCrossCollisionBlock(facing.getOpposite())
                                .ifPresent(inputPropertiesToIgnore::add);
                    }

                    BlockMatchingType inputState = BlockMatchingType.get(input, inputPropertiesToIgnore.toArray(new Property<?>[0]));
                    BlockMatchingType outputState = BlockMatchingType.get(output);

                    if (inputState == BlockMatchingType.CORRECT && outputState == BlockMatchingType.CORRECT) {
                        if (BlockUtils.checkObserverChain(input)) {
                            return new Action().setLookDirection(facing).setNeedWaitModifyLook(true);
                        }
                        return null;
                    }

                    if (inputState == BlockMatchingType.CORRECT) {
                        SchematicBlockContext temp = input;
                        while (temp.requiredState.getBlock() instanceof FallingBlock) {
                            SchematicBlockContext offset = temp.offset(Direction.DOWN);
                            if (BlockMatchingType.get(offset) != BlockMatchingType.CORRECT) {
                                return null;
                            }
                            temp = offset;
                        }

                        if (!output.requiredState.isAir() && !BlockUtils.checkObserverChain(input)) {
                            return null;
                        }

                        for (Direction d : Direction.values()) {
                            SchematicBlockContext offset = output.offset(d);
                            if (offset.blockPos.equals(output.blockPos) || offset.blockPos.equals(input.blockPos) || offset.blockPos.equals(ctx.blockPos)) {
                                continue;
                            }
                            if (offset.requiredState.getBlock() instanceof PistonBaseBlock && !offset.currentState.isAir()) {
                                return null;
                            }
                        }

                    } else if (inputState == BlockMatchingType.ERROR_BLOCK_STATE) {
                        return null;
                    } else {
                        if (!output.requiredState.isAir()) {
                            if (output.currentState.isAir() && input.requiredState.getBlock() instanceof WallBlock) {
                                BlockPosCooldownManager.INSTANCE.setCooldown(ctx.level, "observer", ctx.blockPos, 2);
                                return new Action().setLookDirection(facing).setNeedWaitModifyLook(true);
                            }
                            return null;
                        } else {
                            // 检查是否被其他侦测器侦测
                            if (BlockUtils.checkObserverChain(input)) {
                                return new Action().setLookDirection(facing).setNeedWaitModifyLook(true);
                            }
                            if (!BlockUtils.checkObserverChain(output)) {
                                return null;
                            }
                        }
                    }
                }

                return new Action().setLookDirection(facing).setNeedWaitModifyLook(true);
            }
            case LADDER -> {
                Direction facing = ctx.requiredState.getValue(LadderBlock.FACING);
                return new Action()
                        .setSides(facing)
                        .setLookDirection(facing.getOpposite())
                        .setNeedWaitModifyLook();
            }
            case LANTERN -> {
                if (ctx.requiredState.getValue(LanternBlock.HANGING))
                    return new Action().setLookDirection(Direction.UP);
                return new Action().setLookDirection(Direction.DOWN);
            }
            case ROD -> {
                Block requiredBlock = ctx.requiredState.getBlock();
                Direction facing = ctx.requiredState.getValue(EndRodBlock.FACING);

                // 如果前面朝向自己的末地烛，而放置方式相反，那么反向放置
                if (requiredBlock instanceof EndRodBlock) {
                    BlockState forwardState = ctx.level.getBlockState(ctx.blockPos.relative(facing));
                    BlockState forwardStateSchematic = ctx.level.getBlockState(ctx.blockPos.relative(facing));
                    if (forwardState.is(requiredBlock) && forwardState.getValue(EndRodBlock.FACING) == facing.getOpposite()) {
                        return new Action().setSides(facing);
                    }
                    // 如果投影中后面有相同朝向的末地烛，则先跳过放置
                    if (forwardStateSchematic.is(requiredBlock) && forwardStateSchematic.getValue(EndRodBlock.FACING) == facing) {
                        // 但是这个投影已经被正确填装时可以打印
                        if (forwardStateSchematic == forwardState) return new Action().setSides(facing.getOpposite());
                        return null;
                    }
                }
                return new Action().setSides(facing.getOpposite());
            }
            case TRIPWIRE_HOOK -> {
                Direction facing = ctx.requiredState.getValue(TripWireHookBlock.FACING);
                return new Action().setSides(facing);
            }
            case RAIL -> {
                RailShape shape;
                if (ctx.requiredState.getBlock() instanceof RailBlock)
                    shape = ctx.requiredState.getValue(RailBlock.SHAPE);
                else shape = ctx.requiredState.getValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT);

                // 铁轨安全放置：按原版规则模拟“现在放会怎样”，只有这节形状正确、不会把旁边已经放好的铁轨拉歪、
                // 不会出现没支撑而掉落的上坡铁轨时才放；否则等别的铁轨放好再试，放不对的就不放（见 RailSim）
                if (Configs.Print.SAFE_RAILS.getBooleanValue()) {
                    // 先按规划好的顺序（排在前面的铁轨放好了才轮到这节），再做实时的安全判断
                    Direction look = !RailPlanner.isTurn(ctx.level, ctx.schematic, ctx.blockPos) ? null : RailSim.findSafeLook(ctx.level, ctx.schematic, ctx.blockPos, ctx.requiredState,
                            Reference.MINECRAFT.player.tickCount);
                    if (look == null) {
                        ModuleManager.PRINT.deferToUpperLayer(ctx.blockPos);
                        return null;
                    }
                    // 铁轨的初始方向取玩家水平朝向，服务端按头部朝向判断：要等转头生效
                    return new Action().setLookDirection(look).setNeedWaitModifyLook();
                }
                Action action = new Action().setNeedWaitModifyLook();
                switch (shape) {
                    case EAST_WEST, ASCENDING_EAST -> action.setLookDirection(Direction.EAST);
                    case NORTH_SOUTH, ASCENDING_NORTH -> action.setLookDirection(Direction.NORTH);
                    case ASCENDING_WEST -> action.setLookDirection(Direction.WEST);
                    case ASCENDING_SOUTH -> action.setLookDirection(Direction.SOUTH);
                }
                return action;
            }
            case PISTON -> {
                Direction facing = ctx.requiredState.getValue(BlockStateProperties.FACING);
                // 侦测器安全放置
                if (Configs.Print.SAFELY_OBSERVER.getBooleanValue()) {
                    // 活塞四周
                    for (Direction direction : Direction.values()) {
                        SchematicBlockContext temp = ctx.offset(direction);
                        while (temp.requiredState.getBlock() instanceof ObserverBlock) {
                            @Nullable Direction tempObserverFacing = temp.getRequiredStateProperty(ObserverBlock.FACING).orElse(null);
                            if (tempObserverFacing != null) {
                                SchematicBlockContext offset = temp.offset(tempObserverFacing);
                                if (tempObserverFacing == direction) {
                                    if (BlockMatchingType.get(offset) != BlockMatchingType.CORRECT) {
                                        return null;
                                    }
                                }
                                temp = offset;
                            }
                        }
                    }

                }
                return new Action().setLookDirection(facing.getOpposite()).setNeedWaitModifyLook();
            }
            case SIGN -> {
                Block signBlock = ctx.requiredState.getBlock();
                // 站立告示牌：处理0-15的16方向旋转值
                if (signBlock instanceof StandingSignBlock) {
                    int rotation = ctx.requiredState.getValue(StandingSignBlock.ROTATION);
                    return new Action()
                            .setSides(Direction.DOWN)
                            .setLookRotation(rotation)
                            .setRequiresSupport();
                }
                // 墙告示牌：保留原有4方向逻辑
                if (signBlock instanceof WallSignBlock) {
                    Direction facing = ctx.requiredState.getValue(WallSignBlock.FACING);
                    return new Action()
                            .setSides(facing.getOpposite())
                            .setLookDirection(facing.getOpposite())
                            .setRequiresSupport();
                }
                // 天花板悬挂告示牌处理逻辑
                if (signBlock instanceof WallHangingSignBlock) {
                    Direction facing = ctx.requiredState.getValue(WallHangingSignBlock.FACING);
                    List<Direction> sides = new ArrayList<>();
                    if (facing.getAxis() == Direction.Axis.X) {
                        sides.add(Direction.NORTH);
                        sides.add(Direction.SOUTH);
                    } else if (facing.getAxis() == Direction.Axis.Z) {
                        sides.add(Direction.EAST);
                        sides.add(Direction.WEST);
                    }
                    // 墙挂悬挂式告示牌：朝向由服务端玩家的“头部朝向”决定（两侧都能挂时 N/S 或 E/W 都合法），
                    // 转头包和放置在同一 tick 发出时服务端头部朝向尚未更新 → 朝向反了。先转头，下一 tick 再放
                    return new Action()
                            .setSides(sides.toArray(new Direction[0]))
                            .setLookDirection(facing.getOpposite()).setRequiresSupport().setNeedWaitModifyLook();
                }
                if (signBlock instanceof CeilingHangingSignBlock) {
                    int rotation = ctx.requiredState.getValue(CeilingHangingSignBlock.ROTATION);
                    boolean attachFace = ctx.requiredState.getValue(CeilingHangingSignBlock.ATTACHED);
                    return new Action()
                            .setShift(attachFace)
                            .setSides(Direction.UP)
                            .setLookRotation(rotation)
                            .setRequiresSupport();
                }
                return null;
            }
            case BANNER -> {
                if (ctx.requiredState.getBlock() instanceof BannerBlock) {
                    int rotation = ctx.requiredState.getValue(BannerBlock.ROTATION);
                    return new Action()
                            .setSides(Direction.DOWN)
                            .setLookRotation(rotation)
                            .setRequiresSupport();
                } else if (ctx.requiredState.getBlock() instanceof WallBannerBlock) {
                    Direction facing = ctx.requiredState.getValue(WallBannerBlock.FACING);
                    return new Action()
                            .setSides(facing.getOpposite())
                            .setLookDirection(facing.getOpposite())
                            .setRequiresSupport();
                }
            }
            case SKULL -> {
                if (ctx.requiredState.getBlock() instanceof SkullBlock) {
                    int rotation = ctx.requiredState.getValue(SkullBlock.ROTATION);
                    return new Action()
                            .setSides(Direction.DOWN)
                            .setLookRotation(BlockUtils.getOppositeRotation(rotation))
                            .setRequiresSupport();
                } else if (ctx.requiredState.getBlock() instanceof WallSkullBlock) {
                    Direction facing = ctx.requiredState.getValue(WallSkullBlock.FACING);
                    return new Action()
                            .setSides(facing.getOpposite())
                            .setLookDirection(facing.getOpposite())
                            .setRequiresSupport();
                }
            }
            case CROPS -> {
                String blockKey = BlockUtils.getKeyString(ctx.requiredState.getBlock());
                if (blockKey.contains("pumpkin")) {
                    return new Action()
                            .setItem(Items.PUMPKIN_SEEDS)
                            .setRequiresSupport();
                }
                if (blockKey.contains("melon")) {
                    return new Action()
                            .setItem(Items.MELON_SEEDS)
                            .setRequiresSupport();
                }
                return new Action();
            }
            case SKIP -> {
                return null;
            }
            default -> {
                Block block = ctx.requiredState.getBlock();
                Identifier blockId1 = BlockUtils.getKey(block);
                if (blockId1.toString().contains("coral")) {
                    Identifier blockId2 = of(blockId1.toString().replace("dead_", ""));
                    boolean isBlock = blockId1.toString().contains("block");
                    List<Item> items = new ArrayList<>();
                    items.add(block.asItem());
                    if (Configs.Print.REPLACE_CORAL.getBooleanValue()) {
                        if (!blockId1.equals(blockId2)) {
                            items.add(BlockUtils.getBlock(blockId2).asItem());
                        }
                    }
                    Action action = new Action().setItems(items.toArray(new Item[0]));
                    if (!isBlock) {
                        boolean isWallFan = block instanceof BaseCoralWallFanBlock;
                        Direction facing = isWallFan ? ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite() : Direction.DOWN;
                        action.setSides(facing).setRequiresSupport();
                    }
                    return action;
                }
                Action action = new Action();
                if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
                    Direction side = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    AttachFace face = ctx.requiredState.getValue(BlockStateProperties.ATTACH_FACE);
                    // 简化方向判断逻辑 三元运算符 Direction.UP那报错？ 应该可以正常运行但是还是换了switch格式
                    //Direction sidePitch = face == AttachFace.CEILING ? Direction.UP : face == AttachFace.FLOOR ? Direction.DOWN : side;
                    Direction sidePitch = switch (face) {
                        case CEILING -> Direction.UP;
                        case FLOOR   -> Direction.DOWN;
                        default      -> side;
                    };
                    Direction lookYaw = face == AttachFace.WALL ? side.getOpposite() : side;
                    // 拉杆 / 按钮 / 砂轮的朝向由视角决定，且原版只有在支撑方块存在时才会选这个方向，
                    // 否则会退而求其次贴到别的面上（朝向就错了）：必须先等支撑方块放好，再点支撑方块
                    Direction support = switch (face) {
                        case CEILING -> Direction.UP;
                        case FLOOR -> Direction.DOWN;
                        default -> side.getOpposite();
                    };
                    return new Action().setSides(support).setLookDirection(lookYaw, sidePitch).setNeedWaitModifyLook().setRequiresSupport();
                }
                if (block instanceof HorizontalDirectionalBlock || block instanceof StonecutterBlock
                        // @formatter:off
                        || block instanceof
                            FlowerBedBlock
                        // @formatter:on
                ) {
                    Direction facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    if (block instanceof FenceGateBlock) // 栅栏门
                        facing = facing.getOpposite();
                    action.setLookDirection(facing.getOpposite());
                }
                if (block instanceof BaseEntityBlock) {
                    Direction facing;
                    if (ctx.requiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                        facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                        if (
                        block instanceof DecoratedPotBlock ||
                        block instanceof CampfireBlock) facing = facing.getOpposite();
                        action.setSides(facing).setLookDirection(facing.getOpposite());
                    }
                    if (ctx.requiredState.hasProperty(BlockStateProperties.FACING)) {
                        facing = ctx.requiredState.getValue(BlockStateProperties.FACING);
                        if (ctx.requiredState.getBlock() instanceof ShulkerBoxBlock) {
                            facing = facing.getOpposite();
                            action.setShift();
                        }
                        if (ctx.requiredState.getBlock() instanceof BarrelBlock)
                            action.setNeedWaitModifyLook();
                        action.setSides(facing).setLookDirection(facing.getOpposite());
                        if (block instanceof DispenserBlock)
                            action.setNeedWaitModifyLook();
                    }
                }
                //方块型珊瑚的替换
                if (Configs.Print.REPLACE_CORAL.getBooleanValue() && block.getDescriptionId().endsWith("_coral_block")) {
                    //例子：block.minecraft.dead_tube_coral
                    String type = block.getDescriptionId().replace("block.minecraft.dead_", "").replace("_coral_block", "");
                    switch (type) {
                        case "tube" -> action.setItem(Items.TUBE_CORAL_BLOCK);
                        case "brain" -> action.setItem(Items.BRAIN_CORAL_BLOCK);
                        case "bubble" -> action.setItem(Items.BUBBLE_CORAL_BLOCK);
                        case "fire" -> action.setItem(Items.FIRE_CORAL_BLOCK);
                        case "horn" -> action.setItem(Items.HORN_CORAL_BLOCK);
                    }
                    action.setRequiresSupport();
                }
                return action;
            }
        }
        return null;
    }

    /*** 状态错误：方块类型相同，但方块状态（如朝向、亮度等）不一致 ***/
    private @Nullable Action buildActionErrorBlockState(SchematicBlockContext ctx, ClassHook requiredType, AtomicReference<Boolean> skip) {
        boolean printBreakWrongStateBlock = Configs.Print.BREAK_WRONG_STATE_BLOCK.getBooleanValue();

        switch (requiredType) {
            case SLAB -> {
                if (ctx.requiredState.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                    Direction requiredHalf = ctx.currentState.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? Direction.DOWN : Direction.UP;
                    return new Action().setSides(requiredHalf);
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case SNOW -> {
                int layers = ctx.currentState.getValue(SnowLayerBlock.LAYERS);
                if (layers < ctx.requiredState.getValue(SnowLayerBlock.LAYERS)) {
                    Map<Direction, Vec3> sides = new java.util.EnumMap<>(Direction.class);
                    sides.put(Direction.UP, new Vec3(0, (layers / 8d) - 1, 0));
                    return new ClickAction().setItem(Items.SNOW).setSides(sides);
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case DOOR, TRAPDOOR -> {
                //判断门是不是铁制的，如果是就直接返回
                if (ctx.requiredState.is(Blocks.IRON_DOOR) || ctx.requiredState.is(Blocks.IRON_TRAPDOOR)) {
                    break;
                }
                if (ctx.requiredState.getValue(BlockStateProperties.OPEN) != ctx.currentState.getValue(BlockStateProperties.OPEN)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    boolean facingDiff = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                            != ctx.currentState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    boolean alignDiff = false;
                    if (ctx.requiredState.hasProperty(TrapDoorBlock.HALF)) {
                        alignDiff = ctx.requiredState.getValue(TrapDoorBlock.HALF)
                                != ctx.currentState.getValue(TrapDoorBlock.HALF);
                    }
                    if (ctx.requiredState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                        alignDiff |= ctx.requiredState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                != ctx.currentState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
                    }
                    if (ctx.requiredState.hasProperty(DoorBlock.HINGE)) {
                        alignDiff |= ctx.requiredState.getValue(DoorBlock.HINGE)
                                != ctx.currentState.getValue(DoorBlock.HINGE);
                    }
                    if (facingDiff || alignDiff) {
                        BreakUtils.INSTANCE.add(ctx);
                    }
                }
            }
            case FENCE_GATE -> {
                Direction facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                if (facing.getOpposite() == ctx.currentState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        || ctx.requiredState.getValue(BlockStateProperties.OPEN) != ctx.currentState.getValue(BlockStateProperties.OPEN)
                ) {
                    return new ClickAction().setSides(facing.getOpposite()).setLookDirection(facing);
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case LEVER -> {
                if (ctx.requiredState.getValue(LeverBlock.POWERED) != ctx.currentState.getValue(LeverBlock.POWERED)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case CANDLES -> {
                if (ctx.currentState.getValue(BlockStateProperties.CANDLES) < ctx.requiredState.getValue(BlockStateProperties.CANDLES)) {
                    return new ClickAction().setItem(ctx.requiredState.getBlock().asItem());
                }
                if (!ctx.currentState.getValue(CandleBlock.LIT) && ctx.requiredState.getValue(CandleBlock.LIT)) {
                    return new ClickAction().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
                }
                if (ctx.currentState.getValue(CandleBlock.LIT) && !ctx.requiredState.getValue(CandleBlock.LIT)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case PICKLES -> {
                if (ctx.currentState.getValue(SeaPickleBlock.PICKLES) < ctx.requiredState.getValue(SeaPickleBlock.PICKLES)) {
                    return new ClickAction().setItem(Items.SEA_PICKLE);
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case REPEATER -> {
                if (!ctx.requiredState.getValue(RepeaterBlock.DELAY).equals(ctx.currentState.getValue(RepeaterBlock.DELAY))) {
                    // 每次只点一下（延迟 +1），等服务端结果回到客户端后再点下一下：真服务器有延迟，连点会按旧状态多点
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock &&
                        ctx.requiredState.getValue(RepeaterBlock.POWERED) == ctx.currentState.getValue(RepeaterBlock.POWERED) &&
                        ctx.requiredState.getValue(RepeaterBlock.LOCKED) == ctx.currentState.getValue(RepeaterBlock.LOCKED)
                ) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case COMPARATOR -> {
                if (ctx.requiredState.getValue(ComparatorBlock.MODE) != ctx.currentState.getValue(ComparatorBlock.MODE)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    Direction requiredFacing = ctx.requiredState.getValue(ComparatorBlock.FACING);
                    Direction currentFacing = ctx.currentState.getValue(ComparatorBlock.FACING);
                    if (requiredFacing == currentFacing) {
                        SchematicBlockContext facingFirstBlockCtx = ctx.offset(requiredFacing);
                        // 检验输出信号
                        if (ctx.level.getSignal(ctx.blockPos, requiredFacing) != ctx.schematic.getSignal(ctx.blockPos, requiredFacing)) {
                            // 检验输入端是否为"能输出比较器信号方块"
                            if (facingFirstBlockCtx.requiredState.hasAnalogOutputSignal()) {
                                return null;
                            }
                            // 检验输入端非透明方块
                            if (facingFirstBlockCtx.requiredState.isRedstoneConductor(facingFirstBlockCtx.level, facingFirstBlockCtx.blockPos)) {
                                SchematicBlockContext facingSecondBlockCtx = facingFirstBlockCtx.offset(requiredFacing);
                                // 仿照原版检验物品展示框
                                BlockPos blockPos = facingSecondBlockCtx.blockPos;
                                List<ItemFrame> itemFrameList = facingSecondBlockCtx.schematic.getEntitiesOfClass(
                                        ItemFrame.class,
                                        new AABB(blockPos),
                                        (itemFrame) -> itemFrame.getDirection() == requiredFacing
                                );
                                // 隔非透明方块检验容器
                                if (facingSecondBlockCtx.requiredState.hasAnalogOutputSignal()) {
                                    return null;
                                }
                                // 隔非透明方块检验物品展示框
                                if (!itemFrameList.isEmpty()) {
                                    return null;
                                }
                            }
                        }
                    }
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case CROPS -> {
                if (!Configs.Print.BONEMEAL_CROPS.getBooleanValue()) {
                    return null;
                }
                Block currentBlock = ctx.currentState.getBlock();
                Block requiredBlock = ctx.requiredState.getBlock();
                if (currentBlock == requiredBlock && InventoryUtils.playerHasAccessToItem(mc.player, Items.BONE_MEAL)) {
                    int maxAge = requiredBlock instanceof BeetrootBlock ? 3 : 7;
                    int requiredAge = ctx.requiredState.getValue(requiredBlock instanceof BeetrootBlock ? BeetrootBlock.AGE : StemBlock.AGE);
                    int currentAge = ctx.currentState.getValue(requiredBlock instanceof BeetrootBlock ? BeetrootBlock.AGE : StemBlock.AGE);
                    if (requiredAge == maxAge && currentAge < maxAge) {
                        return new ClickAction().setItem(Items.BONE_MEAL);
                    }
                }
            }
            case NOTE_BLOCK -> {
                if (Configs.Print.NOTE_BLOCK_TUNING.getBooleanValue() && !Objects.equals(ctx.requiredState.getValue(NoteBlock.NOTE), ctx.currentState.getValue(NoteBlock.NOTE))) {
                    // 每次只点一下（音高 +1），等服务端结果回到客户端后再点下一下（分层模式下“状态有变化”的点击不算失败）
                    return new ClickAction();
                }
            }
            case CAMPFIRE -> {
                if (!ctx.requiredState.getValue(CampfireBlock.LIT) && ctx.currentState.getValue(CampfireBlock.LIT)) {
                    return new ClickAction().setItems(Reference.SHOVEL_ITEMS).setSides(Direction.UP);
                }
                if (ctx.requiredState.getValue(CampfireBlock.LIT) && !ctx.currentState.getValue(CampfireBlock.LIT)) {
                    return new ClickAction().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
                }
                if (printBreakWrongStateBlock && ctx.requiredState.getValue(CampfireBlock.FACING) != ctx.currentState.getValue(CampfireBlock.FACING)) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case END_PORTAL_FRAME -> {
                if (ctx.requiredState.getValue(EndPortalFrameBlock.HAS_EYE) && !ctx.currentState.getValue(EndPortalFrameBlock.HAS_EYE)) {
                    return new ClickAction().setItem(Items.ENDER_EYE);
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case FLOWERBED -> {
                if (ctx.currentState.getValue(BlockStateProperties.FLOWER_AMOUNT) <= ctx.requiredState.getValue(BlockStateProperties.FLOWER_AMOUNT)) {
                    return new ClickAction().setItem(ctx.requiredState.getBlock().asItem());
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case RED_STONE_WIRE -> {
                // 在Java版中，对于没有连接到任何红石元件的十字形的红石线，可以按使用键使其变为点状，从而不与任何方向连接，再按一次可以恢复。
                boolean allNoneRequired = ctx.requiredState.getValue(RedStoneWireBlock.NORTH) == RedstoneSide.NONE &&
                        ctx.requiredState.getValue(RedStoneWireBlock.SOUTH) == RedstoneSide.NONE &&
                        ctx.requiredState.getValue(RedStoneWireBlock.EAST) == RedstoneSide.NONE &&
                        ctx.requiredState.getValue(RedStoneWireBlock.WEST) == RedstoneSide.NONE;

                boolean allSideCurrent = ctx.currentState.getValue(RedStoneWireBlock.NORTH) == RedstoneSide.SIDE &&
                        ctx.currentState.getValue(RedStoneWireBlock.SOUTH) == RedstoneSide.SIDE &&
                        ctx.currentState.getValue(RedStoneWireBlock.EAST) == RedstoneSide.SIDE &&
                        ctx.currentState.getValue(RedStoneWireBlock.WEST) == RedstoneSide.SIDE;

                if (allNoneRequired && allSideCurrent) {
                    return new ClickAction().setItem(Items.AIR);
                }
            }
            case VINES, GLOW_LICHEN -> {
                // 只补“投影里有、世界里还没有”的面；世界里多出来的面只能拆掉重放
                boolean extraFace = false;
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN && ctx.requiredState.getBlock() == Blocks.VINE) continue;
                    Object want = BlockUtils.getPropertyByName(ctx.requiredState, direction.name());
                    Object have = BlockUtils.getPropertyByName(ctx.currentState, direction.name());
                    if (Boolean.TRUE.equals(have) && !Boolean.TRUE.equals(want)) extraFace = true;
                }
                if (!extraFace) {
                    for (Direction direction : Direction.values()) {
                        if (direction == Direction.DOWN && ctx.requiredState.getBlock() == Blocks.VINE) continue;
                        if (Boolean.TRUE.equals(BlockUtils.getPropertyByName(ctx.requiredState, direction.name()))
                                && !Boolean.TRUE.equals(BlockUtils.getPropertyByName(ctx.currentState, direction.name()))) {
                            return new Action().setSides(direction).setLookDirection(direction).setRequiresSupport();
                        }
                    }
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case CAULDRON -> {
                if (ctx.currentState.getValue(LayeredCauldronBlock.LEVEL) > ctx.requiredState.getValue(LayeredCauldronBlock.LEVEL)) {
                    if (InventoryUtils.playerHasAccessToItem(mc.player, Items.GLASS_BOTTLE)) {
                        return new ClickAction().setItem(Items.GLASS_BOTTLE);
                    } else {
                        MessageUtils.setOverlayMessage(I18n.BREWINGSTAND_LOWER.getName(getNameFromItem(Items.GLASS_BOTTLE)));
                    }
                }
                if (ctx.currentState.getValue(LayeredCauldronBlock.LEVEL) < ctx.requiredState.getValue(LayeredCauldronBlock.LEVEL))
                    if (InventoryUtils.playerHasAccessToItem(mc.player, Items.POTION)) {
                        return new ClickAction().setItem(Items.POTION);
                    } else {
                        MessageUtils.setOverlayMessage(I18n.BREWINGSTAND_RAISE.getName(getNameFromItem(Items.GLASS_BOTTLE)));
                    }
            }
            case DAYLIGHT_DETECTOR -> {
                if (ctx.currentState.getValue(DaylightDetectorBlock.INVERTED) != ctx.requiredState.getValue(DaylightDetectorBlock.INVERTED)) {
                    return new ClickAction();
                }
            }
            case FIRE -> {
                if (!ctx.requiredState.getValue(FireBlock.AGE).equals(ctx.currentState.getValue(FireBlock.AGE))) {
                    return null;
                }
                if (ctx.requiredState.getBlock() instanceof SoulFireBlock) return null;
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        return new Action().setSides(direction).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                    }
                }
                return new Action().setSides(Direction.DOWN).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
            }
            case COMPOSTER -> {
                if (!Configs.Print.FILL_COMPOSTER.getBooleanValue()) {
                    return null;
                }
                if (ctx.currentState.getValue(ComposterBlock.LEVEL) >= ctx.requiredState.getValue(ComposterBlock.LEVEL)) {
                    return null;
                }
                List<String> whitelist = Configs.Print.FILL_COMPOSTER_WHITELIST.getStrings();
                if (!whitelist.equals(compostWhitelistCache)) {
                    compostWhitelistCache = new ArrayList<>(whitelist);
                    List<Item> whitelistItems = new ArrayList<>();
                    //? if >=26.3 {
                    /*for (Item item : Reference.compostableItems()) {
                    *///?} else
                    for (Item item : Reference.COMPOSTABLE_ITEMS) {
                        for (String rule : whitelist) {
                            if (PinYinSearchUtils.matchName(rule, new ItemStack(item))) {
                                whitelistItems.add(item);
                                break;
                            }
                        }
                    }
                    whitelistItemsCache = whitelistItems.toArray(Item[]::new);
                }
                //? if >=26.3 {
                /*Item[] finalItems = whitelistItemsCache.length > 0 ? whitelistItemsCache : Reference.compostableItems();
                *///?} else
                Item[] finalItems = whitelistItemsCache.length > 0 ? whitelistItemsCache : Reference.COMPOSTABLE_ITEMS;
                if (finalItems.length > 0) {
                    return new ClickAction().setItems(finalItems);
                }
            }
            case STAIR -> {
                if (printBreakWrongStateBlock &&
                        (ctx.requiredState.getValue(StairBlock.FACING) != ctx.currentState.getValue(StairBlock.FACING) ||
                                ctx.requiredState.getValue(StairBlock.HALF) != ctx.currentState.getValue(StairBlock.HALF))) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case DEFAULT -> {
                Class<?>[] ignored = new Class<?>[]
                        {FenceBlock.class,
                                WallBlock.class,
                                IronBarsBlock.class,
                                PressurePlateBlock.class,
                                StainedGlassPaneBlock.class
                        };
                if (printBreakWrongStateBlock && !Arrays.asList(ignored).contains(ctx.requiredState.getBlock().getClass())) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
        }
        return null;
    }

    /*** 方块错误：方块类型完全不同，且不满足缺失/状态错误的条件 ***/
    private @Nullable Action buildActionErrorBlock(SchematicBlockContext ctx, ClassHook requiredType, AtomicReference<Boolean> skip) {
        switch (requiredType) {
            case FARMLAND -> {
                Block[] soilBlocks = new Block[]{Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.DIRT_PATH, Blocks.COARSE_DIRT};
                for (Block soilBlock : soilBlocks) {
                    if (ctx.currentState.getBlock().equals(soilBlock)) {
                        // 锄地 / 铲土径时原版要求不能点底面：必须点顶面（可选面放在 HashMap 里，顺序每次启动都可能不同）
                        return new ClickAction().setItems(Reference.HOE_ITEMS).setSides(Direction.UP);
                    }
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx);
            }
            case DIRT_PATH -> {
                Block[] soilBlocks = new Block[]{Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MYCELIUM, Blocks.PODZOL};
                for (Block soilBlock : soilBlocks) {
                    if (ctx.currentState.getBlock().equals(soilBlock)) {
                        return new ClickAction().setItems(Reference.SHOVEL_ITEMS).setSides(Direction.UP);
                    }
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx);
            }
            case FLOWER_POT -> {
                if (ctx.requiredState.getBlock() instanceof FlowerPotBlock potBlock) {
                    Block content = potBlock.getPotted();
                    if (content != Blocks.AIR) {
                        return new ClickAction().setItem(content.asItem());
                    }
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx);
            }
            case CAULDRON -> {
                if (Arrays.asList(requiredType.classes).contains(ctx.currentState.getBlock().getClass())) {
                    return null;
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case STRIP_LOG -> {
                Block stripped = STRIPPED_LOGS.get(ctx.currentState.getBlock());
                if (stripped != null && stripped == ctx.requiredState.getBlock()) {
                    return new ClickAction().setItems(Reference.AXE_ITEMS);
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx);
            }
            case SIGN -> {
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) {
                    boolean isLegitimateSign = ctx.currentState.getBlock() instanceof StandingSignBlock
                            || ctx.currentState.getBlock() instanceof WallSignBlock
                            || ctx.currentState.getBlock() instanceof WallHangingSignBlock
                            || ctx.currentState.getBlock() instanceof CeilingHangingSignBlock
                            ;
                    if (!isLegitimateSign) {
                        BreakUtils.INSTANCE.add(ctx);
                    }
                }
            }
            case CROPS -> {
                String requiredBlockKey = BlockUtils.getKeyString(ctx.requiredState.getBlock());
                String currentBlockKey = BlockUtils.getKeyString(ctx.currentState.getBlock());
                if (requiredBlockKey.contains("pumpkin_stem") && !currentBlockKey.contains("pumpkin_stem")) {
                    BreakUtils.INSTANCE.add(ctx);
                } else if (requiredBlockKey.contains("melon_stem") && !currentBlockKey.contains("melon_stem")) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            default -> {
                if (Configs.Print.REPLACE_CORAL.getBooleanValue() && ctx.requiredState.getBlock().getDescriptionId().contains("coral")) {
                    break;
                }
                if ((Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && !ctx.requiredState.isAir())
                        || (Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue() && ctx.requiredState.isAir())) {
                    if (BreakUtils.canBreakBlock(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx);
                }
            }
        }
        return null;
    }

    enum ClassHook {
        // 放置
        TORCH(
                BaseTorchBlock.class
        ),                                      // 火把
        SLAB(SlabBlock.class),                  // 台阶
        STAIR(StairBlock.class),                // 楼梯
        TRAPDOOR(TrapDoorBlock.class),          // 活板门
        STRIP_LOG(RotatedPillarBlock.class),    // 去皮原木
        ANVIL(AnvilBlock.class),                // 铁砧
        HOPPER(HopperBlock.class),              // 漏斗
        CAMPFIRE(CampfireBlock.class),          // 营火
        BED(BedBlock.class),                    // 床
        BELL(BellBlock.class),                  // 钟
        AMETHYST(AmethystClusterBlock.class),   // 紫水晶
        DOOR(DoorBlock.class),                  // 门
        COCOA(CocoaBlock.class),                // 可可豆
        CRAFTER(CrafterBlock.class),            // 合成器
        CHEST(ChestBlock.class),                // 箱子
        OBSERVER(ObserverBlock.class),          // 侦测器
        LADDER(LadderBlock.class),              // 梯子
        LANTERN(LanternBlock.class),            // 灯笼
        ROD(RodBlock.class),                    // 末地烛 避雷针
        TRIPWIRE_HOOK(TripWireHookBlock.class), // 绊线钩
        RAIL(BaseRailBlock.class),              // 铁轨
        PISTON(PistonBaseBlock.class),          // 活塞 （为了避免被破坏错误状态破坏）
        SIGN(
                StandingSignBlock.class,
                WallSignBlock.class
                , WallHangingSignBlock.class
                , CeilingHangingSignBlock.class
        ),
        BANNER(AbstractBannerBlock.class),      // 旗帜
        SKULL(AbstractSkullBlock.class),        // 头颅
        CROPS(AttachedStemBlock.class, StemBlock.class, CropBlock.class, BeetrootBlock.class),          // 农作物(茎)

        // 点击
        FLOWER_POT(FlowerPotBlock.class),               // 花盆
        BIG_DRIPLEAF_STEM(BigDripleafStemBlock.class),  // 大垂叶茎
        CAVE_VINES(CaveVinesBlock.class, CaveVinesPlantBlock.class),                // 洞穴藤蔓
        WEEPING_VINES(WeepingVinesBlock.class, WeepingVinesPlantBlock.class),       // 垂泪藤
        TWISTING_VINES(TwistingVinesBlock.class, TwistingVinesPlantBlock.class),    // 缠怨藤
        SNOW(SnowLayerBlock.class),                     // 雪
        CANDLES(CandleBlock.class),                     // 蜡烛
        REPEATER(RepeaterBlock.class),                  // 中继器
        COMPARATOR(ComparatorBlock.class),              // 比较器
        PICKLES(SeaPickleBlock.class),                  // 海泡菜
        NOTE_BLOCK(NoteBlock.class),                    // 音符盒
        END_PORTAL_FRAME(EndPortalFrameBlock.class),    // 末地传送门框架
        FLOWERBED(
                FlowerBedBlock.class
        ), // 花簇（ojng你看看你这是什么抽象命名）
        VINES(VineBlock.class),                         // 藤蔓
        GLOW_LICHEN(GlowLichenBlock.class),             // 发光地衣
        FIRE(FireBlock.class, SoulFireBlock.class),     // 火，灵魂火
        RED_STONE_WIRE(RedStoneWireBlock.class),        // 红石粉
        FENCE_GATE(FenceGateBlock.class),               // 栅栏门
        LEVER(LeverBlock.class),                        // 拉杆
        CAULDRON(CauldronBlock.class, LavaCauldronBlock.class, LayeredCauldronBlock.class), // 炼药锅
        DAYLIGHT_DETECTOR(DaylightDetectorBlock.class), // 阳光探测器
        COMPOSTER(ComposterBlock.class),                // 堆肥桶

        // 其他
        FARMLAND(FarmBlock.class),              // 耕地
        DIRT_PATH(DirtPathBlock.class),         // 土径
        NETHER_PORTAL(NetherPortalBlock.class), // 下界传送门
        SKIP(SkullBlock.class, LiquidBlock.class, BubbleColumnBlock.class, WaterlilyBlock.class), // 跳过
        DEFAULT; // 默认

        private final Class<?>[] classes;

        ClassHook(Class<?>... classes) {
            this.classes = classes;
        }
    }

    // 辅助方法：获取物品名称（版本适配）
    private static Component getNameFromItem(Item item) {
        //? if >=26.1 {
        /*return item.components().getOrDefault(net.minecraft.core.component.DataComponents.ITEM_NAME, net.minecraft.network.chat.CommonComponents.EMPTY);
        *///?} else
        return item.getName();
    }

    private static Identifier of(String string) {
        return Identifier.parse(string);
    }
}