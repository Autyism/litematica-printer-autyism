package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 红石压力测试：大量不同延迟的中继器（含被侧面锁住的）、减法模式比较器、0~24 全音高的音符盒、红石线，
 * 先在世界里真的搭出来让电路通电（投影里记录的就是“正在工作”的状态），再清空、按投影打印回来，
 * 核对延迟 / 模式 / 音高 / 朝向全部正确，并且打印不会卡住。
 * <p>
 * 原作者的版本有这个问题：中继器还没调完延迟就去放别的方块、别的方块又放不上，互相抢最后都不动。
 */
@SuppressWarnings("UnstableApiUsage")
public final class RedstoneGameTest implements FabricClientGameTest {
    private static final BlockPos BASE = new BlockPos(600, 64, 600);
    /** 电路运行时自己会变的属性：不要求和投影一致 */
    private static final Set<String> RUNTIME = Set.of("powered", "power", "locked", "lit", "triggered", "enabled",
            "north", "south", "east", "west");

    private final Map<BlockPos, BlockState> build = new LinkedHashMap<>();
    /** 铁轨部分：主体搭好后按这个顺序逐个放（带方块更新，和玩家手放一样会自动连接） */
    private final Map<BlockPos, BlockState> rails = new LinkedHashMap<>();

    /** 只要求“不放错”的格子（缺失可以接受） */
    private final java.util.Set<BlockPos> adversarial = new java.util.HashSet<>();

    private void rail(int x, int dy, int z, BlockState s) {
        rails.put(BASE.offset(x, dy, z), s);
    }

    private void put(int x, int z, BlockState s) {
        build.put(BASE.offset(x, 1, z), s);
    }

    private static BlockState repeater(Direction facing, int delay) {
        return Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, facing).setValue(RepeaterBlock.DELAY, delay);
    }

    private void design() {
        Block[] floors = {Blocks.STONE, Blocks.GOLD_BLOCK, Blocks.CLAY, Blocks.PACKED_ICE, Blocks.BONE_BLOCK, Blocks.IRON_BLOCK, Blocks.OAK_PLANKS, Blocks.SAND};
        for (int x = 0; x <= 26; x++) {
            for (int z = 0; z <= 30; z++) {
                Block floor = z == 16 ? floors[x % floors.length] : Blocks.STONE;
                build.put(BASE.offset(x, 0, z), floor.defaultBlockState());
            }
        }
        // 8 行中继器链：偶数行由红石块供电，延迟 1~4 交错
        for (int z = 0; z < 8; z++) {
            if (z % 2 == 0) put(0, z, Blocks.REDSTONE_BLOCK.defaultBlockState());
            for (int x = 1; x <= 16; x++) put(x, z, repeater(Direction.WEST, (x + z) % 4 + 1));
        }
        // 锁存：z=11 一行中继器（输入在西边），z=10 的中继器从侧面（北边）给它信号把它锁住
        put(0, 11, Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int x = 1; x <= 16; x++) {
            if (x % 2 == 0) put(x, 9, Blocks.REDSTONE_BLOCK.defaultBlockState());
            put(x, 10, repeater(Direction.NORTH, x % 4 + 1));
            put(x, 11, repeater(Direction.WEST, (x + 2) % 4 + 1));
        }
        // 比较器：比较 / 减法交替
        put(0, 13, Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int x = 1; x <= 12; x++) {
            put(x, 13, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST)
                    .setValue(ComparatorBlock.MODE, x % 2 == 0 ? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE));
        }
        // 音符盒：0~24 全音高，两行，下面垫不同方块（不同乐器）
        for (int x = 0; x <= 24; x++) {
            put(x, 15, Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, x));
            put(x, 16, Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 24 - x));
        }
        // 铁轨（按“正常玩家会怎么放”的顺序真的在世界里放出来，读回的形状就是原版能做到的样子）：
        // 6 条紧贴的南北向动力铁轨、6 条紧贴的东西向激活铁轨、普通铁轨绕的一圈（带拐角）、普通铁轨上坡、
        // 三条紧贴并排的动力铁轨上坡（打包机里出过错的那种）
        BlockState powered = Blocks.POWERED_RAIL.defaultBlockState();
        BlockState activator = Blocks.ACTIVATOR_RAIL.defaultBlockState();
        BlockState plain = Blocks.RAIL.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < 6; x++) for (int z = 22; z < 30; z++) rail(x, 1, z, powered);
        // 东西向的每条线先放中间、最后放两头（两头挨着旁边那条线的两头，先放会被拉过去）
        for (int z = 22; z < 28; z++) {
            for (int x = 9; x < 15; x++) rail(x, 1, z, activator.setValue(net.minecraft.world.level.block.PoweredRailBlock.SHAPE, net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST));
            rail(8, 1, z, activator.setValue(net.minecraft.world.level.block.PoweredRailBlock.SHAPE, net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST));
            rail(15, 1, z, activator.setValue(net.minecraft.world.level.block.PoweredRailBlock.SHAPE, net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST));
        }
        int[][] loop = {{18, 22}, {19, 22}, {20, 22}, {21, 22}, {22, 22}, {22, 23}, {22, 24}, {22, 25}, {22, 26},
                {21, 26}, {20, 26}, {19, 26}, {18, 26}, {18, 25}, {18, 24}, {18, 23}};
        for (int[] q : loop) rail(q[0], 1, q[1], plain);
        rail(24, 1, 23, stone);
        rail(24, 1, 24, stone);
        rail(24, 2, 24, stone);
        rail(24, 1, 25, stone);
        rail(24, 2, 25, stone);
        rail(24, 3, 25, plain);
        rail(24, 3, 24, plain);
        rail(24, 2, 23, plain);
        rail(24, 1, 22, plain);
        // 故意刁难：三条紧贴并排的上坡，原版很多形状只能靠“先放歪再被邻居拉正”得到 —— 这里只要求不放错（可以留空）
        for (int z = 28; z <= 30; z++) {
            rail(19, 1, z, stone);
            rail(18, 1, z, stone);
            rail(18, 2, z, stone);
        }
        for (int z = 28; z <= 30; z++) {
            adversarial.add(BASE.offset(18, 3, z));
            adversarial.add(BASE.offset(19, 2, z));
            adversarial.add(BASE.offset(20, 1, z));
            rail(18, 3, z, powered);
            rail(19, 2, z, powered);
            rail(20, 1, z, powered);
        }
        // 红石线 + 红石火把
        put(0, 18, Blocks.REDSTONE_TORCH.defaultBlockState());
        for (int x = 1; x <= 15; x++) put(x, 18, Blocks.REDSTONE_WIRE.defaultBlockState());
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("redstone")) return;
        design();
        BlockPos min = BASE.offset(-1, 0, -1), max = BASE.offset(27, 4, 31);
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.removeAllPlacements(context);
            sp.getServer().runCommand("gamemode creative @a");
            sp.getServer().runCommand("gamerule randomTickSpeed 0");
            GT.clearArena(sp, min.getX() - 2, min.getZ() - 2, max.getX() + 2, max.getZ() + 2, 75);
            double cx = BASE.getX() + 13.5, cz = BASE.getZ() + 15.5;
            sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f 72 %.1f", cx, cz));
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - cx) < 0.01, 200);
            context.runOnClient(c -> {
                c.player.getAbilities().flying = true;
                c.player.onUpdateAbilities();
            });

            // 1. 在世界里真的搭出来，等电路稳定，读回“工作中”的状态作为投影
            sp.getServer().runOnServer(server -> {
                for (var e : build.entrySet()) server.overworld().setBlockAndUpdate(e.getKey(), e.getValue());
            });
            sp.getServer().runOnServer(server -> {
                for (var e : rails.entrySet()) server.overworld().setBlockAndUpdate(e.getKey(), e.getValue());
            });
            // 音符盒的乐器由下面的方块决定，但直接写方块不会更新它：把下面的方块拿掉再放回去触发更新
            sp.getServer().runOnServer(server -> {
                for (var e : build.entrySet()) {
                    if (!e.getValue().is(Blocks.NOTE_BLOCK)) continue;
                    BlockPos below = e.getKey().below();
                    BlockState floor = server.overworld().getBlockState(below);
                    server.overworld().setBlockAndUpdate(below, Blocks.AIR.defaultBlockState());
                    server.overworld().setBlockAndUpdate(below, floor);
                }
            });
            context.waitTicks(60);
            Map<BlockPos, BlockState> expected = sp.getServer().computeOnServer(server -> {
                Map<BlockPos, BlockState> m = new LinkedHashMap<>();
                for (BlockPos p : BlockPos.betweenClosed(min, max)) m.put(p.immutable(), server.overworld().getBlockState(p));
                return m;
            });
            long locked = expected.values().stream().filter(s -> s.is(Blocks.REPEATER) && s.getValue(RepeaterBlock.LOCKED)).count();
            long powered = expected.values().stream().filter(s -> s.is(Blocks.REPEATER) && s.getValue(RepeaterBlock.POWERED)).count();
            GT.log("[redstone] source built: repeaters powered=" + powered + " locked=" + locked);
            // 2. 清空，写进投影
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(BASE.offset(0, 1, 0), max)) server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                for (BlockPos p : BlockPos.betweenClosed(BASE, BASE.offset(26, 0, 30))) server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            });
            context.waitTicks(20);
            GT.setSchematicStable(context, min, max, p -> expected.getOrDefault(p, Blocks.AIR.defaultBlockState()));
            long nonAir = expected.values().stream().filter(s -> !s.isAir()).count();

            // 3. 打印
            context.runOnClient(c -> {
                Configs.Core.WORK_RANGE.setDoubleValue(30);
                Configs.Print.LAYERED_MODE.setBooleanValue(true);
                Configs.Print.NOTE_BLOCK_TUNING.setBooleanValue(true);
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(8);
                GT.enablePrint();
            });
            int t = 0, lastBad = -1, lastChange = 0;
            for (t = 20; t <= 3000; t += 20) {
                context.waitTicks(20);
                context.runOnClient(c -> {
                    var ws = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
                    for (var e : expected.entrySet()) {
                        if (!ws.getBlockState(e.getKey()).equals(e.getValue())) {
                            GT.setSchematic(min, max, p -> expected.getOrDefault(p, Blocks.AIR.defaultBlockState()));
                            break;
                        }
                    }
                });
                int bad = compare(sp, expected).size();
                if (bad != lastBad) {
                    lastBad = bad;
                    lastChange = t;
                }
                if (t % 200 == 0) GT.log("[redstone] t" + t + " remaining=" + bad + " " + context.computeOnClient(c -> com.autyism.printer.handler.ModuleManager.PRINT.debugState()));
                if (bad == 0 || t - lastChange >= 400) break;
            }
            context.runOnClient(c -> GT.disableAll());
            context.waitTicks(10);
            List<String> wrong = compare(sp, expected);
            Map<String, Integer> kinds = new TreeMap<>();
            for (String w : wrong) kinds.merge(w.substring(0, w.indexOf(" @")), 1, Integer::sum);
            kinds.forEach((k, v) -> GT.log("[redstone]   " + v + "x " + k));
            for (int i = 0; i < Math.min(10, wrong.size()); i++) GT.log("[redstone] WRONG " + wrong.get(i));
            long advMissing = sp.getServer().computeOnServer(server -> adversarial.stream().filter(p -> server.overworld().getBlockState(p).isAir()).count());
            GT.log("[redstone] adversarial side-by-side ramps: " + (adversarial.size() - advMissing) + "/" + adversarial.size() + " placed, " + advMissing + " left empty, 0 wrong");
            GT.log("[redstone] RESULT " + (nonAir - wrong.size()) + "/" + nonAir + " correct, ticks=" + t);
            if (!wrong.isEmpty()) throw new AssertionError("[redstone] " + wrong.size() + " blocks wrong, first: " + wrong.get(0));
        } finally {
            context.runOnClient(c -> GT.disableAll());
            TestSchematicRegion.clear();
        }
    }

    /** 逐格比较（忽略电路运行时属性），返回问题列表：“类型 方块 属性 @ 坐标” */
    private List<String> compare(TestSingleplayerContext sp, Map<BlockPos, BlockState> expected) {
        return sp.getServer().computeOnServer(server -> {
            List<String> out = new ArrayList<>();
            for (var e : expected.entrySet()) {
                BlockState want = e.getValue();
                if (want.isAir()) continue;
                BlockState have = server.overworld().getBlockState(e.getKey());
                String id = BuiltInRegistries.BLOCK.getKey(want.getBlock()).getPath();
                if (have.isAir() && adversarial.contains(e.getKey())) continue;
                if (have.getBlock() != want.getBlock()) {
                    out.add((have.isAir() ? "MISSING " : "WRONG_BLOCK ") + id + " @" + e.getKey().toShortString() + " want=" + want);
                    continue;
                }
                StringBuilder diff = new StringBuilder();
                for (Property<?> p : want.getProperties()) {
                    if (RUNTIME.contains(p.getName())) continue;
                    if (!want.getValue(p).equals(have.getValue(p))) diff.append(p.getName()).append(' ');
                }
                if (!diff.isEmpty()) out.add("WRONG_STATE " + id + " " + diff.toString().trim() + " @" + e.getKey().toShortString()
                        + " want=" + want + " have=" + have);
            }
            return out;
        });
    }
}
