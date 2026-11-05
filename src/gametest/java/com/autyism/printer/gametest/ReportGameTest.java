package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.printer.PrintReport;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Print report: an 8x8 floor of stone in survival with only stone in the inventory. Three planks (no planks in the
 * inventory), one glass on the skip list and one dirt in the way (breaking wrong blocks off) stay unfinished. Once the
 * printer has nothing left to do it makes a report by itself: 3 missing material (planks), 1 skip list, 1 wrong block,
 * nothing "not reached". Locating goes nearest first and moves on when a block gets right (planks given); a new report
 * then has only the glass and the dirt.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ReportGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(900, 65, 900);
    private static final BlockPos MAX = new BlockPos(907, 65, 907);
    private static final BlockPos GLASS = new BlockPos(900, 65, 900);
    private static final List<BlockPos> PLANKS = List.of(new BlockPos(901, 65, 900), new BlockPos(902, 65, 900), new BlockPos(903, 65, 900));
    private static final BlockPos DIRT = new BlockPos(905, 65, 903);

    private static BlockState expected(BlockPos pos) {
        if (pos.equals(GLASS)) return Blocks.GLASS.defaultBlockState();
        if (PLANKS.contains(pos)) return Blocks.OAK_PLANKS.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("report")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.randomTicksOff(sp);
            GT.clearArena(sp, MIN.getX() - 8, MIN.getZ() - 8, MAX.getX() + 8, MAX.getZ() + 8, 75);
            sp.getServer().runCommand("setblock " + DIRT.getX() + " " + DIRT.getY() + " " + DIRT.getZ() + " minecraft:dirt");
            sp.getServer().runCommand("clear @a");
            sp.getServer().runCommand("give @a minecraft:stone 64");
            // stand on a barrier above the floor (the floor block under the player could not be placed)
            sp.getServer().runCommand("setblock 903 66 903 minecraft:barrier");
            double y = 0;
            for (int attempt = 0; attempt < 5 && y < 66.9; attempt++) {
                context.waitTicks(5);
                sp.getServer().runCommand("tp @a 903.5 67 903.5");
                context.waitTicks(20);
                y = context.computeOnClient(c -> c.player.getY());
            }
            if (y < 66.9) throw new AssertionError("[report] the player should stand on the barrier, y=" + y);
            GT.setSchematicStable(context, MIN, MAX, ReportGameTest::expected);
            context.runOnClient(c -> {
                Configs.Core.WORK_RANGE.setDoubleValue(8);
                Configs.Print.PRINT_SKIP.setBooleanValue(true);
                Configs.Print.PRINT_SKIP_LIST.setStrings(List.of("minecraft:glass"));
                if (!Configs.Print.REPORT_WHEN_DONE.getBooleanValue()) throw new AssertionError("[report] Report When Done should be on by default");
            });
            try {
                autoReport(context, sp);
                locate(context, sp);
            } finally {
                context.runOnClient(c -> {
                    GT.disableAll();
                    Configs.Print.PRINT_SKIP.setBooleanValue(false);
                    Configs.Print.PRINT_SKIP_LIST.setStrings(Configs.Print.PRINT_SKIP_LIST.getDefaultStrings());
                    PrintReport.INSTANCE.clearLocate();
                });
            }
        } finally {
            context.runOnClient(c -> GT.disableAll());
            TestSchematicRegion.clear();
        }
    }

    private static PrintReport.Result waitReport(ClientGameTestContext context, String what) {
        for (int t = 0; t < 60; t++) {
            context.waitTicks(10);
            GT.keepSchematic(context, MIN, MAX, ReportGameTest::expected);
            PrintReport.Result r = context.computeOnClient(c -> PrintReport.INSTANCE.isScanning() ? null : PrintReport.INSTANCE.getResult());
            if (r != null) return r;
            if (t % 10 == 9) GT.log("[report] waiting " + what + ": sent " + context.computeOnClient(c -> com.autyism.printer.printer.ActionManager.INSTANCE.sentCount)
                    + " scanning " + context.computeOnClient(c -> PrintReport.INSTANCE.isScanning())
                    + " printer " + context.computeOnClient(c -> com.autyism.printer.utils.ConfigUtils.isPrinterEnable() + "/" + Configs.Print.ENABLED.getBooleanValue()));
        }
        throw new AssertionError("[report] no report " + what + " within 600 ticks");
    }

    private static int count(PrintReport.Result r, PrintReport.Reason reason) {
        Integer c = r.counts().get(reason);
        return c == null ? 0 : c;
    }

    private static void autoReport(ClientGameTestContext context, TestSingleplayerContext sp) {
        context.runOnClient(c -> GT.enablePrint());
        PrintReport.Result r = waitReport(context, "after printing");
        GT.log("[report] automatic report: " + r.counts() + " materials " + r.materials() + " unfinished " + r.unfinished() + " scanned " + r.scanned());
        int stone = sp.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) if (server.overworld().getBlockState(p).is(Blocks.STONE)) n++;
            return n;
        });
        if (stone != 64 - 5) throw new AssertionError("[report] expected 59 stone printed, got " + stone);
        if (count(r, PrintReport.Reason.MATERIAL) != 3) throw new AssertionError("[report] expected 3 missing material, got " + r.counts());
        if (count(r, PrintReport.Reason.SKIP_LIST) != 1) throw new AssertionError("[report] expected 1 skip list, got " + r.counts());
        if (count(r, PrintReport.Reason.WRONG_BLOCK) != 1) throw new AssertionError("[report] expected 1 wrong block, got " + r.counts());
        if (r.unfinished() != 5) throw new AssertionError("[report] expected 5 unfinished, got " + r.unfinished() + " " + r.counts());
        Integer planks = r.materials().get(Blocks.OAK_PLANKS.asItem());
        if (planks == null || planks != 3) throw new AssertionError("[report] expected oak planks x3 in the material list, got " + r.materials());
        GT.screenshot(context, "report_chat");
    }

    private static void locate(ClientGameTestContext context, TestSingleplayerContext sp) {
        context.runOnClient(c -> GT.disableAll());
        context.runOnClient(c -> PrintReport.INSTANCE.locateNext());
        BlockPos first = null;
        for (int t = 0; t < 40 && first == null; t++) {
            context.waitTicks(5);
            first = context.computeOnClient(c -> PrintReport.INSTANCE.target());
        }
        if (first == null) throw new AssertionError("[report] locating found nothing");
        // the player stands over (903, 67, 903): the dirt at (905, 65, 903) is the nearest unfinished block
        if (!first.equals(DIRT)) throw new AssertionError("[report] expected the nearest (the dirt) first, got " + first.toShortString());
        String hud = context.computeOnClient(c -> PrintReport.INSTANCE.hudLine());
        GT.log("[report] locating: " + hud);
        if (hud == null || !hud.contains("1/5")) throw new AssertionError("[report] HUD line should say 1/5: " + hud);
        GT.screenshot(context, "report_locate");
        context.runOnClient(c -> PrintReport.INSTANCE.locateNext());
        context.waitTicks(2);
        BlockPos second = context.computeOnClient(c -> PrintReport.INSTANCE.target());
        if (second == null || second.equals(first)) throw new AssertionError("[report] the second press should move on, got " + second);
        GT.log("[report] next: " + second.toShortString() + " " + context.computeOnClient(c -> PrintReport.INSTANCE.hudLine()));
        // give the planks: they get printed and the report no longer counts them
        sp.getServer().runCommand("give @a minecraft:oak_planks 3");
        context.runOnClient(c -> GT.enablePrint());
        for (int t = 0; t < 40; t++) {
            context.waitTicks(10);
            GT.keepSchematic(context, MIN, MAX, ReportGameTest::expected);
            int placed = sp.getServer().computeOnServer(server -> {
                int n = 0;
                for (BlockPos p : PLANKS) if (server.overworld().getBlockState(p).is(Blocks.OAK_PLANKS)) n++;
                return n;
            });
            if (placed == 3) break;
        }
        context.runOnClient(c -> GT.disableAll());
        BlockPos target = context.computeOnClient(c -> PrintReport.INSTANCE.target());
        if (target != null && PLANKS.contains(target)) throw new AssertionError("[report] still pointing at a printed plank " + target.toShortString());
        context.runOnClient(c -> PrintReport.INSTANCE.report());
        PrintReport.Result r = waitReport(context, "from the hotkey");
        GT.log("[report] after the planks: " + r.counts() + " unfinished " + r.unfinished());
        if (r.unfinished() != 2 || count(r, PrintReport.Reason.SKIP_LIST) != 1 || count(r, PrintReport.Reason.WRONG_BLOCK) != 1) {
            throw new AssertionError("[report] expected only the glass and the dirt left, got " + r.counts());
        }
        // press through to the end: the target goes away after the last one
        context.runOnClient(c -> PrintReport.INSTANCE.locateNext());
        for (int t = 0; t < 40 && context.computeOnClient(c -> PrintReport.INSTANCE.target()) == null; t++) context.waitTicks(5);
        int presses = 0;
        while (context.computeOnClient(c -> PrintReport.INSTANCE.target()) != null && presses < 10) {
            context.runOnClient(c -> PrintReport.INSTANCE.locateNext());
            context.waitTicks(2);
            presses++;
        }
        if (presses != 2) throw new AssertionError("[report] expected 2 presses to pass the 2 left, took " + presses);
        playerInTheWay(context, sp);
        GT.log("[report] PASS");
    }

    /** Standing where a block should go: no endless clicks there, the report says so, and it is printed once the player leaves. */
    private static void playerInTheWay(ClientGameTestContext context, TestSingleplayerContext sp) {
        BlockPos spot = new BlockPos(906, 65, 906);
        sp.getServer().runCommand("setblock 906 65 906 minecraft:air");
        sp.getServer().runCommand("tp @a 906.5 65 906.5");
        context.waitTicks(10);
        context.runOnClient(c -> GT.enablePrint());
        int before = context.computeOnClient(c -> com.autyism.printer.printer.ActionManager.INSTANCE.sentCount);
        for (int t = 0; t < 6; t++) {
            context.waitTicks(20);
            GT.keepSchematic(context, MIN, MAX, ReportGameTest::expected);
        }
        int clicks = context.computeOnClient(c -> com.autyism.printer.printer.ActionManager.INSTANCE.sentCount) - before;
        GT.log("[report] standing in (906, 65, 906) for 120 ticks: " + clicks + " actions sent");
        if (clicks > 2) throw new AssertionError("[report] the printer kept clicking where the player stands: " + clicks);
        context.runOnClient(c -> PrintReport.INSTANCE.report());
        PrintReport.Result r = waitReport(context, "while standing in the way");
        GT.log("[report] standing in the way: " + r.counts());
        if (count(r, PrintReport.Reason.PLAYER_IN_WAY) != 1) throw new AssertionError("[report] expected 1 'you are standing there', got " + r.counts());
        sp.getServer().runCommand("tp @a 903.5 67 903.5");
        boolean printed = false;
        for (int t = 0; t < 20 && !printed; t++) {
            context.waitTicks(10);
            GT.keepSchematic(context, MIN, MAX, ReportGameTest::expected);
            printed = sp.getServer().computeOnServer(server -> server.overworld().getBlockState(spot).is(Blocks.STONE));
        }
        context.runOnClient(c -> GT.disableAll());
        if (!printed) throw new AssertionError("[report] the spot was not printed after the player left");
    }
}
