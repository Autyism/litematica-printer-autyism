package com.autyism.printer.printer;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * 只对“要分好几步才能做好”的少数方块：现在离投影里的状态还差几步。
 * <ul>
 *   <li>点一下前进一格：音符盒（音高 0~24 循环）、中继器（延迟 1~4 循环）；</li>
 *   <li>往同一格再放一次加一个：蜡烛、海泡菜、海龟蛋、雪层、花簇 / 野花、落叶堆、双层台阶、
 *       藤蔓 / 发光地衣 / 幽匿脉络这类可以贴多个面的方块；</li>
 *   <li>一次一次加进去：堆肥桶、炼药锅水位、重生锚充能。</li>
 * </ul>
 * 分层打印里“这一格尝试太多次就先跳过”的判断，只有对这些方块才把“离目标更近了”算作有进展；
 * 其他方块照原来的规则（每次尝试都算一次）。
 *
 * @return 还差几步；-1 = 不是这类方块，或者已经没法一步步走到（例如雪层比投影还厚）
 */
public final class StepProgress {
    private StepProgress() {
    }

    public static int remaining(BlockState required, BlockState current) {
        if (required.getBlock() != current.getBlock()) return -1;
        if (required.hasProperty(BlockStateProperties.NOTE)) {
            return Math.floorMod(required.getValue(BlockStateProperties.NOTE) - current.getValue(BlockStateProperties.NOTE), 25);
        }
        if (required.hasProperty(BlockStateProperties.DELAY)) {
            return Math.floorMod(required.getValue(BlockStateProperties.DELAY) - current.getValue(BlockStateProperties.DELAY), 4);
        }
        for (IntegerProperty p : new IntegerProperty[]{BlockStateProperties.CANDLES, BlockStateProperties.PICKLES, BlockStateProperties.EGGS,
                //? if <1.21.5 {
                /*BlockStateProperties.LAYERS, BlockStateProperties.FLOWER_AMOUNT,
                *///?} else
                BlockStateProperties.LAYERS, BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT,
                BlockStateProperties.LEVEL_COMPOSTER, BlockStateProperties.LEVEL_CAULDRON, BlockStateProperties.RESPAWN_ANCHOR_CHARGES}) {
            if (required.hasProperty(p)) {
                int diff = required.getValue(p) - current.getValue(p);
                return diff >= 0 ? diff : -1;
            }
        }
        if (required.getBlock() instanceof SlabBlock && required.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
            return current.getValue(SlabBlock.TYPE) == SlabType.DOUBLE ? 0 : 1;
        }
        if (required.getBlock() instanceof MultifaceBlock) {
            int missing = 0;
            for (Direction d : Direction.values()) {
                var face = MultifaceBlock.getFaceProperty(d);
                if (!required.hasProperty(face)) continue;
                boolean want = required.getValue(face), have = current.getValue(face);
                if (have && !want) return -1;
                if (want && !have) missing++;
            }
            return missing;
        }
        return -1;
    }
}
