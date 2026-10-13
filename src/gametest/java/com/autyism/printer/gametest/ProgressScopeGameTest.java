package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.LayerDoneScope;
import com.autyism.printer.enums.ProgressScope;
import com.autyism.printer.gui.ConfigUi;
import com.autyism.printer.handler.ModuleManager;
import com.autyism.printer.handler.handlers.GUI;
import com.autyism.printer.utils.PlayerUtils;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.util.LayerMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 进度条统计范围：投影比工作范围大（48x8 一层，范围 6），站在一头打印。
 * 默认“整个投影（已加载）”：总数 = 投影里全部方块，已完成 = 服务端里真的放对的方块；
 * 换成“工作范围内”：只算范围内的方块，范围内打完就是 100%。渲染层照样限制统计。
 * 另外检查三个新设置都在设置界面里、有文字，并截图。
 */
@SuppressWarnings("UnstableApiUsage")
public final class ProgressScopeGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(760, 65, 700);
    private static final BlockPos MAX = new BlockPos(807, 65, 707);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("progress")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.randomTicksOff(sp);
            sp.getServer().runCommand("gamemode creative @a");
            GT.clearArena(sp, MIN.getX() - 6, MIN.getZ() - 6, MAX.getX() + 6, MAX.getZ() + 6, 75);
            double px = MIN.getX() - 1.5, pz = MIN.getZ() + 4.0;
            sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f 64 %.1f -90 20", px, pz));
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - px) < 0.01, 200);
            GT.setSchematicStable(context, MIN, MAX, p -> Blocks.STONE.defaultBlockState());
            long total = (long) (MAX.getX() - MIN.getX() + 1) * (MAX.getZ() - MIN.getZ() + 1);

            context.runOnClient(c -> {
                Configs.Core.WORK_RANGE.setDoubleValue(6);
                Configs.Core.RENDER_HUD.setBooleanValue(true);
                Configs.Print.HIDE_LAYER_MESSAGES.setBooleanValue(true);
                Configs.Print.LAYERED_MODE.setBooleanValue(true);
                Configs.Print.LAYER_DONE_SCOPE.setOptionListValue(LayerDoneScope.WORK_RANGE);
                GT.enablePrint();
            });
            if (Configs.Core.PROGRESS_SCOPE.getDefaultOptionListValue() != ProgressScope.LOADED_AREA
                    || context.computeOnClient(c -> Configs.Core.PROGRESS_SCOPE.getOptionListValue()) != ProgressScope.LOADED_AREA) {
                throw new AssertionError("[progress] the default should be the whole schematic (loaded area)");
            }
            int placed = waitStable(context, sp);
            if (placed == 0 || placed >= total) throw new AssertionError("[progress] expected only the part in range to be printed, got " + placed + "/" + total);

            // 默认：整个投影（已加载的区块）
            context.waitTicks(30);
            long[] loaded = context.computeOnClient(c -> {
                GUI.Progress p = ModuleManager.GUI.getPrintProgress();
                if (p != ModuleManager.GUI.getLoadedPrintProgress()) throw new AssertionError("[progress] the HUD is not showing the whole-schematic progress");
                return new long[]{p.getShownFinished(), p.getShownTotal(), Math.round(p.getProgress() * 1000)};
            });
            GT.log("[progress] whole schematic (default): " + loaded[0] + " / " + loaded[1] + " (" + loaded[2] / 10.0 + "%), server placed " + placed + "/" + total);
            if (loaded[1] != total || loaded[0] != placed) {
                throw new AssertionError("[progress] whole-schematic progress should be " + placed + " / " + total + ", got " + loaded[0] + " / " + loaded[1]);
            }
            context.runOnClient(c -> c.gui.getChat().clearMessages(false));
            context.waitTicks(2);
            GT.screenshot(context, "progress-whole-schematic");

            // 工作范围内
            context.runOnClient(c -> Configs.Core.PROGRESS_SCOPE.setOptionListValue(ProgressScope.WORK_RANGE));
            context.waitTicks(30);
            long inRange = context.computeOnClient(c -> {
                long n = 0;
                for (BlockPos p : BlockPos.betweenClosed(MIN, MAX)) if (PlayerUtils.canInteracted(p)) n++;
                return n;
            });
            long[] range = context.computeOnClient(c -> {
                GUI.Progress p = ModuleManager.GUI.getPrintProgress();
                if (p != ModuleManager.GUI.getRangePrintProgress()) throw new AssertionError("[progress] the HUD is not showing the in-range progress");
                return new long[]{p.getShownFinished(), p.getShownTotal(), Math.round(p.getProgress() * 1000)};
            });
            GT.log("[progress] within range: " + range[0] + " / " + range[1] + " (" + range[2] / 10.0 + "%), blocks in range " + inRange);
            if (range[1] != inRange || range[0] != range[1]) {
                throw new AssertionError("[progress] in-range progress should be " + inRange + " / " + inRange + ", got " + range[0] + " / " + range[1]);
            }
            GT.screenshot(context, "progress-within-range");

            // 渲染层只显示上面一层（投影不在里面）：整个投影的统计也不算这些方块
            context.runOnClient(c -> {
                Configs.Core.PROGRESS_SCOPE.setOptionListValue(ProgressScope.LOADED_AREA);
                var layers = DataManager.getRenderLayerRange();
                layers.setLayerMode(LayerMode.LAYER_RANGE);
                layers.setAxis(Direction.Axis.Y);
                layers.setLayerRangeMax(MIN.getY() + 1);
                layers.setLayerRangeMin(MIN.getY() + 1);
                layers.setLayerRangeMax(MIN.getY() + 1);
            });
            context.waitTicks(30);
            long layerTotal = context.computeOnClient(c -> ModuleManager.GUI.getPrintProgress().getShownTotal());
            context.runOnClient(c -> DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL));
            if (layerTotal != 0) throw new AssertionError("[progress] blocks outside the render layers were counted: " + layerTotal);
            GT.log("[progress] OK: whole schematic " + loaded[0] + "/" + loaded[1] + ", within range " + range[0] + "/" + range[1]
                    + ", render layers respected");
            context.runOnClient(c -> GT.disableAll());

            settings(context);
            restore(context);
        } finally {
            context.runOnClient(c -> {
                GT.disableAll();
                Configs.Core.PROGRESS_SCOPE.setOptionListValue(ProgressScope.LOADED_AREA);
                Configs.Print.LAYER_DONE_SCOPE.setOptionListValue(LayerDoneScope.WHOLE_LAYER);
                Configs.Core.RENDER_HUD.setBooleanValue(false);
                DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
                //? if >=26.2 {
                /*c.gui.setScreen(null);
                *///?} else
                c.setScreen(null);
            });
            TestSchematicRegion.clear();
        }
    }

    /** 退出世界之前把改过的设置改回来（MaLiLib 退出世界时会保存设置，下一个测试进世界时读回来） */
    private static void restore(ClientGameTestContext context) {
        context.runOnClient(c -> {
            GT.disableAll();
            Configs.Core.PROGRESS_SCOPE.setOptionListValue(ProgressScope.LOADED_AREA);
            Configs.Print.LAYER_DONE_SCOPE.setOptionListValue(LayerDoneScope.WHOLE_LAYER);
            Configs.Core.RENDER_HUD.setBooleanValue(false);
            Configs.Print.HIDE_LAYER_MESSAGES.setBooleanValue(false);
            DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        });
    }

    /** 服务端放好的方块数连续 40 tick 不变 */
    private static int waitStable(ClientGameTestContext context, TestSingleplayerContext sp) {
        int last = -1, same = 0;
        for (int t = 0; t < 1200; t++) {
            context.waitTick();
            int n = GT.countPlaced(sp, MIN, MAX);
            if (n == last && n > 0) {
                if (++same >= 40) return n;
            } else {
                same = 0;
                last = n;
            }
        }
        throw new AssertionError("[progress] printing never settled, placed " + last);
    }

    /** 三个新设置都在设置界面的分页里、看得到、有名字和说明（当前语言），截图 */
    private static void settings(ClientGameTestContext context) {
        List<String> problems = context.computeOnClient(c -> {
            List<String> out = new java.util.ArrayList<>();
            check(out, ConfigUi.Tab.CORE, Configs.Core.PROGRESS_SCOPE);
            check(out, ConfigUi.Tab.PRINT, Configs.Print.LAYER_DONE_SCOPE);
            check(out, ConfigUi.Tab.PRINT, Configs.Print.ADJUST_FIRST);
            for (IConfigBase config : List.of(Configs.Core.PROGRESS_SCOPE, Configs.Print.LAYER_DONE_SCOPE, Configs.Print.ADJUST_FIRST)) {
                if (!Configs.All.contains(config)) out.add(config.getName() + " not in the All tab");
            }
            for (String key : List.of("printProgressScope", "printLayerDoneScope", "printAdjustFirst", "progressScope.loaded", "progressScope.range",
                    "layerDoneScope.whole", "layerDoneScope.range")) {
                String full = "litematica-printer-autyism.config." + key;
                if (!net.minecraft.client.resources.language.I18n.exists(full)) out.add("no text for " + full);
                if (!key.contains(".") && !net.minecraft.client.resources.language.I18n.exists(full + ".desc")) out.add("no text for " + full + ".desc");
            }
            if (!net.minecraft.client.resources.language.I18n.exists("litematica-printer-autyism.hud.print_progress_loaded")) out.add("no HUD text");
            return out;
        });
        if (!problems.isEmpty()) throw new AssertionError("[progress/settings] " + problems);
        shot(context, ConfigUi.Tab.PRINT, "settings-120-printing");
        shot(context, ConfigUi.Tab.CORE, "settings-120-core");
        language(context, "zh_cn");
        try {
            shot(context, ConfigUi.Tab.PRINT, "settings-120-printing-zh");
            shot(context, ConfigUi.Tab.CORE, "settings-120-core-zh");
        } finally {
            language(context, "en_us");
        }
        GT.log("[progress/settings] OK: Progress Counts (Core), Layer Counts as Done and Finish Adjusting First (Printing) are listed, visible and have texts");
    }

    private static void language(ClientGameTestContext context, String code) {
        java.util.concurrent.CompletableFuture<Void> reload = context.computeOnClient(c -> {
            c.getLanguageManager().setSelected(code);
            c.options.languageCode = code;
            return c.reloadResourcePacks();
        });
        // 重新加载资源时会盖一层加载画面，等它完全消失
        context.waitFor(c -> reload.isDone() && c.getOverlay() == null, 1200);
        context.waitTicks(5);
    }

    private static void check(List<String> out, ConfigUi.Tab tab, IConfigBase config) {
        if (!tab.getConfigs().contains(config)) {
            out.add(config.getName() + " not in tab " + tab);
            return;
        }
        // 设置界面只列出可见的选项（ConfigUi.getConfigs）
        setTab(tab);
        boolean listed = new ConfigUi(null).getConfigs().stream().anyMatch(w -> w.getConfig() == config);
        if (!listed) out.add(config.getName() + " hidden in tab " + tab);
    }

    private static void setTab(ConfigUi.Tab tab) {
        try {
            java.lang.reflect.Field field = ConfigUi.class.getDeclaredField("tab");
            field.setAccessible(true);
            field.set(null, tab);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void shot(ClientGameTestContext context, ConfigUi.Tab tab, String name) {
        context.runOnClient(c -> {
            setTab(tab);
            //? if >=26.2 {
            /*c.gui.setScreen(new ConfigUi(null));
            *///?} else
            c.setScreen(new ConfigUi(null));
        });
        context.waitTicks(5);
        GT.log("[progress/settings] screenshot " + GT.screenshot(context, name).toAbsolutePath());
        //? if >=26.2 {
        /*context.runOnClient(c -> c.gui.setScreen(null));
        *///?} else
        context.runOnClient(c -> c.setScreen(null));
    }
}
