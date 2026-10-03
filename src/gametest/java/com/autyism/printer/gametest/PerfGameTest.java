package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.config.IConfigBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 性能对比：同一个场景（玩家看着一栋 2 万多方块、带实体的投影），分别测
 * “ALE 全关 / ALE 全开（交替两轮）/ 打印机正在打印 / 打印机开着空转 / 打印机关”
 * 的客户端帧数和服务端每 tick 耗时。ALE 不在时（开发环境）只测打印机部分。
 */
@SuppressWarnings("UnstableApiUsage")
public final class PerfGameTest implements FabricClientGameTest {
    private static final String VILLA = "D:/Games/PCL2/.minecraft/schematics/Up 分享后期工业/完整版2413文件/f房屋 居所 类/不同种类 功能/b别墅[]居民区/aa别墅1(三层带泳池).litematic";
    private static final String[] ALE_FEATURES = {"BLOCK_PICKER", "SAVE_EDIT_BUTTONS", "INFO_FLUIDS_ENTITIES", "TRANSLUCENT_ENTITIES",
            "WATERLOGGED_MARKER", "MATERIAL_LIST_ENTITIES", "VERIFIER_CONTAINERS", "SIGN_REPLACE_FIX", "RENDER_THROUGH_GLASS"};

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("perf")) return;
        Path src = Path.of(VILLA);
        if (!Files.exists(src)) {
            GT.log("[perf] schematic missing, skipped");
            return;
        }
        List<IConfigBoolean> ale = aleFeatures();
        BlockPos origin = new BlockPos(200, -60, 200);
        int[] savedFps = new int[1];
        boolean[] savedVsync = new boolean[1];
        net.minecraft.client.InactivityFpsLimit[] savedInactive = new net.minecraft.client.InactivityFpsLimit[1];
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            sp.getServer().runCommand("gamemode creative @a");
            sp.getServer().runCommand("gamerule randomTickSpeed 0");
            sp.getServer().runCommand("time set noon");
            context.runOnClient(c -> {
                savedFps[0] = c.options.framerateLimit().get();
                savedVsync[0] = c.options.enableVsync().get();
                c.options.framerateLimit().set(260);
                // 测试期间没有键鼠输入，原版会当成挂机把帧数限制到 30 / 10：关掉，只在最小化时限制
                savedInactive[0] = c.options.inactivityFpsLimit().get();
                c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                c.options.enableVsync().set(false);
                c.options.renderDistance().set(12);
            });
            SchematicPlacement placement = context.computeOnClient(client -> {
                try {
                    Path dir = DataManager.getSchematicsBaseDirectory();
                    Files.copy(src, dir.resolve("ale_perf.litematic"), StandardCopyOption.REPLACE_EXISTING);
                    LitematicaSchematic schematic = LitematicaSchematic.createFromFile(dir, "ale_perf.litematic");
                    SchematicPlacement p = SchematicPlacement.createFor(schematic, origin, "perf", true, true);
                    DataManager.getSchematicPlacementManager().addSchematicPlacement(p, false);
                    return p;
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            var size = context.computeOnClient(c -> placement.getSchematic().getTotalSize());
            double cx = origin.getX() + size.getX() / 2.0, cz = origin.getZ() + size.getZ() / 2.0;
            // 站在建筑斜前方的空中，整栋投影都在视野里
            double px = cx - size.getX() * 0.8, pz = cz + size.getZ() * 0.9, py = origin.getY() + size.getY() + 6;
            double yaw = Math.toDegrees(Math.atan2(-(cx - px), cz - pz));
            sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %.1f %.1f %.1f 25", px, py, pz, yaw));
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - px) < 0.01, 200);
            context.runOnClient(c -> {
                c.player.getAbilities().flying = true;
                c.player.onUpdateAbilities();
            });
            context.waitTicks(200); // 等投影区块全部生成网格

            List<String> report = new ArrayList<>();
            if (!ale.isEmpty()) {
                for (int round = 1; round <= 2; round++) {
                    setAll(context, ale, false);
                    report.add(measure(context, sp, "ALE off #" + round));
                    setAll(context, ale, true);
                    report.add(measure(context, sp, "ALE on  #" + round));
                }
            }
            report.add(measure(context, sp, "printer off"));
            context.runOnClient(c -> {
                Configs.Core.WORK_RANGE.setDoubleValue(64);
                Configs.Print.LAYERED_MODE.setBooleanValue(true);
                GT.enablePrint();
            });
            report.add(measure(context, sp, "printer printing"));
            // 打印一段时间后（大部分已放好）测“开着空转”
            context.waitTicks(1200);
            report.add(measure(context, sp, "printer on (mostly done)"));
            context.runOnClient(c -> GT.disableAll());
            report.add(measure(context, sp, "printer off again"));
            for (String r : report) GT.log("[perf] " + r);
            GT.removeAllPlacements(context);
        } finally {
            context.runOnClient(c -> {
                GT.disableAll();
                if (savedFps[0] > 0) c.options.framerateLimit().set(savedFps[0]);
                c.options.enableVsync().set(savedVsync[0]);
                if (savedInactive[0] != null) c.options.inactivityFpsLimit().set(savedInactive[0]);
            });
            setAll(context, ale, true);
        }
    }

    /** 等 2 秒稳定，然后 10 秒内每秒取一次帧数；同时取服务端平均每 tick 耗时 */
    private static String measure(ClientGameTestContext context, TestSingleplayerContext sp, String label) {
        context.waitTicks(40);
        List<Integer> fps = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            context.waitTicks(20);
            fps.add(context.computeOnClient(c -> c.getFps()));
        }
        double mspt = sp.getServer().computeOnServer(s -> s.getAverageTickTimeNanos() / 1_000_000.0);
        double avg = fps.stream().mapToInt(Integer::intValue).average().orElse(0);
        int min = fps.stream().mapToInt(Integer::intValue).min().orElse(0);
        return String.format(Locale.ROOT, "%-26s fps avg=%.0f min=%d  server mspt=%.2f  samples=%s", label, avg, min, mspt, fps);
    }

    private static void setAll(ClientGameTestContext context, List<IConfigBoolean> configs, boolean value) {
        context.runOnClient(c -> {
            for (IConfigBoolean b : configs) b.setBooleanValue(value);
        });
    }

    /** ALE 的功能开关（反射：打印机工程编译时不依赖 ALE） */
    private static List<IConfigBoolean> aleFeatures() {
        List<IConfigBoolean> list = new ArrayList<>();
        try {
            Class<?> cls = Class.forName("com.autyism.ale.config.AleConfigs$Generic");
            for (String name : ALE_FEATURES) {
                Field f = cls.getField(name);
                list.add((IConfigBoolean) f.get(null));
            }
        } catch (ReflectiveOperationException e) {
            GT.log("[perf] ALE not loaded, measuring printer only");
            list.clear();
        }
        return list;
    }
}
