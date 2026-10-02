package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 需求 13：超大打印范围 + 按层从下往上打印。
 * 玩家站着不动，远处有一座 8x8x6 的“雕塑”，打印范围设得很大，必须全部打完，并且每一层都在下一层之后才开始。
 */
@SuppressWarnings("UnstableApiUsage")
public final class LargeRangeGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(130, 64, 30);
    private static final BlockPos MAX = new BlockPos(137, 69, 37);

    private static BlockState expected(BlockPos pos) {
        // 中空的柱状雕塑：外壳石头，内部空气
        boolean shell = pos.getX() == MIN.getX() || pos.getX() == MAX.getX() || pos.getZ() == MIN.getZ() || pos.getZ() == MAX.getZ();
        return shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    private static String dbg(com.autyism.printer.handler.GuiBlockInfo i) {
        return i == null ? "null" : i.pos.toShortString() + " cur=" + i.currentState + " req=" + i.requiredState
                + " interacted=" + i.interacted + " exec=" + i.execute + " inSel=" + i.posInSelectionRange;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("range")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.clearArena(sp, 96, -4, 142, 42, 72);
            sp.getServer().runCommand("tp @a 100.5 64 0.5 0 0");
            context.waitFor(client -> client.player != null && Math.abs(client.player.getX() - 100.5) < 0.01, 200);
            context.waitTicks(20);
            // 先在世界里搭好雕塑，截取成真实的 .litematic 投影，再清空、放置投影
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) server.overworld().setBlockAndUpdate(p, expected(p));
            });
            context.waitFor(client -> client.level.getBlockState(MIN).is(Blocks.STONE) && client.level.getBlockState(MAX).is(Blocks.STONE), 200);
            GT.captureAndPlace(context, sp, MIN, MAX, MIN, "ale_test_sculpture");
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) server.overworld().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            });
            GT.waitSchematicBlock(context, MIN, Blocks.STONE);
            sp.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                for (int i = 0; i < 6; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
                player.inventoryMenu.sendAllDataToRemote();
            });
            // 服务器没有反作弊：用属性把交互距离放大（原版服务端也会校验这个属性）
            sp.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 64");
            sp.getServer().runCommand("tp @a 100.5 64 0.5 0 0");
            context.waitFor(client -> client.player != null && client.player.getInventory().getItem(0).is(Items.STONE)
                    && Math.abs(client.player.getX() - 100.5) < 0.01, 200);
            context.waitTicks(5);

            long total = BlockPos.betweenClosedStream(MIN, MAX).filter(p -> !expected(p).isAir()).count();
            int[] layerDone = new int[MAX.getY() - MIN.getY() + 1];
            context.runOnClient(client -> {
                Configs.Core.WORK_RANGE.setDoubleValue(Double.parseDouble(System.getProperty("ale.range", "100")));
                Configs.Print.LAYERED_MODE.setBooleanValue(Boolean.parseBoolean(System.getProperty("ale.layered", "true")));
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(4);
                GT.enablePrint();
            });
            int[] tick = {0};
            int[] lastCount = {0};
            boolean[] orderViolated = {false};
            int ticks = GT.waitServer(context, () -> {
                tick[0]++;
                int[] perLayer = sp.getServer().computeOnServer(server -> {
                    int[] c = new int[MAX.getY() - MIN.getY() + 1];
                    for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) {
                        if (!server.overworld().getBlockState(p).isAir()) c[p.getY() - MIN.getY()]++;
                    }
                    return c;
                });
                int placed = 0;
                for (int i = 0; i < perLayer.length; i++) {
                    placed += perLayer[i];
                    // 上一层没打完时，这一层不能有方块
                    if (i > 0 && perLayer[i] > 0 && perLayer[i - 1] < 28) orderViolated[0] = true;
                }
                if (tick[0] % 40 == 0 || placed != lastCount[0]) {
                    if (tick[0] % 40 == 0) {
                        GT.log("[range] t" + tick[0] + " placed=" + placed + "/" + total + " layers=" + java.util.Arrays.toString(perLayer));

                    }
                    lastCount[0] = placed;
                }
                return placed >= total;
            }, 1200, "[range] large-range print did not finish");
            context.runOnClient(client -> GT.disableAll());
            var wrong = GT.mismatches(sp, MIN, MAX, LargeRangeGameTest::expected);
            if (!wrong.isEmpty()) throw new AssertionError("[range] wrong blocks: " + wrong.subList(0, Math.min(10, wrong.size())));
            boolean layered = Boolean.parseBoolean(System.getProperty("ale.layered", "true"));
            if (layered && orderViolated[0]) throw new AssertionError("[range] an upper layer got blocks before the layer below was complete");
            GT.log("[range] OK: " + total + " blocks printed ~30 blocks away in " + ticks + " ticks, " + (layered ? "strictly bottom-up" : "(layered mode off)"));
        } finally {
            context.runOnClient(client -> {
                GT.disableAll();
                Configs.Print.LAYERED_MODE.setBooleanValue(false);
            });
        }
    }
}
