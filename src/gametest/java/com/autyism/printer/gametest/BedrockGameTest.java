package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.handler.ModuleManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.util.LayerMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 需求 2：破基岩模式下，框选一个范围的基岩要能批量破除（配合 bedrockminer 模组）。
 */
@SuppressWarnings("UnstableApiUsage")
public final class BedrockGameTest implements FabricClientGameTest {
    private static final List<BlockPos> BEDROCK = List.of(
            new BlockPos(80, 64, 0), new BlockPos(82, 64, 0), new BlockPos(84, 64, 0),
            new BlockPos(86, 64, 0), new BlockPos(88, 64, 0));
    /** 加入破基岩方块列表的末地传送门框架 */
    private static final BlockPos FRAME = new BlockPos(84, 64, -3);
    private static final BlockPos SEL_MIN = new BlockPos(79, 64, -3);
    private static final BlockPos SEL_MAX = new BlockPos(89, 64, 1);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("bedrock")) return;
        if (!FabricLoader.getInstance().isModLoaded("bedrockminer")) {
            throw new AssertionError("[bedrock] bedrockminer is not loaded in the test environment");
        }
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            runWith(context, sp, com.autyism.printer.enums.BedrockBackend.BUNNYI);
            if (FabricLoader.getInstance().isModLoaded("bedrock-miner")) {
                runWith(context, sp, com.autyism.printer.enums.BedrockBackend.LXYAN);
            } else {
                GT.log("[bedrock] lxyan2333 bedrock-miner not loaded, skipping that backend (run with -PwithLxyan)");
            }
        } finally {
            context.runOnClient(client -> GT.disableAll());
        }
    }

    private static void runWith(ClientGameTestContext context, TestSingleplayerContext sp, com.autyism.printer.enums.BedrockBackend backend) {
        context.runOnClient(client -> GT.disableAll());
        context.waitTicks(5);
        GT.clearArena(sp, 76, -6, 92, 8, 72);
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (int x = 76; x <= 92; x++)
                for (int z = -6; z <= 8; z++) level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
            for (BlockPos p : BEDROCK) level.setBlockAndUpdate(p, Blocks.BEDROCK.defaultBlockState());
            level.setBlockAndUpdate(FRAME, Blocks.END_PORTAL_FRAME.defaultBlockState());
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.PISTON, 32));
            player.getInventory().setItem(1, new ItemStack(Items.REDSTONE_TORCH, 32));
            player.getInventory().setItem(2, new ItemStack(Items.SLIME_BLOCK, 32));
            player.getInventory().setSelectedSlot(4);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(4));
            player.inventoryMenu.sendAllDataToRemote();
        });
        sp.getServer().runCommand("item replace entity @a hotbar.3 with minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5}]");
        sp.getServer().runCommand("effect give @a minecraft:haste infinite 1 true");
        sp.getServer().runCommand("tp @a 84.5 64 -1.5 0 60");
        context.waitFor(client -> client.player != null && client.level.getBlockState(BEDROCK.getFirst()).is(Blocks.BEDROCK)
                && client.player.getInventory().getItem(3).is(Items.NETHERITE_PICKAXE)
                && client.player.getInventory().getItem(0).is(Items.PISTON)
                && Math.abs(client.player.getZ() + 1.5) < 0.01, 200);
        context.waitTicks(5);
        context.runOnClient(client -> {
            // 渲染层设成与基岩不同的单层：破基岩模式默认应忽略渲染层
            var range = DataManager.getRenderLayerRange();
            range.setLayerMode(LayerMode.SINGLE_LAYER);
            range.setAxis(net.minecraft.core.Direction.Axis.Y);
            range.setLayerSingle(70);
            var selectionManager = DataManager.getSelectionManager();
            if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) selectionManager.switchSelectionMode();
            AreaSelection selection = DataManager.getSimpleArea();
            Box box = selection.getSubRegionBox(selection.getName());
            if (box == null) box = selection.getSelectedSubRegionBox();
            box.setPos1(SEL_MIN);
            box.setPos2(SEL_MAX);
            Configs.Core.WORK_RANGE.setDoubleValue(0);
            Configs.Bedrock.BACKEND.setOptionListValue(backend);
            Configs.Bedrock.IGNORE_RENDER_LAYER.setBooleanValue(true);
            Configs.Bedrock.BLOCK_LIST.setStrings(java.util.List.of("minecraft:bedrock", "minecraft:end_portal_frame"));
            Configs.Bedrock.ENABLED.setBooleanValue(true);
            ModuleManager.BEDROCK.resetScanState();
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
        });
        int[] t = {0};
        int ticks = GT.waitServer(context, () -> {
            int left = remaining(sp);
            if (t[0]++ % 100 == 0) GT.log("[bedrock/" + backend + "] t" + t[0] + " remaining bedrock=" + left);
            return left == 0;
        }, 2400, "[bedrock/" + backend + "] selected bedrock was not all broken");
        context.runOnClient(client -> {
            GT.disableAll();
            DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        });
        GT.log("[bedrock/" + backend + "] OK: " + BEDROCK.size() + " bedrock + 1 end portal frame (block list) broken in " + ticks + " ticks (render layer y=70 ignored)");
    }

    private static int remaining(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos p : BEDROCK) if (server.overworld().getBlockState(p).is(Blocks.BEDROCK)) n++;
            if (server.overworld().getBlockState(FRAME).is(Blocks.END_PORTAL_FRAME)) n++;
            return n;
        });
    }
}
