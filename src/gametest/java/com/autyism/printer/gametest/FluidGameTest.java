package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用桶打印流体：两格深的泳池（内部格子四周全是水，只能靠“无限水”补满）、岩浆池、装满水 / 岩浆的炼药锅。
 */
@SuppressWarnings("UnstableApiUsage")
public final class FluidGameTest implements FabricClientGameTest {
    private static final BlockPos BASE = new BlockPos(700, 64, 700);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("fluid")) return;
        Map<BlockPos, BlockState> states = new HashMap<>();
        // 泳池：外墙 8x8，内部 6x6，深 2
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            boolean wall = x == 0 || z == 0 || x == 7 || z == 7;
            states.put(BASE.offset(x, 0, z), Blocks.STONE.defaultBlockState()); // 池底
            for (int y = 1; y <= 2; y++) {
                states.put(BASE.offset(x, y, z), wall ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
        }
        // 岩浆池 3x3 内部 1 格
        BlockPos lava = BASE.offset(12, 0, 0);
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
            states.put(lava.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            states.put(lava.offset(x, 1, z), x == 1 && z == 1 ? Blocks.LAVA.defaultBlockState() : Blocks.STONE.defaultBlockState());
        }
        // 炼药锅
        BlockPos c1 = BASE.offset(12, 1, 5), c2 = BASE.offset(14, 1, 5);
        states.put(c1.below(), Blocks.STONE.defaultBlockState());
        states.put(c2.below(), Blocks.STONE.defaultBlockState());
        states.put(c1, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        states.put(c2, Blocks.LAVA_CAULDRON.defaultBlockState());
        // 别墅里那种被台阶完全包住的水（下面铁块、四周和上面都是台阶）：只能在放上面的台阶之前从上方放
        for (int i = 0; i < 3; i++) {
            BlockPos w = BASE.offset(9 + i * 3, 1, 9);
            states.put(w.below(), Blocks.IRON_BLOCK.defaultBlockState());
            states.put(w, Blocks.WATER.defaultBlockState());
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                states.put(w.relative(d), Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
                states.put(w.relative(d).below(), Blocks.STONE.defaultBlockState());
            }
            states.put(w.above(), Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        }
        BlockPos min = BASE.offset(-1, -1, -1), max = BASE.offset(17, 4, 11);
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.removeAllPlacements(context); // 防止别的测试留下的投影放置影响工作区域
            sp.getServer().runCommand("gamemode creative @a");
            GT.clearArena(sp, min.getX() - 2, min.getZ() - 2, max.getX() + 2, max.getZ() + 2, 75);
            sp.getServer().runCommand("tp @a 708.5 71 704.5");
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - 708.5) < 0.01, 200);
            context.runOnClient(c -> {
                c.player.getAbilities().flying = true;
                c.player.onUpdateAbilities();
            });
            context.waitTicks(40);
            GT.setSchematicStable(context, min, max, p -> states.getOrDefault(p, p.getY() == BASE.getY() - 1
                        ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState()));
            context.runOnClient(c -> {
                Configs.Core.WORK_RANGE.setDoubleValue(24);
                Configs.Print.LAYERED_MODE.setBooleanValue(true);
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(8);
                GT.enablePrint();
            });
            int done = 0;
            for (int t = 0; t < 1200; t += 20) {
                context.waitTicks(20);
                done = sp.getServer().computeOnServer(s -> {
                    int n = 0;
                    for (var e : states.entrySet()) if (s.overworld().getBlockState(e.getKey()).equals(e.getValue())) n++;
                    return n;
                });
                if (done == states.size()) break;
            }
            context.runOnClient(c -> GT.disableAll());
            List<String> wrong = sp.getServer().computeOnServer(s -> {
                List<String> out = new java.util.ArrayList<>();
                for (var e : states.entrySet()) {
                    BlockState have = s.overworld().getBlockState(e.getKey());
                    if (!have.equals(e.getValue())) out.add(e.getKey().toShortString() + " want=" + e.getValue() + " have=" + have);
                }
                return out;
            });
            for (String w : wrong) GT.log("[fluid] WRONG " + w);
            GT.log("[fluid] RESULT " + (states.size() - wrong.size()) + "/" + states.size() + " correct");
            if (!wrong.isEmpty()) throw new AssertionError("[fluid] " + wrong.size() + " wrong, first: " + wrong.get(0));
        } finally {
            context.runOnClient(c -> GT.disableAll());
            TestSchematicRegion.clear();
        }
    }
}
