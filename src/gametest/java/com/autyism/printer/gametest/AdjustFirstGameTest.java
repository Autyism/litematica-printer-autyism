package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.handler.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调整完再放下一个（默认）：放下一个还要点几下才对的方块（中继器延迟、比较器模式、音符盒音高、阳光探测器、拉杆、活板门、栅栏门、
 * 蜡烛、海泡菜、雪层、双层台阶、花盆、花簇、耕地），服务端处理的先后顺序里，从这个方块放下到它完全调好之间不能放任何别的方块。
 * 然后把设置关掉再打一遍：旧的顺序（先放完再调整），应该看得到中间放了别的方块；两次最后都必须完全正确。
 * 用 BlockLog 按服务端处理顺序记下每一次方块变化。生存模式、材料在背包里（要从背包换到手上）。可以加 -Plag / -Pnoghost 跑。
 */
@SuppressWarnings("UnstableApiUsage")
public final class AdjustFirstGameTest implements FabricClientGameTest {
    private static final BlockPos BASE = new BlockPos(700, 65, 700);
    /** 运行时会自己变的属性（红石信号），不比较；拉杆的 powered 是它自己的状态，要比较 */
    private static final Set<String> RUNTIME = Set.of("powered", "power", "locked", "in_wall");

    private final Map<BlockPos, BlockState> schematic = new LinkedHashMap<>();
    /** 要调整的方块 */
    private final List<BlockPos> adjustable = new ArrayList<>();
    /** 世界里的地面（不打印） */
    private final Map<BlockPos, BlockState> floor = new LinkedHashMap<>();

    private void cell(int x, int z, BlockState target) {
        BlockPos pos = BASE.offset(x, 0, z);
        schematic.put(pos, target);
        adjustable.add(pos);
        // 每个要调整的方块后面跟一块玻璃：扫描顺序里紧接着就是“别的方块”
        schematic.put(pos.east(), Blocks.GLASS.defaultBlockState());
    }

    private void design() {
        if (!schematic.isEmpty()) return;
        for (int x = -3; x <= 14; x++) {
            for (int z = -5; z <= 6; z++) floor.put(BASE.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        }
        BlockState note = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.BASEDRUM);
        // 第一排：红石
        cell(0, 0, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, 2));
        cell(2, 0, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, 4));
        cell(4, 0, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST).setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT));
        cell(6, 0, note.setValue(NoteBlock.NOTE, 3));
        cell(8, 0, note.setValue(NoteBlock.NOTE, 12));
        cell(10, 0, Blocks.DAYLIGHT_DETECTOR.defaultBlockState().setValue(DaylightDetectorBlock.INVERTED, true));
        // 第二排：开关、数量
        cell(0, 2, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH)
                .setValue(LeverBlock.POWERED, true));
        cell(2, 2, Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.NORTH).setValue(TrapDoorBlock.OPEN, true));
        cell(4, 2, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH).setValue(FenceGateBlock.OPEN, true));
        cell(6, 2, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3));
        cell(8, 2, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true));
        cell(10, 2, Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 3).setValue(SeaPickleBlock.WATERLOGGED, false));
        // 第三排：再放一次 / 转换
        cell(0, 4, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 4));
        cell(2, 4, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
        cell(4, 4, Blocks.POTTED_DANDELION.defaultBlockState());
        cell(6, 4, Blocks.PINK_PETALS.defaultBlockState().setValue(BlockStateProperties.FLOWER_AMOUNT, 3)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        floor.put(BASE.offset(6, -1, 4), Blocks.GRASS_BLOCK.defaultBlockState());
        cell(8, 4, Blocks.FARMLAND.defaultBlockState());
    }

    private static final Item[] ITEMS = {Items.REPEATER, Items.COMPARATOR, Items.NOTE_BLOCK, Items.DAYLIGHT_DETECTOR, Items.LEVER,
            Items.OAK_TRAPDOOR, Items.OAK_FENCE_GATE, Items.CANDLE, Items.FLINT_AND_STEEL, Items.SEA_PICKLE, Items.SNOW, Items.OAK_SLAB,
            Items.FLOWER_POT, Items.DANDELION, Items.PINK_PETALS, Items.DIRT, Items.IRON_HOE, Items.GLASS};

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("adjust")) return;
        if (!Configs.Print.ADJUST_FIRST.getDefaultBooleanValue()) throw new AssertionError("[adjust] Finish Adjusting First should be on by default");
        design();
        BlockPos min = BASE.offset(-1, 0, -1), max = BASE.offset(12, 0, 5);
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.randomTicksOff(sp);
            GT.clearArena(sp, BASE.getX() - 6, BASE.getZ() - 8, BASE.getX() + 18, BASE.getZ() + 10, 75);
            sp.getServer().runOnServer(server -> {
                for (var e : floor.entrySet()) server.overworld().setBlockAndUpdate(e.getKey(), e.getValue());
            });
            double px = BASE.getX() + 6.0, pz = BASE.getZ() - 2.5;
            sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f %d %.1f 0 30", px, BASE.getY(), pz));
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - px) < 0.01 && c.level.getBlockState(BASE.below()).is(Blocks.STONE), 200);
            GT.setSchematicStable(context, min, max, p -> schematic.getOrDefault(p, Blocks.AIR.defaultBlockState()));

            String lag = System.getProperty("ale.lag", "") + (System.getProperty("ale.noghost", "").isBlank() ? "" : " noghost=" + System.getProperty("ale.noghost"));
            Result first = print(context, sp, min, max, true);
            GT.log("[adjust] adjust first (default)" + (lag.isBlank() ? "" : ", lag " + lag) + ": " + first.summary());
            if (!first.wrong.isEmpty()) throw new AssertionError("[adjust] wrong after printing: " + first.wrong);
            if (!first.violations.isEmpty()) {
                throw new AssertionError("[adjust] another block was placed while a block was still being adjusted: " + first.violations.subList(0, Math.min(10, first.violations.size())));
            }

            // 旧的顺序：先把方块都放完，再回来调整
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : schematic.keySet()) server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            });
            context.waitTicks(10);
            Result old;
            try {
                old = print(context, sp, min, max, false);
            } finally {
                // 退出世界之前改回来（MaLiLib 退出世界时会保存设置）
                context.runOnClient(c -> Configs.Print.ADJUST_FIRST.setBooleanValue(true));
            }
            GT.log("[adjust] old order (setting off): " + old.summary());
            if (!old.wrong.isEmpty()) throw new AssertionError("[adjust] wrong after printing in the old order: " + old.wrong);
            if (old.violations.isEmpty()) throw new AssertionError("[adjust] the old order did not place other blocks before adjusting (setting has no effect?)");
            GT.log("[adjust] OK: " + adjustable.size() + " blocks adjusted before the next block in the default order ("
                    + first.ticks + " ticks), old order still available (" + old.violations.size() + " placements in between, " + old.ticks + " ticks)");
        } finally {
            BlockLog.stop();
            context.runOnClient(c -> {
                GT.disableAll();
                Configs.Print.ADJUST_FIRST.setBooleanValue(true);
            });
            TestSchematicRegion.clear();
        }
    }

    private record Result(int ticks, List<String> wrong, List<String> violations, Map<BlockPos, int[]> timing) {
        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(ticks).append(" ticks, wrong=").append(wrong.size()).append(", other blocks placed while adjusting=").append(violations.size());
            int maxSpan = 0;
            for (int[] t : timing.values()) maxSpan = Math.max(maxSpan, t[1] - t[0]);
            sb.append(", longest placement-to-done=").append(maxSpan).append(" server ticks");
            return sb.toString();
        }
    }

    private Result print(ClientGameTestContext context, TestSingleplayerContext sp, BlockPos min, BlockPos max, boolean adjustFirst) {
        sp.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            // 材料都放在背包后面的格子里：每种都要先换到手上
            for (int i = 0; i < ITEMS.length; i++) {
                player.getInventory().setItem(9 + i, new ItemStack(ITEMS[i], ITEMS[i].getDefaultMaxStackSize()));
            }
            player.inventoryMenu.sendAllDataToRemote();
        });
        context.waitFor(c -> c.player.getInventory().getItem(9).is(ITEMS[0]), 200);
        BlockLog.start(min, max);
        context.runOnClient(c -> {
            Configs.Core.WORK_RANGE.setDoubleValue(10);
            Configs.Print.ADJUST_FIRST.setBooleanValue(adjustFirst);
            Configs.Print.LAYERED_MODE.setBooleanValue(true);
            Configs.Print.NOTE_BLOCK_TUNING.setBooleanValue(true);
            GT.enablePrint();
        });
        int maxTicks = 3000;
        int ticks;
        try {
            ticks = GT.waitServer(context, () -> wrong(sp).isEmpty(), maxTicks, "[adjust] printing (adjustFirst=" + adjustFirst + ") did not finish");
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + ": wrong=" + wrong(sp) + " state=" + context.computeOnClient(c -> ModuleManager.PRINT.debugState()));
        }
        context.runOnClient(c -> GT.disableAll());
        context.waitTicks(20);
        BlockLog.stop();
        List<String> wrong = wrong(sp);
        // 按服务端处理顺序检查：每个要调整的方块，从放下到最后一次变成正确之间，别的格子有没有放下新方块
        List<BlockLog.Change> log = BlockLog.changes();
        List<String> violations = new ArrayList<>();
        Map<BlockPos, int[]> timing = new LinkedHashMap<>();
        for (BlockPos pos : adjustable) {
            BlockState want = schematic.get(pos);
            int placed = -1, doneSince = -1;
            int placedTick = 0, doneTick = 0;
            for (BlockLog.Change ch : log) {
                if (!ch.pos().equals(pos)) continue;
                if (placed < 0 && ch.before().isAir() && !ch.after().isAir()) {
                    placed = ch.index();
                    placedTick = ch.tick();
                }
                if (placed < 0) continue;
                if (same(want, ch.after())) {
                    if (doneSince < 0) {
                        doneSince = ch.index();
                        doneTick = ch.tick();
                    }
                } else {
                    doneSince = -1;
                }
            }
            if (placed < 0 || doneSince < 0) {
                violations.add(id(want) + " @" + pos.toShortString() + " never placed / never finished in the log");
                continue;
            }
            timing.put(pos, new int[]{placedTick, doneTick});
            for (BlockLog.Change ch : log) {
                if (ch.index() <= placed || ch.index() >= doneSince || ch.pos().equals(pos)) continue;
                if (ch.before().isAir() && !ch.after().isAir()) {
                    violations.add(id(ch.after()) + " placed @" + ch.pos().toShortString() + " while " + id(want) + " @" + pos.toShortString()
                            + " was being adjusted (placed #" + placed + ", done #" + doneSince + ", this #" + ch.index() + ")");
                }
            }
        }
        for (var e : timing.entrySet()) {
            GT.log("[adjust]   " + id(schematic.get(e.getKey())) + " @" + e.getKey().toShortString() + ": placed at server tick " + e.getValue()[0]
                    + ", finished at " + e.getValue()[1] + (adjustFirst ? "" : " (old order)"));
        }
        return new Result(ticks, wrong, violations, timing);
    }

    private List<String> wrong(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            List<String> out = new ArrayList<>();
            for (var e : schematic.entrySet()) {
                BlockState have = server.overworld().getBlockState(e.getKey());
                if (!same(e.getValue(), have)) out.add(e.getKey().toShortString() + " want " + e.getValue() + " have " + have);
            }
            return out;
        });
    }

    /** 方块相同、除了红石运行时的属性以外都相同（拉杆自己的开关要相同） */
    private static boolean same(BlockState want, BlockState have) {
        if (want.getBlock() != have.getBlock()) return false;
        for (Property<?> p : want.getProperties()) {
            if (RUNTIME.contains(p.getName()) && !(want.getBlock() instanceof LeverBlock)) continue;
            if (!want.getValue(p).equals(have.getValue(p))) return false;
        }
        return true;
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }
}
