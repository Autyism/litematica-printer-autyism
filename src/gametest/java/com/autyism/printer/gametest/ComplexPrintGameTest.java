package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.handler.ModuleManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 自测：用用户文件夹里带大量内饰的真实投影（楼梯/台阶/门/告示牌/红石/容器/流体……），创造模式分层打印，
 * 打完后逐方块与投影对比，按“方块 + 问题类型 + 不同的属性”汇总，找出打印机放错的东西。
 * <p>
 * 投影列表：D:/Dev/Projects/ale-work/complex-list.txt（每行一个 .litematic 路径，UTF-8），没有就用内置的几个。
 */
@SuppressWarnings("UnstableApiUsage")
public final class ComplexPrintGameTest implements FabricClientGameTest {
    private static final Path LIST = Path.of(prop("ale.list", "D:/Dev/Projects/ale-work/complex-list.txt"));
    /** 红石运行时状态：电路自己会改（充能、点亮、活塞伸出……），打印机不需要也不可能放成一样，单独统计 */
    private static final java.util.Set<String> RUNTIME_PROPS = java.util.Set.of("powered", "lit", "power", "triggered", "extended", "enabled", "locked", "short", "occupied", "attached", "disarmed");
    private static final String BASE = "D:/Games/PCL2/.minecraft/schematics/Up 分享后期工业/完整版2413文件/f房屋 居所 类/";
    private static final List<String> DEFAULTS = List.of(
            BASE + "不同时代 国家/z中世纪建筑/酒馆旅店/蔚蓝旅店.litematic",
            BASE + "不同种类 功能/b别墅[]居民区/aa别墅1(三层带泳池).litematic",
            BASE + "不同时代 国家/z中世纪建筑/中世纪庄园.litematic");

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("complex")) return;
        List<String> files = new ArrayList<>(DEFAULTS);
        try {
            if (Files.exists(LIST)) {
                files.clear();
                for (String line : Files.readAllLines(LIST, StandardCharsets.UTF_8)) {
                    line = line.strip().replace("\uFEFF", "");
                    if (!line.isEmpty() && !line.startsWith("#")) files.add(line);
                }
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        int maxTicks = Integer.parseInt(prop("ale.ticks", "6000"));
        List<String> summary = new ArrayList<>();
        // -Pstartindex=N：第一个投影放在第 N 个位置（x = 200 + N*200），用来单独复现排在后面的投影的问题
        int index = Integer.parseInt(prop("ale.startindex", "0"));
        for (String file : files) {
            Path src = Path.of(file);
            if (!Files.exists(src)) {
                GT.log("[complex] missing " + file);
                continue;
            }
            summary.add(runOne(context, src, index++, maxTicks));
        }
        for (String s : summary) GT.log("[complex] SUMMARY " + s);
    }

    private String runOne(ClientGameTestContext context, Path src, int index, int maxTicks) {
        String name = src.getFileName().toString();
        BlockPos origin = new BlockPos(200 + index * 200, -60, 200);
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            sp.getServer().runCommand("gamemode creative @a");
            // 关掉随机刻：草蔓延、藤蔓生长、树苗长大这些自然变化不算打印机的错
            GT.randomTicksOff(sp);
            context.runOnClient(c -> c.options.renderDistance().set(8));
            SchematicPlacement placement = context.computeOnClient(client -> {
                try {
                    Path dir = DataManager.getSchematicsBaseDirectory();
                    Files.copy(src, dir.resolve("ale_complex_" + index + ".litematic"), StandardCopyOption.REPLACE_EXISTING);
                    LitematicaSchematic schematic = LitematicaSchematic.createFromFile(dir, "ale_complex_" + index + ".litematic");
                    SchematicPlacement p = SchematicPlacement.createFor(schematic, origin, "complex" + index, true, true);
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
            // 站在建筑正上方（飞着），不挡住任何一层
            double cx = (min.getX() + max.getX() + 1) / 2.0, cz = (min.getZ() + max.getZ() + 1) / 2.0;
            int standY = max.getY() + 3;
            double range = Math.min(64, Math.ceil(Math.sqrt(Math.pow((max.getX() - min.getX()) / 2.0 + 1, 2) + Math.pow((max.getZ() - min.getZ()) / 2.0 + 1, 2) + Math.pow(standY + 1.62 - min.getY(), 2))) + 1);
            sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f %d %.1f", cx, standY, cz));
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - cx) < 0.01, 200);
            context.runOnClient(c -> {
                c.player.getAbilities().flying = true;
                c.player.onUpdateAbilities();
            });
            context.waitTicks(100);
            Map<BlockPos, BlockState> expected = context.computeOnClient(c -> {
                var w = SchematicWorldHandler.getSchematicWorld();
                Map<BlockPos, BlockState> m = new LinkedHashMap<>();
                for (BlockPos p : BlockPos.betweenClosed(min, max)) m.put(p.immutable(), w.getBlockState(p));
                return m;
            });
            long nonAir = expected.values().stream().filter(s -> !s.isAir()).count();
            GT.log("[complex] " + name + " box " + min.toShortString() + " -> " + max.toShortString() + " non-air=" + nonAir + " range=" + range);

            context.runOnClient(c -> {
                Configs.Core.WORK_RANGE.setDoubleValue(range);
                Configs.Print.LAYERED_MODE.setBooleanValue(true);
                Configs.Print.PRINT_FLUIDS_WITH_BUCKET.setBooleanValue(true); // 默认关，测试时打开
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(16);
                GT.enablePrint();
            });
            int last = -1, lastChange = 0, t = 0;
            for (t = 1; t <= maxTicks; t++) {
                context.waitTick();
                if (t <= 100 && t % 5 == 0) {
                    String st = context.computeOnClient(c -> "layer=" + ModuleManager.PRINT.getCurrentLayer() + " pending=" + ModuleManager.PRINT.getLastPendingPos());
                    GT.log("[complex] " + name + " early t" + t + " " + st);
                }
                if (t % 40 != 0) continue;
                int placed = countMatching(sp, expected);
                if (placed != last) {
                    last = placed;
                    lastChange = t;
                }
                if (t % 400 == 0 || t <= 400 && t % 80 == 0) {
                    int layer = context.computeOnClient(c -> ModuleManager.PRINT.getCurrentLayer());
                    GT.log("[complex] " + name + " t" + t + " matching=" + placed + "/" + nonAir + " layer=" + layer);
                }
                if (t % 400 == 0 || t - lastChange == 400) {
                    GT.log("[complex] " + name + " STALL " + context.computeOnClient(c -> {
                        BlockPos lp = ModuleManager.PRINT.getLastPendingPos();
                        BlockPos fs = ModuleManager.PRINT.getLastForcedSkipPos();
                        String forced = " forcedSkips=" + ModuleManager.PRINT.getForcedLayerSkips() + (fs == null ? "" : " lastForced=" + fs.toShortString()
                                + " want=" + SchematicWorldHandler.getSchematicWorld().getBlockState(fs) + " have=" + c.level.getBlockState(fs)
                                + " below=" + c.level.getBlockState(fs.below()));
                        forced += " STATE " + ModuleManager.PRINT.debugState();
                        if (lp == null) return "no pending pos, layer=" + ModuleManager.PRINT.getCurrentLayer() + forced;
                        return "layer=" + ModuleManager.PRINT.getCurrentLayer() + " pending=" + lp.toShortString()
                                + " want=" + SchematicWorldHandler.getSchematicWorld().getBlockState(lp)
                                + " have=" + c.level.getBlockState(lp)
                                + " below=" + c.level.getBlockState(lp.below())
                                + " canInteract=" + com.autyism.printer.utils.PlayerUtils.canInteracted(lp) + forced;
                    }));
                }
                if (placed >= nonAir || t - lastChange >= 600) break;
            }
            context.runOnClient(c -> GT.disableAll());
            context.waitTicks(20);
            String report = compare(sp, name, expected);
            shoot(context, sp, name, min, max, index);
            // 关世界之前删掉投影放置（关世界时 Litematica 会把放置存进这个世界的配置，下次进同名世界又读回来）
            GT.removeAllPlacements(context);
            return name + " ticks=" + t + " " + report;
        } finally {
            GT.removeAllPlacements(context);
            context.runOnClient(c -> GT.disableAll());
        }
    }

    /** 完工截图：隐藏投影和界面，从建筑斜前方高处看整栋建筑 */
    private static void shoot(ClientGameTestContext context, TestSingleplayerContext sp, String name, BlockPos min, BlockPos max, int index) {
        double cx = (min.getX() + max.getX() + 1) / 2.0, cz = (min.getZ() + max.getZ() + 1) / 2.0;
        double size = Math.max(max.getX() - min.getX(), Math.max(max.getZ() - min.getZ(), max.getY() - min.getY())) + 1;
        double px = cx - size * 0.6, pz = cz + size * 0.75, py = min.getY() + size * 0.55;
        double dx = cx - px, dz = cz - pz, dy = (min.getY() + max.getY()) / 2.0 - (py + 1.62);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        sp.getServer().runCommand("time set noon");
        sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f %.1f %.1f %.1f %.1f", px, py, pz, yaw, pitch));
        context.runOnClient(c -> {
            //? if >=26.2 {
            /*if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
            *///?} else
            c.options.hideGui = true;
            c.options.renderDistance().set(12);
            fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_RENDERING.setBooleanValue(false);
        });
        context.waitTicks(160); // 等分层完成提示消失
        java.nio.file.Path shot = context.takeScreenshot("complex-" + index);
        GT.log("[complex] " + name + " SCREENSHOT " + shot.toAbsolutePath());
        context.runOnClient(c -> {
            //? if >=26.2 {
            /*if (c.gui.hud.isHidden()) c.gui.hud.toggle();
            *///?} else
            c.options.hideGui = false;
            fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_RENDERING.setBooleanValue(true);
        });
    }

    private static int countMatching(TestSingleplayerContext sp, Map<BlockPos, BlockState> expected) {
        return sp.getServer().computeOnServer(server -> {
            var level = server.overworld();
            int n = 0;
            for (var e : expected.entrySet()) {
                if (!e.getValue().isAir() && sameIgnoringRuntime(e.getValue(), level.getBlockState(e.getKey()))) n++;
            }
            return n;
        });
    }

    /** 方块相同，并且除了红石运行时属性以外的属性都相同（音符盒音高、中继器延迟、朝向……） */
    private static boolean sameIgnoringRuntime(BlockState want, BlockState have) {
        if (want.getBlock() != have.getBlock()) return false;
        for (Property<?> p : want.getProperties()) {
            if (!RUNTIME_PROPS.contains(p.getName()) && !want.getValue(p).equals(have.getValue(p))) return false;
        }
        return true;
    }

    /** 逐方块比较并汇总问题 */
    private static String compare(TestSingleplayerContext sp, String name, Map<BlockPos, BlockState> expected) {
        Map<String, List<String>> issues = new TreeMap<>();
        int[] counts = new int[6]; // ok, missing, wrongBlock, wrongState, extra, runtimeState
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (var e : expected.entrySet()) {
                BlockState want = e.getValue();
                BlockState have = level.getBlockState(e.getKey());
                String key;
                if (want.equals(have)) {
                    counts[0]++;
                    continue;
                }
                if (want.isAir()) {
                    if (have.isAir()) {
                        counts[0]++;
                        continue;
                    }
                    counts[4]++;
                    key = "EXTRA " + id(have);
                } else if (have.isAir() || (have.getFluidState().isSource() && !want.getFluidState().isSource() && have.getBlock() != want.getBlock())) {
                    counts[1]++;
                    key = "MISSING " + id(want) + (have.isAir() ? "" : " (have " + id(have) + ")");
                } else if (have.getBlock() != want.getBlock()) {
                    counts[2]++;
                    key = "WRONG_BLOCK " + id(want) + " -> " + id(have);
                } else {
                    StringBuilder diff = new StringBuilder();
                    boolean runtimeOnly = true;
                    for (Property<?> p : want.getProperties()) {
                        if (!want.getValue(p).equals(have.getValue(p))) {
                            diff.append(p.getName()).append('=').append(want.getValue(p)).append("->").append(have.getValue(p)).append(' ');
                            if (!RUNTIME_PROPS.contains(p.getName())) runtimeOnly = false;
                        }
                    }
                    if (runtimeOnly) counts[5]++;
                    else counts[3]++;
                    key = (runtimeOnly ? "RUNTIME_STATE " : "WRONG_STATE ") + id(want) + " " + diff.toString().trim();
                }
                String extra = "";
                // 每类问题的前两个附上六个邻居“投影 vs 世界”（排查连接状态、被推动等问题）
                if (!key.startsWith("RUNTIME_STATE") && issues.getOrDefault(key, List.of()).size() < 2) {
                    StringBuilder nb = new StringBuilder(" | ");
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                        BlockPos n = e.getKey().relative(d);
                        nb.append(d.getName()).append(": want ").append(id(expected.getOrDefault(n, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())))
                                .append(" have ").append(id(level.getBlockState(n))).append("; ");
                    }
                    extra = nb.toString();
                }
                issues.computeIfAbsent(key, k -> new ArrayList<>()).add(e.getKey().toShortString() + " want=" + want + extra);
            }
        });
        GT.log("[complex] " + name + " RESULT ok=" + counts[0] + " missing=" + counts[1] + " wrongBlock=" + counts[2] + " wrongState=" + counts[3] + " extra=" + counts[4] + " runtimeState=" + counts[5]);
        // 按“同类问题数量”排序输出
        issues.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .limit(60)
                .forEach(e -> GT.log("[complex] " + name + "   " + e.getValue().size() + "x " + e.getKey() + "  e.g. " + e.getValue().get(0)));
        // -Pdumpmissing=true：列出每一类问题的全部坐标（排查用）
        if (Boolean.getBoolean("ale.dumpmissing")) {
            issues.forEach((k, v) -> {
                if (k.startsWith("RUNTIME_STATE")) return;
                for (String pos : v) GT.log("[complex] " + name + "   ALL " + k + " @ " + pos.replaceAll(" want=.*", ""));
            });
        }
        return "ok=" + counts[0] + " missing=" + counts[1] + " wrongBlock=" + counts[2] + " wrongState=" + counts[3] + " extra=" + counts[4] + " runtimeState=" + counts[5];
    }

    /** 系统属性；没设置或是空字符串（真实实例启动脚本里变量为空）时用默认值 */
    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : v.strip();
    }

    private static String id(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }
}
