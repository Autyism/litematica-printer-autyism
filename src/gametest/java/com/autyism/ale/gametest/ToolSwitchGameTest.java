package com.autyism.ale.gametest;

import com.autyism.ale.config.Configs;
import com.autyism.ale.enums.MiningFilterType;
import com.autyism.ale.enums.SelectionType;
import com.autyism.ale.handler.ModuleManager;
import com.autyism.ale.utils.ToolSwitchUtils;
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

/**
 * 需求 14：挖掘模式自动切换工具不依赖 Tweakeroo；
 * 要用的工具耐久快耗尽时：不改用别的工具，直接停止挖掘，手上换成不会坏的物品（方块），并提示。
 */
@SuppressWarnings("UnstableApiUsage")
public final class ToolSwitchGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(60, 64, 0);
    private static final BlockPos MAX = new BlockPos(62, 64, 2);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("tool")) return;
        if (FabricLoader.getInstance().isModLoaded("tweakeroo")) {
            throw new AssertionError("[tool] Tweakeroo must NOT be loaded for this test");
        }
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            // 场景 1：快捷栏有满耐久石镐、背包有铁铲 → 挖石头自动换上石镐，全部挖完
            setup(context, sp, false, true);
            GT.waitServer(context, () -> remaining(sp) == 0, 600, "[tool] auto switch: area was not mined");
            int stoneDmg = damage(sp, Items.STONE_PICKAXE);
            if (stoneDmg <= 0) throw new AssertionError("[tool] auto switch did not use the stone pickaxe");
            GT.log("[tool] auto switch OK: stone pickaxe chosen automatically (damage " + stoneDmg + ")");

            // 场景 2：最佳工具（下界合金镐）快坏了，另有石镐 → 不能改用石镐，必须停止挖掘、手上换成方块、提示
            long warnBefore = ToolSwitchUtils.getLastWarnTime();
            int badBefore = setup(context, sp, true, true);
            context.waitTicks(80);
            int left = remaining(sp);
            if (left != 9) throw new AssertionError("[tool] mining did not stop: " + (9 - left) + " blocks were mined");
            if (damage(sp, Items.NETHERITE_PICKAXE) != badBefore) throw new AssertionError("[tool] nearly broken pickaxe was used");
            if (damage(sp, Items.STONE_PICKAXE) != 0) throw new AssertionError("[tool] fell back to another tool (stone pickaxe was used)");
            boolean handIsBlock = context.computeOnClient(client -> client.player.getMainHandItem().is(Items.DIRT));
            if (!handIsBlock) throw new AssertionError("[tool] hand was not switched to a non-damageable block");
            if (ToolSwitchUtils.getLastWarnTime() <= warnBefore) throw new AssertionError("[tool] no on-screen warning was shown");
            context.takeScreenshot("ale-tool-durability-warning");
            GT.log("[tool] protection OK: mining stopped, no tool used, hand switched to dirt, warning shown");

            // 修好工具后关闭再开启打印机 → 继续挖掘
            sp.getServer().runOnServer(server -> {
                var inv = server.getPlayerList().getPlayers().getFirst().getInventory();
                for (int i = 0; i < 36; i++) if (inv.getItem(i).is(Items.NETHERITE_PICKAXE)) inv.getItem(i).setDamageValue(0);
                server.getPlayerList().getPlayers().getFirst().inventoryMenu.sendAllDataToRemote();
            });
            context.waitTicks(5);
            context.runOnClient(client -> Configs.Core.WORK_SWITCH.setBooleanValue(false));
            context.waitTicks(2);
            context.runOnClient(client -> Configs.Core.WORK_SWITCH.setBooleanValue(true));
            GT.waitServer(context, () -> remaining(sp) == 0, 400, "[tool] mining did not resume after repairing");
            GT.log("[tool] resume OK: after repair + printer toggle, mining finished");
        } finally {
            context.runOnClient(client -> GT.disableAll());
        }
    }

    private static int setup(ClientGameTestContext context, TestSingleplayerContext sp, boolean badPick, boolean goodPick) {
        context.runOnClient(client -> GT.disableAll());
        GT.clearArena(sp, 56, -3, 66, 6, 70);
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (BlockPos pos : BlockPos.betweenClosed(MIN, MAX)) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            if (badPick) {
                ItemStack bad = new ItemStack(Items.NETHERITE_PICKAXE);
                bad.setDamageValue(bad.getMaxDamage() - 5);
                player.getInventory().setItem(0, bad);
            }
            if (goodPick) player.getInventory().setItem(4, new ItemStack(Items.STONE_PICKAXE));
            player.getInventory().setItem(20, new ItemStack(Items.IRON_SHOVEL));
            player.getInventory().setItem(7, new ItemStack(Items.DIRT, 16));
            player.getInventory().setSelectedSlot(0);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
            player.inventoryMenu.sendAllDataToRemote();
        });
        sp.getServer().runCommand("tp @a 61.5 64 -1.5 0 30");
        context.waitFor(client -> client.player != null && client.level.getBlockState(MIN).is(Blocks.STONE)
                && client.player.getInventory().getItem(7).is(Items.DIRT)
                && client.player.getInventory().getItem(0).is(badPick ? Items.NETHERITE_PICKAXE : Items.AIR)
                && client.player.getInventory().getSelectedSlot() == 0
                && Math.abs(client.player.getZ() + 1.5) < 0.01, 200);
        context.waitTicks(3);
        int damage = damage(sp, Items.NETHERITE_PICKAXE);
        context.runOnClient(client -> {
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
            Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(1);
            Configs.Break.BREAK_INTERVAL.setIntegerValue(0);
            Configs.Break.BREAK_LIMITER.setOptionListValue(MiningFilterType.CUSTOM);
            Configs.Break.BREAK_LIMIT.setOptionListValue(UsageRestriction.ListType.NONE);
            Configs.Mine.EXCAVATE_LIMITER.setOptionListValue(MiningFilterType.CUSTOM);
            Configs.Mine.EXCAVATE_LIMIT.setOptionListValue(UsageRestriction.ListType.NONE);
            Configs.Mine.MINE_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
            Configs.Mine.MINE_INSTANT_ONLY.setBooleanValue(false);
            Configs.Mine.ENABLED.setBooleanValue(true);
            ModuleManager.MINE.resetScanState();
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
        });
        return damage;
    }

    private static int remaining(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos pos : BlockPos.betweenClosed(MIN, MAX)) if (!server.overworld().getBlockState(pos).isAir()) n++;
            return n;
        });
    }

    /** 背包中某种工具的损耗值（找不到返回 -1） */
    private static int damage(TestSingleplayerContext sp, net.minecraft.world.item.Item item) {
        return sp.getServer().computeOnServer(server -> {
            var inv = server.getPlayerList().getPlayers().getFirst().getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(item)) return s.getDamageValue();
            }
            return -1;
        });
    }
}
