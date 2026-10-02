package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.ShulkerSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import java.util.List;

/**
 * 需求 7：缺材料时从背包里的潜影盒取（Advanced Shulkerboxes，不模拟右键），背包满时先回塞用不到的物品。
 * 需求 8：放置投影里的潜影盒时，内容物必须和投影一致。
 */
@SuppressWarnings("UnstableApiUsage")
public final class ShulkerGameTest implements FabricClientGameTest {
    private static final BlockPos ROW_MIN = new BlockPos(300, 64, 0);
    private static final BlockPos ROW_MAX = new BlockPos(305, 64, 0);
    private static final BlockPos CHEST = new BlockPos(302, 64, 3);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("shulker")) return;
        if (!FabricLoader.getInstance().isModLoaded("shulkerbox")) throw new AssertionError("Advanced Shulkerboxes not loaded");
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.clearArena(sp, 292, -8, 316, 10, 72);
            sp.getServer().runCommand("tp @a 302.5 64 1.5 180 20");
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - 302.5) < 0.01, 200);
            context.waitTicks(10);
            // 投影：一排 6 个石头
            sp.getServer().runOnServer(s -> {
                for (BlockPos p : BlockPos.betweenClosed(ROW_MIN, ROW_MAX)) s.overworld().setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
            });
            context.waitFor(c -> c.level.getBlockState(ROW_MAX).is(Blocks.STONE), 100);
            GT.captureAndPlace(context, sp, ROW_MIN, ROW_MAX, ROW_MIN, "ale_test_row");
            context.runOnClient(c -> {
                Configs.Print.USE_QUICK_SHULKER.setBooleanValue(true);
                Configs.Print.SHULKER_SOURCE.setOptionListValue(ShulkerSource.MOD);
            });

            scenarioRestock(context, sp, false);
            scenarioRestock(context, sp, true);
            GT.removeAllPlacements(context);
            scenarioContents(context, sp);
        } finally {
            context.runOnClient(c -> {
                GT.disableAll();
                Configs.Print.USE_QUICK_SHULKER.setBooleanValue(false);
            });
        }
    }

    private static ItemStack shulkerWith(ItemStack... items) {
        ItemStack box = new ItemStack(Items.WHITE_SHULKER_BOX);
        NonNullList<ItemStack> list = NonNullList.withSize(27, ItemStack.EMPTY);
        for (int i = 0; i < items.length; i++) list.set(i, items[i]);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(list));
        return box;
    }

    /** 需求 7：材料只在潜影盒里；full=true 时背包被杂物塞满 */
    private static void scenarioRestock(ClientGameTestContext context, TestSingleplayerContext sp, boolean full) {
        context.runOnClient(c -> GT.disableAll());
        sp.getServer().runOnServer(s -> {
            ServerLevel level = s.overworld();
            for (BlockPos p : BlockPos.betweenClosed(ROW_MIN, ROW_MAX)) level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState()); // 旁边的可交互方块，不能被误开
            var player = s.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            if (full) {
                for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(i % 2 == 0 ? Items.ANDESITE : Items.GRANITE, 64));
                // 潜影盒里也有安山岩（回塞优先放同种物品），以及一组石头
                player.getInventory().setItem(20, shulkerWith(new ItemStack(Items.STONE, 64), new ItemStack(Items.ANDESITE, 3)));
            } else {
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
                player.getInventory().setItem(20, shulkerWith(new ItemStack(Items.STONE, 64)));
            }
            player.getInventory().setSelectedSlot(0);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
            player.inventoryMenu.sendAllDataToRemote();
        });
        context.waitFor(c -> c.player.getInventory().getItem(20).is(Items.WHITE_SHULKER_BOX)
                && c.level.getBlockState(ROW_MIN).isAir(), 100);
        context.waitTicks(5);
        boolean[] screenSeen = {false};
        context.runOnClient(c -> GT.enablePrint());
        int ticks;
        try {
            ticks = GT.waitServer(context, () -> {
                if (context.computeOnClient(c -> c.screen != null)) screenSeen[0] = true;
                return GT.countPlaced(sp, ROW_MIN, ROW_MAX) == 6;
            }, 400, "[shulker] restock" + (full ? " (full inventory)" : "") + ": row was not printed");
        } catch (AssertionError e) {
            GT.log("[shulker] FAIL STATE server: " + sp.getServer().computeOnServer(sv -> {
                var pl = sv.getPlayerList().getPlayers().getFirst();
                return "shift=" + pl.isShiftKeyDown() + " input=" + pl.getLastClientInput() + " menu=" + pl.containerMenu.getClass().getSimpleName()
                        + " usingItem=" + pl.isUsingItem() + " hand=" + pl.getMainHandItem() + " cooldown=" + pl.getCooldowns().isOnCooldown(pl.getMainHandItem());
            }));
            GT.log("[shulker] FAIL STATE placed=" + GT.countPlaced(sp, ROW_MIN, ROW_MAX) + " " + context.computeOnClient(c ->
                    com.autyism.printer.handler.ModuleManager.PRINT.debugState() + " qsBusy=" + com.autyism.printer.utils.QuickShulkerUtils.isBusy()
                            + " screen=" + c.screen + " menu=" + c.player.containerMenu.getClass().getSimpleName()
                            + " hand=" + c.player.getMainHandItem() + " slot20=" + c.player.getInventory().getItem(20)));
            throw e;
        }
        context.waitTicks(10);
        context.runOnClient(c -> GT.disableAll());
        var wrong = GT.mismatches(sp, ROW_MIN, ROW_MAX, p -> Blocks.STONE.defaultBlockState());
        if (!wrong.isEmpty()) throw new AssertionError("[shulker] wrong blocks: " + wrong);
        if (screenSeen[0]) throw new AssertionError("[shulker] a container screen became visible");
        // 正式环境里类名是混淆名，直接比较对象
        String menu = sp.getServer().computeOnServer(s -> {
            var pl = s.getPlayerList().getPlayers().getFirst();
            return pl.containerMenu == pl.inventoryMenu ? null : pl.containerMenu.getClass().getName();
        });
        if (menu != null) throw new AssertionError("[shulker] a container is still open: " + menu);
        if (full) {
            int andesiteInShulker = sp.getServer().computeOnServer(s -> {
                var inv = s.getPlayerList().getPlayers().getFirst().getInventory();
                for (int i = 0; i < 36; i++) {
                    ItemStack st = inv.getItem(i);
                    if (st.is(Items.WHITE_SHULKER_BOX)) {
                        int n = 0;
                        for (ItemStack c : st.get(DataComponents.CONTAINER).nonEmptyItems()) if (c.is(Items.ANDESITE)) n += c.getCount();
                        return n;
                    }
                }
                return -1;
            });
            if (andesiteInShulker <= 3) throw new AssertionError("[shulker] nothing was deposited back into the shulker (andesite=" + andesiteInShulker + ")");
            GT.log("[shulker] full inventory OK: deposited andesite into the shulker (now " + andesiteInShulker + "), took stone, printed 6 in " + ticks + " ticks");
        } else {
            GT.log("[shulker] restock OK: stone taken from the shulker via Advanced Shulkerboxes, 6 printed in " + ticks + " ticks, no screen, chest untouched");
        }
    }

    /** 需求 8：投影里的潜影盒有内容物，必须拿内容物一致的那个放 */
    private static void scenarioContents(ClientGameTestContext context, TestSingleplayerContext sp) {
        BlockPos withDiamond = new BlockPos(300, 64, -4);
        BlockPos empty = new BlockPos(302, 64, -4);
        context.runOnClient(c -> GT.disableAll());
        sp.getServer().runOnServer(s -> {
            ServerLevel level = s.overworld();
            level.setBlockAndUpdate(withDiamond, Blocks.WHITE_SHULKER_BOX.defaultBlockState());
            if (level.getBlockEntity(withDiamond) instanceof ShulkerBoxBlockEntity be) be.setItem(13, new ItemStack(Items.DIAMOND, 1));
            level.setBlockAndUpdate(empty, Blocks.WHITE_SHULKER_BOX.defaultBlockState());
        });
        context.waitFor(c -> c.level.getBlockState(empty).is(Blocks.WHITE_SHULKER_BOX), 100);
        context.waitTicks(5);
        GT.captureAndPlace(context, sp, withDiamond, empty, withDiamond, "ale_test_shulkers");
        sp.getServer().runOnServer(s -> {
            ServerLevel level = s.overworld();
            for (BlockPos p : BlockPos.betweenClosed(withDiamond, empty)) level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            var player = s.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            player.getInventory().setItem(0, shulkerWith(new ItemStack(Items.DIRT, 5)));      // 内容不符，不能用
            player.getInventory().setItem(1, new ItemStack(Items.WHITE_SHULKER_BOX));          // 空盒
            player.getInventory().setItem(15, shulkerWith(new ItemStack(Items.DIAMOND, 1)));  // 装一颗钻石
            player.getInventory().setSelectedSlot(0);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
            player.inventoryMenu.sendAllDataToRemote();
        });
        GT.waitSchematicBlock(context, withDiamond, Blocks.WHITE_SHULKER_BOX);
        context.waitFor(c -> c.player.getInventory().getItem(15).is(Items.WHITE_SHULKER_BOX), 100);
        context.runOnClient(c -> GT.enablePrint());
        GT.waitServer(context, () -> sp.getServer().computeOnServer(s ->
                s.overworld().getBlockState(withDiamond).is(Blocks.WHITE_SHULKER_BOX)
                        && s.overworld().getBlockState(empty).is(Blocks.WHITE_SHULKER_BOX)), 300, "[shulker] schematic shulkers were not placed");
        context.waitTicks(10);
        context.runOnClient(c -> GT.disableAll());
        List<String> problems = sp.getServer().computeOnServer(s -> {
            List<String> out = new java.util.ArrayList<>();
            if (s.overworld().getBlockEntity(withDiamond) instanceof ShulkerBoxBlockEntity be) {
                int diamonds = 0, other = 0;
                for (int i = 0; i < be.getContainerSize(); i++) {
                    if (be.getItem(i).is(Items.DIAMOND)) diamonds += be.getItem(i).getCount();
                    else if (!be.getItem(i).isEmpty()) other++;
                }
                if (diamonds != 1 || other != 0) out.add("diamond shulker has diamonds=" + diamonds + " other=" + other);
            } else out.add("no shulker at " + withDiamond);
            if (s.overworld().getBlockEntity(empty) instanceof ShulkerBoxBlockEntity be2) {
                if (!be2.isEmpty()) out.add("empty shulker is not empty");
            } else out.add("no shulker at " + empty);
            var inv = s.getPlayerList().getPlayers().getFirst().getInventory();
            boolean dirtBoxKept = false;
            for (int i = 0; i < 36; i++) {
                ItemStack st = inv.getItem(i);
                if (st.is(Items.WHITE_SHULKER_BOX)) {
                    for (ItemStack c : st.get(DataComponents.CONTAINER).nonEmptyItems()) if (c.is(Items.DIRT)) dirtBoxKept = true;
                }
            }
            if (!dirtBoxKept) out.add("the shulker with dirt was placed");
            return out;
        });
        if (!problems.isEmpty()) throw new AssertionError("[shulker] contents check failed: " + problems);
        GT.log("[shulker] contents OK: diamond shulker placed where the schematic has the diamond, empty one where empty, dirt one kept");
    }
}
