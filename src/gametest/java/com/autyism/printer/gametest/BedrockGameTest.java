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
        // 真实实例里装着 Meteor：确认能检测到 NoGhostBlocks（bunnyi 版破基岩模组和它不兼容，打印机会提示）
        GT.log("[bedrock] Meteor NoGhostBlocks active=" + context.computeOnClient(c -> com.autyism.printer.compat.MeteorCompat.isNoGhostBlocksActive()));
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            String order = System.getProperty("ale.backends", "BUNNYI,LXYAN");
            java.util.List<String> failures = new java.util.ArrayList<>();
            for (String name : order.split(",")) {
                var backend = com.autyism.printer.enums.BedrockBackend.valueOf(name.trim());
                if (backend == com.autyism.printer.enums.BedrockBackend.LXYAN && !FabricLoader.getInstance().isModLoaded("bedrock-miner")) {
                    GT.log("[bedrock] lxyan2333 bedrock-miner not loaded, skipping that backend (run with -PwithLxyan)");
                    continue;
                }
                try {
                    if (!System.getProperty("ale.bedrockonly", "").isEmpty()) GT.log("[bedrock] spaced test skipped");
                    else runWith(context, sp, backend);
                    //? if >=1.21.11 {
                    runPatch(context, sp, backend);
                    runWide(context, sp, backend);
                    runWalkAway(context, sp, backend);
                    runWornPick(context, sp, backend);
                    runPacketGuard(context, sp, backend);
                    //?}
                } catch (AssertionError e) {
                    GT.log("[bedrock] backend " + backend + " FAILED: " + e.getMessage());
                    failures.add(backend + ": " + e.getMessage());
                }
            }
            if (!failures.isEmpty()) throw new AssertionError("[bedrock] " + failures);
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
            //? if <1.21 {
            /*player.getInventory().selected = 4;
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(4));
            *///?} else {
            player.getInventory().setSelectedSlot(4);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(4));
            //?}
            player.inventoryMenu.sendAllDataToRemote();
        });
        //? if <1.20.5 {
        /*sp.getServer().runCommand("item replace entity @a hotbar.3 with minecraft:netherite_pickaxe{Enchantments:[{id:\"minecraft:efficiency\",lvl:5s}]}");
        *///?} else
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

    //? if >=1.21.11 {
    // 玩家报的问题：一个一个破没事，框一小片就到处放活塞、火把，哪一格都破不掉。一片连在一起的基岩，玩家站在旁边够得着
    private static final List<BlockPos> PATCH = java.util.stream.IntStream.range(0, 9)
            .mapToObj(i -> new BlockPos(83 + i % 3, 64, i / 3)).toList();
    private static final BlockPos PATCH_MIN = new BlockPos(82, 64, -1);
    private static final BlockPos PATCH_MAX = new BlockPos(86, 64, 3);

    // 布置一片基岩、物品、镐（damage = 已磨损多少），打开破基岩模式
    private static void preparePatch(ClientGameTestContext context, TestSingleplayerContext sp, com.autyism.printer.enums.BedrockBackend backend,
                                     int pickDamage, BlockPos selMin, BlockPos selMax) {
        preparePatch(context, sp, backend, pickDamage, selMin, selMax, PATCH);
    }

    private static void preparePatch(ClientGameTestContext context, TestSingleplayerContext sp, com.autyism.printer.enums.BedrockBackend backend,
                                     int pickDamage, BlockPos selMin, BlockPos selMax, List<BlockPos> blocks) {
        context.runOnClient(client -> {
            GT.disableAll();
            com.autyism.printer.utils.ToolSwitchUtils.resetHalt();
            com.autyism.printer.compat.BedrockCompat.clearTasks();
        });
        context.waitTicks(5);
        GT.clearArena(sp, 76, -6, 92, 8, 72);
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (int x = 76; x <= 92; x++)
                for (int z = -6; z <= 8; z++) level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
            for (BlockPos p : blocks) level.setBlockAndUpdate(p, Blocks.BEDROCK.defaultBlockState());
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.PISTON, 64));
            player.getInventory().setItem(1, new ItemStack(Items.REDSTONE_TORCH, 64));
            player.getInventory().setItem(2, new ItemStack(Items.SLIME_BLOCK, 64));
            player.getInventory().setSelectedSlot(4);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(4));
            player.inventoryMenu.sendAllDataToRemote();
        });
        sp.getServer().runCommand("item replace entity @a hotbar.3 with minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5},minecraft:damage=" + pickDamage + "]");
        sp.getServer().runCommand("effect give @a minecraft:haste infinite 1 true");
        sp.getServer().runCommand("tp @a 84.5 64 -1.5 0 60");
        context.waitFor(client -> client.player != null && client.level.getBlockState(blocks.getFirst()).is(Blocks.BEDROCK)
                && client.player.getInventory().getItem(3).is(Items.NETHERITE_PICKAXE)
                && client.player.getInventory().getItem(3).getDamageValue() == pickDamage
                && client.player.getInventory().getItem(0).is(Items.PISTON)
                && Math.abs(client.player.getZ() + 1.5) < 0.01, 200);
        context.waitTicks(5);
        context.runOnClient(client -> {
            DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
            var selectionManager = DataManager.getSelectionManager();
            if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) selectionManager.switchSelectionMode();
            AreaSelection selection = DataManager.getSimpleArea();
            Box box = selection.getSubRegionBox(selection.getName());
            if (box == null) box = selection.getSelectedSubRegionBox();
            box.setPos1(selMin);
            box.setPos2(selMax);
            Configs.Core.WORK_RANGE.setDoubleValue(0);
            Configs.Break.TOOL_DURABILITY_PROTECT.setBooleanValue(true);
            Configs.Break.TOOL_DURABILITY_THRESHOLD.setIntegerValue(10);
            Configs.Bedrock.BACKEND.setOptionListValue(backend);
            Configs.Bedrock.IGNORE_RENDER_LAYER.setBooleanValue(true);
            Configs.Bedrock.BLOCK_LIST.setStrings(java.util.List.of("minecraft:bedrock"));
            Configs.Bedrock.ENABLED.setBooleanValue(true);
            ModuleManager.BEDROCK.resetScanState();
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
        });
    }

    private static boolean isMinerBlock(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON) || state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON)
                || state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH) || state.is(Blocks.SLIME_BLOCK);
    }

    // 服务端：{活塞（含推出中的）数, 破基岩留下的方块数}
    private static int[] minerBlocks(TestSingleplayerContext sp) {
        return minerBlocks(sp, 77, 91);
    }

    private static int[] minerBlocks(TestSingleplayerContext sp, int x0, int x1) {
        return sp.getServer().computeOnServer(server -> {
            int pistons = 0, all = 0;
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(x0, 64, -5), new BlockPos(x1, 68, 7))) {
                var state = server.overworld().getBlockState(p);
                if (!isMinerBlock(state)) continue;
                all++;
                if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON) || state.is(Blocks.MOVING_PISTON)) pistons++;
            }
            return new int[]{pistons, all};
        });
    }

    private static int remainingPatch(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos p : PATCH) if (server.overworld().getBlockState(p).is(Blocks.BEDROCK)) n++;
            return n;
        });
    }

    private static int pickDamage(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            var inv = server.getPlayerList().getPlayers().getFirst().getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(Items.NETHERITE_PICKAXE)) return inv.getItem(i).getDamageValue();
            }
            return -1;
        });
    }

    // 一片连在一起的 9 格：要一格一格破完，同一时间只在一格旁边放活塞，最后不留活塞、火把
    private static void runPatch(ClientGameTestContext context, TestSingleplayerContext sp, com.autyism.printer.enums.BedrockBackend backend) {
        String tag = "[bedrock-patch/" + backend + "]";
        preparePatch(context, sp, backend, 0, PATCH_MIN, PATCH_MAX);
        int[] maxPistons = {0};
        int[] t = {0};
        int ticks = GT.waitServer(context, () -> {
            int left = remainingPatch(sp);
            int[] mb = minerBlocks(sp);
            maxPistons[0] = Math.max(maxPistons[0], mb[0]);
            if (t[0]++ % 100 == 0) GT.log(tag + " t" + t[0] + " remaining=" + left + " pistons=" + mb[0] + " minerBlocks=" + mb[1]);
            return left == 0;
        }, 4800, tag + " the patch was not all broken");
        // 收尾（拆掉最后一格的活塞、火把）
        context.waitTicks(60);
        int[] after = minerBlocks(sp);
        context.runOnClient(client -> GT.disableAll());
        GT.log(tag + " broken " + PATCH.size() + " in " + ticks + " ticks, max pistons at once=" + maxPistons[0] + ", left over=" + after[1]
                + ", pick wear=" + pickDamage(sp));
        if (maxPistons[0] > 2) throw new AssertionError(tag + " pistons for several targets at once (max " + maxPistons[0] + ")");
        if (after[1] > 0) throw new AssertionError(tag + " " + after[1] + " pistons/torches/slime left behind");
    }

    // 伸手范围里散开的 8 格：两个 5x5 的角（相距 5 格的可以同时破），一次一格和同时 4 格各破一遍，比耗时，都不留活塞、火把
    private static final List<BlockPos> WIDE = List.of(
            new BlockPos(81, 64, -4), new BlockPos(86, 64, -4), new BlockPos(81, 64, 1), new BlockPos(86, 64, 1),
            new BlockPos(82, 64, -3), new BlockPos(87, 64, -3), new BlockPos(82, 64, 2), new BlockPos(87, 64, 2));

    private static void runWide(ClientGameTestContext context, TestSingleplayerContext sp, com.autyism.printer.enums.BedrockBackend backend) {
        int[] took = new int[2];
        int[] activeCaps = {1, 4};
        for (int i = 0; i < 2; i++) {
            int cap = activeCaps[i];
            String tag = "[bedrock-wide/" + backend + " x" + cap + "]";
            context.runOnClient(client -> Configs.Bedrock.MAX_ACTIVE.setIntegerValue(cap));
            preparePatch(context, sp, backend, 0, new BlockPos(80, 64, -5), new BlockPos(88, 64, 3), WIDE);
            int[] maxPistons = {0};
            took[i] = GT.waitServer(context, () -> {
                maxPistons[0] = Math.max(maxPistons[0], wideMinerBlocks(sp)[0]);
                return wideLeft(sp) == 0;
            }, 4800, tag + " not all broken");
            context.waitTicks(80);
            int[] after = wideMinerBlocks(sp);
            context.runOnClient(client -> GT.disableAll());
            GT.log(tag + " broken " + WIDE.size() + " in " + took[i] + " ticks, max pistons at once=" + maxPistons[0] + ", left over=" + after[1]);
            if (after[1] > 0) throw new AssertionError(tag + " " + after[1] + " pistons/torches/slime left behind");
        }
        context.runOnClient(client -> Configs.Bedrock.MAX_ACTIVE.setIntegerValue(Configs.Bedrock.MAX_ACTIVE.getDefaultIntegerValue()));
        GT.log("[bedrock-wide/" + backend + "] one at a time " + took[0] + " ticks, four at once " + took[1] + " ticks");
    }

    private static int wideLeft(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos p : WIDE) if (server.overworld().getBlockState(p).is(Blocks.BEDROCK)) n++;
            return n;
        });
    }

    private static int[] wideMinerBlocks(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            int pistons = 0, all = 0;
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(77, 63, -8), new BlockPos(91, 68, 6))) {
                var state = server.overworld().getBlockState(p);
                if (!isMinerBlock(state)) continue;
                all++;
                if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON) || state.is(Blocks.MOVING_PISTON)) pistons++;
            }
            return new int[]{pistons, all};
        });
    }

    // 破到一半人走开：打印机先去破新位置够得着的格子，回来再把剩下的（包括走开时正在破的那一格）破完，不留活塞、火把
    private static final List<BlockPos> FAR = List.of(new BlockPos(100, 64, 0), new BlockPos(101, 64, 0), new BlockPos(102, 64, 0));

    private static void runWalkAway(ClientGameTestContext context, TestSingleplayerContext sp, com.autyism.printer.enums.BedrockBackend backend) {
        String tag = "[bedrock-walk/" + backend + "]";
        preparePatch(context, sp, backend, 0, PATCH_MIN, new BlockPos(104, 64, 3));
        GT.clearArena(sp, 95, -6, 106, 8, 72);
        sp.getServer().runOnServer(server -> {
            for (int x = 95; x <= 106; x++)
                for (int z = -6; z <= 8; z++) server.overworld().setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
            for (BlockPos p : FAR) server.overworld().setBlockAndUpdate(p, Blocks.BEDROCK.defaultBlockState());
        });
        // 第一格正在破（活塞已经放下）时走开
        GT.waitServer(context, () -> minerBlocks(sp, 77, 106)[0] > 0, 400, tag + " the first block was never started");
        int leftBefore = remainingPatch(sp);
        sp.getServer().runCommand("tp @a 101.5 64 -1.5 0 60");
        int ticks = GT.waitServer(context, () -> farLeft(sp) == 0 && minerBlocks(sp, 95, 106)[1] == 0, 600,
                tag + " the blocks where the player went were not broken and cleaned up");
        GT.log(tag + " walked away with " + leftBefore + " left: the 3 blocks there broken and cleaned up in " + ticks + " ticks");
        sp.getServer().runCommand("tp @a 84.5 64 -1.5 0 60");
        int back = GT.waitServer(context, () -> remainingPatch(sp) == 0, 2400, tag + " the rest was not broken after coming back");
        context.waitTicks(60);
        int[] after = minerBlocks(sp, 77, 106);
        context.runOnClient(client -> GT.disableAll());
        GT.log(tag + " back: the rest broken in " + back + " ticks, left over=" + after[1]);
        if (after[1] > 0) throw new AssertionError(tag + " " + after[1] + " pistons/torches/slime left behind");
    }

    private static int farLeft(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos p : FAR) if (server.overworld().getBlockState(p).is(Blocks.BEDROCK)) n++;
            return n;
        });
    }

    // 镐只剩 15 点（阈值 10）：交一两格就停，镐不坏、不低于阈值，打印机停下并提示
    private static void runWornPick(ClientGameTestContext context, TestSingleplayerContext sp, com.autyism.printer.enums.BedrockBackend backend) {
        String tag = "[bedrock-wear/" + backend + "]";
        int max = new ItemStack(Items.NETHERITE_PICKAXE).getMaxDamage();
        preparePatch(context, sp, backend, max - 15, PATCH_MIN, PATCH_MAX);
        GT.waitServer(context, () -> context.computeOnClient(client -> com.autyism.printer.utils.ToolSwitchUtils.isHalted()), 2400,
                tag + " the printer did not stop for the worn pickaxe");
        context.waitTicks(100);
        int damage = pickDamage(sp);
        int left = remainingPatch(sp);
        boolean idle = context.computeOnClient(client -> com.autyism.printer.compat.BedrockCompat.isIdle());
        context.runOnClient(client -> GT.disableAll());
        GT.log(tag + " halted: pick left=" + (damage < 0 ? "GONE" : max - damage) + ", bedrock left=" + left + "/" + PATCH.size() + ", miner idle=" + idle);
        if (damage < 0) throw new AssertionError(tag + " the pickaxe broke");
        if (max - damage < 10) throw new AssertionError(tag + " the pickaxe went below the threshold: " + (max - damage));
        if (!idle) throw new AssertionError(tag + " the bedrock miner still has work after the stop");
    }

    // 最后一道保险：破基岩模组手上已经有活（例如玩家自己点的），镐正好到阈值。它要挖活塞时，挖掘包被拦下，
    // 镐一点都不再磨，破基岩模组的活被清掉，打印机停下。
    private static void runPacketGuard(ClientGameTestContext context, TestSingleplayerContext sp, com.autyism.printer.enums.BedrockBackend backend) {
        String tag = "[bedrock-guard/" + backend + "]";
        int max = new ItemStack(Items.NETHERITE_PICKAXE).getMaxDamage();
        // 框选的地方没有基岩：打印机自己不交任何一格
        preparePatch(context, sp, backend, max - 10, new BlockPos(77, 64, 5), new BlockPos(78, 64, 6));
        // 等打印机把破基岩模组打开（同步要一两 tick）再交
        context.waitTicks(20);
        boolean accepted = context.computeOnClient(client -> com.autyism.printer.compat.BedrockCompat.addToBreakList(PATCH.get(4), client.level));
        GT.log(tag + " miner accepted the direct task=" + accepted);
        GT.waitServer(context, () -> context.computeOnClient(client -> com.autyism.printer.utils.ToolSwitchUtils.isHalted()), 1200,
                tag + " the break packet was not stopped");
        context.waitTicks(60);
        int damage = pickDamage(sp);
        boolean idle = context.computeOnClient(client -> com.autyism.printer.compat.BedrockCompat.isIdle());
        context.runOnClient(client -> GT.disableAll());
        GT.log(tag + " halted: pick left=" + (damage < 0 ? "GONE" : max - damage) + ", miner idle=" + idle);
        if (damage != max - 10) throw new AssertionError(tag + " the pickaxe was worn past the threshold: left " + (damage < 0 ? "GONE" : max - damage));
        if (!idle) throw new AssertionError(tag + " the bedrock miner still has work after the stop");
    }
    //?}

    private static int remaining(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos p : BEDROCK) if (server.overworld().getBlockState(p).is(Blocks.BEDROCK)) n++;
            if (server.overworld().getBlockState(FRAME).is(Blocks.END_PORTAL_FRAME)) n++;
            return n;
        });
    }
}
