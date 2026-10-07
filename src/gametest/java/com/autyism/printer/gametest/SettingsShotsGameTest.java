package com.autyism.printer.gametest;

import com.autyism.printer.gui.ConfigUi;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Screenshots of the settings screen (Core, Hotkeys, the end of Printing, Breaking Blocks, Mining) for checking the
 * layout and for the docs. Only runs when asked for: -Pgt=settings
 */
@SuppressWarnings("UnstableApiUsage")
public final class SettingsShotsGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!System.getProperty("ale.gt", "").contains("settings")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            context.waitTicks(20);
            shot(context, ConfigUi.Tab.CORE, false, "settings-core");
            shot(context, ConfigUi.Tab.HOTKEYS, false, "settings-hotkeys");
            shot(context, ConfigUi.Tab.PRINT, true, "settings-printing-end");
            shot(context, ConfigUi.Tab.BREAK, false, "settings-breaking");
            shot(context, ConfigUi.Tab.EXCAVATE, false, "settings-mining");
        } finally {
            //? if >=26.2 {
            /*context.runOnClient(c -> c.gui.setScreen(null));
            *///?} else
            context.runOnClient(c -> c.setScreen(null));
        }
    }

    private static void shot(ClientGameTestContext context, ConfigUi.Tab tab, boolean scrollToEnd, String name) {
        context.runOnClient(c -> {
            try {
                Field field = ConfigUi.class.getDeclaredField("tab");
                field.setAccessible(true);
                field.set(null, tab);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            //? if >=26.2 {
            /*c.gui.setScreen(new ConfigUi(null));
            *///?} else
            c.setScreen(new ConfigUi(null));
        });
        context.waitTicks(5);
        if (scrollToEnd) {
            context.runOnClient(c -> {
                try {
                    Method method = GuiListBase.class.getDeclaredMethod("getListWidget");
                    method.setAccessible(true);
                    //? if >=26.2 {
                    /*WidgetListBase<?, ?> list = (WidgetListBase<?, ?>) method.invoke(c.gui.screen());
                    *///?} else
                    WidgetListBase<?, ?> list = (WidgetListBase<?, ?>) method.invoke(c.screen);
                    list.getScrollbar().setValue(list.getScrollbar().getMaxValue());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            context.waitTicks(5);
        }
        context.takeScreenshot(name);
        GT.log("[settings] " + name);
    }
}
