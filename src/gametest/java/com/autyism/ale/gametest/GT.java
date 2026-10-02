package com.autyism.ale.gametest;

import com.autyism.ale.config.Configs;
import com.autyism.ale.enums.RadiusShapeType;
import com.autyism.ale.enums.SelectionType;
import com.autyism.ale.handler.ModuleManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** gametest 公共工具。 */
@SuppressWarnings("UnstableApiUsage")
public final class GT {
    private GT() {
    }

    public static void log(String msg) {
        System.out.println("[ALE-GT] " + msg);
    }

    public static TestSingleplayerContext newWorld(ClientGameTestContext context) {
        TestSingleplayerContext sp = context.worldBuilder().create();
        sp.getServer().runCommand("gamerule doDaylightCycle false");
        sp.getServer().runCommand("gamerule doMobSpawning false");
        sp.getServer().runCommand("gamerule doWeatherCycle false");
        sp.getServer().runCommand("gamemode survival @a");
        context.runOnClient(client -> configureCommon());
        return sp;
    }

    /** 每 tick 在测试线程上检查（可访问服务端），返回用掉的 tick 数 */
    public static int waitServer(ClientGameTestContext context, BooleanSupplier done, int maxTicks, String failMessage) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            if (done.getAsBoolean()) return tick;
            context.waitTick();
        }
        throw new AssertionError(failMessage + " within " + maxTicks + " ticks");
    }

    /** 清空一片区域：y=63 基岩地面，上方空气 */
    public static void clearArena(TestSingleplayerContext sp, int x0, int z0, int x1, int z1, int yTop) {
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.BEDROCK.defaultBlockState());
                    for (int y = 64; y <= yTop; y++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        });
    }

    /** 在 Litematica 的投影世界中写入方块，并把区域登记为“投影区域”（打印机会处理） */
    public static void setSchematic(BlockPos min, BlockPos max, Function<BlockPos, BlockState> states) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) throw new AssertionError("Schematic world is unavailable");
        for (int cx = (min.getX() >> 4) - 1; cx <= (max.getX() >> 4) + 1; cx++) {
            for (int cz = (min.getZ() >> 4) - 1; cz <= (max.getZ() >> 4) + 1; cz++) {
                schematic.getChunkSource().loadChunk(cx, cz);
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            schematic.setBlock(pos.immutable(), states.apply(pos.immutable()), 3);
        }
        TestSchematicRegion.activate(min, max);
    }

    public static List<String> mismatches(TestSingleplayerContext sp, BlockPos min, BlockPos max, Function<BlockPos, BlockState> expected) {
        return sp.getServer().computeOnServer(server -> {
            List<String> result = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                BlockState state = server.overworld().getBlockState(pos);
                BlockState want = expected.apply(pos.immutable());
                if (state != want) result.add(pos.toShortString() + "=" + state + " (want " + want + ")");
            }
            return result;
        });
    }

    public static int countPlaced(TestSingleplayerContext sp, BlockPos min, BlockPos max) {
        return sp.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (!server.overworld().getBlockState(pos).isAir()) n++;
            }
            return n;
        });
    }

    public static void enablePrint() {
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        Configs.Print.ENABLED.setBooleanValue(true);
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_RENDER_LAYER);
        ModuleManager.PRINT.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    public static void configureCommon() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(5.0D);
        Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
        Configs.Core.PAUSE_ON_CONTAINER.setBooleanValue(true);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(0);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(3);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(true);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(false);
        disableAll();
    }

    public static void disableAll() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Mine.ENABLED.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
    }
}
