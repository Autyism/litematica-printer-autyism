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
 * 生存：要放的 8 种方块都在背包里，快捷栏是空的。打印机要把几种一起换进快捷栏（见 Print.prefetchForDeferred），
 * 不是一种一种地换、每换一种都等一次服务端：Litematica 默认能换进方块的是 1~5 格，8 种分 2 批换完（算上余量最多 3 批）。
 * 放好的方块一块都不能错（-Plag=… 时也一样）。
 */
@SuppressWarnings("UnstableApiUsage")
public final class PrefetchGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(300, 64, 10);
    private static final BlockPos MAX = new BlockPos(307, 64, 11);
    private static final Block[] MATERIALS = {Blocks.STONE, Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.DIRT,
            Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.BRICKS};

    private static BlockState expected(BlockPos pos) {
        return MATERIALS[pos.getX() - MIN.getX()].defaultBlockState();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("prefetch")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.clearArena(sp, MIN.getX() - 4, MIN.getZ() - 4, MAX.getX() + 4, MAX.getZ() + 4, 70);
            // 站在两排方块前面正中间：最远的一格也在生存模式的距离（4.5 格）以内
            sp.getServer().runCommand("tp @a 303.5 64 9.5 0 30");
            context.waitFor(client -> client.player != null && Math.abs(client.player.getZ() - 9.5) < 0.01, 200);
            context.waitTicks(20);
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) server.overworld().setBlockAndUpdate(p, expected(p));
            });
            context.waitFor(client -> client.level.getBlockState(MAX).is(expected(MAX).getBlock()), 200);
            GT.captureAndPlace(context, sp, MIN, MAX, MIN, "ale_test_prefetch");
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) server.overworld().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                // 材料只放在背包主区，快捷栏全空
                for (int i = 0; i < MATERIALS.length; i++) {
                    player.getInventory().setItem(9 + i, new ItemStack(MATERIALS[i].asItem(), 64));
                }
                //? if <1.21 {
                /*player.getInventory().selected = 0;
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(0));
                *///?} else {
                player.getInventory().setSelectedSlot(0);
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
                //?}
                player.inventoryMenu.sendAllDataToRemote();
            });
            GT.waitSchematicBlock(context, MIN, expected(MIN).getBlock());
            context.waitFor(client -> client.player.getInventory().getItem(9 + MATERIALS.length - 1).getCount() == 64, 200);
            context.waitTicks(10);
            int total = (MAX.getX() - MIN.getX() + 1) * (MAX.getY() - MIN.getY() + 1) * (MAX.getZ() - MIN.getZ() + 1);
            context.runOnClient(client -> {
                fi.dy.masa.litematica.util.InventoryUtils.setPickBlockableSlots("1,2,3,4,5");
                UseRecord.resetClicks();
                GT.enablePrint();
            });
            int[] tick = {0};
            GT.waitServer(context, () -> {
                tick[0]++;
                return GT.countPlaced(sp, MIN, MAX) >= total;
            }, 1200, "[prefetch] print did not finish");
            context.waitTicks(20);
            context.runOnClient(client -> GT.disableAll());
            int[] clicks = context.computeOnClient(c -> UseRecord.clickTicks());
            long batches = Arrays.stream(clicks).distinct().count();
            var wrong = GT.mismatches(sp, MIN, MAX, PrefetchGameTest::expected);
            GT.log("[prefetch] " + total + " blocks in " + tick[0] + " ticks, " + clicks.length + " swaps in " + batches
                    + " ticks " + Arrays.toString(clicks) + ", wrong=" + wrong.size() + " lag=" + System.getProperty("ale.lag", ""));
            if (!wrong.isEmpty()) throw new AssertionError("[prefetch] " + wrong.size() + " wrong blocks, e.g. " + wrong.subList(0, Math.min(5, wrong.size())));
            if (clicks.length < MATERIALS.length) throw new AssertionError("[prefetch] only " + clicks.length + " swaps for " + MATERIALS.length + " materials");
            if (batches > 3) throw new AssertionError("[prefetch] items were swapped in one by one: " + batches + " ticks with swaps " + Arrays.toString(clicks));
            GT.log("[prefetch] OK");
        } finally {
            GT.removeAllPlacements(context);
            context.runOnClient(client -> GT.disableAll());
        }
    }
}
