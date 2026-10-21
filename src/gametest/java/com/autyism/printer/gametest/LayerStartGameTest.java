package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.LayerDoneScope;
import com.autyism.printer.handler.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/**
 * 分层打印开机 / 换层要等多久（玩家反馈：层数多了以后打开打印机，要等特别久才开始打新的一层）。
 * 一座 SIZE x SIZE、35 层已经打好的底座，正中间上面一座 10x10x5 还没打的塔；玩家飞在塔上方，工作范围 64、渲染距离 8（玩家的设置）。
 * 开打印机后量：多少 tick 放下塔的第一个方块、每一层从开始到下一层开始隔多久、塔多久打完。整层判定和“工作范围内”各测一次。
 */
@SuppressWarnings("UnstableApiUsage")
public final class LayerStartGameTest implements FabricClientGameTest {
    /** 底座边长（默认 160：10x10 个区块列，一层的整层判定比每 tick 的预算大） */
    private static final int SIZE = Integer.getInteger("ale.layerstartsize", 160);
    private static final BlockPos MIN = new BlockPos(320, 70, 320);
    private static final BlockPos MAX = new BlockPos(320 + SIZE - 1, 109, 320 + SIZE - 1);
    private static final int BASE_TOP = 104;
    private static final int CENTER = 320 + SIZE / 2;
    private static final BlockPos TOWER_MIN = new BlockPos(CENTER - 5, 105, CENTER - 5);
    private static final BlockPos TOWER_MAX = new BlockPos(CENTER + 4, 109, CENTER + 4);
    private static final String TP = String.format(Locale.ROOT, "tp @a %.1f 112 %.1f 0 90", CENTER + 0.5, CENTER + 0.5);
    private static final int TOWER_LAYERS = TOWER_MAX.getY() - TOWER_MIN.getY() + 1;
    private static final int PER_TOWER_LAYER = 100;

    private static BlockState expected(BlockPos p) {
        if (p.getY() <= BASE_TOP) return Blocks.STONE.defaultBlockState();
        boolean tower = p.getX() >= TOWER_MIN.getX() && p.getX() <= TOWER_MAX.getX() && p.getZ() >= TOWER_MIN.getZ() && p.getZ() <= TOWER_MAX.getZ();
        return tower ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("layerstart")) return;
        int[] savedRd = new int[1];
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.randomTicksOff(sp);
            context.runOnClient(c -> {
                savedRd[0] = c.options.renderDistance().get();
                c.options.renderDistance().set(8);
            });
            sp.getServer().runCommand(TP);
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - (CENTER + 0.5)) < 0.01, 400);
            fly(context);
            context.waitTicks(40);
            GT.clearArena(sp, MIN.getX() - 4, MIN.getZ() - 4, MAX.getX() + 4, MAX.getZ() + 4, MAX.getY() + 6);
            // 先在世界里搭好整座（底座 + 塔），截取成真实的投影并放置，再拆掉塔
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) {
                    BlockState s = expected(p);
                    if (!s.isAir()) server.overworld().setBlock(p, s, 2 | 16);
                }
            });
            // 看玩家脚下附近的格子（大底座的角落可能在渲染距离外，客户端看不到）
            BlockPos baseCenter = new BlockPos(CENTER, BASE_TOP, CENTER);
            context.waitFor(c -> c.level.getBlockState(baseCenter).is(Blocks.STONE) && c.level.getBlockState(TOWER_MAX).is(Blocks.OAK_PLANKS), 600);
            GT.captureAndPlace(context, sp, MIN, MAX, MIN, "ale_test_layerstart");
            GT.waitSchematicBlock(context, TOWER_MAX, Blocks.OAK_PLANKS);
            //? if >=1.20.5
            sp.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 64");

            // 第一次：统计从头算；第二次换“工作范围内”；第三次再开整层判定（统计已经算好了，像玩家第二次开打印机）
            for (LayerDoneScope scope : new LayerDoneScope[]{LayerDoneScope.WHOLE_LAYER, LayerDoneScope.WORK_RANGE, LayerDoneScope.WHOLE_LAYER}) {
                clearTower(context, sp);
                run(context, sp, scope);
            }
        } finally {
            context.runOnClient(c -> {
                GT.disableAll();
                Configs.Print.LAYER_DONE_SCOPE.setOptionListValue(LayerDoneScope.WHOLE_LAYER);
                if (savedRd[0] > 0) c.options.renderDistance().set(savedRd[0]);
            });
            GT.removeAllPlacements(context);
        }
    }

    private static void fly(ClientGameTestContext context) {
        context.runOnClient(c -> {
            c.player.getAbilities().flying = true;
            c.player.onUpdateAbilities();
        });
    }

    /** 拆掉塔、补满材料、回到塔上方 */
    private static void clearTower(ClientGameTestContext context, TestSingleplayerContext sp) {
        sp.getServer().runOnServer(server -> {
            for (BlockPos p : BlockPos.betweenClosed(TOWER_MIN, TOWER_MAX)) server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            for (int i = 0; i < 9; i++) player.getInventory().setItem(i, new ItemStack(Items.OAK_PLANKS, 64));
            player.inventoryMenu.sendAllDataToRemote();
        });
        context.waitFor(c -> c.level.getBlockState(TOWER_MIN).isAir() && c.level.getBlockState(TOWER_MAX).isAir()
                && c.player.getInventory().getItem(8).is(Items.OAK_PLANKS), 200);
        sp.getServer().runCommand(TP);
        fly(context);
        context.waitTicks(40);
    }

    private static void run(ClientGameTestContext context, TestSingleplayerContext sp, LayerDoneScope scope) {
        context.runOnClient(c -> {
            Configs.Core.WORK_RANGE.setDoubleValue(64);
            Configs.Core.ITERATION_TIME_LIMIT.setIntegerValue(10);
            Configs.Core.PROGRESS_SCOPE.setOptionListValue(com.autyism.printer.enums.ProgressScope.LOADED_AREA);
            Configs.Print.LAYERED_MODE.setBooleanValue(true);
            Configs.Print.LAYER_DONE_SCOPE.setOptionListValue(scope);
            Configs.Print.HIDE_LAYER_MESSAGES.setBooleanValue(true);
            GT.enablePrint();
        });
        int[] firstAt = new int[TOWER_LAYERS];
        java.util.Arrays.fill(firstAt, -1);
        int[] doneAt = new int[TOWER_LAYERS];
        java.util.Arrays.fill(doneAt, -1);
        StringBuilder layers = new StringBuilder();
        Integer[] lastLayer = {Integer.MIN_VALUE};
        int[] tick = {0};
        long start = System.nanoTime();
        int ticks = GT.waitServer(context, () -> {
            int t = tick[0]++;
            Integer layer = context.computeOnClient(c -> ModuleManager.PRINT.getCurrentLayer());
            if (!java.util.Objects.equals(layer, lastLayer[0])) {
                if (layers.length() < 1500) layers.append(" t").append(t).append(":").append(layer);
                lastLayer[0] = layer;
            }
            int[] perLayer = sp.getServer().computeOnServer(server -> {
                int[] n = new int[TOWER_LAYERS];
                for (BlockPos p : BlockPos.betweenClosed(TOWER_MIN, TOWER_MAX)) {
                    if (!server.overworld().getBlockState(p).isAir()) n[p.getY() - TOWER_MIN.getY()]++;
                }
                return n;
            });
            boolean all = true;
            for (int i = 0; i < TOWER_LAYERS; i++) {
                if (perLayer[i] > 0 && firstAt[i] < 0) firstAt[i] = t;
                if (perLayer[i] >= PER_TOWER_LAYER && doneAt[i] < 0) doneAt[i] = t;
                all &= perLayer[i] >= PER_TOWER_LAYER;
            }
            return all;
        }, 6000, "[layerstart] the tower was not printed (" + scope + ")");
        double seconds = (System.nanoTime() - start) / 1e9;
        context.runOnClient(c -> GT.disableAll());
        StringBuilder gaps = new StringBuilder();
        for (int i = 0; i < TOWER_LAYERS; i++) {
            gaps.append(String.format(Locale.ROOT, " y%d first@%d done@%d", TOWER_MIN.getY() + i, firstAt[i], doneAt[i]));
        }
        GT.log(String.format(Locale.ROOT, "[layerstart] %s size %d: first tower block after %d ticks, tower done after %d ticks (%.1f s wall)%s",
                scope, SIZE, firstAt[0], ticks, seconds, gaps));
        GT.log("[layerstart] " + scope + " printer layer timeline:" + layers);
        var wrong = GT.mismatches(sp, TOWER_MIN, TOWER_MAX, LayerStartGameTest::expected);
        if (!wrong.isEmpty()) throw new AssertionError("[layerstart] wrong tower blocks: " + wrong.subList(0, Math.min(10, wrong.size())));
    }
}
