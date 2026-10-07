package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.HighlightStyleType;
import com.autyism.printer.printer.MissingMaterialTracker;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 画面相关的功能：方块高亮（轮廓 / 填充 / 透视）、打印机 HUD、调试信息、缺失材料 HUD。
 * 只截图并检查状态（-Pgt=visuals），截图用来对比各个版本画出来的是不是一样。
 */
@SuppressWarnings("UnstableApiUsage")
public final class VisualsGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(300, 64, 6);
    private static final BlockPos MAX = new BlockPos(306, 65, 12);
    private static final BlockPos GOLD = new BlockPos(303, 65, 9);

    private static BlockState expected(BlockPos pos) {
        if (pos.equals(GOLD)) return Blocks.GOLD_BLOCK.defaultBlockState();
        return ((pos.getX() + pos.getZ()) & 1) == 0 ? Blocks.STONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!System.getProperty("ale.gt", "").contains("visuals")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            sp.getServer().runCommand("time set noon");
            GT.clearArena(sp, 290, -6, 316, 22, 75);
            sp.getServer().runCommand("tp @a 303.5 64 1.5 0 25");
            context.waitFor(client -> client.player != null && Math.abs(client.player.getZ() - 1.5) < 0.01, 200);
            context.waitTicks(20);
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) server.overworld().setBlockAndUpdate(p, expected(p));
            });
            context.waitFor(client -> client.level.getBlockState(MAX).is(expected(MAX).getBlock()), 200);
            GT.captureAndPlace(context, sp, MIN, MAX, MIN, "ale_test_visuals");
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) server.overworld().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                player.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
                player.getInventory().setItem(10, new ItemStack(Items.OAK_PLANKS, 64));
                player.inventoryMenu.sendAllDataToRemote();
            });
            GT.waitSchematicBlock(context, MIN, expected(MIN).getBlock());
            context.waitTicks(10);

            context.runOnClient(client -> {
                Configs.Core.WORK_RANGE.setDoubleValue(16);
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(1);
                Configs.Highlight.HIGHLIGHT_ENABLED.setBooleanValue(true);
                Configs.Highlight.HIGHLIGHT_STYLE.setOptionListValue(HighlightStyleType.BOTH);
                Configs.Highlight.HIGHLIGHT_FADE_DURATION.setIntegerValue(40);
                Configs.Highlight.HIGHLIGHT_THROUGH_WALLS.setBooleanValue(false);
                Configs.Core.RENDER_HUD.setBooleanValue(true);
                Configs.Core.DEBUG_OUTPUT.setBooleanValue(true);
                Configs.Core.MISSING_MATERIAL_HUD.setBooleanValue(true);
                GT.enablePrint();
            });
            context.waitTicks(25);
            GT.log("[visuals] SCREENSHOT " + GT.screenshot(context, "visuals-1-both").toAbsolutePath());

            context.runOnClient(client -> {
                Configs.Highlight.HIGHLIGHT_STYLE.setOptionListValue(HighlightStyleType.FILLED);
                Configs.Highlight.HIGHLIGHT_THROUGH_WALLS.setBooleanValue(true);
            });
            context.waitTicks(10);
            GT.log("[visuals] SCREENSHOT " + GT.screenshot(context, "visuals-2-filled-through-walls").toAbsolutePath());

            int total = (MAX.getX() - MIN.getX() + 1) * (MAX.getY() - MIN.getY() + 1) * (MAX.getZ() - MIN.getZ() + 1);
            GT.waitServer(context, () -> GT.countPlaced(sp, MIN, MAX) >= total - 1, 1200, "[visuals] print did not finish");
            context.waitTicks(20);
            boolean goldMissing = context.computeOnClient(client -> MissingMaterialTracker.getInstance().getMissing().stream()
                    .anyMatch(e -> e.item == Items.GOLD_BLOCK));
            context.runOnClient(client -> {
                Configs.Highlight.HIGHLIGHT_STYLE.setOptionListValue(HighlightStyleType.OUTLINE);
                Configs.Highlight.HIGHLIGHT_THROUGH_WALLS.setBooleanValue(false);
            });
            context.waitTicks(5);
            GT.log("[visuals] SCREENSHOT " + GT.screenshot(context, "visuals-3-missing-material").toAbsolutePath());
            if (!goldMissing) throw new AssertionError("[visuals] the gold block was not reported as a missing material");
            var wrong = GT.mismatches(sp, MIN, MAX, p -> p.equals(GOLD) ? Blocks.AIR.defaultBlockState() : expected(p));
            if (!wrong.isEmpty()) throw new AssertionError("[visuals] wrong blocks: " + wrong);
            GT.log("[visuals] OK: " + (total - 1) + " blocks printed with highlights, HUD and debug output on; gold block listed as missing");
        } finally {
            GT.removeAllPlacements(context);
            context.runOnClient(client -> {
                GT.disableAll();
                Configs.Highlight.HIGHLIGHT_ENABLED.setBooleanValue(false);
                Configs.Core.RENDER_HUD.setBooleanValue(false);
                Configs.Core.DEBUG_OUTPUT.setBooleanValue(false);
            });
        }
    }
}
