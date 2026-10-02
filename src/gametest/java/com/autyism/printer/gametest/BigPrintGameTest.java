package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * 需求 13 压力测试：一次要打的方块很多（16x16x7 实心，1792 个，4 种材料，生存模式）。
 * 玩家站着不动，范围 32；不能中途“罢工”（长时间没有进展），分层模式下必须严格从下往上。
 */
@SuppressWarnings("UnstableApiUsage")
public final class BigPrintGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(200, 64, 10);
    private static final BlockPos MAX = new BlockPos(215, 70, 25);
    private static final Block[] MATERIALS = {Blocks.STONE, Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.DIRT};

    private static BlockState expected(BlockPos pos) {
        return MATERIALS[Math.floorMod(pos.getX() + pos.getZ() * 3 + pos.getY(), MATERIALS.length)].defaultBlockState();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("big")) return;
        boolean layered = Boolean.parseBoolean(System.getProperty("ale.layered", "true"));
        double range = Double.parseDouble(System.getProperty("ale.range", "32"));
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.clearArena(sp, 190, 0, 226, 36, 80);
            sp.getServer().runCommand("tp @a 207.5 64 4.5 0 0");
            context.waitFor(client -> client.player != null && Math.abs(client.player.getX() - 207.5) < 0.01, 200);
            context.waitTicks(20);
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) server.overworld().setBlockAndUpdate(p, expected(p));
            });
            context.waitFor(client -> client.level.getBlockState(MAX).is(expected(MAX).getBlock()), 200);
            GT.captureAndPlace(context, sp, MIN, MAX, MIN, "ale_test_big");
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) server.overworld().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                for (int i = 0; i < 28; i++) player.getInventory().setItem(i + 8, new ItemStack(MATERIALS[i % 4].asItem(), 64));
                player.inventoryMenu.sendAllDataToRemote();
            });
            GT.waitSchematicBlock(context, MIN, expected(MIN).getBlock());
            sp.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 64");
            context.waitTicks(10);

            int total = (MAX.getX() - MIN.getX() + 1) * (MAX.getY() - MIN.getY() + 1) * (MAX.getZ() - MIN.getZ() + 1);
            int layerSize = (MAX.getX() - MIN.getX() + 1) * (MAX.getZ() - MIN.getZ() + 1);
            context.runOnClient(client -> {
                Configs.Core.WORK_RANGE.setDoubleValue(range);
                Configs.Print.LAYERED_MODE.setBooleanValue(layered);
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(Integer.parseInt(System.getProperty("ale.bpt", "8")));
                Configs.Core.RENDER_HUD.setBooleanValue(true);
                GT.enablePrint();
            });
            int layersBefore = context.computeOnClient(c -> com.autyism.printer.handler.ModuleManager.PRINT.getLayersCompleted());
            double[] lastHud = {0};
            boolean[] hudJumped = {false};
            int[] tick = {0}, lastPlaced = {0}, lastProgressTick = {0}, maxStall = {0};
            boolean[] orderViolated = {false};
            long start = System.nanoTime();
            GT.waitServer(context, () -> {
                tick[0]++;
                int[] perLayer = sp.getServer().computeOnServer(server -> {
                    int[] c = new int[MAX.getY() - MIN.getY() + 1];
                    for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) {
                        if (!server.overworld().getBlockState(p).isAir()) c[p.getY() - MIN.getY()]++;
                    }
                    return c;
                });
                int placed = Arrays.stream(perLayer).sum();
                for (int i = 1; i < perLayer.length; i++) {
                    if (perLayer[i] > 0 && perLayer[i - 1] < layerSize) orderViolated[0] = true;
                }
                if (placed != lastPlaced[0]) {
                    maxStall[0] = Math.max(maxStall[0], tick[0] - lastProgressTick[0]);
                    lastProgressTick[0] = tick[0];
                    lastPlaced[0] = placed;
                }
                double hud = context.computeOnClient(c -> com.autyism.printer.handler.ModuleManager.GUI.getPrintProgress().getProgress());
                if (hud + 0.02 < lastHud[0]) {
                    hudJumped[0] = true;
                    GT.log("[big] HUD progress went backwards: " + lastHud[0] + " -> " + hud);
                }
                lastHud[0] = Math.max(lastHud[0], hud);
                if (tick[0] == 150) context.takeScreenshot("ale-print-hud");
                if (tick[0] % 100 == 0) {
                    GT.log("[big] t" + tick[0] + " placed=" + placed + "/" + total + " layers=" + Arrays.toString(perLayer)
                            + " fps=" + context.computeOnClient(c -> c.getFps()));
                }
                if (tick[0] - lastProgressTick[0] > 200) {
                    throw new AssertionError("[big] printer stalled for 200 ticks at " + placed + "/" + total + " layers=" + Arrays.toString(perLayer));
                }
                return placed >= total;
            }, 6000, "[big] print did not finish");
            double secs = (System.nanoTime() - start) / 1e9;
            context.waitTicks(40);
            double finalHud = context.computeOnClient(c -> com.autyism.printer.handler.ModuleManager.GUI.getPrintProgress().getProgress());
            int layersDone = context.computeOnClient(c -> com.autyism.printer.handler.ModuleManager.PRINT.getLayersCompleted()) - layersBefore;
            GT.log("[big] HUD final progress=" + finalHud + " layer messages=" + layersDone + " jumped=" + hudJumped[0]);
            if (hudJumped[0]) throw new AssertionError("[big] HUD progress jumped backwards");
            if (finalHud < 0.999) throw new AssertionError("[big] HUD did not reach 100%: " + finalHud);
            int layerCount = MAX.getY() - MIN.getY() + 1;
            if (layered && (layersDone < layerCount - 1 || layersDone > layerCount)) throw new AssertionError("[big] expected one completion message per layer (" + layerCount + "), got " + layersDone);
            context.runOnClient(client -> GT.disableAll());
            var wrong = GT.mismatches(sp, MIN, MAX, BigPrintGameTest::expected);
            if (!wrong.isEmpty()) throw new AssertionError("[big] " + wrong.size() + " wrong blocks, e.g. " + wrong.subList(0, Math.min(5, wrong.size())));
            if (layered && orderViolated[0]) throw new AssertionError("[big] layer order violated");
            GT.log("[big] OK: " + total + " blocks in " + tick[0] + " ticks (" + String.format("%.1f", secs) + "s), longest stall "
                    + maxStall[0] + " ticks, layered=" + layered + ", range=" + range);
        } finally {
            GT.removeAllPlacements(context);
            context.runOnClient(client -> {
                GT.disableAll();
                Configs.Print.LAYERED_MODE.setBooleanValue(false);
            });
        }
    }
}
