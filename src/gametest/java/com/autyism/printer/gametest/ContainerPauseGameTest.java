package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 需求 1：打印机开着时去箱子拿材料，打印不准。
 * 用 mixin 把 OpenScreen 包延迟 12 tick（模拟服务器延迟），打印过程中右键箱子、在箱子界面停留后关闭。
 * 关闭保护时记录错放数量（复现问题），开启保护时必须与投影完全一致、且箱子打开期间不放置任何方块。
 */
@SuppressWarnings("UnstableApiUsage")
public final class ContainerPauseGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(40, 64, 0);
    private static final BlockPos MAX = new BlockPos(46, 64, 2);
    private static final BlockPos CHEST = new BlockPos(43, 64, 5);

    // 12 种材料，多于快捷栏格数，迫使打印机持续从背包交换物品到手上
    private static final net.minecraft.world.level.block.Block[] MATERIALS = {
            Blocks.STONE, Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.DIRT, Blocks.BRICKS, Blocks.GRANITE,
            Blocks.DIORITE, Blocks.ANDESITE, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.END_STONE, Blocks.MOSSY_COBBLESTONE};

    private static BlockState expected(BlockPos pos) {
        int i = Math.floorMod((pos.getX() - MIN.getX()) * 3 + pos.getZ() - MIN.getZ(), MATERIALS.length);
        return MATERIALS[i].defaultBlockState();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("container")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            int fixed = runScenario(context, sp, true);
            if (fixed != 0) throw new AssertionError("[container] guard ON still produced " + fixed + " wrong blocks");
            GT.log("[container] guard ON: OK (all blocks correct, nothing placed while the chest was open)");
            int bugMismatches = runScenario(context, sp, false);
            GT.log("[container] guard OFF: " + bugMismatches + " wrong/missing blocks (bug reproduction)");
        } finally {
            TestHooks.delayOpenScreenTicks = 0;
            context.runOnClient(client -> GT.disableAll());
        }
    }

    private int runScenario(ClientGameTestContext context, TestSingleplayerContext sp, boolean guard) {
        context.runOnClient(client -> {
            GT.disableAll();
            if (client.screen != null) client.player.closeContainer();
        });
        GT.clearArena(sp, 36, -3, 50, 8, 70);
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
            if (level.getBlockEntity(CHEST) instanceof ChestBlockEntity chest) {
                chest.setItem(0, new ItemStack(Items.STONE, 64));
                chest.setItem(1, new ItemStack(Items.OAK_PLANKS, 64));
            }
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            for (int i = 0; i < MATERIALS.length; i++) {
                player.getInventory().setItem(9 + i, new ItemStack(MATERIALS[i].asItem(), 64));
            }
            player.getInventory().setSelectedSlot(0);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
            player.inventoryMenu.sendAllDataToRemote();
        });
        sp.getServer().runCommand("tp @a 43.5 64 3.5 0 0");
        context.waitFor(client -> client.player != null && client.level.getBlockState(CHEST).is(Blocks.CHEST)
                && client.player.getInventory().getItem(9).is(Items.STONE)
                && client.player.getInventory().getItem(0).isEmpty() && client.player.getInventory().getItem(1).isEmpty()
                && Math.abs(client.player.getZ() - 3.5) < 0.01, 200);
        context.waitTicks(5);

        TestHooks.delayOpenScreenTicks = 12;
        context.runOnClient(client -> {
            GT.setSchematic(MIN, MAX, ContainerPauseGameTest::expected);
            Configs.Core.PAUSE_ON_CONTAINER.setBooleanValue(guard);
            Configs.Placement.PLACE_INTERVAL.setIntegerValue(2);
            Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(1);
            GT.enablePrint();
        });
        context.waitTicks(4);
        // 玩家右键箱子（服务端立即打开，客户端 12 tick 后才知道）
        context.runOnClient(client -> client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(CHEST).add(0, 0.5, 0), Direction.UP, CHEST, false)));
        context.waitFor(client -> client.screen instanceof AbstractContainerScreen<?>, 100);
        int atOpen = GT.countPlaced(sp, MIN, MAX);
        context.waitTicks(15);
        int beforeClose = GT.countPlaced(sp, MIN, MAX);
        if (guard && beforeClose != atOpen) {
            throw new AssertionError("[container] printer placed blocks while the chest screen was open: " + atOpen + " -> " + beforeClose);
        }
        context.runOnClient(client -> client.player.closeContainer());
        TestHooks.delayOpenScreenTicks = 0;

        int total = (MAX.getX() - MIN.getX() + 1) * (MAX.getZ() - MIN.getZ() + 1);
        try {
            GT.waitServer(context, () -> GT.countPlaced(sp, MIN, MAX) >= total, 600, "print did not finish");
        } catch (AssertionError e) {
            if (guard) throw e;
        }
        context.waitTicks(10);
        context.runOnClient(client -> GT.disableAll());
        List<String> wrong = GT.mismatches(sp, MIN, MAX, ContainerPauseGameTest::expected);
        if (!wrong.isEmpty()) GT.log("[container] guard " + guard + " mismatches: " + wrong);
        return wrong.size();
    }
}
