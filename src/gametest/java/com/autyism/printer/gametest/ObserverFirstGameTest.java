package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 侦测器先于依附它的方块（-Pgt=observerfirst）。
 * <p>
 * 侦测器朝上，上面是要靠它支撑的中继器：照侦测器安全放置的规则，侦测器要等上面的方块，中继器又要等侦测器，两边都放不了。
 * 每个场景先在世界里不带方块更新地搭好整台（周围的活塞、灯、BUD 状态的活塞……），截成投影，再拿掉侦测器和中继器让打印机打：
 * 侦测器放下、以及中继器放上去时它亮的那一下没影响的，两个都要打出来；有影响的两个都不能放，周围的东西也一下都不能动。
 * 最后一个场景测“侦测器放置影响检测”：普通的侦测器（面前已经有方块）旁边有 BUD 状态的活塞，开着检测时不放；关掉检测作对照，活塞真的会被推出去。
 */
@SuppressWarnings("UnstableApiUsage")
public final class ObserverFirstGameTest implements FabricClientGameTest {
    private static final int Y = 64;
    /** 不发方块更新、不更新形状（搭场景时不让机器提前动） */
    private static final int QUIET = 2 | 16;

    private record Scenario(String name, boolean expectPlaced, boolean impactCheck, BiConsumer<ServerLevel, BlockPos> extra,
                            boolean glassOutput, boolean inputPresent) {
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("observerfirst")) return;
        List<Scenario> scenarios = List.of(
                new Scenario("stone output, nothing around", true, true, (l, o) -> {
                }, false, false),
                new Scenario("stone output next to a piston", false, true, (l, o) -> {
                    set(l, o.offset(1, -1, 0), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
                    set(l, o.offset(2, -1, 0), Blocks.SANDSTONE.defaultBlockState());
                }, false, false),
                new Scenario("stone output next to a lamp", false, true, (l, o) ->
                        set(l, o.offset(1, -1, 0), Blocks.REDSTONE_LAMP.defaultBlockState()), false, false),
                new Scenario("glass output next to a lamp", true, true, (l, o) ->
                        set(l, o.offset(1, -1, 0), Blocks.REDSTONE_LAMP.defaultBlockState()), true, false),
                new Scenario("glass output next to a BUD piston", false, true, (l, o) -> {
                    set(l, o.offset(1, -1, 0), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
                    set(l, o.offset(2, -1, 0), Blocks.SANDSTONE.defaultBlockState());
                    // 准连接：活塞上面那一格（o+(1,0,0)）旁边的红石块，活塞“该推”却没收到方块更新
                    set(l, o.offset(2, 0, 0), Blocks.REDSTONE_BLOCK.defaultBlockState());
                }, true, false),
                new Scenario("another observer watches this spot", false, true, (l, o) ->
                        set(l, o.offset(1, 0, 0), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST)), false, false),
                // 身后是另一个侦测器，但它看的是别处：侦测器不管充能，亮一下不会动到它
                new Scenario("output is an observer looking elsewhere", true, true, (l, o) ->
                        set(l, o.below(), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.NORTH)), false, false),
                // 石头旁边的中继器输出那一面对着石头：石头被充能不会让它动
                new Scenario("stone output next to a repeater pointing into it", true, true, (l, o) ->
                        set(l, o.offset(1, -1, 0), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.EAST)), false, false),
                // 中继器输入那一面对着石头：石头一被充能它就亮
                new Scenario("stone output feeding a repeater", false, true, (l, o) ->
                        set(l, o.offset(1, -1, 0), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST)), false, false),
                // 准连接：侦测器身后是空气，活塞在空气下面一格。侦测器亮时信号进了那格空气，活塞的“上面那一格”有信号，它就伸出去
                new Scenario("air output above a quasi-connected piston", false, true, (l, o) -> {
                    set(l, o.below(), Blocks.AIR.defaultBlockState());
                    set(l, o.below(2), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
                }, false, false),
                // 对照（影响检测关掉）：照原来的规则侦测器会放下（身后在投影里是空气），中继器放上去时活塞真的伸出去了
                new Scenario("control, impact check off: air output above a quasi-connected piston", true, false, (l, o) -> {
                    set(l, o.below(), Blocks.AIR.defaultBlockState());
                    set(l, o.below(2), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
                }, false, false),
                // 活塞正面朝上顶着石头：正面不收信号，但它“上面那一格”（石头）旁边的侦测器在发信号，准连接照样让它伸出去
                new Scenario("piston facing up into a stone output", false, true, (l, o) ->
                        set(l, o.below(2), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP)), false, false),
                // 斜下方的活塞读得到被充能的石头（准连接），只是收不到方块更新：会“上膛”，也不行
                new Scenario("piston diagonally below a stone output", false, true, (l, o) ->
                        set(l, o.offset(1, -2, 0), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH)), false, false),
                new Scenario("door next to a stone output", false, true, (l, o) -> {
                    BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
                    set(l, o.offset(1, -1, 0), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
                    set(l, o.offset(1, 0, 0), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
                }, false, false),
                // 拉杆的 POWERED 是它自己发的信号，石头被充能它不会动：可以放
                new Scenario("lever on a stone output", true, true, (l, o) ->
                        set(l, o.offset(1, -1, 0), Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL)
                                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)), false, false),
                new Scenario("hopper next to a stone output", false, true, (l, o) ->
                        set(l, o.offset(1, -1, 0), Blocks.HOPPER.defaultBlockState()), false, false),
                new Scenario("unpowered powered rail next to a stone output", false, true, (l, o) ->
                        set(l, o.offset(1, -1, 0), Blocks.POWERED_RAIL.defaultBlockState()), false, false),
                // 普通铁轨的丁字路口：侦测器发来的更新会让它按有没有信号重新选方向
                new Scenario("rail junction next to a stone output", false, true, (l, o) -> {
                    BlockState ns = Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH);
                    set(l, o.offset(1, -1, 0), ns);
                    set(l, o.offset(1, -1, 1), ns);
                    set(l, o.offset(1, -1, -1), ns);
                    set(l, o.offset(2, -1, 0), Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST));
                }, false, false),
                new Scenario("impact check: BUD piston next to a normal observer", false, true, (l, o) -> {
                    set(l, o.offset(1, 0, 0), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
                    set(l, o.offset(2, 0, 0), Blocks.SANDSTONE.defaultBlockState());
                    set(l, o.offset(1, 2, 0), Blocks.REDSTONE_BLOCK.defaultBlockState());
                }, false, true),
                new Scenario("control, impact check off: BUD piston next to a normal observer", true, false, (l, o) -> {
                    set(l, o.offset(1, 0, 0), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
                    set(l, o.offset(2, 0, 0), Blocks.SANDSTONE.defaultBlockState());
                    set(l, o.offset(1, 2, 0), Blocks.REDSTONE_BLOCK.defaultBlockState());
                }, false, true));
        List<String> failures = new ArrayList<>();
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            sp.getServer().runCommand("gamemode creative @a");
            GT.randomTicksOff(sp);
            context.runOnClient(c -> c.options.renderDistance().set(8));
            for (int i = 0; i < scenarios.size(); i++) {
                String r = runScenario(context, sp, scenarios.get(i), i);
                if (r != null) failures.add(r);
            }
        } finally {
            context.runOnClient(c -> GT.disableAll());
        }
        if (!failures.isEmpty()) throw new AssertionError("[observerfirst] " + failures);
        GT.log("[observerfirst] OK: all " + scenarios.size() + " scenarios");
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, QUIET);
    }

    /** null = 通过 */
    private String runScenario(ClientGameTestContext context, TestSingleplayerContext sp, Scenario s, int index) {
        // 每个场景隔 32 格，不和别的场景共用区块（Litematica 删掉上一个投影、载入下一个时在后台按区块处理）
        BlockPos o = new BlockPos(400 + index * 32, Y + 1, 400);
        BlockPos min = o.offset(-2, -1, -2), max = o.offset(3, 2, 2);
        // 先过去（区块要在客户端载入，投影才会显示出来）
        sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f", o.getX() + 0.5, Y + 4, o.getZ() + 3.5));
        context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - (o.getX() + 0.5)) < 0.01 && Math.abs(c.player.getZ() - (o.getZ() + 3.5)) < 0.01, 200);
        context.runOnClient(c -> {
            c.player.getAbilities().flying = true;
            c.player.onUpdateAbilities();
        });
        context.waitTicks(20);
        GT.clearArena(sp, min.getX() - 2, min.getZ() - 2, max.getX() + 2, max.getZ() + 2, Y + 8);
        BlockState observer = Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.UP);
        BlockState top = s.inputPresent() ? Blocks.STONE.defaultBlockState()
                : Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.NORTH).setValue(RepeaterBlock.DELAY, 2);
        sp.getServer().runOnServer(server -> {
            ServerLevel l = server.overworld();
            // 底座：一层石头（侦测器身后那一格可以换成玻璃），侦测器，上面的中继器 / 石头，再加上场景自己的东西
            for (BlockPos p : BlockPos.betweenClosed(min.getX(), Y, min.getZ(), max.getX(), Y, max.getZ())) set(l, p.immutable(), Blocks.STONE.defaultBlockState());
            if (s.glassOutput()) set(l, o.below(), Blocks.GLASS.defaultBlockState());
            set(l, o, observer);
            set(l, o.above(), top);
            s.extra().accept(l, o);
        });
        Map<BlockPos, BlockState> before = snapshot(sp, min, max);
        GT.captureAndPlace(context, sp, min, max, min, "ale_obsfirst_" + index);
        // 拿掉要打印的：侦测器，以及要靠它支撑的中继器（普通侦测器的场景只拿掉侦测器）
        sp.getServer().runOnServer(server -> {
            ServerLevel l = server.overworld();
            if (!s.inputPresent()) set(l, o.above(), Blocks.AIR.defaultBlockState());
            set(l, o, Blocks.AIR.defaultBlockState());
        });
        GT.waitSchematicBlock(context, o, Blocks.OBSERVER);
        // 等 Litematica 后台把这个投影载完、上一个投影清完，投影世界里整块区域都和搭好的一样再开打印机。
        // 要连续 20 tick 都对：刚传送过来时区块还在陆续载入，Litematica 会在检查通过以后又在后台重建一遍（那一下投影是错位的）
        int readyTicks = 0;
        for (int i = 0; i < 600; i++) {
            boolean ready = context.computeOnClient(c -> {
                //? if >=1.21.11 {
                if (fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler.INSTANCE.hasAnyTasks()) return false;
                //?}
                var w = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
                for (var e : before.entrySet()) {
                    BlockPos p = e.getKey();
                    boolean inside = p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY()
                            && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
                    BlockState want = inside ? e.getValue() : Blocks.AIR.defaultBlockState();
                    if (!w.getBlockState(p).equals(want)) return false;
                }
                return true;
            });
            readyTicks = ready ? readyTicks + 1 : 0;
            if (readyTicks >= 20) break;
            if (i == 599) throw new AssertionError("[observerfirst] " + s.name() + ": the schematic never matched the built structure");
            context.waitTick();
        }
        GT.log("[observerfirst] " + s.name() + " schematic check: " + context.computeOnClient(c -> {
            var w = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
            StringBuilder b = new StringBuilder();
            for (BlockPos p : new BlockPos[]{min, min.above(), o, o.above(), o.above(2), o.offset(1, 0, 0), o.offset(1, 2, 0)}) {
                b.append(p.toShortString()).append('=').append(w.getBlockState(p).getBlock()).append(" world=").append(c.level.getBlockState(p).getBlock()).append("; ");
            }
            return b.toString();
        }));
        context.runOnClient(c -> {
            Configs.Core.WORK_RANGE.setDoubleValue(8);
            Configs.Print.SAFELY_OBSERVER.setBooleanValue(true);
            Configs.Print.OBSERVER_SUPPORT_FIRST.setBooleanValue(true);
            Configs.Print.OBSERVER_IMPACT_CHECK.setBooleanValue(s.impactCheck());
            GT.enablePrint();
        });
        // 除了侦测器和它上面那一格，别的格子一下都不能变（每 tick 都看，亮一下就灭的灯、推出去又收回的活塞也算）
        List<String> moved = new ArrayList<>();
        int stable = 0;
        boolean placed = false;
        for (int t = 0; t < 160 && stable < 40; t++) {
            context.waitTick();
            Map<BlockPos, BlockState> now = snapshot(sp, min, max);
            for (var e : before.entrySet()) {
                if (e.getKey().equals(o) || e.getKey().equals(o.above())) continue;
                BlockState was = e.getValue(), is = now.get(e.getKey());
                if (!was.equals(is) && moved.size() < 6) moved.add("t" + t + " " + e.getKey().toShortString() + " " + was + " -> " + is);
            }
            boolean both = now.get(o).is(Blocks.OBSERVER) && now.get(o.above()).is(top.getBlock());
            if (both != placed) stable = 0;
            placed = both;
            stable++;
        }
        context.runOnClient(c -> {
            GT.disableAll();
            Configs.Print.OBSERVER_IMPACT_CHECK.setBooleanValue(true);
        });
        Map<BlockPos, BlockState> end = snapshot(sp, min, max);
        GT.removeAllPlacements(context);
        String result = "placed=" + placed + " (want " + s.expectPlaced() + "), observer=" + end.get(o) + ", above=" + end.get(o.above());
        // 对照场景（检测关掉）：侦测器放下去会推动 BUD 活塞，证明这个场景确实危险
        boolean control = !s.impactCheck();
        if (control) {
            boolean pistonMoved = !moved.isEmpty();
            GT.log("[observerfirst] " + s.name() + ": " + result + ", the piston " + (pistonMoved ? "moved as expected" : "did NOT move") + " " + moved);
            return placed && pistonMoved ? null : s.name() + ": control did not reproduce (" + result + ", moved=" + moved + ")";
        }
        GT.log("[observerfirst] " + s.name() + ": " + result + (moved.isEmpty() ? ", nothing else changed" : ", CHANGED " + moved));
        if (placed != s.expectPlaced()) return s.name() + ": " + result;
        if (!moved.isEmpty()) return s.name() + ": something moved " + moved;
        if (placed && !end.get(o).equals(observer.setValue(ObserverBlock.POWERED, false))) return s.name() + ": wrong observer " + end.get(o);
        return null;
    }

    private static Map<BlockPos, BlockState> snapshot(TestSingleplayerContext sp, BlockPos min, BlockPos max) {
        return sp.getServer().computeOnServer(server -> {
            Map<BlockPos, BlockState> m = new LinkedHashMap<>();
            for (BlockPos p : BlockPos.betweenClosed(min.offset(-1, -1, -1), max.offset(1, 1, 1))) m.put(p.immutable(), server.overworld().getBlockState(p));
            return m;
        });
    }
}
