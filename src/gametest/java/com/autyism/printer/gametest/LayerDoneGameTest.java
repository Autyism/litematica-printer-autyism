package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.LayerDoneScope;
import com.autyism.printer.handler.ModuleManager;
import com.autyism.printer.handler.handlers.Print;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 分层完成判定：两层的投影（36x6，每层 216 块）比工作范围（6）大得多，飞在一头的上方打印。
 * 默认“整层”：范围内的部分打完后不往上走，等玩家走过去把这一层剩下的打完（一步一步传送过去），
 * 整层打完才提示“这一层完成”（统计是整层）、才开始放上一层；服务端处理顺序里上一层的方块全在这一层之后。
 * “工作范围内”：范围内的部分打完就算完成，马上开始放上一层（以前的做法）。“隐藏分层完成提示”只影响显示。
 */
@SuppressWarnings("UnstableApiUsage")
public final class LayerDoneGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(840, 65, 700);
    private static final BlockPos MAX = new BlockPos(875, 66, 705);
    private static final int LAYER = MIN.getY();
    private static final int PER_LAYER = (MAX.getX() - MIN.getX() + 1) * (MAX.getZ() - MIN.getZ() + 1);

    private static BlockState expected(BlockPos pos) {
        return pos.getY() == LAYER ? Blocks.STONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("layerdone")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.randomTicksOff(sp);
            sp.getServer().runCommand("gamemode creative @a");
            GT.clearArena(sp, MIN.getX() - 8, MIN.getZ() - 8, MAX.getX() + 8, MAX.getZ() + 8, 75);
            teleport(context, sp, MIN.getX() - 1.5);
            GT.setSchematicStable(context, MIN, MAX, LayerDoneGameTest::expected);
            context.runOnClient(c -> {
                Configs.Core.WORK_RANGE.setDoubleValue(6);
                Configs.Print.LAYERED_MODE.setBooleanValue(true);
            });
            if (Configs.Print.LAYER_DONE_SCOPE.getDefaultOptionListValue() != LayerDoneScope.WHOLE_LAYER
                    || context.computeOnClient(c -> Configs.Print.LAYER_DONE_SCOPE.getOptionListValue()) != LayerDoneScope.WHOLE_LAYER) {
                throw new AssertionError("[layerdone] the default should be the whole layer");
            }
            try {
                wholeLayer(context, sp);
                withinRange(context, sp);
            } finally {
                // 退出世界之前改回来（MaLiLib 退出世界时会保存设置）
                context.runOnClient(c -> {
                    GT.disableAll();
                    Configs.Print.LAYER_DONE_SCOPE.setOptionListValue(LayerDoneScope.WHOLE_LAYER);
                    Configs.Print.HIDE_LAYER_MESSAGES.setBooleanValue(false);
                });
            }
        } finally {
            BlockLog.stop();
            context.runOnClient(c -> {
                GT.disableAll();
                Configs.Print.LAYER_DONE_SCOPE.setOptionListValue(LayerDoneScope.WHOLE_LAYER);
                Configs.Print.HIDE_LAYER_MESSAGES.setBooleanValue(false);
            });
            TestSchematicRegion.clear();
        }
    }

    private static void wholeLayer(ClientGameTestContext context, TestSingleplayerContext sp) {
        int announcedBefore = context.computeOnClient(c -> ModuleManager.PRINT.getAnnouncements().size());
        int skipsBefore = context.computeOnClient(c -> ModuleManager.PRINT.getForcedLayerSkips());
        BlockLog.start(MIN, MAX);
        context.runOnClient(c -> GT.enablePrint());
        int[] counts = waitStable(context, sp);
        GT.log("[layerdone] whole layer, standing at the west end: layer " + LAYER + " " + counts[0] + "/" + PER_LAYER + ", layer " + (LAYER + 1) + " " + counts[1]);
        if (counts[0] == 0 || counts[0] >= PER_LAYER) throw new AssertionError("[layerdone] expected part of layer " + LAYER + " in range, got " + counts[0]);
        if (counts[1] != 0) throw new AssertionError("[layerdone] the next layer was started before the whole layer was done: " + counts[1]);
        // 等一会儿：不会因为“没有放置尝试”被看门狗强行跳到上一层；没事可做时也不再每 tick 重扫范围
        int passesBefore = context.computeOnClient(c -> ModuleManager.PRINT.debugPasses());
        context.waitTicks(120);
        int passes = context.computeOnClient(c -> ModuleManager.PRINT.debugPasses()) - passesBefore;
        GT.log("[layerdone] range scans while waiting 120 ticks for the player: " + passes);
        if (passes > 30) throw new AssertionError("[layerdone] the printer kept rescanning its range while there was nothing to do: " + passes + " scans in 120 ticks");
        int upper = GT.countPlaced(sp, MIN.above(), MAX);
        int layerNow = context.computeOnClient(c -> ModuleManager.PRINT.getCurrentLayer());
        if (upper != 0 || layerNow != LAYER) throw new AssertionError("[layerdone] moved on while the layer was unfinished: upper=" + upper + " layer=" + layerNow);
        if (context.computeOnClient(c -> ModuleManager.PRINT.getAnnouncements().size()) != announcedBefore) {
            throw new AssertionError("[layerdone] a layer was announced as done although it was not");
        }
        GT.log("[layerdone] waited 120 ticks: still on layer " + layerNow + ", nothing above, no message, state "
                + context.computeOnClient(c -> ModuleManager.PRINT.debugState()));
        // 一步一步走过去，把这一层剩下的打完
        for (double x = MIN.getX() + 4.5; ; x += 6) {
            double stand = Math.min(x, MAX.getX() + 2.5);
            teleport(context, sp, stand);
            counts = waitStable(context, sp);
            GT.log("[layerdone] standing at x=" + stand + ": layer " + LAYER + " " + counts[0] + "/" + PER_LAYER + ", layer " + (LAYER + 1) + " " + counts[1]);
            if (counts[0] < PER_LAYER && counts[1] > 0) {
                throw new AssertionError("[layerdone] the next layer got blocks while this one was unfinished: " + counts[0] + "/" + PER_LAYER);
            }
            if (stand >= MAX.getX() + 2.5) break;
        }
        if (counts[0] != PER_LAYER) throw new AssertionError("[layerdone] layer " + LAYER + " not finished after walking along it: " + counts[0]);
        if (counts[1] == 0) throw new AssertionError("[layerdone] the next layer did not start after the whole layer was done");
        context.runOnClient(c -> GT.disableAll());
        BlockLog.stop();
        // 服务端处理顺序：上一层的第一个方块在这一层最后一个方块之后
        List<BlockLog.Change> log = BlockLog.changes();
        int lastLower = -1, firstUpper = Integer.MAX_VALUE;
        for (BlockLog.Change ch : log) {
            if (!ch.before().isAir() || ch.after().isAir()) continue;
            if (ch.pos().getY() == LAYER) lastLower = Math.max(lastLower, ch.index());
            else firstUpper = Math.min(firstUpper, ch.index());
        }
        if (firstUpper < lastLower) throw new AssertionError("[layerdone] an upper block (#" + firstUpper + ") was placed before the last lower block (#" + lastLower + ")");
        List<Print.LayerAnnouncement> announced = context.computeOnClient(c -> ModuleManager.PRINT.getAnnouncements());
        Print.LayerAnnouncement done = announced.stream().skip(announcedBefore).filter(a -> a.stats().layer() == LAYER).findFirst()
                .orElseThrow(() -> new AssertionError("[layerdone] no 'layer done' message for layer " + LAYER));
        if (done.stats().total() != PER_LAYER || done.stats().correct() != PER_LAYER || !done.shown()) {
            throw new AssertionError("[layerdone] the message should count the whole layer (" + PER_LAYER + " correct), got " + done);
        }
        int skips = context.computeOnClient(c -> ModuleManager.PRINT.getForcedLayerSkips()) - skipsBefore;
        if (skips != 0) throw new AssertionError("[layerdone] the stall watchdog skipped " + skips + " time(s) while waiting for the player");
        GT.log("[layerdone] OK whole layer: layer " + LAYER + " message after all " + PER_LAYER + " blocks (" + done.stats() + "), last lower block #" + lastLower
                + ", first upper block #" + firstUpper);
    }

    private static void withinRange(ClientGameTestContext context, TestSingleplayerContext sp) {
        sp.getServer().runOnServer(server -> {
            for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        });
        teleport(context, sp, MIN.getX() - 1.5);
        int announcedBefore = context.computeOnClient(c -> ModuleManager.PRINT.getAnnouncements().size());
        context.runOnClient(c -> {
            Configs.Print.LAYER_DONE_SCOPE.setOptionListValue(LayerDoneScope.WORK_RANGE);
            Configs.Print.HIDE_LAYER_MESSAGES.setBooleanValue(true);
            GT.enablePrint();
        });
        int[] counts = waitStable(context, sp);
        context.runOnClient(c -> GT.disableAll());
        GT.log("[layerdone] within range, standing at the west end: layer " + LAYER + " " + counts[0] + "/" + PER_LAYER + ", layer " + (LAYER + 1) + " " + counts[1]);
        if (counts[0] >= PER_LAYER || counts[1] == 0) {
            throw new AssertionError("[layerdone] 'within range' should start the next layer as soon as the part in range is done: " + counts[0] + ", " + counts[1]);
        }
        List<Print.LayerAnnouncement> announced = context.computeOnClient(c -> ModuleManager.PRINT.getAnnouncements());
        Print.LayerAnnouncement done = announced.stream().skip(announcedBefore).filter(a -> a.stats().layer() == LAYER).findFirst()
                .orElseThrow(() -> new AssertionError("[layerdone] no 'layer done' record for layer " + LAYER + " within range"));
        if (done.stats().total() >= PER_LAYER || done.shown()) {
            throw new AssertionError("[layerdone] 'within range' should count only the part in range and the message should be hidden: " + done);
        }
        GT.log("[layerdone] OK within range: next layer started with layer " + LAYER + " at " + counts[0] + "/" + PER_LAYER + ", message counted "
                + done.stats().total() + " blocks and was hidden (Hide Layer Messages)");
    }

    /** 飞在两层上面（不挡住任何格子），沿着投影的长边移动 */
    private static void teleport(ClientGameTestContext context, TestSingleplayerContext sp, double x) {
        double z = MIN.getZ() + 3.0;
        context.runOnClient(c -> {
            c.player.getAbilities().flying = true;
            c.player.onUpdateAbilities();
        });
        sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f %d %.1f -90 60", x, MAX.getY() + 1, z));
        context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - x) < 0.01, 200);
        context.runOnClient(c -> {
            c.player.getAbilities().flying = true;
            c.player.onUpdateAbilities();
        });
        context.waitTicks(5);
        // 必须飞在两层上面：站在地上会挡住这一层的格子
        double y = context.computeOnClient(c -> c.player.getY());
        if (y < MAX.getY() + 0.9) throw new AssertionError("[layerdone] the player is not flying above the layers: y=" + y);
    }

    /** 等到服务端两层的方块数连续 40 tick 不变，返回 {这一层, 上一层} */
    private static int[] waitStable(ClientGameTestContext context, TestSingleplayerContext sp) {
        int[] last = null;
        int same = 0;
        for (int t = 0; t < 1200; t++) {
            context.waitTick();
            int[] now = {GT.countPlaced(sp, MIN, MAX.below()), GT.countPlaced(sp, MIN.above(), MAX)};
            if (last != null && now[0] == last[0] && now[1] == last[1] && now[0] > 0) {
                if (++same >= 40) return now;
            } else {
                same = 0;
                last = now;
            }
        }
        throw new AssertionError("[layerdone] printing never settled: " + java.util.Arrays.toString(last));
    }
}
