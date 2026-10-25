package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.handler.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 玩家反馈：分层打印重开以后很快到了当前层，但下面打好过的层里少了一个方块，打印机还当作那一层打好了，不回去补。
 * 一座 10x10、4 层都打好的平台（投影和世界一样），从下面几层拿掉方块：打印机关着时拿掉再打开、开着打完以后拿掉、
 * 人走远（区块卸载）时拿掉再回来打开。每一种都要补回去。
 */
@SuppressWarnings("UnstableApiUsage")
public final class LayerRevisitGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(900, 65, 700);
    private static final BlockPos MAX = new BlockPos(909, 68, 709);
    /** 最上面一层往东伸出去一长条（离人很远、没打）：整层判定时打印机停在最上面一层等人走过去 */
    private static final BlockPos FAR_MAX = new BlockPos(940, 68, 702);

    private static BlockState expected(BlockPos pos) {
        if (pos.getX() > MAX.getX() || pos.getZ() > MAX.getZ()) {
            return pos.getY() == MAX.getY() && pos.getZ() <= FAR_MAX.getZ() ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        return pos.getY() % 2 == 0 ? Blocks.STONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("layerrevisit")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.randomTicksOff(sp);
            sp.getServer().runCommand("gamemode creative @a");
            GT.clearArena(sp, MIN.getX() - 8, MIN.getZ() - 8, FAR_MAX.getX() + 8, MAX.getZ() + 8, 80);
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) server.overworld().setBlock(p, expected(p), 2 | 16);
            });
            fly(context, sp, MIN.getX() + 5.0, MIN.getZ() + 5.0);
            GT.setSchematicStable(context, MIN, new BlockPos(FAR_MAX.getX(), MAX.getY(), MAX.getZ()), LayerRevisitGameTest::expected);
            context.runOnClient(c -> {
                Configs.Print.LAYERED_MODE.setBooleanValue(true);
                Configs.Core.WORK_RANGE.setDoubleValue(8);
            });
            List<String> failures = new java.util.ArrayList<>();
            // 1. 打印机关着时拿掉一格，再打开
            check(context, sp, failures, "off, then on", List.of(new BlockPos(903, 66, 703)), false, false);
            // 2. 开着、停在最上面一层（东边那一长条还没打，人不过去）时拿掉下面两层各一格：以前要等最上面一层打完才回来补
            check(context, sp, failures, "on, finished", List.of(new BlockPos(904, 65, 702), new BlockPos(906, 67, 707)), true, false);
            // 3. 人走远（区块卸载）时拿掉，回来再打开
            check(context, sp, failures, "away, then back", List.of(new BlockPos(901, 67, 708)), false, true);
            // 4. 重开（关了马上再开）：最底层少一格
            check(context, sp, failures, "restart", List.of(new BlockPos(908, 65, 701)), false, false);
            if (!failures.isEmpty()) throw new AssertionError("[layerrevisit] " + failures);
        } finally {
            context.runOnClient(c -> {
                GT.disableAll();
                Configs.Print.LAYERED_MODE.setBooleanValue(false);
            });
            TestSchematicRegion.clear();
        }
    }

    private static void check(ClientGameTestContext context, TestSingleplayerContext sp, List<String> failures, String name,
                              List<BlockPos> holes, boolean printerOn, boolean away) {
        String tag = "[layerrevisit " + name + "]";
        if (printerOn) {
            context.runOnClient(c -> GT.enablePrint());
            // 先让它把所有层都看一遍（全部是对的）
            context.waitTicks(100);
        } else {
            context.runOnClient(c -> GT.disableAll());
        }
        if (away) {
            sp.getServer().runCommand("tp @a 2000 90 2000");
            context.waitFor(c -> c.player != null && c.player.getX() > 1900, 200);
            context.waitTicks(100);
        }
        sp.getServer().runOnServer(server -> {
            for (BlockPos p : holes) server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        });
        if (away) fly(context, sp, MIN.getX() + 5.0, MIN.getZ() + 5.0);
        context.waitFor(c -> c.level != null && holes.stream().allMatch(p -> c.level.getBlockState(p).isAir()), 200);
        context.waitTicks(20);
        int layerBefore = context.computeOnClient(c -> ModuleManager.PRINT.getCurrentLayer() == null ? Integer.MIN_VALUE : ModuleManager.PRINT.getCurrentLayer());
        if (!printerOn) context.runOnClient(c -> GT.enablePrint());
        int[] t = {0};
        boolean ok = true;
        try {
            t[0] = GT.waitServer(context, () -> sp.getServer().computeOnServer(server -> holes.stream().allMatch(p -> server.overworld().getBlockState(p).equals(expected(p))))
                    , 600, tag + " not filled");
        } catch (AssertionError e) {
            ok = false;
            failures.add(name);
            GT.log(tag + " FAILED: holes " + holes + " not filled in 600 ticks; layer before=" + layerBefore + " now=" + context.computeOnClient(c -> ModuleManager.PRINT.getCurrentLayer())
                    + " state=" + context.computeOnClient(c -> ModuleManager.PRINT.debugState()));
        }
        if (ok) GT.log(tag + " OK: " + holes.size() + " missing block(s) filled in " + t[0] + " ticks (layer before " + layerBefore + ", range "
                + context.computeOnClient(c -> com.autyism.printer.utils.ConfigUtils.getEffectiveRange()) + ")");
        if (name.equals("restart")) {
            // 关了马上再开
            context.runOnClient(c -> GT.disableAll());
        }
    }

    private static void fly(ClientGameTestContext context, TestSingleplayerContext sp, double x, double z) {
        context.runOnClient(c -> {
            if (c.player != null) {
                c.player.getAbilities().flying = true;
                c.player.onUpdateAbilities();
            }
        });
        sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f %d %.1f 0 90", x, MAX.getY() + 1, z));
        context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - x) < 0.01 && c.player.getY() > MAX.getY() + 0.5, 400);
        context.runOnClient(c -> {
            c.player.getAbilities().flying = true;
            c.player.onUpdateAbilities();
        });
        context.waitTicks(20);
    }
}
