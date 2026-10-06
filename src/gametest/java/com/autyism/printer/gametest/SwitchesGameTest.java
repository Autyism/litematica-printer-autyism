package com.autyism.printer.gametest;

import com.autyism.printer.compat.BedrockCompat;
import com.autyism.printer.config.Configs;
import com.autyism.printer.config.HotkeysCallback;
import com.autyism.printer.enums.FillBlockModeType;
import com.autyism.printer.enums.SelectionType;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 打印机开关、各模式快捷键和轮换模式：
 * - 打印机开关打开时一个模式都没开，会自动开“打印”；
 * - 模式快捷键在打印机关着时直接开始这个模式（以前总开关关着时按了没反应）；
 * - 轮换模式只开下一个模式，默认顺便关掉打印机；
 * - 旧版打印机的配置（单模式、轮换快捷键）能迁移过来；
 * - 在世界里：总开关关着、只按“填充”的快捷键就能开始填充；轮换到排流体后打印机是关的，不会自己动，打开后才排。
 */
@SuppressWarnings("UnstableApiUsage")
public final class SwitchesGameTest implements FabricClientGameTest {
    private static final BlockPos SEL_MIN = new BlockPos(900, 64, 0);
    private static final BlockPos SEL_MAX = new BlockPos(904, 65, 4);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("switches")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            context.runOnClient(c -> logic());
            context.runOnClient(c -> migration());
            inWorld(context, sp);
        } finally {
            context.runOnClient(c -> {
                GT.disableAll();
                Configs.Hotkeys.CYCLE_TURNS_OFF.setBooleanValue(true);
            });
        }
    }

    private static ConfigBooleanHotkeyed[] modes() {
        return new ConfigBooleanHotkeyed[]{Configs.Print.ENABLED, Configs.Mine.ENABLED, Configs.Fill.ENABLED, Configs.Fluid.ENABLED, Configs.Bedrock.ENABLED};
    }

    /** 当前状态：P = 打印机开着；后面是开着的模式（p 打印、m 挖掘、f 填充、l 排流体、b 破基岩） */
    private static String state() {
        StringBuilder sb = new StringBuilder(Configs.Core.WORK_SWITCH.getBooleanValue() ? "P:" : "-:");
        String letters = "pmflb";
        ConfigBooleanHotkeyed[] all = modes();
        for (int i = 0; i < all.length; i++) {
            if (all[i].getBooleanValue()) sb.append(letters.charAt(i));
        }
        return sb.toString();
    }

    private static void expect(String step, String wanted) {
        String now = state();
        if (!now.equals(wanted)) throw new AssertionError("[switches] " + step + ": expected " + wanted + " but was " + now);
    }

    private static void logic() {
        GT.disableAll();
        expect("all off", "-:");

        // 打印机开关：一个模式都没开 -> 自动开打印
        HotkeysCallback.togglePrinter();
        expect("printer key with no mode", "P:p");
        HotkeysCallback.togglePrinter();
        expect("printer key again", "-:p");

        // 以前的坑：总开关关着，打印模式开着，按打印模式的快捷键 -> 现在直接开始打印
        HotkeysCallback.toggleMode(Configs.Print.ENABLED);
        expect("print key while printer off", "P:p");
        // 开着时：加一个模式、去掉一个模式、去掉最后一个模式时打印机关掉
        HotkeysCallback.toggleMode(Configs.Mine.ENABLED);
        expect("mine key while printing", "P:pm");
        HotkeysCallback.toggleMode(Configs.Print.ENABLED);
        expect("print key while printing and mining", "P:m");
        HotkeysCallback.toggleMode(Configs.Mine.ENABLED);
        expect("mine key, last mode", "-:");
        // 打印机关着时按别的模式：只开那一个
        Configs.Print.ENABLED.setBooleanValue(true);
        Configs.Fill.ENABLED.setBooleanValue(true);
        HotkeysCallback.toggleMode(Configs.Fluid.ENABLED);
        expect("fluid key while printer off", "P:l");

        // 轮换（默认关打印机）
        boolean bedrock = BedrockCompat.isAvailable();
        Configs.Hotkeys.CYCLE_TURNS_OFF.setBooleanValue(true);
        GT.disableAll();
        Configs.Print.ENABLED.setBooleanValue(true);
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
        expect("printing", "P:p");
        HotkeysCallback.cycleMode();
        expect("cycle 1", "-:m");
        HotkeysCallback.cycleMode();
        expect("cycle 2", "-:f");
        HotkeysCallback.cycleMode();
        expect("cycle 3", "-:l");
        HotkeysCallback.cycleMode();
        expect("cycle 4", bedrock ? "-:b" : "-:p");
        if (bedrock) {
            HotkeysCallback.cycleMode();
            expect("cycle 5", "-:p");
        }
        // 关打印机后再打开：用轮换选中的模式
        HotkeysCallback.cycleMode();
        HotkeysCallback.togglePrinter();
        expect("printer on after cycling", "P:m");
        // 几个模式同时开着时轮换：从第一个开着的往后换，只留一个
        Configs.Fill.ENABLED.setBooleanValue(true);
        HotkeysCallback.cycleMode();
        expect("cycle with two modes", "-:f");

        // 轮换不关打印机：开着就直接换模式继续，关着就保持关着
        Configs.Hotkeys.CYCLE_TURNS_OFF.setBooleanValue(false);
        HotkeysCallback.togglePrinter();
        expect("printer on", "P:f");
        HotkeysCallback.cycleMode();
        expect("cycle keeps printer on", "P:l");
        HotkeysCallback.togglePrinter();
        HotkeysCallback.cycleMode();
        expect("cycle keeps printer off", bedrock ? "-:b" : "-:p");
        Configs.Hotkeys.CYCLE_TURNS_OFF.setBooleanValue(true);

        // 在设置界面里直接打开打印机（不是按键）也一样：没模式就开打印
        GT.disableAll();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
        expect("switch set directly", "P:p");
        GT.disableAll();
        GT.log("[switches] logic OK: printer key, mode keys and cycle mode behave as described" + (bedrock ? " (bedrock mod present)" : ""));
    }

    /** 旧版打印机的配置：单模式 + 选中挖掘、轮换快捷键 Ctrl+Shift、总开关 Ctrl+Tab */
    private static void migration() {
        try {
            Field field = Configs.class.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            Configs configs = (Configs) field.get(null);
            configs.save();
            File current = new File("./config/litematica-printer-autyism.json");
            File backup = new File("./config/litematica-printer-autyism.json.gtbak");
            File legacy = new File("./config/litematica-printer.json");
            Files.copy(current.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.delete(current.toPath());
                Files.writeString(legacy.toPath(), """
                        {
                          "litematica-printer": {
                            "workingSwitch": {"enabled": false, "hotkey": {"keys": "LEFT_CONTROL,TAB"}},
                            "modeSwitch": "single",
                            "printerMode": "mine",
                            "print": {"enabled": true, "hotkey": {"keys": ""}},
                            "mine": {"enabled": false, "hotkey": {"keys": "LEFT_ALT,M"}},
                            "switchPrinterMode": {"keys": "LEFT_CONTROL,LEFT_SHIFT"},
                            "workRange": 5.0
                          }
                        }
                        """, StandardCharsets.UTF_8);
                configs.load();
                String cycle = Configs.Hotkeys.CYCLE_MODE.getKeybind().getStringValue();
                String printer = Configs.Core.WORK_SWITCH.getKeybind().getStringValue();
                String mineKey = Configs.Mine.ENABLED.getKeybind().getStringValue();
                String got = state() + " cycle=" + cycle + " printer=" + printer + " mineKey=" + mineKey;
                String wanted = "-:m cycle=LEFT_CONTROL,LEFT_SHIFT printer=LEFT_CONTROL,TAB mineKey=LEFT_ALT,M";
                if (!got.equals(wanted)) throw new AssertionError("[switches] migration: expected " + wanted + " but was " + got);
                if (!current.isFile()) throw new AssertionError("[switches] migration did not write the new config file");
            } finally {
                Files.deleteIfExists(legacy.toPath());
                Files.copy(backup.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.delete(backup.toPath());
                configs.load();
            }
            GT.log("[switches] migration OK: single mode 'mine', cycle hotkey and printer hotkey carried over from the old printer");
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new AssertionError("[switches] migration test failed to run: " + e, e);
        }
    }

    private static void inWorld(ClientGameTestContext context, TestSingleplayerContext sp) {
        GT.clearArena(sp, 890, -10, 914, 14, 72);
        sp.getServer().runCommand("tp @a 902.5 64 8.5 180 20");
        context.waitFor(c -> c.player != null && Math.abs(c.player.getZ() - 8.5) < 0.01, 200);
        context.waitTicks(20);
        sp.getServer().runOnServer(s -> {
            ServerLevel level = s.overworld();
            for (BlockPos p : BlockPos.betweenClosed(SEL_MIN, SEL_MAX)) level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            var player = s.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            for (int i = 0; i < 4; i++) player.getInventory().setItem(9 + i, new ItemStack(Items.COBBLESTONE, 64));
            player.inventoryMenu.sendAllDataToRemote();
        });
        context.waitTicks(10);
        context.runOnClient(c -> {
            var selectionManager = DataManager.getSelectionManager();
            if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) selectionManager.switchSelectionMode();
            AreaSelection selection = DataManager.getSimpleArea();
            Box box = selection.getSubRegionBox(selection.getName());
            if (box == null) box = selection.getSelectedSubRegionBox();
            box.setPos1(SEL_MIN);
            box.setPos2(SEL_MAX);
            Configs.Core.WORK_RANGE.setDoubleValue(10);
            Configs.Fill.FILL_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
            Configs.Fill.FILL_BLOCK_MODE.setOptionListValue(FillBlockModeType.BLOCKLIST);
            Configs.Fill.FILL_BLOCK_LIST.setStrings(List.of("minecraft:cobblestone"));
            Configs.Fluid.FLUID_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
            Configs.Fluid.FLUID_REPLACE_BLOCK_LIST.setStrings(List.of("minecraft:cobblestone"));
            Configs.Fluid.FLUID_LIST.setStrings(List.of("minecraft:water", "minecraft:lava"));
            // 你实例里坏掉时的状态：总开关关着，打印模式开着
            GT.disableAll();
            Configs.Print.ENABLED.setBooleanValue(true);
            HotkeysCallback.toggleMode(Configs.Fill.ENABLED);
        });
        int total = 5 * 2 * 5;
        int ticks = GT.waitServer(context, () -> GT.countPlaced(sp, SEL_MIN, SEL_MAX) >= total, 1200, "[switches] fill key did not start filling");
        context.runOnClient(c -> expect("after the fill key", "P:f"));
        GT.log("[switches] fill hotkey started filling with the printer off before: " + total + " blocks in " + ticks + " ticks");

        // 轮换到排流体：打印机关掉，放水后不会自己动
        context.runOnClient(c -> HotkeysCallback.cycleMode());
        context.runOnClient(c -> expect("cycled to fluid", "-:l"));
        sp.getServer().runOnServer(s -> {
            ServerLevel level = s.overworld();
            for (BlockPos p : BlockPos.betweenClosed(SEL_MIN.offset(1, 1, 1), SEL_MAX.offset(-1, 0, -1))) {
                level.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
            }
        });
        context.waitTicks(60);
        int water = countWater(sp);
        if (water != 9) throw new AssertionError("[switches] the printer worked while it was off after cycling: " + water + " water blocks left of 9");
        context.runOnClient(c -> HotkeysCallback.togglePrinter());
        int fluidTicks = GT.waitServer(context, () -> countWater(sp) == 0, 1200, "[switches] fluid removal did not start after turning the printer on");
        context.runOnClient(c -> expect("printer on in fluid mode", "P:l"));
        context.runOnClient(c -> GT.disableAll());
        GT.log("[switches] in world OK: nothing happened while the printer was off after cycling; fluid removal ran after turning it on (" + fluidTicks + " ticks)");
    }

    private static int countWater(TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(s -> {
            int n = 0;
            for (BlockPos p : BlockPos.betweenClosed(SEL_MIN, SEL_MAX)) {
                if (s.overworld().getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) n++;
            }
            return n;
        });
    }
}
