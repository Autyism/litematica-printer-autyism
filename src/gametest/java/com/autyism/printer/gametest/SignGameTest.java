package com.autyism.printer.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求 11：依附在其他告示牌上的告示牌都要能打印（各种组合），朝向/挂法/文字都要一致。
 */
@SuppressWarnings("UnstableApiUsage")
public final class SignGameTest implements FabricClientGameTest {
    private static final int X = 400, Z = 0;
    private static final BlockPos MIN = new BlockPos(X - 1, 64, Z - 2);
    private static final BlockPos MAX = new BlockPos(X + 15, 68, Z + 2);

    private static Map<BlockPos, BlockState> layout() {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        // A：立式告示牌上再立一块
        m.put(new BlockPos(X, 64, Z), Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 4));
        m.put(new BlockPos(X, 65, Z), Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 9));
        // B：墙上告示牌挂在立式告示牌侧面（附着在北边的立式告示牌上，朝南）
        m.put(new BlockPos(X + 3, 64, Z), Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0));
        m.put(new BlockPos(X + 3, 64, Z + 1), Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.SOUTH));
        // C：悬挂式告示牌挂在另一块悬挂式告示牌下面（不斜挂），最下面再挂一块斜挂的
        m.put(new BlockPos(X + 6, 68, Z), Blocks.STONE.defaultBlockState());
        m.put(new BlockPos(X + 6, 67, Z), Blocks.OAK_HANGING_SIGN.defaultBlockState().setValue(CeilingHangingSignBlock.ROTATION, 0).setValue(CeilingHangingSignBlock.ATTACHED, false));
        m.put(new BlockPos(X + 6, 66, Z), Blocks.OAK_HANGING_SIGN.defaultBlockState().setValue(CeilingHangingSignBlock.ROTATION, 8).setValue(CeilingHangingSignBlock.ATTACHED, false));
        m.put(new BlockPos(X + 6, 65, Z), Blocks.OAK_HANGING_SIGN.defaultBlockState().setValue(CeilingHangingSignBlock.ROTATION, 3).setValue(CeilingHangingSignBlock.ATTACHED, true));
        // D：墙挂悬挂式告示牌挂在另一块墙挂悬挂式告示牌旁边（朝北，沿 X 轴依次附着）
        m.put(new BlockPos(X + 9, 66, Z), Blocks.STONE.defaultBlockState());
        m.put(new BlockPos(X + 10, 66, Z), Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState().setValue(WallHangingSignBlock.FACING, Direction.NORTH));
        m.put(new BlockPos(X + 11, 66, Z), Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState().setValue(WallHangingSignBlock.FACING, Direction.NORTH));
        // E：立式告示牌立在墙上告示牌上面
        m.put(new BlockPos(X + 13, 65, Z - 1), Blocks.STONE.defaultBlockState());
        m.put(new BlockPos(X + 13, 65, Z), Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.SOUTH));
        m.put(new BlockPos(X + 13, 66, Z), Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 12));
        return m;
    }

    private static BlockState expected(BlockPos pos) {
        BlockState s = layout().get(pos);
        return s == null ? Blocks.AIR.defaultBlockState() : s;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("sign")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.clearArena(sp, X - 6, Z - 8, X + 20, Z + 8, 72);
            sp.getServer().runCommand("tp @a " + (X + 6.5) + " 64 " + (Z + 4.5) + " 180 10");
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - X - 6.5) < 0.01, 200);
            context.waitTicks(10);
            Map<BlockPos, BlockState> layout = layout();
            sp.getServer().runOnServer(s -> {
                ServerLevel level = s.overworld();
                for (var e : layout.entrySet()) {
                    level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
                    if (level.getBlockEntity(e.getKey()) instanceof SignBlockEntity sign) {
                        String id = e.getKey().getX() + "," + e.getKey().getY();
                        sign.updateText(t -> t.setMessage(0, Component.literal("F " + id)), true);
                        sign.updateText(t -> t.setMessage(1, Component.literal("B " + id)), false);
                    }
                }
            });
            context.waitTicks(10);
            GT.captureAndPlace(context, sp, MIN, MAX, MIN, "ale_test_signs");
            sp.getServer().runOnServer(s -> {
                ServerLevel level = s.overworld();
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                var player = s.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                player.getInventory().setItem(9, new ItemStack(Items.OAK_SIGN, 16));
                player.getInventory().setItem(10, new ItemStack(Items.OAK_HANGING_SIGN, 16));
                player.getInventory().setItem(11, new ItemStack(Items.STONE, 16));
                player.inventoryMenu.sendAllDataToRemote();
            });
            GT.waitSchematicBlock(context, new BlockPos(X, 64, Z), Blocks.OAK_SIGN);
            context.runOnClient(c -> {
                com.autyism.printer.config.Configs.Core.WORK_RANGE.setDoubleValue(8);
                GT.enablePrint();
            });
            long want = layout.size();
            try {
                GT.waitServer(context, () -> GT.countPlaced(sp, MIN, MAX) >= want, 600, "[sign] not all signs printed");
            } catch (AssertionError e) {
                GT.log("[sign] placed " + GT.countPlaced(sp, MIN, MAX) + "/" + want);
            }
            context.waitTicks(40);
            context.runOnClient(c -> GT.disableAll());
            List<String> wrong = new ArrayList<>();
            for (var e : layout.entrySet()) {
                BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(e.getKey()));
                if (got != e.getValue()) wrong.add(e.getKey().toShortString() + " got " + got + " want " + e.getValue());
                String text = sp.getServer().computeOnServer(s -> s.overworld().getBlockEntity(e.getKey()) instanceof SignBlockEntity sign
                        ? sign.getFrontText().getMessage(0, false).getString() : null);
                String id = e.getKey().getX() + "," + e.getKey().getY();
                if (text != null && !text.equals("F " + id)) wrong.add(e.getKey().toShortString() + " text '" + text + "'");
            }
            context.takeScreenshot("ale-signs");
            if (!wrong.isEmpty()) throw new AssertionError("[sign] " + wrong.size() + " problems:\n  " + String.join("\n  ", wrong));
            GT.log("[sign] OK: all " + want + " sign-on-sign cases printed with correct state and text");
        } finally {
            GT.removeAllPlacements(context);
            context.runOnClient(c -> GT.disableAll());
        }
    }
}
