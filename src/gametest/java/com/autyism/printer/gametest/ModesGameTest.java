package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.FillBlockModeType;
import com.autyism.printer.enums.SelectionType;
import com.autyism.printer.handler.ModuleManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 其他模式：填充（选区里的空气填成指定方块）、排流体（选区里的水 / 岩浆全部用方块填掉）。生存模式、材料在背包里。
 */
@SuppressWarnings("UnstableApiUsage")
public final class ModesGameTest implements FabricClientGameTest {
    private static final BlockPos SEL_MIN = new BlockPos(800, 64, 0);
    private static final BlockPos SEL_MAX = new BlockPos(806, 66, 6);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("modes")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.clearArena(sp, 790, -10, 816, 16, 75);
            sp.getServer().runCommand("tp @a 803.5 64 10.5 180 20");
            context.waitFor(c -> c.player != null && Math.abs(c.player.getZ() - 10.5) < 0.01, 200);
            context.waitTicks(20);
            context.runOnClient(c -> {
                var selectionManager = DataManager.getSelectionManager();
                if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) selectionManager.switchSelectionMode();
                AreaSelection selection = DataManager.getSimpleArea();
                Box box = selection.getSubRegionBox(selection.getName());
                if (box == null) box = selection.getSelectedSubRegionBox();
                box.setPos1(SEL_MIN);
                box.setPos2(SEL_MAX);
                Configs.Core.WORK_RANGE.setDoubleValue(12);
            });
            fill(context, sp);
            fluid(context, sp);
        } finally {
            context.runOnClient(c -> GT.disableAll());
        }
    }

    private static void giveAndPrepare(TestSingleplayerContext sp, net.minecraft.world.item.Item item, int stacks) {
        sp.getServer().runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            for (int i = 0; i < stacks; i++) player.getInventory().setItem(9 + i, new ItemStack(item, 64));
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static void fill(ClientGameTestContext context, TestSingleplayerContext sp) {
        sp.getServer().runOnServer(s -> {
            ServerLevel level = s.overworld();
            for (BlockPos p : BlockPos.betweenClosed(SEL_MIN, SEL_MAX)) level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            // 已有的方块不能被动
            level.setBlockAndUpdate(SEL_MIN.offset(2, 0, 2), Blocks.GOLD_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(SEL_MIN.offset(4, 1, 4), Blocks.DIAMOND_BLOCK.defaultBlockState());
        });
        giveAndPrepare(sp, Items.COBBLESTONE, 6);
        context.waitTicks(10);
        context.runOnClient(c -> {
            GT.disableAll();
            Configs.Fill.FILL_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
            Configs.Fill.FILL_BLOCK_MODE.setOptionListValue(FillBlockModeType.BLOCKLIST);
            Configs.Fill.FILL_BLOCK_LIST.setStrings(List.of("minecraft:cobblestone"));
            Configs.Fill.ENABLED.setBooleanValue(true);
            ModuleManager.FILL.resetScanState();
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
        });
        int total = 7 * 3 * 7;
        int ticks = GT.waitServer(context, () -> GT.countPlaced(sp, SEL_MIN, SEL_MAX) >= total, 1200, "[modes] fill did not finish");
        context.runOnClient(c -> GT.disableAll());
        String wrong = sp.getServer().computeOnServer(s -> {
            ServerLevel level = s.overworld();
            if (!level.getBlockState(SEL_MIN.offset(2, 0, 2)).is(Blocks.GOLD_BLOCK)) return "gold block replaced";
            if (!level.getBlockState(SEL_MIN.offset(4, 1, 4)).is(Blocks.DIAMOND_BLOCK)) return "diamond block replaced";
            for (BlockPos p : BlockPos.betweenClosed(SEL_MIN, SEL_MAX)) {
                var st = level.getBlockState(p);
                if (!st.is(Blocks.COBBLESTONE) && !st.is(Blocks.GOLD_BLOCK) && !st.is(Blocks.DIAMOND_BLOCK)) return p.toShortString() + " is " + st;
            }
            // 选区外不能放
            for (BlockPos p : BlockPos.betweenClosed(SEL_MIN.offset(-2, 0, -2), SEL_MAX.offset(2, 1, 2))) {
                boolean inside = p.getX() >= SEL_MIN.getX() && p.getX() <= SEL_MAX.getX() && p.getY() >= SEL_MIN.getY() && p.getY() <= SEL_MAX.getY()
                        && p.getZ() >= SEL_MIN.getZ() && p.getZ() <= SEL_MAX.getZ();
                if (!inside && level.getBlockState(p).is(Blocks.COBBLESTONE)) return "placed outside the selection at " + p.toShortString();
            }
            return null;
        });
        if (wrong != null) throw new AssertionError("[modes] fill: " + wrong);
        GT.log("[modes] fill OK: " + (total - 2) + " air blocks filled with cobblestone in " + ticks + " ticks, existing blocks kept, nothing outside");
    }

    private static void fluid(ClientGameTestContext context, TestSingleplayerContext sp) {
        sp.getServer().runOnServer(s -> {
            ServerLevel level = s.overworld();
            for (BlockPos p : BlockPos.betweenClosed(SEL_MIN, SEL_MAX)) level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
            // 选区里挖一个 5x2x5 的池子，放水；角落放岩浆
            for (BlockPos p : BlockPos.betweenClosed(SEL_MIN.offset(1, 1, 1), SEL_MAX.offset(-1, 0, -1))) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
            for (BlockPos p : BlockPos.betweenClosed(SEL_MIN.offset(1, 1, 1), SEL_MAX.offset(-1, -1, -1))) {
                level.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
            }
            level.setBlockAndUpdate(SEL_MIN.offset(1, 1, 1), Blocks.LAVA.defaultBlockState());
            level.setBlockAndUpdate(SEL_MIN.offset(5, 2, 5), Blocks.WATER.defaultBlockState()); // 上层一格水源，会流开
        });
        giveAndPrepare(sp, Items.COBBLESTONE, 4);
        context.waitTicks(40);
        int fluidsBefore = countFluids(sp);
        context.runOnClient(c -> {
            GT.disableAll();
            Configs.Fluid.FLUID_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
            Configs.Fluid.FILL_FLOWING_FLUID.setBooleanValue(true);
            Configs.Fluid.FLUID_REPLACE_BLOCK_LIST.setStrings(List.of("minecraft:cobblestone"));
            Configs.Fluid.FLUID_LIST.setStrings(List.of("minecraft:water", "minecraft:lava"));
            Configs.Fluid.ENABLED.setBooleanValue(true);
            ModuleManager.FLUID_REMOVAL.resetScanState();
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
        });
        int ticks = GT.waitServer(context, () -> countFluids(sp) == 0, 1200, "[modes] fluid removal did not finish");
        context.runOnClient(c -> GT.disableAll());
        GT.log("[modes] fluid removal OK: " + fluidsBefore + " fluid blocks (water, flowing water, lava) removed in " + ticks + " ticks");
    }

    private static int countFluids(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(s -> {
            int n = 0;
            for (BlockPos p : BlockPos.betweenClosed(SEL_MIN, SEL_MAX)) if (!s.overworld().getFluidState(p).isEmpty()) n++;
            return n;
        });
    }
}
