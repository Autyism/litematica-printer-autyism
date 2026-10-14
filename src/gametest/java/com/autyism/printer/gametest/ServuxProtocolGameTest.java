package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.printer.ServerProtocol;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.PlacementHandler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真服务器上的轻松放置协议（V3，由 Servux 处理）。
 * <p>
 * 要先在本地开一个装了 Servux + 地毯（不开 accurateBlockPlacement）的测试服：release-work\printer120\servux-test.ps1，端口 25599。
 * 客户端以离线玩家 ALEGameTest（op 2）连进去，打开 Litematica 的“实体数据同步”（只有开着它 Litematica 才认得出 Servux）。
 * 打印机用协议放：各种朝向的侦测器、活塞、发射器、投掷器、木桶、中继器、比较器、漏斗、音符盒都要放对，而且要比转头放快。
 * <p>
 * -Pservuxrefuse=true：测试服把轻松放置的权限调高了（玩家没权限），协议放置会被服务端拒绝：打印机要在几次之后改回转头放置，最后照样全部放对。
 * 只在 -Pgt 里明确写了 servux 时运行（要先开测试服）。
 */
@SuppressWarnings("UnstableApiUsage")
public final class ServuxProtocolGameTest implements FabricClientGameTest {
    private static final String ADDRESS = "localhost:25599";
    private static final int Y = -60;
    private static final BlockPos MIN = new BlockPos(-7, Y, 0);
    private static final BlockPos MAX = new BlockPos(9, Y, 20);
    private static final String NAME = "ale_test_servux";

    private static Map<BlockPos, BlockState> reference() {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        Direction[] all = Direction.values();
        Block[] sixWay = {Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.DISPENSER, Blocks.DROPPER, Blocks.BARREL};
        for (int r = 0; r < sixWay.length; r++) {
            for (int i = 0; i < all.length; i++) {
                m.put(new BlockPos(-6 + i * 2, Y, r * 2), sixWay[r].defaultBlockState().setValue(BlockStateProperties.FACING, all[i]));
            }
        }
        Direction[] horizontal = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < 16; i++) {
            m.put(new BlockPos(-6 + (i % 8) * 2, Y, 12 + (i / 8) * 2), Blocks.REPEATER.defaultBlockState()
                    .setValue(RepeaterBlock.FACING, horizontal[i / 4]).setValue(RepeaterBlock.DELAY, i % 4 + 1));
        }
        for (int i = 0; i < 8; i++) {
            m.put(new BlockPos(-6 + i * 2, Y, 16), Blocks.COMPARATOR.defaultBlockState()
                    .setValue(ComparatorBlock.FACING, horizontal[i / 2]).setValue(ComparatorBlock.MODE, i % 2 == 0 ? ComparatorMode.COMPARE : ComparatorMode.SUBTRACT));
        }
        Direction[] hopper = {Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < hopper.length; i++) {
            m.put(new BlockPos(-6 + i * 2, Y, 18), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, hopper[i]));
        }
        int[] notes = {0, 5, 12, 24};
        for (int i = 0; i < notes.length; i++) {
            m.put(new BlockPos(-6 + i * 2, Y, 20), Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, notes[i]));
        }
        return m;
    }

    private static String stateArg(BlockState s) {
        // BlockState.toString(): Block{minecraft:observer}[facing=north,powered=false] -> minecraft:observer[facing=north,powered=false]
        String t = s.toString();
        return t.replaceFirst("^Block\\{([^}]*)}", "$1");
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Arrays.asList(System.getProperty("ale.gt", "").split(",")).contains("servux")) return;
        boolean expectRefuse = Boolean.getBoolean("ale.servuxrefuse");
        Map<BlockPos, BlockState> ref = reference();
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(ADDRESS),
                new ServerData("printer test", ADDRESS, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.player != null && client.level != null && client.screen == null, 1200);
        context.waitTicks(40);
        try {
            command(context, "gamemode creative");
            command(context, "tp @s 1.5 " + Y + " 10.5 0 30");
            command(context, "attribute @s minecraft:block_interaction_range base set 64");
            command(context, "fill -8 " + Y + " -1 10 " + (Y + 1) + " 21 air");
            context.waitTicks(20);
            for (var e : ref.entrySet()) {
                command(context, "setblock " + e.getKey().getX() + " " + e.getKey().getY() + " " + e.getKey().getZ() + " " + stateArg(e.getValue()));
            }
            context.waitFor(client -> ref.entrySet().stream().allMatch(e -> client.level.getBlockState(e.getKey()) == e.getValue()), 400);
            var dir = context.computeOnClient(client -> DataManager.getSchematicsBaseDirectory());
            boolean written = context.computeOnClient(client -> {
                var area = new fi.dy.masa.litematica.selection.AreaSelection();
                area.setName(NAME);
                area.addSubRegionBox(new fi.dy.masa.litematica.selection.Box(MIN, MAX, NAME), false);
                area.setExplicitOrigin(MIN);
                var schematic = fi.dy.masa.litematica.schematic.LitematicaSchematic.createFromWorld(client.level, area,
                        new fi.dy.masa.litematica.schematic.LitematicaSchematic.SchematicSaveInfo(false, false), "ALE", s -> GT.log("capture: " + s));
                return schematic != null && schematic.writeToFile(dir, NAME, true);
            });
            if (!written) throw new AssertionError("[servux] could not capture the reference");
            context.runOnClient(client -> {
                var loaded = fi.dy.masa.litematica.schematic.LitematicaSchematic.createFromFile(dir, NAME + ".litematic");
                if (loaded == null) throw new AssertionError("[servux] could not read back the schematic");
                var placement = fi.dy.masa.litematica.schematic.placement.SchematicPlacement.createFor(loaded, MIN, NAME, true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
            });
            BlockPos first = ref.keySet().iterator().next();
            GT.waitSchematicBlock(context, first, ref.get(first).getBlock());

            int withProtocol = print(context, ref, true, expectRefuse);
            String state = context.computeOnClient(c -> ServerProtocol.describe());
            if (expectRefuse && !state.contains("refused=true")) throw new AssertionError("[servux] protocol was refused by the server but the printer kept using it: " + state);
            if (!expectRefuse && !state.contains("refused=false")) throw new AssertionError("[servux] the printer gave the protocol up although Servux accepts it: " + state);
            int withoutProtocol = print(context, ref, false, expectRefuse);
            GT.log("[servux] OK" + (expectRefuse ? " (refusing server)" : "") + ": " + ref.size() + " blocks with the protocol in " + withProtocol
                    + " ticks, turning the head in " + withoutProtocol + " ticks; " + state);
            if (!expectRefuse && withProtocol >= withoutProtocol) {
                throw new AssertionError("[servux] the protocol was not faster: " + withProtocol + " vs " + withoutProtocol + " ticks");
            }
        } finally {
            GT.removeAllPlacements(context);
            context.runOnClient(client -> {
                GT.disableAll();
                client.disconnect(new TitleScreen(), false);
            });
            context.waitFor(client -> client.level == null, 600);
        }
    }

    /** 清空、发材料、打印一遍，返回用了几个 tick；最后每一格都必须和投影完全一样 */
    private static int print(ClientGameTestContext context, Map<BlockPos, BlockState> ref, boolean protocol, boolean expectRefuse) {
        context.runOnClient(client -> GT.disableAll());
        command(context, "fill -8 " + Y + " -1 10 " + (Y + 1) + " 21 air");
        context.waitFor(client -> ref.keySet().stream().allMatch(p -> client.level.getBlockState(p).isAir()), 400);
        command(context, "gamemode survival");
        command(context, "clear @s");
        for (String item : List.of("observer", "piston", "sticky_piston", "dispenser", "dropper", "barrel", "repeater", "comparator", "hopper", "note_block")) {
            command(context, "give @s minecraft:" + item + " 64");
        }
        context.waitTicks(20);
        context.runOnClient(client -> {
            fi.dy.masa.litematica.config.Configs.Generic.ENTITY_DATA_SYNC.setBooleanValue(true);
            fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.AUTO);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(protocol);
            Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(0);
            Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
            Configs.Placement.PLACE_COOLDOWN.setIntegerValue(3);
            Configs.Core.WORK_RANGE.setDoubleValue(32);
            Configs.Print.LAYERED_MODE.setBooleanValue(false);
            Configs.Print.NOTE_BLOCK_TUNING.setBooleanValue(true);
        });
        if (protocol) {
            // Litematica 只有开着“实体数据同步”时才和 Servux 打招呼（打开后要等它收到 Servux 的回应）
            for (int i = 0; i < 200 && !context.computeOnClient(c -> PlacementHandler.getEffectiveProtocolVersion() == EasyPlaceProtocol.V3); i++) {
                context.waitTick();
            }
            String litematica = context.computeOnClient(c -> String.valueOf(PlacementHandler.getEffectiveProtocolVersion()));
            String printer = context.computeOnClient(c -> String.valueOf(ServerProtocol.effective()));
            GT.log("[servux] Litematica's protocol choice with entity data sync on: " + litematica + ", the printer's: " + printer);
            if (!"V3".equals(litematica)) throw new AssertionError("[servux] Litematica did not recognise Servux: " + litematica);
        }
        context.runOnClient(client -> GT.enablePrint());
        int ticks = 0;
        int lastExact = -1, stableSince = 0;
        while (ticks < 2400) {
            int exact = context.computeOnClient(client -> (int) ref.entrySet().stream().filter(e -> client.level.getBlockState(e.getKey()) == e.getValue()).count());
            boolean allPlaced = context.computeOnClient(client -> ref.entrySet().stream().allMatch(e -> client.level.getBlockState(e.getKey()).is(e.getValue().getBlock())));
            if (exact == ref.size()) break;
            if (exact != lastExact) {
                lastExact = exact;
                stableSince = ticks;
            }
            // 不认协议的服务器上试放错了的几块打印机不会去拆：其余都放好、一阵子没变化就算结束
            if (expectRefuse && protocol && allPlaced && ticks - stableSince > 100) break;
            context.waitTick();
            ticks++;
            if (ticks % 200 == 0) GT.log("[servux] protocol=" + protocol + " t" + ticks + " " + context.computeOnClient(c -> ServerProtocol.describe()));
        }
        context.waitTicks(20);
        context.runOnClient(client -> GT.disableAll());
        List<String> wrong = context.computeOnClient(client -> {
            List<String> w = new ArrayList<>();
            for (var e : ref.entrySet()) {
                BlockState s = client.level.getBlockState(e.getKey());
                if (s != e.getValue()) w.add(e.getKey().toShortString() + "=" + s + " (want " + e.getValue() + ")");
            }
            return w;
        });
        // 不认协议的服务器：确认之前同一时间只试放一块，“不按协议就放不对”的那块放错就停用协议，所以最多错一块
        int allowed = protocol && expectRefuse ? 1 : 0;
        if (wrong.size() > allowed) throw new AssertionError("[servux] " + wrong.size() + " wrong blocks (protocol=" + protocol + "), e.g. " + wrong.subList(0, Math.min(4, wrong.size())));
        if (!wrong.isEmpty()) GT.log("[servux] the refused protocol tries left " + wrong.size() + " wrong blocks: " + wrong);
        GT.log("[servux] protocol=" + protocol + ": all " + ref.size() + " blocks correct after " + ticks + " ticks");
        return ticks;
    }

    private static void command(ClientGameTestContext context, String cmd) {
        context.runOnClient(client -> client.player.connection.sendCommand(cmd));
        context.waitTicks(1);
    }
}
