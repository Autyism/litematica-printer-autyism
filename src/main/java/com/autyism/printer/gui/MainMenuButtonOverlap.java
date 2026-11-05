package com.autyism.printer.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Moves overlapping buttons in Litematica's main menu apart (see MixinGuiBaseButtonOverlap). */
public final class MainMenuButtonOverlap {
    private static final String MAIN_MENU = "fi.dy.masa.litematica.gui.GuiMainMenu";
    private static final int STEP = 22;
    private static Field buttonsField;
    private static boolean unavailable;

    private MainMenuButtonOverlap() {
    }

    public static void resolve(GuiBase gui) {
        if (unavailable || !gui.getClass().getName().equals(MAIN_MENU)) {
            return;
        }
        List<ButtonBase> buttons = buttons(gui);
        if (buttons.size() < 2) {
            return;
        }
        int bottomLimit = gui.height - 30; // the tool mode button sits at the bottom left
        for (int i = 1; i < buttons.size(); i++) {
            ButtonBase moving = buttons.get(i);
            int guard = 0;
            while (overlapsEarlier(buttons, i) && moving.getY() + STEP + moving.getHeight() <= bottomLimit && guard++ < 20) {
                moving.setPosition(moving.getX(), moving.getY() + STEP);
            }
        }
    }

    private static boolean overlapsEarlier(List<ButtonBase> buttons, int index) {
        ButtonBase b = buttons.get(index);
        for (int j = 0; j < index; j++) {
            ButtonBase a = buttons.get(j);
            if (b.getX() < a.getX() + a.getWidth() && a.getX() < b.getX() + b.getWidth()
                    && b.getY() < a.getY() + a.getHeight() && a.getY() < b.getY() + b.getHeight()) {
                return true;
            }
        }
        return false;
    }

    /** GuiBase keeps its buttons in a private list (older malilib: wrappers holding the button). */
    public static List<ButtonBase> buttons(GuiBase gui) {
        List<ButtonBase> out = new ArrayList<>();
        try {
            if (buttonsField == null) {
                buttonsField = GuiBase.class.getDeclaredField("buttons");
                buttonsField.setAccessible(true);
            }
            Object list = buttonsField.get(gui);
            if (list instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof ButtonBase button) {
                        out.add(button);
                    } else if (o != null) {
                        Method get = o.getClass().getMethod("getButton");
                        Object button = get.invoke(o);
                        if (button instanceof ButtonBase b) {
                            out.add(b);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            unavailable = true;
        }
        return out;
    }
}
