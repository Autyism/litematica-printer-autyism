package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.MiningFilterType;
import com.autyism.printer.enums.SelectionType;
import com.autyism.printer.handler.ModuleManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * 大范围秒破深板岩（服务端也装了 ALE 时，见 compat/AleFastBreak；单人游戏的内置服务端也算）。
 * 下界合金镐效率 V、急迫 II：深板岩一 tick 只有 54%，原版一次只能挖一格。带着 ALE 的请求，打印机像挖石头一样一 tick 挖一大片；
 * 挖完客户端和服务端一致（没有幽灵方块），镐的磨损和挖掉的格数一样。要用 -PwithAle=<ALE 的 jar> 跑。
 */
@SuppressWarnings("UnstableApiUsage")
public final class DeepslateGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("deepslate")) return;
        //? if >=1.21.11 {
        if (!FabricLoader.getInstance().isModLoaded("autyism-le")) {
            GT.log("[deepslate] ALE is not loaded, skipping (run with -PwithAle=<ALE jar>)");
            return;
        }
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            run(context, sp);
        } finally {
            context.runOnClient(client -> GT.disableAll());
        }
        //?}
    }

    //? if >=1.21.11 {
    // 人站在上方的玻璃上（和耐久测试一样），同一 tick 里够得着一大片
    private static final BlockPos MIN = new BlockPos(154, 64, -3);
    private static final BlockPos MAX = new BlockPos(168, 66, 11);

    private static void run(ClientGameTestContext context, TestSingleplayerContext sp) {
        context.runOnClient(client -> GT.disableAll());
        GT.clearArena(sp, 150, -7, 172, 15, 74);
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (BlockPos pos : BlockPos.betweenClosed(MIN, MAX)) level.setBlockAndUpdate(pos, Blocks.DEEPSLATE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(161, 70, 4), Blocks.GLASS.defaultBlockState());
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            player.getInventory().setItem(7, new ItemStack(Items.DIRT, 16));
            player.getInventory().setSelectedSlot(0);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
            player.inventoryMenu.sendAllDataToRemote();
        });
        sp.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5}]");
        sp.getServer().runCommand("effect give @a minecraft:haste infinite 1 true");
        sp.getServer().runCommand("tp @a 161.5 71 4.5 0 90");
        context.waitFor(client -> client.player != null && client.level.getBlockState(MIN).is(Blocks.DEEPSLATE)
                && client.player.getInventory().getItem(0).is(Items.NETHERITE_PICKAXE)
                && client.player.hasEffect(net.minecraft.world.effect.MobEffects.HASTE)
                && Math.abs(client.player.getZ() - 4.5) < 0.01 && client.player.onGround(), 300);
        context.waitFor(client -> com.autyism.printer.utils.ServerEffects.digSpeed() >= 1, 100);
        int total = deepslate(sp);
        float progress = context.computeOnClient(c -> c.level.getBlockState(MIN).getDestroyProgress(c.player, c.level, MIN));
        boolean fast = context.computeOnClient(c -> com.autyism.printer.compat.AleFastBreak.canBreakNow(c.level.getBlockState(MIN), progress));
        GT.log("[deepslate] " + total + " deepslate, progress per tick " + progress + ", ALE fast break available: " + fast);
        if (!fast) throw new AssertionError("[deepslate] ALE fast break is not available in single player");
        context.runOnClient(client -> {
            com.autyism.printer.utils.ToolSwitchUtils.resetHalt();
            DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
            var selectionManager = DataManager.getSelectionManager();
            if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) selectionManager.switchSelectionMode();
            AreaSelection selection = DataManager.getSimpleArea();
            Box box = selection.getSubRegionBox(selection.getName());
            if (box == null) box = selection.getSelectedSubRegionBox();
            box.setPos1(MIN);
            box.setPos2(MAX);
            Configs.Break.AUTO_TOOL_SWITCH.setBooleanValue(true);
            Configs.Break.TOOL_DURABILITY_PROTECT.setBooleanValue(true);
            Configs.Break.TOOL_DURABILITY_THRESHOLD.setIntegerValue(10);
            Configs.Break.BREAK_INSTANT_MINE.setBooleanValue(true);
            Configs.Core.WORK_RANGE.setDoubleValue(16);
            Configs.Core.AUTO_RAISE_REACH.setBooleanValue(true);
            Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(0);
            Configs.Break.BREAK_INTERVAL.setIntegerValue(0);
            Configs.Break.BREAK_LIMITER.setOptionListValue(MiningFilterType.CUSTOM);
            Configs.Break.BREAK_LIMIT.setOptionListValue(UsageRestriction.ListType.NONE);
            Configs.Mine.EXCAVATE_LIMITER.setOptionListValue(MiningFilterType.CUSTOM);
            Configs.Mine.EXCAVATE_LIMIT.setOptionListValue(UsageRestriction.ListType.NONE);
            Configs.Mine.MINE_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
            Configs.Mine.ENABLED.setBooleanValue(true);
            ModuleManager.MINE.resetScanState();
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
        });
        int[] mostInOneTick = {0};
        int[] previous = {total};
        int ticks = GT.waitServer(context, () -> {
            int left = deepslate(sp);
            mostInOneTick[0] = Math.max(mostInOneTick[0], previous[0] - left);
            previous[0] = left;
            return left == 0;
        }, 1200, "[deepslate] the area was not mined");
        context.waitTicks(40);
        context.runOnClient(client -> GT.disableAll());
        List<String> differences = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(MIN, MAX)) {
            BlockPos p = pos.immutable();
            var server = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(p));
            var client = context.computeOnClient(c -> c.level.getBlockState(p));
            if (!server.equals(client)) differences.add(p.toShortString() + " client=" + client + " server=" + server);
        }
        int wear = sp.getServer().computeOnServer(server -> {
            var inv = server.getPlayerList().getPlayers().getFirst().getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(Items.NETHERITE_PICKAXE)) return inv.getItem(i).getDamageValue();
            }
            return -1;
        });
        GT.log("[deepslate] mined " + total + " in " + ticks + " ticks, most in one tick: " + mostInOneTick[0] + ", pickaxe wear " + wear
                + ", client/server differences: " + differences.size());
        if (!differences.isEmpty()) throw new AssertionError("[deepslate] client and server differ: " + differences.getFirst());
        if (mostInOneTick[0] < 5) throw new AssertionError("[deepslate] at most " + mostInOneTick[0] + " deepslate in one tick: not mined at once");
        if (wear != total) throw new AssertionError("[deepslate] pickaxe wear " + wear + " for " + total + " blocks");
        sp.getServer().runCommand("effect clear @a");
        GT.log("[deepslate] OK: the area was mined like instant blocks, no ghost blocks");
    }

    private static int deepslate(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos pos : BlockPos.betweenClosed(MIN, MAX)) if (server.overworld().getBlockState(pos).is(Blocks.DEEPSLATE)) n++;
            return n;
        });
    }
    //?}
}
