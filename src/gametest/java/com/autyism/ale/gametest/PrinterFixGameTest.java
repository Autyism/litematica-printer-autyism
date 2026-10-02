package com.autyism.ale.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import com.autyism.ale.config.Configs;
import com.autyism.ale.enums.MiningFilterType;
import com.autyism.ale.enums.RadiusShapeType;
import com.autyism.ale.enums.SelectionType;
import com.autyism.ale.handler.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 修复验证：
 * 1. 渲染层：层范围在够不着的位置时不得打印到其他层；跳跃中打印也只能落在所选层。
 * 2. 物品切换：材料在背包（非快捷栏）时先换手再放置，放置结果必须与投影一致。
 * 3. 秒破挖掘：满级效率 + 急迫下整片沙子/泥土/石头同时秒破，跳过不能秒破的黑曜石，且客户端与服务端一致（无幽灵方块）。
 */
@SuppressWarnings("UnstableApiUsage")
public final class PrinterFixGameTest implements FabricClientGameTest {
    // 打印区域：x 0..2, z 0..2, y 64..70，选中层 y = 64
    private static final BlockPos PRINT_MIN = new BlockPos(0, 64, 0);
    private static final BlockPos PRINT_MAX = new BlockPos(2, 70, 2);
    private static final int LAYER = 64;

    // 挖掘区域：x 21..25, z 0..2, y 64..65
    private static final BlockPos MINE_MIN = new BlockPos(21, 64, 0);
    private static final BlockPos MINE_MAX = new BlockPos(25, 65, 2);
    private static final List<BlockPos> OBSIDIAN = List.of(new BlockPos(23, 64, 1), new BlockPos(21, 65, 2));

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("printer")) return;
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getServer().runCommand("gamerule doDaylightCycle false");
            sp.getServer().runCommand("gamerule doMobSpawning false");
            sp.getServer().runCommand("gamemode survival @a");
            context.runOnClient(client -> configureCommon());

            testLayerOutOfReach(context, sp);
            testLayerWhileJumping(context, sp);
            testInstantMining(context, sp);
        } finally {
            context.runOnClient(client -> disableAll());
        }
    }

    // ---------------------------------------------------------------- 渲染层

    private static Block expectedPrintBlock(BlockPos pos) {
        return ((pos.getX() + pos.getZ()) & 1) == 0 ? Blocks.STONE : Blocks.OAK_PLANKS;
    }

    private static void buildPrintArena(TestSingleplayerContext sp, BlockPos glass, double px, double py, double pz) {
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (int x = -6; x <= 8; x++) {
                for (int z = -6; z <= 8; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.BEDROCK.defaultBlockState());
                    for (int y = 64; y <= 80; y++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            level.setBlockAndUpdate(glass, Blocks.GLASS.defaultBlockState());
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            // 材料只放在背包主区（非快捷栏），强制走“交换到手 → 下一 tick 确认 → 放置”的路径
            for (int i = 0; i < 6; i++) {
                player.getInventory().setItem(9 + i, new ItemStack(i % 2 == 0 ? Items.STONE : Items.OAK_PLANKS, 64));
            }
            player.getInventory().setSelectedSlot(0);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
            player.inventoryMenu.sendAllDataToRemote();
        });
        sp.getServer().runCommand("tp @a " + px + " " + py + " " + pz);
    }

    private static void prepareSchematic() {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) throw new AssertionError("Schematic world is unavailable");
        schematic.getChunkSource().loadChunk(0, 0);
        schematic.getChunkSource().loadChunk(-1, 0);
        schematic.getChunkSource().loadChunk(0, -1);
        schematic.getChunkSource().loadChunk(-1, -1);
        for (BlockPos pos : BlockPos.betweenClosed(PRINT_MIN, PRINT_MAX)) {
            schematic.setBlock(pos.immutable(), expectedPrintBlock(pos).defaultBlockState(), 3);
        }
        TestSchematicRegion.activate(PRINT_MIN, PRINT_MAX);
    }

    private static void setLayer(int y) {
        LayerRange range = DataManager.getRenderLayerRange();
        range.setLayerMode(LayerMode.LAYER_RANGE);
        range.setAxis(Direction.Axis.Y);
        range.setLayerRangeMax(y);
        range.setLayerRangeMin(y);
        range.setLayerRangeMax(y);
        if (range.getLayerMin() != y || range.getLayerMax() != y) {
            throw new AssertionError("Could not set layer range, got " + range.getLayerMin() + ".." + range.getLayerMax());
        }
    }

    private static void enablePrint() {
        Configs.Print.ENABLED.setBooleanValue(true);
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_RENDER_LAYER);
        ModuleManager.PRINT.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    /** 统计打印区域内 y >= fromY 的被放置方块（玻璃除外），返回坐标列表 */
    private static List<BlockPos> placedAbove(TestSingleplayerContext sp, int fromY) {
        return sp.getServer().computeOnServer(server -> {
            ServerLevel level = server.overworld();
            List<BlockPos> result = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(PRINT_MIN, PRINT_MAX)) {
                if (pos.getY() < fromY) continue;
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && !state.is(Blocks.GLASS)) result.add(pos.immutable());
            }
            return result;
        });
    }

    private static void testLayerOutOfReach(ClientGameTestContext context, TestSingleplayerContext sp) {
        // 玩家站在 y=68 的玻璃上，眼睛约 70.6；工作半径 5 → 选中层 y=64 够不着。
        // 修复前：层裁剪后 minY(65) > maxY(64) 被交换成 [64,65]，会把方块打到 y=65（未选中的层）。
        BlockPos glass = new BlockPos(1, 68, 1);
        buildPrintArena(sp, glass, 1.5, 69, 1.5);
        context.waitFor(client -> client.player != null && client.level != null
                && client.level.getBlockState(glass).is(Blocks.GLASS)
                && client.player.getInventory().getItem(9).is(Items.STONE)
                && Math.abs(client.player.getY() - 69) < 0.01, 200);
        context.runOnClient(client -> {
            prepareSchematic();
            setLayer(LAYER);
            enablePrint();
        });
        context.waitTicks(60);
        context.runOnClient(client -> Configs.Core.WORK_SWITCH.setBooleanValue(false));

        List<BlockPos> wrong = placedAbove(sp, LAYER);
        if (!wrong.isEmpty()) {
            throw new AssertionError("[layer/out-of-reach] printed outside the selected layer: " + wrong);
        }
        System.out.println("[PrinterFixGameTest] layer out-of-reach: OK (nothing printed)");
    }

    private static void testLayerWhileJumping(ClientGameTestContext context, TestSingleplayerContext sp) {
        // 玩家站在 y=65 的玻璃上（选中层可达），持续跳跃，打印只能落在 y=64。
        BlockPos glass = new BlockPos(1, 65, 1);
        buildPrintArena(sp, glass, 1.5, 66, 1.5);
        context.waitFor(client -> client.player != null && client.level != null
                && client.level.getBlockState(glass).is(Blocks.GLASS)
                && client.player.getInventory().getItem(9).is(Items.STONE)
                && Math.abs(client.player.getY() - 66) < 0.01, 200);
        context.runOnClient(client -> {
            prepareSchematic();
            setLayer(LAYER);
            enablePrint();
        });
        context.getInput().holdKey(options -> options.keyJump);
        int ticks;
        try {
            ticks = waitServer(context, () -> layerComplete(sp), 400, "layer 64 never completed");
            context.waitTicks(20);
        } finally {
            context.getInput().releaseKey(options -> options.keyJump);
        }
        context.runOnClient(client -> Configs.Core.WORK_SWITCH.setBooleanValue(false));

        List<BlockPos> wrong = placedAbove(sp, LAYER + 1);
        if (!wrong.isEmpty()) {
            throw new AssertionError("[layer/jumping] printed outside the selected layer: " + wrong);
        }
        List<String> mismatched = sp.getServer().computeOnServer(server -> {
            List<String> result = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(PRINT_MIN.atY(LAYER), PRINT_MAX.atY(LAYER))) {
                BlockState state = server.overworld().getBlockState(pos);
                if (!state.is(expectedPrintBlock(pos))) result.add(pos.toShortString() + "=" + state);
            }
            return result;
        });
        if (!mismatched.isEmpty()) {
            throw new AssertionError("[layer/jumping] wrong block on the selected layer: " + mismatched);
        }
        System.out.println("[PrinterFixGameTest] layer while jumping: OK (layer complete in " + ticks + " ticks, no wrong blocks)");
    }

    /** 每 tick 在测试线程上检查服务端状态（waitFor 的谓词运行在客户端线程，不能访问服务端） */
    private static int waitServer(ClientGameTestContext context, java.util.function.BooleanSupplier done,
                                  int maxTicks, String failMessage) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            if (done.getAsBoolean()) return tick;
            context.waitTick();
        }
        throw new AssertionError(failMessage + " within " + maxTicks + " ticks");
    }

    private static boolean layerComplete(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            for (BlockPos pos : BlockPos.betweenClosed(PRINT_MIN.atY(LAYER), PRINT_MAX.atY(LAYER))) {
                if (server.overworld().getBlockState(pos).isAir()) return false;
            }
            return true;
        });
    }

    // ---------------------------------------------------------------- 秒破挖掘

    private static Block mineBlock(BlockPos pos) {
        if (OBSIDIAN.contains(pos)) return Blocks.OBSIDIAN;
        return switch (Math.floorMod(pos.getX() + pos.getY() * 2 + pos.getZ(), 3)) {
            case 0 -> Blocks.SAND;
            case 1 -> Blocks.DIRT;
            default -> Blocks.STONE;
        };
    }

    private static void testInstantMining(ClientGameTestContext context, TestSingleplayerContext sp) {
        context.runOnClient(client -> {
            disableAll();
            TestSchematicRegion.clear();
            DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        });
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (int x = 16; x <= 30; x++) {
                for (int z = -6; z <= 8; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.BEDROCK.defaultBlockState());
                    for (int y = 64; y <= 80; y++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            for (BlockPos pos : BlockPos.betweenClosed(MINE_MIN, MINE_MAX)) {
                level.setBlockAndUpdate(pos, mineBlock(pos).defaultBlockState());
            }
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
            player.inventoryMenu.sendAllDataToRemote();
        });
        sp.getServer().runCommand("tp @a 23.5 64 -1.5");
        sp.getServer().runCommand("item replace entity @a hotbar.1 with minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5}]");
        sp.getServer().runCommand("item replace entity @a hotbar.2 with minecraft:netherite_shovel[minecraft:enchantments={\"minecraft:efficiency\":5}]");
        sp.getServer().runCommand("effect give @a minecraft:haste infinite 1 true");

        context.waitFor(client -> client.player != null && client.level != null
                && client.level.getBlockState(MINE_MIN).is(mineBlock(MINE_MIN))
                && client.player.getInventory().getItem(1).is(Items.NETHERITE_PICKAXE)
                && client.player.getInventory().getItem(2).is(Items.NETHERITE_SHOVEL)
                && client.player.hasEffect(net.minecraft.world.effect.MobEffects.HASTE)
                && Math.abs(client.player.getZ() + 1.5) < 0.01, 200);
        context.waitTicks(5);

        context.runOnClient(client -> {
            var selectionManager = DataManager.getSelectionManager();
            if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) {
                selectionManager.switchSelectionMode();
            }
            AreaSelection selection = DataManager.getSimpleArea();
            Box box = selection.getSubRegionBox(selection.getName());
            if (box == null) box = selection.getSelectedSubRegionBox();
            if (box == null) throw new AssertionError("Litematica simple selection has no box");
            box.setPos1(MINE_MIN);
            box.setPos2(MINE_MAX);

            Configs.Break.AUTO_TOOL_SWITCH.setBooleanValue(true);
            Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(0);
            Configs.Break.BREAK_INTERVAL.setIntegerValue(0);
            Configs.Break.BREAK_COOLDOWN.setIntegerValue(3);
            Configs.Break.BREAK_INSTANT_MINE.setBooleanValue(false);
            Configs.Break.BREAK_LIMITER.setOptionListValue(MiningFilterType.CUSTOM);
            Configs.Break.BREAK_LIMIT.setOptionListValue(UsageRestriction.ListType.NONE);
            Configs.Mine.EXCAVATE_LIMITER.setOptionListValue(MiningFilterType.CUSTOM);
            Configs.Mine.EXCAVATE_LIMIT.setOptionListValue(UsageRestriction.ListType.NONE);
            Configs.Mine.MINE_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
            Configs.Mine.MINE_INSTANT_ONLY.setBooleanValue(true);
            Configs.Mine.ENABLED.setBooleanValue(true);
            ModuleManager.MINE.resetScanState();
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
        });

        int ticks = waitServer(context, () -> {
            int remaining = remainingMineable(sp);
            String hand = context.computeOnClient(client -> client.player.getMainHandItem().toString()
                    + " slot=" + client.player.getInventory().getSelectedSlot());
            System.out.println("[PrinterFixGameTest] mine tick: remaining=" + remaining + " hand=" + hand);
            return remaining == 0;
        }, 100, "mineable blocks remain");
        context.waitTicks(20);
        context.runOnClient(client -> Configs.Core.WORK_SWITCH.setBooleanValue(false));

        List<String> serverState = sp.getServer().computeOnServer(server -> {
            List<String> result = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(MINE_MIN, MINE_MAX)) {
                BlockState state = server.overworld().getBlockState(pos);
                boolean obsidian = OBSIDIAN.contains(pos);
                if (obsidian != state.is(Blocks.OBSIDIAN) || (!obsidian && !state.isAir())) {
                    result.add(pos.toShortString() + "=" + state);
                }
            }
            return result;
        });
        if (!serverState.isEmpty()) {
            throw new AssertionError("[mine] unexpected server blocks: " + serverState);
        }
        List<String> ghosts = context.computeOnClient(client -> {
            List<String> result = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(MINE_MIN, MINE_MAX)) {
                BlockState clientState = client.level.getBlockState(pos);
                boolean obsidian = OBSIDIAN.contains(pos);
                if (obsidian != clientState.is(Blocks.OBSIDIAN) || (!obsidian && !clientState.isAir())) {
                    result.add(pos.toShortString() + "=" + clientState);
                }
            }
            return result;
        });
        if (!ghosts.isEmpty()) {
            throw new AssertionError("[mine] client/server mismatch (ghost blocks): " + ghosts);
        }
        if (ticks > 10) {
            throw new AssertionError("[mine] instant mining too slow: " + ticks + " ticks for 28 blocks");
        }
        System.out.println("[PrinterFixGameTest] instant mining: OK (28 blocks in " + ticks + " ticks, obsidian skipped, no ghosts)");
    }

    private static int remainingMineable(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            int count = 0;
            for (BlockPos pos : BlockPos.betweenClosed(MINE_MIN, MINE_MAX)) {
                if (OBSIDIAN.contains(pos)) continue;
                if (!server.overworld().getBlockState(pos).isAir()) count++;
            }
            return count;
        });
    }

    // ---------------------------------------------------------------- 配置

    private static void configureCommon() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(5.0D);
        Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(0);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(3);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(true);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(false);
        disableAll();
    }

    private static void disableAll() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Mine.ENABLED.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
    }
}
