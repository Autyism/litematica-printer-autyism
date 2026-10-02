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
 * 需求 14：挖掘模式自动切换工具不依赖 Tweakeroo；工具耐久快耗尽时停止使用并提示。
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
            // 场景 A：快捷栏有一把快坏的下界合金镐和一把满耐久的石镐，背包里有铁铲 → 挖石头用石镐，快坏的镐不掉耐久
            int badDamageBefore = setup(context, sp, true);
            GT.waitServer(context, () -> remaining(sp) == 0, 600, "[tool] area A was not mined");
            int[] after = damages(sp);
            if (after[0] != badDamageBefore) throw new AssertionError("[tool] nearly broken pickaxe was used: damage " + badDamageBefore + " -> " + after[0]);
            if (after[1] <= 0) throw new AssertionError("[tool] stone pickaxe was not used (damage " + after[1] + ")");
            GT.log("[tool] scenario A OK: stone pickaxe used (damage " + after[1] + "), nearly broken netherite pickaxe untouched");

            // 场景 B：只有一把快坏的镐 → 不能用它挖（改用空手），耐久不变，并显示提示
            long warnBefore = ToolSwitchUtils.getLastWarnTime();
            int damageB = setup(context, sp, false);
            int[] tick = {0};
            GT.waitServer(context, () -> {
                if (tick[0]++ % 200 == 0) {
                    String c = context.computeOnClient(client -> client.player.getInventory().getSelectedSlot() + " " + client.player.getMainHandItem()
                            + " dmg=" + client.player.getMainHandItem().getDamageValue() + " slot0=" + client.player.getInventory().getItem(0)
                            + "/" + client.player.getInventory().getItem(0).getDamageValue());
                    String s = sp.getServer().computeOnServer(server -> {
                        var p = server.getPlayerList().getPlayers().getFirst();
                        return p.getInventory().getSelectedSlot() + " " + p.getMainHandItem() + " slot0=" + p.getInventory().getItem(0)
                                + "/" + p.getInventory().getItem(0).getDamageValue();
                    });
                    GT.log("[tool] B t" + tick[0] + " remaining=" + remaining(sp) + " client " + c + " | server " + s);
                }
                return remaining(sp) == 0;
            }, 1500, "[tool] area B was not mined by hand");
            int[] afterB = damages(sp);
            if (afterB[0] != damageB) throw new AssertionError("[tool] nearly broken pickaxe was used in B: " + damageB + " -> " + afterB[0]);
            if (ToolSwitchUtils.getLastWarnTime() <= warnBefore) throw new AssertionError("[tool] no low-durability warning was shown");
            GT.log("[tool] scenario B OK: nearly broken pickaxe never used, warning shown, blocks mined by hand");
        } finally {
            context.runOnClient(client -> GT.disableAll());
        }
    }

    private static int setup(ClientGameTestContext context, TestSingleplayerContext sp, boolean withGoodPick) {
        context.runOnClient(client -> GT.disableAll());
        GT.clearArena(sp, 56, -3, 66, 6, 70);
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (BlockPos pos : BlockPos.betweenClosed(MIN, MAX)) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            ItemStack bad = new ItemStack(Items.NETHERITE_PICKAXE);
            bad.setDamageValue(bad.getMaxDamage() - 5);
            player.getInventory().setItem(0, bad);
            if (withGoodPick) {
                player.getInventory().setItem(4, new ItemStack(Items.STONE_PICKAXE));
                player.getInventory().setItem(20, new ItemStack(Items.IRON_SHOVEL));
            }
            player.getInventory().setSelectedSlot(0);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
            player.inventoryMenu.sendAllDataToRemote();
        });
        sp.getServer().runCommand("tp @a 61.5 64 -1.5 0 30");
        context.waitFor(client -> client.player != null && client.level.getBlockState(MIN).is(Blocks.STONE)
                && client.player.getInventory().getItem(0).is(Items.NETHERITE_PICKAXE)
                && client.player.getInventory().getItem(4).is(withGoodPick ? Items.STONE_PICKAXE : Items.AIR)
                && client.player.getInventory().getSelectedSlot() == 0
                && Math.abs(client.player.getZ() + 1.5) < 0.01, 200);
        context.waitTicks(3);
        int damage = sp.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getInventory().getItem(0).getDamageValue());
        context.runOnClient(client -> {
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

    /** [0] = 快坏的下界合金镐当前损耗（找不到返回 -1），[1] = 石镐损耗 */
    private static int[] damages(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            var inv = server.getPlayerList().getPlayers().getFirst().getInventory();
            int bad = -1, good = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(Items.NETHERITE_PICKAXE)) bad = s.getDamageValue();
                if (s.is(Items.STONE_PICKAXE)) good = s.getDamageValue();
            }
            return new int[]{bad, good};
        });
    }
}
