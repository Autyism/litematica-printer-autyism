package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.handler.ModuleManager;
import com.autyism.printer.printer.MissingMaterialTracker;
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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 打印含水方块（-Pgt=waterlog；选项关闭时的对照：-Pgt=waterlogoff）。
 * <p>
 * 一个 7x7 的格子阵：每格一个含水方块（各种形状），四周是石墙、下面是石头地板，水流不出去 → 应该被加上水。
 * 另外几格故意留口子：投影里一侧是空的（楼梯开着的一侧、顶部台阶下面没有地板）→ 永远不加水，也不能卡住打印；
 * 楼梯背面（整面）朝着空格 → 封住的，照样加水；一侧的墙是闪长岩、先跳过不打印 → 先等，墙打好之后再加水。
 * 最后检查：哪里都没有漏出来的水，打印进度 100%。再在生存模式下测水桶用完、缺水桶显示在缺失材料 HUD、补上后继续。
 */
@SuppressWarnings("UnstableApiUsage")
public final class WaterlogGameTest implements FabricClientGameTest {
    private static final int W = 7, H = 7;
    private static final BlockPos ORIGIN = new BlockPos(1000, 64, 0);

    /** 一格：位置、投影里的状态、最后应不应该含水 */
    private record Cell(BlockPos pos, BlockState state, boolean wet) {
    }

    private static BlockPos cell(int i, int j) {
        return ORIGIN.offset(2 * i + 1, 1, 2 * j + 1);
    }

    private static BlockState wet(BlockState s) {
        return s.setValue(BlockStateProperties.WATERLOGGED, true);
    }

    private static BlockState chain() {
        //? if <1.21.9 {
        /*return Blocks.CHAIN.defaultBlockState();
        *///?} else
        return Blocks.IRON_CHAIN.defaultBlockState();
    }

    /** 正常的格子（都应该加上水） */
    private static List<BlockState> normalStates() {
        List<BlockState> l = new ArrayList<>();
        l.add(Blocks.OAK_SLAB.defaultBlockState());
        l.add(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (Half h : Half.values()) l.add(Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, d).setValue(StairBlock.HALF, h));
        }
        l.add(Blocks.OAK_FENCE.defaultBlockState());
        l.add(Blocks.COBBLESTONE_WALL.defaultBlockState());
        l.add(Blocks.GLASS_PANE.defaultBlockState());
        l.add(Blocks.IRON_BARS.defaultBlockState());
        l.add(chain());
        l.add(Blocks.LANTERN.defaultBlockState());
        for (boolean open : new boolean[]{false, true}) {
            for (Half h : Half.values()) l.add(Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, open).setValue(TrapDoorBlock.HALF, h));
        }
        l.add(Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        l.add(Blocks.OAK_SIGN.defaultBlockState());
        l.add(Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.NORTH));
        l.add(Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState().setValue(WallHangingSignBlock.FACING, Direction.NORTH));
        l.add(Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        l.add(Blocks.RAIL.defaultBlockState());
        l.add(Blocks.CANDLE.defaultBlockState());
        l.add(Blocks.DEAD_TUBE_CORAL_FAN.defaultBlockState());
        l.add(Blocks.LIGHTNING_ROD.defaultBlockState());
        l.add(Blocks.COPPER_GRATE.defaultBlockState());
        l.add(Blocks.POINTED_DRIPSTONE.defaultBlockState());
        l.add(Blocks.AMETHYST_CLUSTER.defaultBlockState());
        l.add(Blocks.MANGROVE_ROOTS.defaultBlockState());
        l.add(Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0).setValue(ScaffoldingBlock.BOTTOM, false));
        l.add(Blocks.SEA_PICKLE.defaultBlockState());
        l.add(Blocks.CONDUIT.defaultBlockState());
        l.add(Blocks.DECORATED_POT.defaultBlockState());
        l.add(Blocks.HEAVY_CORE.defaultBlockState());
        l.add(Blocks.GLOW_LICHEN.defaultBlockState().setValue(BlockStateProperties.DOWN, true));
        return l;
    }

    /** 挂着的方块：上面要有锁链（世界里预先放好，不在投影里） */
    private static final List<BlockState> HANGING = List.of(
            Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true),
            // 挂在锁链上的悬挂告示牌是“attached”的样子（原版放置时就是这样）
            Blocks.OAK_HANGING_SIGN.defaultBlockState().setValue(BlockStateProperties.ATTACHED, true));

    private static final BlockPos E1 = cell(0, 0);       // 西边的墙是闪长岩：先跳过不打印 → 等，墙打好之后加水
    private static final BlockPos E2A = cell(W - 1, 1);  // 东边投影里是空的，楼梯开着的一侧朝东 → 永远不加水
    private static final BlockPos E2C = cell(W - 1, 2);  // 东边投影里是空的，楼梯背面（整面）朝东 → 封住的，加水
    private static final BlockPos E2B = cell(W - 1, 3);  // 顶部台阶，下面地板投影里是空的 → 永远不加水

    private static List<Cell> layout() {
        List<Cell> cells = new ArrayList<>();
        cells.add(new Cell(E1, wet(Blocks.OAK_SLAB.defaultBlockState()), true));
        cells.add(new Cell(E2A, wet(Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH)), false));
        cells.add(new Cell(E2C, wet(Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST)), true));
        cells.add(new Cell(E2B, wet(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)), false));
        cells.add(new Cell(cell(3, 2), wet(HANGING.get(0)), true));
        cells.add(new Cell(cell(3, 4), wet(HANGING.get(1)), true));
        List<BlockState> normal = normalStates();
        int k = 0;
        for (int j = 0; j < H && k < normal.size(); j++) {
            for (int i = 0; i < W && k < normal.size(); i++) {
                BlockPos p = cell(i, j);
                if (cells.stream().anyMatch(c -> c.pos.equals(p))) continue;
                cells.add(new Cell(p, wet(normal.get(k++)), true));
            }
        }
        if (k < normal.size()) throw new AssertionError("[waterlog] grid too small for " + normal.size() + " states");
        return cells;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean on = GTFilter.enabled("waterlog");
        boolean off = GTFilter.enabled("waterlogoff");
        if (System.getProperty("ale.gt", "").isBlank() || (!on && !off)) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.randomTicksOff(sp);
            if (on) {
                grid(context, sp, true);
                survival(context, sp);
            } else {
                grid(context, sp, false);
            }
        } finally {
            GT.removeAllPlacements(context);
            context.runOnClient(c -> {
                GT.disableAll();
                Configs.Print.PRINT_WATERLOGGED.setBooleanValue(false);
                Configs.Print.PRINT_SKIP.setBooleanValue(false);
                Configs.Print.PRINT_SKIP_LIST.setStrings(List.of());
                Configs.Core.RENDER_HUD.setBooleanValue(false);
            });
        }
    }

    private static void grid(ClientGameTestContext context, TestSingleplayerContext sp, boolean option) {
        List<Cell> cells = layout();
        Map<BlockPos, Cell> byPos = new HashMap<>();
        for (Cell c : cells) byPos.put(c.pos, c);
        BlockPos max = ORIGIN.offset(2 * W, 1, 2 * H);
        sp.getServer().runCommand("gamemode creative @a");
        GT.clearArena(sp, ORIGIN.getX() - 3, ORIGIN.getZ() - 3, max.getX() + 3, max.getZ() + 3, 80);
        double cx = ORIGIN.getX() + W + 0.5, cz = ORIGIN.getZ() + H + 0.5;
        sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f 75 %.1f 0 90", cx, cz));
        context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - cx) < 0.01, 200);
        context.runOnClient(c -> {
            c.player.getAbilities().flying = true;
            c.player.onUpdateAbilities();
        });
        context.waitTicks(20);
        // 1. 在世界里搭好“原样”（含水），截成投影
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            for (BlockPos p : BlockPos.betweenClosed(ORIGIN, max)) {
                BlockState s;
                if (p.getY() == ORIGIN.getY()) {
                    s = p.equals(E2B.below()) ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState();
                } else if (byPos.containsKey(p)) {
                    s = byPos.get(p).state;
                } else if (p.equals(E2A.east()) || p.equals(E2C.east())) {
                    s = Blocks.AIR.defaultBlockState();
                } else if (p.equals(E1.west())) {
                    s = Blocks.DIORITE.defaultBlockState();
                } else {
                    s = Blocks.STONE.defaultBlockState();
                }
                level.setBlock(p.immutable(), s, flags);
            }
            // 挂着的方块上面的锁链（不在投影范围里）
            level.setBlock(cell(3, 2).above(), chain(), flags);
            level.setBlock(cell(3, 4).above(), chain(), flags);
            for (Cell c : cells) {
                level.setBlock(c.pos, Block.updateFromNeighbourShapes(level.getBlockState(c.pos), level, c.pos), flags);
            }
        });
        Map<BlockPos, BlockState> expected = sp.getServer().computeOnServer(server -> {
            Map<BlockPos, BlockState> m = new HashMap<>();
            for (BlockPos p : BlockPos.betweenClosed(ORIGIN, max)) m.put(p.immutable(), server.overworld().getBlockState(p));
            return m;
        });
        for (Cell c : cells) {
            BlockState s = expected.get(c.pos);
            if (!s.hasProperty(BlockStateProperties.WATERLOGGED) || !s.getValue(BlockStateProperties.WATERLOGGED)) {
                throw new AssertionError("[waterlog] source block at " + c.pos.toShortString() + " is " + s + ", wanted " + c.state);
            }
        }
        GT.captureAndPlace(context, sp, ORIGIN, max, ORIGIN, "ale_test_waterlog");
        sp.getServer().runOnServer(server -> {
            for (BlockPos p : BlockPos.betweenClosed(ORIGIN, max)) {
                server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        });
        GT.waitSchematicBlock(context, cell(0, 1), expected.get(cell(0, 1)).getBlock());
        sp.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 20");
        context.waitTicks(10);

        // 2. 打印
        context.runOnClient(c -> {
            Configs.Core.WORK_RANGE.setDoubleValue(20);
            Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(4);
            Configs.Print.PRINT_WATERLOGGED.setBooleanValue(option);
            Configs.Print.PRINT_SKIP.setBooleanValue(option);
            Configs.Print.PRINT_SKIP_LIST.setStrings(option ? List.of("minecraft:diorite") : List.of());
            Configs.Core.RENDER_HUD.setBooleanValue(true);
            GT.enablePrint();
        });
        int maxTicks = Integer.getInteger("ale.ticks", 3600);
        if (!option) {
            // 选项关闭：方块照常放（不含水），不用水桶，哪里都没有水
            GT.waitServer(context, () -> sp.getServer().computeOnServer(server -> {
                for (Cell c : cells) if (server.overworld().getBlockState(c.pos).getBlock() != c.state.getBlock()) return false;
                return true;
            }), maxTicks, "[waterlog-off] blocks were not placed");
            context.waitTicks(100);
            List<String> water = strayWater(sp, ORIGIN, max, byPos, true);
            long wetCount = sp.getServer().computeOnServer(server -> cells.stream().filter(c -> server.overworld().getBlockState(c.pos).getValue(BlockStateProperties.WATERLOGGED)).count());
            GT.log("[waterlog-off] " + cells.size() + " blocks placed, waterlogged=" + wetCount + ", water elsewhere=" + water.size());
            if (wetCount > 0 || !water.isEmpty()) throw new AssertionError("[waterlog-off] water appeared with the option off: " + water);
            GT.log("[waterlog-off] OK: option off behaves as before (blocks placed dry, no water used)");
            return;
        }
        // 闪长岩墙先不打：E1 只能等；其余全部完成
        int ticks;
        try {
            ticks = GT.waitServer(context, () -> sp.getServer().computeOnServer(server -> {
                for (Cell c : cells) {
                    if (c.pos.equals(E1)) continue;
                    BlockState s = server.overworld().getBlockState(c.pos);
                    if (s.getBlock() != c.state.getBlock() || s.getValue(BlockStateProperties.WATERLOGGED) != c.wet) return false;
                }
                return true;
            }), maxTicks, "[waterlog] waterloggable blocks were not finished");
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + ": " + describe(sp, cells));
        }
        context.waitTicks(60);
        String e1 = sp.getServer().computeOnServer(server -> server.overworld().getBlockState(E1).toString());
        List<String> stray = strayWater(sp, ORIGIN, max, byPos, false);
        GT.log("[waterlog] phase 1: " + (cells.size() - 1) + " blocks done in " + ticks + " ticks; waiting slab next to the skipped wall: " + e1 + "; stray water=" + stray);
        if (!stray.isEmpty()) throw new AssertionError("[waterlog] water escaped: " + stray);
        if (e1.contains("waterlogged=true")) throw new AssertionError("[waterlog] waterlogged although its wall was missing: " + e1);
        if (!e1.contains("oak_slab")) throw new AssertionError("[waterlog] waiting slab was not placed: " + e1);
        // 墙打好之后加水
        context.runOnClient(c -> Configs.Print.PRINT_SKIP.setBooleanValue(false));
        int ticks2 = GT.waitServer(context, () -> sp.getServer().computeOnServer(server ->
                server.overworld().getBlockState(E1).getValue(BlockStateProperties.WATERLOGGED)
                        && server.overworld().getBlockState(E1.west()).is(Blocks.DIORITE)), maxTicks, "[waterlog] slab was not filled after its wall was printed");
        GT.log("[waterlog] phase 2: wall printed and slab filled after " + ticks2 + " ticks");
        // 留口子的两格永远不含水，但不能挡住“完成”
        double progress = 0;
        for (int t = 0; t < 400 && progress < 1.0; t++) {
            context.waitTick();
            progress = context.computeOnClient(c -> ModuleManager.GUI.getPrintProgress().getProgress());
        }
        context.runOnClient(c -> GT.disableAll());
        context.waitTicks(20);
        List<String> wrong = new ArrayList<>();
        sp.getServer().runOnServer(server -> {
            for (Cell c : cells) {
                BlockState s = server.overworld().getBlockState(c.pos);
                if (s.getBlock() != c.state.getBlock() || s.getValue(BlockStateProperties.WATERLOGGED) != c.wet) wrong.add(c.pos.toShortString() + " " + s);
            }
        });
        stray = strayWater(sp, ORIGIN, max, byPos, false);
        long wetDone = cells.stream().filter(Cell::wet).count();
        GT.log("[waterlog] RESULT " + (cells.size() - wrong.size()) + "/" + cells.size() + " correct (" + wetDone + " waterlogged, "
                + (cells.size() - wetDone) + " left dry because the schematic leaves a side open), stray water=" + stray.size() + ", progress=" + progress);
        if (!wrong.isEmpty()) throw new AssertionError("[waterlog] wrong: " + wrong);
        if (!stray.isEmpty()) throw new AssertionError("[waterlog] water escaped: " + stray);
        if (progress < 1.0) throw new AssertionError("[waterlog] printing never reached 100% (dry blocks blocked it?): " + progress);
        GT.removeAllPlacements(context);
    }

    /** 投影范围（和外面一圈）里，不是该含水的格子却有水 */
    private static List<String> strayWater(TestSingleplayerContext sp, BlockPos min, BlockPos max, Map<BlockPos, Cell> cells, boolean anyWater) {
        return sp.getServer().computeOnServer(server -> {
            List<String> out = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(min.offset(-2, -1, -2), max.offset(2, 2, 2))) {
                BlockState s = server.overworld().getBlockState(p);
                if (s.getFluidState().isEmpty()) continue;
                Cell c = cells.get(p.immutable());
                if (!anyWater && c != null && c.wet) continue;
                out.add(p.toShortString() + " " + s);
            }
            return out;
        });
    }

    private static String describe(TestSingleplayerContext sp, List<Cell> cells) {
        return sp.getServer().computeOnServer(server -> {
            StringBuilder sb = new StringBuilder();
            for (Cell c : cells) {
                BlockState s = server.overworld().getBlockState(c.pos);
                if (s.getBlock() != c.state.getBlock() || (s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED) != c.wet)) {
                    sb.append(c.pos.toShortString()).append('=').append(s).append(" (want ").append(c.wet ? "wet " : "dry ").append(c.state.getBlock()).append("); ");
                }
            }
            return sb.toString();
        });
    }

    /** 生存模式：水桶会用掉；没有水桶时缺失材料 HUD 里显示水桶；补上之后继续 */
    private static void survival(ClientGameTestContext context, TestSingleplayerContext sp) {
        BlockPos o = new BlockPos(1000, 64, 40);
        BlockPos a = o.offset(1, 1, 1), b = o.offset(3, 1, 1), max = o.offset(4, 1, 2);
        GT.clearArena(sp, o.getX() - 3, o.getZ() - 3, max.getX() + 3, max.getZ() + 4, 80);
        sp.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(o.offset(2, 1, 4), Blocks.BEDROCK.defaultBlockState()));
        sp.getServer().runCommand("tp @a " + (o.getX() + 2.5) + " 66 " + (o.getZ() + 4.5) + " 180 50");
        context.waitFor(c -> c.player != null && Math.abs(c.player.getZ() - (o.getZ() + 4.5)) < 0.01, 200);
        context.waitTicks(20);
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            for (BlockPos p : BlockPos.betweenClosed(o, max)) {
                level.setBlock(p.immutable(), p.getY() == o.getY() || (!p.equals(a) && !p.equals(b)) ? Blocks.STONE.defaultBlockState()
                        : wet(Blocks.OAK_FENCE.defaultBlockState()), flags);
            }
            for (BlockPos p : List.of(a, b)) level.setBlock(p, Block.updateFromNeighbourShapes(level.getBlockState(p), level, p), flags);
            level.setBlock(o.offset(2, 1, 4), Blocks.BEDROCK.defaultBlockState(), flags); // 站的地方（不在投影里）
        });
        GT.captureAndPlace(context, sp, o, max, o, "ale_test_waterlog_survival");
        sp.getServer().runOnServer(server -> {
            for (BlockPos p : BlockPos.betweenClosed(o, max)) server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            player.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
            player.getInventory().setItem(10, new ItemStack(Items.OAK_FENCE, 64));
            player.getInventory().setItem(11, new ItemStack(Items.WATER_BUCKET));
            player.inventoryMenu.sendAllDataToRemote();
        });
        sp.getServer().runCommand("gamemode survival @a");
        GT.waitSchematicBlock(context, a, Blocks.OAK_FENCE);
        context.waitTicks(10);
        context.runOnClient(c -> {
            Configs.Print.PRINT_WATERLOGGED.setBooleanValue(true);
            Configs.Print.PRINT_SKIP.setBooleanValue(false);
            GT.enablePrint();
        });
        GT.waitServer(context, () -> sp.getServer().computeOnServer(server -> {
            int wetCount = 0;
            for (BlockPos p : List.of(a, b)) {
                BlockState s = server.overworld().getBlockState(p);
                if (!s.is(Blocks.OAK_FENCE)) return false;
                if (s.getValue(BlockStateProperties.WATERLOGGED)) wetCount++;
            }
            return wetCount == 1;
        }), 1200, "[waterlog/survival] first fence was not filled");
        boolean hudShows = false;
        for (int t = 0; t < 100 && !hudShows; t++) {
            context.waitTick();
            hudShows = context.computeOnClient(c -> MissingMaterialTracker.getInstance().getMissing().stream().anyMatch(e -> e.item == Items.WATER_BUCKET));
        }
        if (!hudShows) throw new AssertionError("[waterlog/survival] the missing water bucket was not shown in the missing-material HUD");
        sp.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(12, new ItemStack(Items.WATER_BUCKET));
            player.inventoryMenu.sendAllDataToRemote();
        });
        int ticks = GT.waitServer(context, () -> sp.getServer().computeOnServer(server ->
                server.overworld().getBlockState(a).getValue(BlockStateProperties.WATERLOGGED) && server.overworld().getBlockState(b).getValue(BlockStateProperties.WATERLOGGED)), 1200,
                "[waterlog/survival] second fence was not filled after a bucket was added");
        int empty = sp.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getInventory().countItem(Items.BUCKET));
        context.runOnClient(c -> GT.disableAll());
        List<String> stray = strayWater(sp, o, max, Map.of(a, new Cell(a, null, true), b, new Cell(b, null, true)), false);
        GT.log("[waterlog/survival] OK: bucket used up, missing water bucket shown in the HUD, filled " + ticks + " ticks after adding one; empty buckets=" + empty + ", stray water=" + stray.size());
        if (empty != 2) throw new AssertionError("[waterlog/survival] expected 2 empty buckets, got " + empty);
        if (!stray.isEmpty()) throw new AssertionError("[waterlog/survival] water escaped: " + stray);
    }
}
