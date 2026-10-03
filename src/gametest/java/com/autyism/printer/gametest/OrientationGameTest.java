package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 朝向回归测试：各种“由视角 / 点击位置决定状态”的方块全部打印一遍，逐个核对状态。
 * 复杂建筑自测里发现过门轴反了、藤蔓贴错面、拉杆 / 活塞朝向不对，这里集中覆盖。
 */
@SuppressWarnings("UnstableApiUsage")
public final class OrientationGameTest implements FabricClientGameTest {
    private static final BlockPos BASE = new BlockPos(500, 64, 500);
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final Map<BlockPos, BlockState> states = new HashMap<>();
    /** 门轴在中间的双开门（原版做不到两扇都对） */
    private final List<BlockPos[]> innerPairs = new java.util.ArrayList<>();
    private int cursor;

    /** 每个用例占一个 3x3 的格子，中心放测试方块 */
    private BlockPos next() {
        int i = cursor++;
        return BASE.offset((i % 12) * 3, 0, (i / 12) * 3);
    }

    private void put(BlockPos p, BlockState s) {
        states.put(p, s);
    }

    private void door(BlockPos p, net.minecraft.world.level.block.Block block, Direction facing, DoorHingeSide hinge) {
        BlockState lower = block.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        put(p, lower);
        put(p.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private void build() {
        for (var block : List.of(Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.OBSERVER, Blocks.DISPENSER, Blocks.DROPPER)) {
            for (Direction d : Direction.values()) {
                BlockState s = block.defaultBlockState().setValue(BlockStateProperties.FACING, d);
                if (block instanceof PistonBaseBlock) s = s.setValue(PistonBaseBlock.EXTENDED, false);
                put(next(), s);
            }
        }
        for (var block : List.of(Blocks.LEVER, Blocks.STONE_BUTTON)) {
            for (Direction d : HORIZONTAL) {
                BlockPos p = next();
                put(p.relative(d.getOpposite()), Blocks.STONE.defaultBlockState());
                put(p, block.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL).setValue(BlockStateProperties.HORIZONTAL_FACING, d));
            }
            for (Direction d : HORIZONTAL) {
                BlockPos p = next();
                put(p, block.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR).setValue(BlockStateProperties.HORIZONTAL_FACING, d));
            }
            for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST}) {
                BlockPos p = next();
                put(p.above(), Blocks.STONE.defaultBlockState());
                put(p, block.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.CEILING).setValue(BlockStateProperties.HORIZONTAL_FACING, d));
            }
        }
        for (Direction d : HORIZONTAL) {
            for (DoorHingeSide hinge : DoorHingeSide.values()) {
                BlockPos p = next();
                BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, d).setValue(DoorBlock.HINGE, hinge)
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
                put(p, lower);
                put(p.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            }
        }
        for (Direction d : HORIZONTAL) {
            BlockPos p = next();
            put(p.relative(d), Blocks.STONE.defaultBlockState());
            put(p, Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d), true));
        }
        for (Direction d : HORIZONTAL) {
            BlockPos p = next();
            put(p.relative(d.getOpposite()), Blocks.STONE.defaultBlockState());
            put(p, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, d));
        }
        for (Direction d : HORIZONTAL) {
            for (Half h : Half.values()) {
                put(next(), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, d).setValue(StairBlock.HALF, h));
                put(next(), Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, d).setValue(TrapDoorBlock.HALF, h));
            }
            put(next(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, d));
            put(next(), Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, d));
            put(next(), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, d));
            put(next(), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, d));
            put(next(), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, d));
            put(next(), Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, d));
        }
        put(next(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        // 铁门（单扇）、双开门（门轴在两边 / 门轴在中间，木门和铁门）
        for (Direction d : HORIZONTAL) {
            for (DoorHingeSide hinge : DoorHingeSide.values()) door(next(), Blocks.IRON_DOOR, d, hinge);
            for (var block : List.of(Blocks.OAK_DOOR, Blocks.IRON_DOOR)) {
                // 面朝 d 时，hinge=LEFT 的门轴在逆时针那一侧：左扇 LEFT + 右扇 RIGHT = 门轴在外侧
                BlockPos p = next();
                door(p, block, d, DoorHingeSide.LEFT);
                door(p.relative(d.getClockWise()), block, d, DoorHingeSide.RIGHT);
                // 门轴在中间的双开门：原版放第二扇时总会把门轴放到外侧，所以先放的那扇能放对、后放的那扇放不对 ——
                // 这两扇里允许有一扇留空，但绝不能放错
                BlockPos q = next();
                door(q, block, d, DoorHingeSide.RIGHT);
                door(q.relative(d.getClockWise()), block, d, DoorHingeSide.LEFT);
                innerPairs.add(new BlockPos[]{q, q.relative(d.getClockWise())});
            }
            // 铁活板门（上 / 下半）、打开着的木活板门（上 / 下半）
            for (Half h : Half.values()) {
                put(next(), Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, d).setValue(TrapDoorBlock.HALF, h));
                put(next(), Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, d).setValue(TrapDoorBlock.HALF, h)
                        .setValue(TrapDoorBlock.OPEN, true));
            }
        }
        // 大箱子：两半 LEFT / RIGHT，4 个朝向
        for (Direction d : HORIZONTAL) {
            BlockPos p = next();
            // 箱子朝向 d 时，LEFT 那一半的伙伴在 d 的顺时针方向
            BlockPos partner = p.relative(d.getClockWise());
            put(p, Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, d)
                    .setValue(BlockStateProperties.CHEST_TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT));
            put(partner, Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, d)
                    .setValue(BlockStateProperties.CHEST_TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("orientation")) return;
        build();
        int maxX = BASE.getX() + 12 * 3, maxZ = BASE.getZ() + (cursor / 12 + 1) * 3;
        BlockPos min = BASE.offset(-2, -1, -2), max = new BlockPos(maxX + 1, BASE.getY() + 2, maxZ + 1);
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.removeAllPlacements(context); // 防止别的测试留下的投影放置影响工作区域
            sp.getServer().runCommand("gamemode creative @a");
            GT.randomTicksOff(sp);
            GT.clearArena(sp, min.getX() - 2, min.getZ() - 2, max.getX() + 2, max.getZ() + 2, 75);
            double cx = (BASE.getX() + maxX) / 2.0, cz = (BASE.getZ() + maxZ) / 2.0;
            sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f 70 %.1f", cx, cz));
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - cx) < 0.01, 200);
            context.runOnClient(c -> {
                c.player.getAbilities().flying = true;
                c.player.onUpdateAbilities();
            });
            context.waitTicks(40);
            GT.setSchematicStable(context, min, max, p -> states.getOrDefault(p, p.getY() == BASE.getY() - 1
                        ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState()));
            context.runOnClient(c -> {
                Configs.Core.WORK_RANGE.setDoubleValue(30);
                Configs.Print.LAYERED_MODE.setBooleanValue(true);
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(4);
                GT.enablePrint();
            });
            int done = 0;
            for (int t = 0; t < 1200; t += 20) {
                context.waitTicks(20);
                // Litematica 可能在后台清掉直接写进投影世界的方块（前面的测试删过投影放置）：发现就补写
                context.runOnClient(c -> {
                    var ws = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
                    for (var e : states.entrySet()) {
                        if (!ws.getBlockState(e.getKey()).equals(e.getValue())) {
                            GT.setSchematic(min, max, p -> states.getOrDefault(p, p.getY() == BASE.getY() - 1
                                    ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState()));
                            break;
                        }
                    }
                });
                done = sp.getServer().computeOnServer(s -> {
                    int n = 0;
                    for (var e : states.entrySet()) if (s.overworld().getBlockState(e.getKey()).equals(e.getValue())) n++;
                    return n;
                });
                if (done == states.size()) break;
                if (t % 100 == 0) {
                    int d = done;
                    GT.log("[orientation] t" + t + " done=" + d + " " + context.computeOnClient(c -> com.autyism.printer.handler.ModuleManager.PRINT.debugState()));
                }
            }
            context.runOnClient(c -> GT.disableAll());
            List<String> wrong = sp.getServer().computeOnServer(s -> {
                List<String> out = new java.util.ArrayList<>(); // 可修改：下面会去掉允许留空的门
                for (var e : states.entrySet()) {
                    BlockState have = s.overworld().getBlockState(e.getKey());
                    if (!have.equals(e.getValue())) out.add(e.getKey().toShortString() + " want=" + e.getValue() + " have=" + have);
                }
                return out;
            });
            // 门轴在中间的双开门：每对最多一扇（上下两格）可以留空；不能放错
            int innerEmpty = 0;
            for (BlockPos[] pair : innerPairs) {
                int emptyDoors = 0;
                for (BlockPos p : pair) {
                    boolean empty = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(p).isAir() && s.overworld().getBlockState(p.above()).isAir());
                    if (empty) {
                        emptyDoors++;
                        wrong.removeIf(w -> w.startsWith(p.toShortString() + " ") || w.startsWith(p.above().toShortString() + " "));
                    }
                }
                if (emptyDoors > 1) wrong.add("inner-hinge pair at " + pair[0].toShortString() + ": both doors empty");
                innerEmpty += emptyDoors;
            }
            GT.log("[orientation] inner-hinge double doors: " + innerPairs.size() + " pairs, " + innerEmpty + " doors left empty (vanilla can't place them), none wrong");
            for (String w : wrong) GT.log("[orientation] WRONG " + w);
            if (!wrong.isEmpty()) {
                BlockPos first = states.keySet().stream().filter(p -> wrong.stream().anyMatch(w -> w.startsWith(p.toShortString() + " "))).findFirst().orElse(null);
                if (first != null) {
                    context.runOnClient(c -> GT.enablePrint());
                    context.waitTicks(5);
                    GT.log("[orientation] DEBUG " + first.toShortString() + " " + context.computeOnClient(c ->
                            com.autyism.printer.handler.ModuleManager.PRINT.debugPos(first) + " schematic=" + fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld().getBlockState(first)
                                    + " hand=" + c.player.getMainHandItem() + " creative=" + c.player.getAbilities().instabuild));
                }
            }
            GT.log("[orientation] RESULT " + (states.size() - wrong.size()) + "/" + states.size() + " correct");
            if (!wrong.isEmpty()) throw new AssertionError("[orientation] " + wrong.size() + " blocks wrong, first: " + wrong.get(0));
        } finally {
            context.runOnClient(c -> GT.disableAll());
            TestSchematicRegion.clear();
        }
    }
}
