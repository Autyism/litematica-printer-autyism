package com.autyism.printer.printer;

import com.autyism.printer.config.Configs;
import com.autyism.printer.utils.BlockUtils;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 先调整完再放下一个。
 * <p>
 * 有些方块放下以后还要在同一格点几下才和投影一样：中继器延迟、比较器模式、音符盒音高、阳光探测器反相、
 * 拉杆、木门 / 活板门 / 栅栏门开关、蜡烛 / 海泡菜 / 雪层 / 花簇的数量（拿着同样的物品点）、花盆里的花、耕地 / 土径、去皮原木……
 * 以前打印机按扫描顺序往后走：放下这种方块后接着把这一轮剩下的方块都放完，下一轮扫描回到这一格才点一下
 * （刚放下时服务端还没确认、这一格还有冷却，扫到了也要等下一轮），点几下就要几轮 —— 看起来就是“先把方块都放完，再回去一个个调”。
 * 现在打印机停在这一格，每点一下都等服务端确认，调整完再放下一个方块（Print 里的 adjust 相关代码）。
 * <p>
 * 这里只列打印机会在原地点击调整的方块（和 PlacementGuide 里“状态错误 / 方块错误”时点击的分支对应），其他方块不受影响。
 * 在同一格再放一次的（双层台阶的第二块、藤蔓 / 发光地衣多贴一面）不算：它们不影响机器怎么动，双层台阶又很常见，
 * 一块一块等服务器确认会让大建筑慢很多，照旧的顺序处理。
 */
public final class AdjustFirst {
    private AdjustFirst() {
    }

    private static Map<Block, Block> stripped;

    private static Map<Block, Block> strippedLogs() {
        if (stripped == null) stripped = BlockUtils.getStrippedBlocksMap();
        return stripped;
    }

    /**
     * 刚放下投影里的 required（手上拿的是 placedWith）之后，还要在这一格接着点 / 再放才能和投影一样。
     *
     * @param protocolSetsState 这次放置的状态由轻松放置协议直接决定（单人游戏里的 V3 协议：延迟、音高、比较器模式、反相、开关都按投影放）
     */
    public static boolean expectsFollowUp(BlockState required, @Nullable Item placedWith, boolean protocolSetsState) {
        Block b = required.getBlock();
        if (b instanceof RepeaterBlock) return !protocolSetsState && required.getValue(RepeaterBlock.DELAY) != 1;
        if (b instanceof ComparatorBlock) return !protocolSetsState && required.getValue(ComparatorBlock.MODE) != ComparatorMode.COMPARE;
        if (b instanceof NoteBlock) return !protocolSetsState && Configs.Print.NOTE_BLOCK_TUNING.getBooleanValue() && required.getValue(NoteBlock.NOTE) != 0;
        if (b instanceof DaylightDetectorBlock) return !protocolSetsState && required.getValue(DaylightDetectorBlock.INVERTED);
        if (b instanceof LeverBlock) return required.getValue(LeverBlock.POWERED);
        if (b instanceof DoorBlock || b instanceof TrapDoorBlock) {
            return !protocolSetsState && !isIron(required) && required.getValue(BlockStateProperties.OPEN);
        }
        if (b instanceof FenceGateBlock) return !protocolSetsState && required.getValue(FenceGateBlock.OPEN);
        if (b instanceof CandleBlock) return required.getValue(CandleBlock.CANDLES) > 1 || required.getValue(CandleBlock.LIT);
        if (b instanceof SeaPickleBlock) return required.getValue(SeaPickleBlock.PICKLES) > 1;
        if (b instanceof SnowLayerBlock) return required.getValue(SnowLayerBlock.LAYERS) > 1;
        if (b instanceof CampfireBlock) return !required.getValue(CampfireBlock.LIT) && !required.getValue(CampfireBlock.WATERLOGGED);
        if (b instanceof EndPortalFrameBlock) return required.getValue(EndPortalFrameBlock.HAS_EYE);
        if (isFlowerBed(b)) return required.getValue(BlockStateProperties.FLOWER_AMOUNT) > 1;
        // 花盆里的花：先放空花盆再把花放进去
        if (b instanceof FlowerPotBlock && b != Blocks.FLOWER_POT) return true;
        // 耕地 / 土径：先放泥土再用锄头 / 锹
        if (b instanceof FarmBlock || b instanceof DirtPathBlock) return true;
        if (b instanceof ComposterBlock) return Configs.Print.FILL_COMPOSTER.getBooleanValue() && required.getValue(ComposterBlock.LEVEL) > 0;
        // 红石粉点：单独放下的红石粉是十字形，点一下变成点
        if (b instanceof RedStoneWireBlock) return allSides(required, RedstoneSide.NONE);
        if (Configs.Print.BONEMEAL_CROPS.getBooleanValue() && cropAge(required) != null) {
            IntegerProperty age = cropAge(required);
            return required.getValue(age) == maxCropAge(b);
        }
        // 去皮原木：手上只有没去皮的原木时，先放原木再用斧头去皮
        if (placedWith != null && placedWith != b.asItem()) {
            Block placed = Block.byItem(placedWith);
            return placed != Blocks.AIR && strippedLogs().get(placed) == b;
        }
        return false;
    }

    /**
     * 世界里这一格（current）能不能由打印机在原地点几下 / 再放几次变成投影里的 required。
     * 只看方块本身（不看材料、够不够得着），没有任何副作用，扫描整层时用。和 PlacementGuide 里对应的分支保持一致。
     */
    public static boolean canAdjustInPlace(BlockState required, BlockState current) {
        Block b = required.getBlock();
        Block c = current.getBlock();
        if (b != c) {
            // 方块不同，只有“转换”：泥土锄成耕地、铲成土径，空花盆放花，原木去皮
            if (b instanceof FarmBlock) {
                return c == Blocks.GRASS_BLOCK || c == Blocks.DIRT || c == Blocks.DIRT_PATH || c == Blocks.COARSE_DIRT;
            }
            if (b instanceof DirtPathBlock) {
                return c == Blocks.GRASS_BLOCK || c == Blocks.DIRT || c == Blocks.COARSE_DIRT || c == Blocks.ROOTED_DIRT
                        || c == Blocks.MYCELIUM || c == Blocks.PODZOL;
            }
            if (b instanceof FlowerPotBlock && b != Blocks.FLOWER_POT) return c == Blocks.FLOWER_POT;
            return strippedLogs().get(c) == b;
        }
        if (b instanceof RepeaterBlock) return !required.getValue(RepeaterBlock.DELAY).equals(current.getValue(RepeaterBlock.DELAY));
        if (b instanceof ComparatorBlock) return required.getValue(ComparatorBlock.MODE) != current.getValue(ComparatorBlock.MODE);
        if (b instanceof NoteBlock) {
            return Configs.Print.NOTE_BLOCK_TUNING.getBooleanValue() && !required.getValue(NoteBlock.NOTE).equals(current.getValue(NoteBlock.NOTE));
        }
        if (b instanceof DaylightDetectorBlock) return required.getValue(DaylightDetectorBlock.INVERTED) != current.getValue(DaylightDetectorBlock.INVERTED);
        if (b instanceof LeverBlock) return required.getValue(LeverBlock.POWERED) != current.getValue(LeverBlock.POWERED);
        if (b instanceof DoorBlock || b instanceof TrapDoorBlock) {
            return !isIron(required) && required.getValue(BlockStateProperties.OPEN) != current.getValue(BlockStateProperties.OPEN);
        }
        if (b instanceof FenceGateBlock) {
            return required.getValue(FenceGateBlock.OPEN) != current.getValue(FenceGateBlock.OPEN)
                    || required.getValue(FenceGateBlock.FACING).getOpposite() == current.getValue(FenceGateBlock.FACING);
        }
        if (b instanceof CandleBlock) {
            return current.getValue(CandleBlock.CANDLES) < required.getValue(CandleBlock.CANDLES)
                    || current.getValue(CandleBlock.LIT) != required.getValue(CandleBlock.LIT);
        }
        if (b instanceof SeaPickleBlock) return current.getValue(SeaPickleBlock.PICKLES) < required.getValue(SeaPickleBlock.PICKLES);
        if (b instanceof SnowLayerBlock) return current.getValue(SnowLayerBlock.LAYERS) < required.getValue(SnowLayerBlock.LAYERS);
        if (b instanceof SlabBlock) return required.getValue(SlabBlock.TYPE) == SlabType.DOUBLE && current.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
        if (b instanceof CampfireBlock) return required.getValue(CampfireBlock.LIT) != current.getValue(CampfireBlock.LIT);
        if (b instanceof EndPortalFrameBlock) return required.getValue(EndPortalFrameBlock.HAS_EYE) && !current.getValue(EndPortalFrameBlock.HAS_EYE);
        if (isFlowerBed(b)) return current.getValue(BlockStateProperties.FLOWER_AMOUNT) < required.getValue(BlockStateProperties.FLOWER_AMOUNT);
        if (b instanceof VineBlock || b instanceof GlowLichenBlock) {
            boolean missing = false;
            for (Direction d : Direction.values()) {
                if (d == Direction.DOWN && b == Blocks.VINE) continue;
                Object want = BlockUtils.getPropertyByName(required, d.name());
                Object have = BlockUtils.getPropertyByName(current, d.name());
                if (Boolean.TRUE.equals(have) && !Boolean.TRUE.equals(want)) return false; // 多出来的面只能拆掉重放
                if (Boolean.TRUE.equals(want) && !Boolean.TRUE.equals(have)) missing = true;
            }
            return missing;
        }
        if (b instanceof ComposterBlock) {
            return Configs.Print.FILL_COMPOSTER.getBooleanValue() && current.getValue(ComposterBlock.LEVEL) < required.getValue(ComposterBlock.LEVEL);
        }
        if (b instanceof LayeredCauldronBlock) return !required.getValue(LayeredCauldronBlock.LEVEL).equals(current.getValue(LayeredCauldronBlock.LEVEL));
        if (b instanceof RedStoneWireBlock) return allSides(required, RedstoneSide.NONE) && allSides(current, RedstoneSide.SIDE);
        IntegerProperty age = cropAge(required);
        if (age != null && Configs.Print.BONEMEAL_CROPS.getBooleanValue()) {
            return required.getValue(age) == maxCropAge(b) && current.getValue(age) < required.getValue(age);
        }
        return false;
    }

    private static boolean isIron(BlockState state) {
        return state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR);
    }

    private static boolean isFlowerBed(Block b) {
        //? if <1.21.5 {
        /*return b instanceof PinkPetalsBlock;
        *///?} else
        return b instanceof FlowerBedBlock;
    }

    private static boolean allSides(BlockState wire, RedstoneSide side) {
        return wire.getValue(RedStoneWireBlock.NORTH) == side && wire.getValue(RedStoneWireBlock.SOUTH) == side
                && wire.getValue(RedStoneWireBlock.EAST) == side && wire.getValue(RedStoneWireBlock.WEST) == side;
    }

    /** 和 PlacementGuide 催熟农作物的分支一样：甜菜根看 0~3，其他农作物 / 茎看 0~7 */
    @Nullable
    private static IntegerProperty cropAge(BlockState state) {
        Block b = state.getBlock();
        if (!(b instanceof CropBlock) && !(b instanceof StemBlock)) return null;
        IntegerProperty age = b instanceof BeetrootBlock ? BeetrootBlock.AGE : StemBlock.AGE;
        return state.hasProperty(age) ? age : null;
    }

    private static int maxCropAge(Block b) {
        return b instanceof BeetrootBlock ? 3 : 7;
    }
}
