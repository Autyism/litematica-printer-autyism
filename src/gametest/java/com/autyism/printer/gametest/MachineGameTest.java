//? if 1.21.11 {
package com.autyism.printer.gametest;

import com.autyism.printer.Reference;
import com.autyism.printer.config.Configs;
import com.autyism.printer.handler.ModuleManager;
import com.autyism.printer.printer.ActionConfirm;
import com.autyism.printer.printer.ServerProtocol;
import com.autyism.printer.printer.TickTrace;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.PlacementHandler;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// 真实红石机器的打印速度（-Pgt=machine -Pdebugstall=true）：用户正在搭的 576k 刷沙机（5241 格，573 个侦测器、
// 200 个音符盒、172 个中继器……），连到本地测试服（Servux + 地毯，开着 antiCheatDisabled，和用户的服务器一样；
// 见 release-work\printer120\machine-test.ps1），按用户实例里的打印机设置打印。
//
// 每 tick 记下打印机停在哪一步（TickTrace），最后汇总：总共多少 tick、没放东西的 tick 都停在哪、最长的几次停顿、
// 每一层从什么时候开始；打到一半关掉再打开，量重新开始要多久。打完核对每一格（红石运行时属性除外）。
// 玩家跟着当前层飞（在这一层上方 3 格），和用户自己搭的时候一样。
//
// -Pmachinemode=survival：生存模式，测试按当前和上面两层缺的材料发物品（不用潜影盒）。
@SuppressWarnings("UnstableApiUsage")
public final class MachineGameTest implements FabricClientGameTest {
    private static final String ADDRESS = "localhost:25599";
    private static final String DEFAULT_SCHEMATIC = "D:/Games/PCL2/.minecraft/versions/1.21.11-Fabric 0.19.5 Main/schematics/sync/576k换色刷沙机v2 by WireJefio.litematic";
    private static final String USER_CONFIG = "D:/Games/PCL2/.minecraft/versions/1.21.11-Fabric 0.19.5 Main/config/litematica-printer-autyism.json";
    private static final BlockPos ORIGIN = new BlockPos(100, -60, 100);
    // 红石运行时状态：电路自己会改，不要求和投影一样
    private static final Set<String> RUNTIME_PROPS = Set.of("powered", "lit", "power", "triggered", "extended", "enabled", "locked", "short",
            "occupied", "attached", "disarmed");

    private record Sample(int tick, int actions, String stop, String detail, Integer layer, int pending, String timing) {
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Arrays.asList(System.getProperty("ale.gt", "").split(",")).contains("machine")) return;
        if (!TickTrace.ENABLED) throw new AssertionError("[machine] needs -Pdebugstall=true");
        // -Pmachine=flat:20 —— 一层 20x20 的下界砖平台（用户说“打印下界砖都是一排一排地打过去的”）
        String machine = prop("ale.machine", DEFAULT_SCHEMATIC);
        Path src = machine.startsWith("flat:") ? Path.of(machine.replace(':', '_')) : Path.of(machine);
        boolean survival = "survival".equals(prop("ale.machinemode", "creative"));
        int maxTicks = Integer.parseInt(prop("ale.ticks", "12000"));
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(ADDRESS),
                new ServerData("printer test", ADDRESS, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.player != null && client.level != null && client.screen == null, 1200);
        context.waitTicks(40);
        try {
            run(context, src, survival, maxTicks);
        } finally {
            GT.removeAllPlacements(context);
            context.runOnClient(client -> {
                GT.disableAll();
                client.disconnect(new TitleScreen(), false);
            });
            context.waitFor(client -> client.level == null, 600);
        }
    }

    private void run(ClientGameTestContext context, Path src, boolean survival, int maxTicks) {
        String name = src.getFileName().toString();
        int flat = name.startsWith("flat_") ? Integer.parseInt(name.substring(5)) : 0;
        command(context, "gamemode creative");
        command(context, "carpet antiCheatDisabled true");
        // 原版的交互距离（以前的 servux 测试在这个测试服上把它调到了 64，存在玩家数据里）：和用户的服务器一样靠地毯的 32 格
        command(context, "attribute @s minecraft:block_interaction_range base reset");
        command(context, "time set noon");
        command(context, "gamerule doDaylightCycle false");
        command(context, "gamerule randomTickSpeed 0");
        if (flat > 0) captureFlat(context, flat);
        SchematicPlacement placement = context.computeOnClient(client -> {
            try {
                Path dir = DataManager.getSchematicsBaseDirectory();
                if (flat == 0) Files.copy(src, dir.resolve("ale_machine.litematic"), StandardCopyOption.REPLACE_EXISTING);
                LitematicaSchematic schematic = LitematicaSchematic.createFromFile(dir, "ale_machine.litematic");
                SchematicPlacement p = SchematicPlacement.createFor(schematic, ORIGIN, "machine", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(p, false);
                return p;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        int[] bb = context.computeOnClient(c -> {
            int[] r = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (var b : placement.getSubRegionBoxes(fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.ANY).values()) {
                for (BlockPos q : new BlockPos[]{b.getPos1(), b.getPos2()}) {
                    r[0] = Math.min(r[0], q.getX()); r[1] = Math.min(r[1], q.getY()); r[2] = Math.min(r[2], q.getZ());
                    r[3] = Math.max(r[3], q.getX()); r[4] = Math.max(r[4], q.getY()); r[5] = Math.max(r[5], q.getZ());
                }
            }
            return r;
        });
        BlockPos min = new BlockPos(bb[0], bb[1], bb[2]);
        BlockPos max = new BlockPos(bb[3], bb[4], bb[5]);
        double cx = (min.getX() + max.getX() + 1) / 2.0, cz = (min.getZ() + max.getZ() + 1) / 2.0;
        // 先清空（上一次留下的机器、掉落物）
        // 一条 fill 最多 32768 格：按高度分几段清
        // 平台：上面 40 格也清掉（上一次测试留下的机器在同一个地方，下面清空以后上面的沙子、混凝土粉末会掉下来）
        int clearTop = flat > 0 ? min.getY() + 40 : max.getY() + 4;
        if (!Boolean.getBoolean("ale.machinekeep")) {
            for (int y0 = min.getY(); y0 <= clearTop; y0 += 10) {
                command(context, String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", min.getX() - 2, y0, min.getZ() - 2, max.getX() + 2,
                        Math.min(y0 + 9, clearTop), max.getZ() + 2));
            }
        }
        command(context, "kill @e[type=item]");
        command(context, String.format(Locale.ROOT, "tp @s %.1f %d %.1f", cx, min.getY() + 3, cz));
        context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - cx) < 0.01, 200);
        context.runOnClient(c -> {
            c.player.getAbilities().flying = true;
            c.player.onUpdateAbilities();
        });
        context.waitFor(c -> SchematicWorldHandler.getSchematicWorld() != null
                && !SchematicWorldHandler.getSchematicWorld().getBlockState(firstNonAir(min, max)).isAir(), 600);
        Map<BlockPos, BlockState> expected = context.computeOnClient(c -> {
            var w = SchematicWorldHandler.getSchematicWorld();
            Map<BlockPos, BlockState> m = new LinkedHashMap<>();
            for (BlockPos p : BlockPos.betweenClosed(min, max)) {
                BlockState s = w.getBlockState(p);
                if (!s.isAir()) m.put(p.immutable(), s);
            }
            return m;
        });
        GT.log("[machine] " + name + " box " + min.toShortString() + " -> " + max.toShortString() + " non-air=" + expected.size()
                + (survival ? " survival" : " creative"));
        if (survival) {
            command(context, "gamemode survival");
            command(context, "clear @s");
            // 生存模式飞不起来：不受重力，测试每换一层把玩家挪到这一层上方
            command(context, "attribute @s minecraft:gravity base set 0");
        }

        // 用户实例里的打印机设置（读进来之后只关掉总开关，打印时再打开）
        context.runOnClient(c -> {
            var json = fi.dy.masa.malilib.util.data.json.JsonUtils.parseJsonFile(Path.of(USER_CONFIG));
            if (json == null || !json.isJsonObject()) throw new AssertionError("[machine] cannot read " + USER_CONFIG);
            fi.dy.masa.malilib.config.ConfigUtils.readConfigBase(json.getAsJsonObject(), Reference.MOD_ID, Configs.OPTIONS);
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            fi.dy.masa.litematica.config.Configs.Generic.ENTITY_DATA_SYNC.setBooleanValue(true);
            fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.AUTO);
            DataManager.getRenderLayerRange().setLayerMode(fi.dy.masa.malilib.util.LayerMode.ALL);
        });
        for (int i = 0; i < 200 && !context.computeOnClient(c -> PlacementHandler.getEffectiveProtocolVersion() == EasyPlaceProtocol.V3); i++) {
            context.waitTick();
        }
        GT.log("[machine] protocol " + context.computeOnClient(c -> PlacementHandler.getEffectiveProtocolVersion() + " printer=" + ServerProtocol.effective()
                + " workRange=" + Configs.Core.WORK_RANGE.getDoubleValue() + " effRange=" + com.autyism.printer.utils.ConfigUtils.getEffectiveRange()
                + " layered=" + Configs.Print.LAYERED_MODE.getBooleanValue() + " scope=" + Configs.Print.LAYER_DONE_SCOPE.getOptionListValue()
                + " adjustFirst=" + Configs.Print.ADJUST_FIRST.getBooleanValue() + " lagCheck=" + Configs.Core.LAG_CHECK.getBooleanValue()
                + " timeLimit=" + Configs.Core.ITERATION_TIME_LIMIT.getIntegerValue() + " antiCheatDisabled(client copy)=" + carpetAntiCheat()
                + " printerSeesIt=" + com.autyism.printer.compat.CarpetRules.antiCheatDisabled()));

        // 平台只有一种材料：一次发够（机器的材料种类多，每种最多三组，免得背包放不下）
        int maxStacks = flat > 0 ? 9 : 3;
        if (survival) supply(context, expected, min.getY(), maxStacks);
        context.runOnClient(c -> {
            TickTrace.reset();
            GT.enablePrint();
        });
        // -Pmachinetrace=from-to：这几 tick 每 tick 打一行（true = 1-400）
        String traceProp = prop("ale.machinetrace", "");
        int traceFrom = -1, traceTo = -1;
        if (traceProp.equals("true")) {
            traceFrom = 1;
            traceTo = 400;
        } else if (traceProp.matches("[0-9]+-[0-9]+")) {
            traceFrom = Integer.parseInt(traceProp.split("-")[0]);
            traceTo = Integer.parseInt(traceProp.split("-")[1]);
        }
        List<Sample> samples = new ArrayList<>();
        Map<Integer, Integer> layerFirstTick = new TreeMap<>();
        // -Pmachineprofile=true：打印机扫描时每毫秒取一次主线程的调用栈，最后列出时间花在哪些方法里
        StackSampler sampler = Boolean.getBoolean("ale.machineprofile") ? StackSampler.start(context.computeOnClient(c -> Thread.currentThread())) : null;
        int lastActions = context.computeOnClient(c -> TickTrace.actions);
        Integer movedTo = null;
        int lastMatch = -1, lastChange = 0, restartAt = -1, restartFirstAction = -1, restartLayerBefore = Integer.MIN_VALUE;
        int firstAction = -1, firstMatch = -1, movements = 0, lastMovementLog = -100, movementSamples = 0;
        int t;
        for (t = 1; t <= maxTicks; t++) {
            context.waitTick();
            final int tick = t;
            Sample s = context.computeOnClient(c -> {
                Sample r = new Sample(tick, TickTrace.actions, TickTrace.stop, TickTrace.detail, ModuleManager.PRINT.getCurrentLayer(),
                        c.level == null ? 0 : ActionConfirm.size(), String.format(Locale.ROOT, "scan=%.1fms exec=%.1fms send=%.1fms",
                                TickTrace.iterNanos / 1e6, TickTrace.execNanos / 1e6, TickTrace.sendNanos / 1e6) + " " + Prof.takeLine());
                TickTrace.reset();
                return r;
            });
            int acted = s.actions() - lastActions;
            lastActions = s.actions();
            samples.add(new Sample(t, acted, s.stop(), s.detail(), s.layer(), s.pending(), s.timing()));
            if (traceFrom >= 0 && t >= traceFrom && t <= traceTo) {
                GT.log("[machine] tick " + t + " acted=" + acted + " layer=" + s.layer() + " " + s.timing() + " stop=" + s.stop() + " " + s.detail());
            }
            if (s.layer() != null) layerFirstTick.putIfAbsent(s.layer(), t);
            if (firstAction < 0 && acted > 0) firstAction = t;
            // 机器自己动了（有方块正被活塞推着走、或者投影里收着的活塞伸出来了）：每 5 tick 看一次，数一共看到几次；
            // 前 3 次记下当时打印机最近放了什么
            if (t % 5 == 0) {
                List<String> moving = context.computeOnClient(c -> movingNow(c.level, expected, min, max));
                if (!moving.isEmpty()) {
                    movementSamples++;
                    if (movements < 3 && t - lastMovementLog >= 20) {
                        movements++;
                        lastMovementLog = t;
                        GT.log("[machine] MOVEMENT at tick " + t + ": " + moving);
                        for (String r : context.computeOnClient(c -> UseRecord.recent(40))) GT.log("[machine]   sent " + r);
                    }
                }
            }
            // 玩家跟着当前层：在这一层上方 3 格（和自己搭的时候一样飞在旁边）
            if (s.layer() != null && !s.layer().equals(movedTo)) {
                movedTo = s.layer();
                int y = s.layer() + 3;
                context.runOnClient(c -> {
                    c.player.setPos(cx, y, cz);
                    c.player.setDeltaMovement(Vec3.ZERO);
                    if (!survival) {
                        c.player.getAbilities().flying = true;
                        c.player.onUpdateAbilities();
                    }
                });
            }
            if (restartAt > 0 && restartFirstAction < 0 && acted > 0) {
                restartFirstAction = t;
                GT.log("[machine] after turning the printer on again: first action after " + (t - restartAt) + " ticks (layer before "
                        + restartLayerBefore + ", now " + s.layer() + ")");
            }
            // 平台只有几百格：每 tick 都数，量出放完的准确时间
            if (t % 10 != 0 && flat == 0) continue;
            int match = countMatching(context, expected);
            if (match != lastMatch) {
                lastMatch = match;
                lastChange = t;
            }
            if (firstMatch < 0 && match > 0) firstMatch = t;
            if (flat > 0 && match == expected.size()) {
                GT.log("[machine] " + name + " all " + match + " blocks done at tick " + t + " (first block seen at tick " + firstMatch
                        + ", first action at tick " + firstAction + ")");
                break;
            }
            if (t % 10 != 0) continue;
            if (survival && t % 20 == 0) supply(context, expected, s.layer() == null ? min.getY() : s.layer(), maxStacks);
            if (t % 200 == 0) {
                GT.log(String.format(Locale.ROOT, "[machine] t%d matching=%d/%d layer=%s pending=%d", t, match, expected.size(), s.layer(), s.pending()));
            }
            // 打到一半：关掉打印机再打开，量重新开始要多久（用户说开机后要 15~20 秒才开始打新的一层）
            if (restartAt < 0 && flat == 0 && match >= expected.size() / 2) {
                restartLayerBefore = s.layer() == null ? Integer.MIN_VALUE : s.layer();
                context.runOnClient(c -> Configs.Core.WORK_SWITCH.setBooleanValue(false));
                context.waitTicks(40);
                context.runOnClient(c -> Configs.Core.WORK_SWITCH.setBooleanValue(true));
                restartAt = t;
                GT.log("[machine] t" + t + " printer turned off for 40 ticks and on again (layer " + restartLayerBefore + ", matching " + match + ")");
            }
            if (t - lastChange >= 600) break;
        }
        context.runOnClient(c -> GT.disableAll());
        if (sampler != null) sampler.stopAndReport();
        GT.log("[machine] " + name + " last progress at tick " + lastChange + " (matching " + lastMatch + "/" + expected.size() + ", first block seen at tick "
                + firstMatch + ", machine seen moving in " + movementSamples + " checks)");
        context.waitTicks(40);
        summarize(name, samples, layerFirstTick, t);
        verify(context, name, expected);
    }

    // 现在正在被推着的方块、投影里收着却伸出来的活塞（最多 8 个）
    private static List<String> movingNow(net.minecraft.client.multiplayer.ClientLevel level, Map<BlockPos, BlockState> expected, BlockPos min, BlockPos max) {
        List<String> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockState s = level.getBlockState(p);
            boolean moving = s.getBlock() instanceof net.minecraft.world.level.block.piston.MovingPistonBlock;
            if (!moving && s.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock && s.getValue(net.minecraft.world.level.block.piston.PistonBaseBlock.EXTENDED)) {
                BlockState want = expected.get(p);
                moving = want == null || !want.hasProperty(net.minecraft.world.level.block.piston.PistonBaseBlock.EXTENDED)
                        || !want.getValue(net.minecraft.world.level.block.piston.PistonBaseBlock.EXTENDED);
            }
            if (moving) {
                out.add(p.toShortString() + " " + s);
                if (out.size() >= 8) break;
            }
        }
        return out;
    }

    // 在世界里铺一层 n x n 的下界砖，存成投影，再清掉
    private static void captureFlat(ClientGameTestContext context, int n) {
        BlockPos a = ORIGIN, b = ORIGIN.offset(n - 1, 0, n - 1);
        String box = String.format(Locale.ROOT, "%d %d %d %d %d %d", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
        command(context, "tp @s " + (a.getX() + n / 2) + " " + (a.getY() + 3) + " " + (a.getZ() + n / 2));
        command(context, "fill " + box + " nether_bricks");
        context.waitFor(c -> c.level.getBlockState(a).is(net.minecraft.world.level.block.Blocks.NETHER_BRICKS)
                && c.level.getBlockState(b).is(net.minecraft.world.level.block.Blocks.NETHER_BRICKS), 400);
        boolean written = context.computeOnClient(c -> {
            var area = new fi.dy.masa.litematica.selection.AreaSelection();
            area.setName("ale_machine");
            area.addSubRegionBox(new fi.dy.masa.litematica.selection.Box(a, b, "ale_machine"), false);
            area.setExplicitOrigin(a);
            var schematic = LitematicaSchematic.createFromWorld(c.level, area, new LitematicaSchematic.SchematicSaveInfo(false, false), "ALE",
                    msg -> GT.log("capture: " + msg));
            return schematic != null && schematic.writeToFile(DataManager.getSchematicsBaseDirectory(), "ale_machine", true);
        });
        if (!written) throw new AssertionError("[machine] could not save the flat schematic");
        command(context, "fill " + box + " air");
        context.waitFor(c -> c.level.getBlockState(a).isAir() && c.level.getBlockState(b).isAir(), 400);
    }

    private static BlockPos firstNonAir(BlockPos min, BlockPos max) {
        var w = SchematicWorldHandler.getSchematicWorld();
        if (w == null) return min;
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!w.getBlockState(p).isAir()) return p.immutable();
        }
        return min;
    }

    // 服务端的地毯规则在客户端的副本（装了地毯的客户端连上地毯服务器时会收到全部规则）
    private static String carpetAntiCheat() {
        try {
            return String.valueOf(Class.forName("carpet.CarpetSettings").getField("antiCheatDisabled").getBoolean(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            return "carpet not loaded";
        }
    }

    // 生存模式：当前层和上面两层还缺的方块，背包里不够就发（每种最多两组），背包快满时清掉用不上的
    private static void supply(ClientGameTestContext context, Map<BlockPos, BlockState> expected, int layer, int maxStacks) {
        List<String> commands = context.computeOnClient(c -> {
            // 先当前这一层，再上面两层（背包放不下时先保证当前这一层的材料）
            Map<Item, Integer> need = new LinkedHashMap<>();
            for (int yy = layer; yy <= layer + 2; yy++) for (var e : expected.entrySet()) {
                int y = e.getKey().getY();
                if (y != yy) continue;
                if (sameIgnoringRuntime(e.getValue(), c.level.getBlockState(e.getKey()))) continue;
                Item item = e.getValue().getBlock().asItem();
                if (item == Items.AIR) continue;
                need.merge(item, 1, Integer::sum);
                // 用户的设置用海带填堆肥桶（每一级平均要 3 个多）
                if (e.getValue().getBlock() instanceof net.minecraft.world.level.block.ComposterBlock
                        && e.getValue().getValue(net.minecraft.world.level.block.ComposterBlock.LEVEL) > 0) {
                    need.merge(Items.KELP, 8 * e.getValue().getValue(net.minecraft.world.level.block.ComposterBlock.LEVEL), Integer::sum);
                }
            }
            var inv = c.player.getInventory();
            Map<Item, Integer> have = new HashMap<>();
            int free = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack st = inv.getItem(i);
                if (st.isEmpty()) free++;
                else have.merge(st.getItem(), st.getCount(), Integer::sum);
            }
            List<String> out = new ArrayList<>();
            if (free < 8) {
                for (Item item : have.keySet()) {
                    if (!need.containsKey(item)) out.add("clear @s " + BuiltInRegistries.ITEM.getKey(item));
                }
            }
            // 背包放不下的不发（放不下的会掉在地上，测试就成了缺材料）：按空格子数发，每种最多 maxStacks 组
            int room = free - 2;
            for (var e : need.entrySet()) {
                int stack = new ItemStack(e.getKey()).getMaxStackSize();
                int want = Math.min(maxStacks * stack, e.getValue() + 8);
                int h = have.getOrDefault(e.getKey(), 0);
                if (h >= want) continue;
                int stacks = Math.min(room, (want - h + stack - 1) / stack);
                if (stacks <= 0) continue;
                room -= stacks;
                out.add("give @s " + BuiltInRegistries.ITEM.getKey(e.getKey()) + " " + Math.min(want - h, stacks * stack));
            }
            return out;
        });
        for (String cmd : commands) command(context, cmd);
    }

    private static int countMatching(ClientGameTestContext context, Map<BlockPos, BlockState> expected) {
        return context.computeOnClient(c -> {
            int n = 0;
            for (var e : expected.entrySet()) {
                if (sameIgnoringRuntime(e.getValue(), c.level.getBlockState(e.getKey()))) n++;
            }
            return n;
        });
    }

    private static boolean sameIgnoringRuntime(BlockState want, BlockState have) {
        if (want.getBlock() != have.getBlock()) return false;
        for (Property<?> p : want.getProperties()) {
            if (!RUNTIME_PROPS.contains(p.getName()) && !want.getValue(p).equals(have.getValue(p))) return false;
        }
        return true;
    }

    // 汇总：没放东西的 tick 停在哪、最长的停顿、每层开始的 tick
    private static void summarize(String name, List<Sample> samples, Map<Integer, Integer> layerFirstTick, int ticks) {
        int acting = 0, actions = 0;
        Map<String, Integer> idleByStop = new TreeMap<>();
        Map<String, Integer> idleByDetail = new TreeMap<>();
        for (Sample s : samples) {
            if (s.actions() > 0) {
                acting++;
                actions += s.actions();
                continue;
            }
            idleByStop.merge(s.stop().isEmpty() ? "(none)" : s.stop(), 1, Integer::sum);
            if (!s.detail().isEmpty()) idleByDetail.merge(category(s.detail()), 1, Integer::sum);
        }
        GT.log(String.format(Locale.ROOT, "[machine] %s SUMMARY ticks=%d with-actions=%d actions=%d", name, ticks, acting, actions));
        idleByStop.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> GT.log("[machine]   idle at " + e.getKey() + ": " + e.getValue() + " ticks"));
        idleByDetail.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
                .forEach(e -> GT.log("[machine]   idle detail " + e.getKey() + ": " + e.getValue() + " ticks"));
        // 连续 10 tick 以上什么都没放
        List<int[]> streaks = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= samples.size(); i++) {
            boolean idle = i < samples.size() && samples.get(i).actions() == 0;
            if (idle && start < 0) start = i;
            if (!idle && start >= 0) {
                if (i - start >= 10) streaks.add(new int[]{start, i - start});
                start = -1;
            }
        }
        int total = 0;
        for (int[] s : streaks) total += s[1];
        GT.log("[machine]   " + streaks.size() + " pauses of 10+ ticks, " + total + " ticks in total");
        streaks.stream().sorted((a, b) -> b[1] - a[1]).limit(30).forEach(s -> {
            Set<String> stops = new LinkedHashSet<>();
            for (int i = s[0]; i < s[0] + s[1]; i++) stops.add(samples.get(i).stop());
            Sample first = samples.get(s[0]);
            Sample last = samples.get(s[0] + s[1] - 1);
            GT.log("[machine]   pause t" + first.tick() + " for " + s[1] + " ticks, layer " + first.layer() + "->" + last.layer()
                    + " stops=" + stops + " first=" + clip(first.detail()) + " last=" + clip(last.detail()) + " pending=" + first.pending() + "->" + last.pending());
        });
        StringBuilder layers = new StringBuilder();
        layerFirstTick.forEach((y, tick) -> layers.append(' ').append(y).append('@').append(tick));
        GT.log("[machine]   layers first seen:" + layers);
    }

    private static String category(String detail) {
        String[] w = detail.split(" ");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.length && i < 5; i++) {
            if (w[i].matches("-?\\d+,.*") || w[i].startsWith("held=") || w[i].startsWith("left=") || w[i].startsWith("count=")
                    || w[i].startsWith("inFlight=") || w[i].matches("\\d+")) break;
            if (b.length() > 0) b.append(' ');
            b.append(w[i]);
        }
        return b.toString();
    }

    private static String clip(String s) {
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    // 逐格核对：缺的、放错的、状态不对的（红石运行时属性不算）
    private static void verify(ClientGameTestContext context, String name, Map<BlockPos, BlockState> expected) {
        List<String> lines = context.computeOnClient(c -> {
            Map<String, Integer> kinds = new TreeMap<>();
            List<String> detailLines = new ArrayList<>();
            int[] counts = new int[4]; // ok, missing, wrong block, wrong state
            for (var e : expected.entrySet()) {
                BlockState want = e.getValue();
                BlockState have = c.level.getBlockState(e.getKey());
                if (sameIgnoringRuntime(want, have)) {
                    counts[0]++;
                    continue;
                }
                String key;
                if (have.isAir() || have.canBeReplaced()) {
                    counts[1]++;
                    key = "MISSING " + id(want);
                } else if (have.getBlock() != want.getBlock()) {
                    counts[2]++;
                    key = "WRONG_BLOCK " + id(want) + " -> " + id(have);
                } else {
                    counts[3]++;
                    StringBuilder diff = new StringBuilder();
                    for (Property<?> p : want.getProperties()) {
                        if (!RUNTIME_PROPS.contains(p.getName()) && !want.getValue(p).equals(have.getValue(p))) {
                            diff.append(' ').append(p.getName()).append('=').append(want.getValue(p)).append("->").append(have.getValue(p));
                        }
                    }
                    key = "WRONG_STATE " + id(want) + diff;
                }
                // -Pmachinedump=comparator,observer：这几种方块每一格都列出来（默认每种只列 4 个例子）
                boolean dumpAll = java.util.Arrays.asList(prop("ale.machinedump", "").split(",")).contains(id(want));
                if (kinds.merge(key, 1, Integer::sum) <= 4 || dumpAll) {
                    // 前几个附上为什么没放：侦测器看的那一格和身后那一格，其他方块看下面那一格
                    var w = SchematicWorldHandler.getSchematicWorld();
                    StringBuilder why = new StringBuilder("[machine]     e.g. " + e.getKey().toShortString() + " want=" + want + " have=" + have);
                    if (want.getBlock() instanceof net.minecraft.world.level.block.ObserverBlock) {
                        var facing = want.getValue(net.minecraft.world.level.block.ObserverBlock.FACING);
                        BlockPos in = e.getKey().relative(facing), outPos = e.getKey().relative(facing.getOpposite());
                        why.append(" | input ").append(w.getBlockState(in)).append(" have ").append(c.level.getBlockState(in))
                                .append(" | output ").append(w.getBlockState(outPos)).append(" have ").append(c.level.getBlockState(outPos));
                    } else {
                        BlockPos below = e.getKey().below();
                        why.append(" | below ").append(w.getBlockState(below)).append(" have ").append(c.level.getBlockState(below));
                    }
                    detailLines.add(why.toString());
                }
            }
            List<String> out = new ArrayList<>();
            out.add("[machine] " + name + " RESULT ok=" + counts[0] + " missing=" + counts[1] + " wrongBlock=" + counts[2] + " wrongState=" + counts[3]);
            kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(30)
                    .forEach(e -> out.add("[machine]   " + e.getValue() + "x " + e.getKey()));
            out.addAll(detailLines);
            return out;
        });
        for (String l : lines) GT.log(l);
    }

    private static String id(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }

    private static void command(ClientGameTestContext context, String cmd) {
        context.runOnClient(client -> client.player.connection.sendCommand(cmd));
        context.waitTicks(1);
    }

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : v.strip();
    }
}
//?} else {
/*package com.autyism.printer.gametest;

// 只在 1.21.11 上跑（见 1.21.11 上的这个文件）：其他版本是空的
@SuppressWarnings("UnstableApiUsage")
public final class MachineGameTest implements net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest {
    @Override
    public void runTest(net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext context) {
    }
}
*///?}
