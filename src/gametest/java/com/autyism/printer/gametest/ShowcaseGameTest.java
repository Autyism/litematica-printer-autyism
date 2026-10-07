package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布页截图（只在 -Pgt=showcase 时运行）：在测试世界原本的地面上盖一间小木屋，存成投影，再清掉，
 * 让打印机在生存模式下从最底层开始打印。打到一半截一张（剩下的部分还是投影的幽灵方块），完工后再截一张。
 */
@SuppressWarnings("UnstableApiUsage")
public final class ShowcaseGameTest implements FabricClientGameTest {
    /** The cottage relative to its floor corner {@code o}: 7 x 7 walls, a stair roof with overhang, two lamp posts, flowers. */
    static Map<BlockPos, BlockState> cottage(BlockPos o) {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++) m.put(o.offset(x, 0, z), planks);
        for (int y = 1; y <= 3; y++) for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++) {
            boolean edgeX = x == 0 || x == 6, edgeZ = z == 0 || z == 6;
            if (!edgeX && !edgeZ) continue;
            m.put(o.offset(x, y, z), edgeX && edgeZ ? Blocks.COBBLESTONE.defaultBlockState() : planks);
        }
        m.put(o.offset(1, 2, 0), glass);
        m.put(o.offset(5, 2, 0), glass);
        for (int z = 2; z <= 4; z++) {
            m.put(o.offset(0, 2, z), glass);
            m.put(o.offset(6, 2, z), glass);
        }
        for (int x = 2; x <= 4; x++) m.put(o.offset(x, 2, 6), glass);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        m.put(o.offset(3, 1, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        m.put(o.offset(3, 2, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // gable ends with a small window
        for (int i = 0; i <= 3; i++) for (int z = i; z <= 6 - i; z++) {
            m.put(o.offset(0, 4 + i, z), planks);
            m.put(o.offset(6, 4 + i, z), planks);
        }
        m.put(o.offset(0, 5, 3), glass);
        m.put(o.offset(6, 5, 3), glass);
        // roof: stairs rising towards the ridge, one block of overhang all round
        BlockState south = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
        BlockState north = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        for (int x = -1; x <= 7; x++) {
            for (int i = 0; i <= 3; i++) {
                m.put(o.offset(x, 4 + i, -1 + i), south);
                m.put(o.offset(x, 4 + i, 7 - i), north);
            }
            m.put(o.offset(x, 8, 3), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        }
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        BlockState lantern = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false);
        m.put(o.offset(1, 0, -2), fence);
        m.put(o.offset(1, 1, -2), lantern);
        m.put(o.offset(5, 0, -2), fence);
        m.put(o.offset(5, 1, -2), lantern);
        m.put(o.offset(-2, 0, 1), Blocks.POPPY.defaultBlockState());
        m.put(o.offset(-2, 0, 4), Blocks.DANDELION.defaultBlockState());
        m.put(o.offset(8, 0, 2), Blocks.CORNFLOWER.defaultBlockState());
        m.put(o.offset(8, 0, 5), Blocks.POPPY.defaultBlockState());
        return m;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!System.getProperty("ale.gt", "").contains("showcase")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.randomTicksOff(sp);
            sp.getServer().runCommand("time set 6000");
            sp.getServer().runCommand("weather clear");
            // load the chunk first: for an unloaded chunk getHeight answers the bottom of the world
            int ground = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                level.getChunk(300 >> 4, 300 >> 4);
                return level.getHeight(Heightmap.Types.MOTION_BLOCKING, 300, 300);
            }) - 1;
            GT.log("[showcase] ground at y=" + ground);
            if (ground < -63) throw new AssertionError("[showcase] no ground found at the site (y=" + ground + ")");
            BlockPos o = new BlockPos(300, ground + 1, 300);
            BlockPos min = o.offset(-2, 0, -2), max = o.offset(8, 8, 7);
            // stand in front of the cottage, a little to the left, looking at it
            double px = o.getX() - 5.5, pz = o.getZ() - 8.5, py = ground + 1;
            double dx = o.getX() + 3.5 - px, dz = o.getZ() + 3.5 - pz, dy = (o.getY() + 4.0) - (py + 1.62);
            float yaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", px, py, pz, yaw, pitch));
            context.waitFor(client -> client.player != null && Math.abs(client.player.getX() - px) < 0.01, 200);
            //? if >=1.20.5
            sp.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 64");
            context.waitTicks(20);
            // flat grass under and around the site, nothing above it
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                for (int x = 280; x <= 325; x++) for (int z = 275; z <= 325; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, ground, z), Blocks.GRASS_BLOCK.defaultBlockState());
                    for (int y = ground + 1; y <= ground + 20; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            });
            Map<BlockPos, BlockState> plan = cottage(o);
            sp.getServer().runOnServer(server -> plan.forEach((p, s) -> server.overworld().setBlockAndUpdate(p, s)));
            context.waitTicks(10);
            // what the world holds now (stair corners, fence connections) is what the schematic will hold
            Map<BlockPos, BlockState> built = sp.getServer().computeOnServer(server -> {
                Map<BlockPos, BlockState> b = new HashMap<>();
                for (BlockPos p : BlockPos.betweenClosed(min, max)) b.put(p.immutable(), server.overworld().getBlockState(p));
                return b;
            });
            int total = (int) built.values().stream().filter(s -> !s.isAir()).count();
            GT.captureAndPlace(context, sp, min, max, min, "ale_showcase_cottage");
            sp.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(min, max)) server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                // enough full stacks of every block the schematic holds (a door counts twice, which only leaves one spare)
                Map<Item, Integer> need = new LinkedHashMap<>();
                for (BlockState s : built.values()) if (!s.isAir()) need.merge(s.getBlock().asItem(), 1, Integer::sum);
                int slot = 9;
                for (var e : need.entrySet()) for (int left = e.getValue(); left > 0; left -= 64) {
                    if (slot > 35) throw new AssertionError("[showcase] the materials do not fit in the inventory: " + need);
                    player.getInventory().setItem(slot++, new ItemStack(e.getKey(), 64));
                }
                player.inventoryMenu.sendAllDataToRemote();
            });
            GT.waitSchematicBlock(context, o, Blocks.OAK_PLANKS);
            context.runOnClient(client -> {
                Configs.Core.WORK_RANGE.setDoubleValue(26.0D);
                Configs.Print.LAYERED_MODE.setBooleanValue(true);
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(3);
                Configs.Core.RENDER_HUD.setBooleanValue(true);
                GT.enablePrint();
            });
            boolean[] halfShot = {false};
            int[] tick = {0};
            try {
            GT.waitServer(context, () -> {
                int placed = sp.getServer().computeOnServer(server -> {
                    int n = 0;
                    for (BlockPos p : BlockPos.betweenClosed(min, max)) if (!server.overworld().getBlockState(p).isAir()) n++;
                    return n;
                });
                if (++tick[0] % 100 == 0) {
                    String state = context.computeOnClient(c -> com.autyism.printer.handler.ModuleManager.PRINT.debugState()
                            + " | corner: " + com.autyism.printer.handler.ModuleManager.PRINT.debugPos(o)
                            + " | hand=" + c.player.getMainHandItem() + " pos=" + c.player.blockPosition());
                    GT.log("[showcase] t" + tick[0] + " placed=" + placed + "/" + total + " " + state);
                }
                if (!halfShot[0] && placed >= total * 45 / 100) {
                    halfShot[0] = true;
                    // pause so that no new "layer done" message pops up between tidying and the shot
                    context.runOnClient(client -> Configs.Core.WORK_SWITCH.setBooleanValue(false));
                    context.waitTicks(10);
                    tidy(context);
                    GT.screenshot(context, "printer-showcase-building");
                    context.runOnClient(client -> Configs.Core.WORK_SWITCH.setBooleanValue(true));
                }
                return placed >= total;
            }, 1500, "[showcase] cottage was not finished");
            } catch (AssertionError e) {
                List<String> missing = GT.mismatches(sp, min, max, p -> built.get(p));
                GT.log("[showcase] unfinished: " + missing.size() + " blocks missing or wrong: " + missing);
                throw e;
            }
            context.runOnClient(client -> GT.disableAll());
            context.waitTicks(40);
            tidy(context);
            //? if >=26.2 {
            /*context.runOnClient(client -> { if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); });
            *///?} else
            context.runOnClient(client -> client.options.hideGui = true);
            context.waitTicks(3);
            GT.screenshot(context, "printer-showcase-done");
            //? if >=26.2 {
            /*context.runOnClient(client -> { if (client.gui.hud.isHidden()) client.gui.hud.toggle(); });
            *///?} else
            context.runOnClient(client -> client.options.hideGui = false);
            List<String> wrong = GT.mismatches(sp, min, max, p -> built.get(p));
            GT.log("[showcase] " + total + " blocks printed, " + wrong.size() + " differ from the schematic" + (wrong.isEmpty() ? "" : ": " + wrong.subList(0, Math.min(5, wrong.size()))));
            if (!wrong.isEmpty()) throw new AssertionError("[showcase] " + wrong.size() + " blocks differ from the schematic");
            GT.log("[showcase] OK");
        } finally {
            GT.removeAllPlacements(context);
            context.runOnClient(client -> {
                GT.disableAll();
                //? if >=26.2 {
                /*if (client.gui.hud.isHidden()) client.gui.hud.toggle();
                *///?} else
                client.options.hideGui = false;
            });
        }
    }

    /** No chat lines, toasts or malilib message boxes in the release screenshots. */
    private static void tidy(ClientGameTestContext context) {
        context.runOnClient(client -> {
            //? if >=26.2 {
            /*client.gui.hud.getChat().clearMessages(false);
            client.gui.toastManager().clear();
            *///?} elif <1.21.2 {
            /*client.gui.getChat().clearMessages(false);
            client.getToasts().clear();
            *///?} else {
            client.gui.getChat().clearMessages(false);
            client.getToastManager().clear();
            //?}
            // malilib keeps the in-game message box private and offers no way to clear it
            try {
                Field box = fi.dy.masa.malilib.util.InfoUtils.class.getDeclaredField("IN_GAME_MESSAGES");
                box.setAccessible(true);
                Object renderer = box.get(null);
                Field messages = renderer.getClass().getDeclaredField("messages");
                messages.setAccessible(true);
                ((List<?>) messages.get(renderer)).clear();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("[showcase] could not clear malilib's message box", e);
            }
        });
        context.waitTicks(3);
    }
}
