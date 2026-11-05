package com.autyism.printer.gui;

import com.autyism.printer.I18n;
import com.autyism.printer.printer.PrintReport;
import com.autyism.printer.utils.MessageUtils;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.minecraft.client.Minecraft;

/**
 * "Print report" in Litematica's Schematic Verifier: the report of the printer for that placement, all of it or only
 * the render layers, as the verifier's range button says.
 */
public final class VerifierReportButton {
    private static final int ROW_Y = 20;
    /** For the tests: the button added last. */
    public static ButtonGeneric last;

    private VerifierReportButton() {
    }

    public static void add(GuiSchematicVerifier gui) {
        SchematicPlacement placement = gui.getPlacement();
        if (placement == null) return;
        String label = I18n.REPORT_VERIFIER_BUTTON.getName().getString();
        int width = Minecraft.getInstance().font.width(label) + 10;
        int x = 12;
        for (ButtonBase b : MainMenuButtonOverlap.buttons(gui)) {
            if (b.getY() == ROW_Y) x = Math.max(x, b.getX() + b.getWidth() + 4);
        }
        int y = ROW_Y;
        java.util.List<ButtonBase> buttons = MainMenuButtonOverlap.buttons(gui);
        if (x + width > gui.width - 10) {
            // no room at the end of the first row: bottom right, left of any button already there
            x = gui.width - width - 12;
            y = gui.height - 26;
            for (int guard = 0; guard < 10; guard++) {
                ButtonBase hit = null;
                for (ButtonBase b : buttons) {
                    if (x < b.getX() + b.getWidth() && b.getX() < x + width && y < b.getY() + b.getHeight() && b.getY() < y + 20) hit = b;
                }
                if (hit == null) break;
                x = hit.getX() - width - 4;
                y = hit.getY();
            }
        }
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        button.setHoverStrings(I18n.REPORT_VERIFIER_HOVER.getName().getString());
        last = button;
        gui.addButton(button, (btn, mouseButton) -> {
            boolean layers = placement.getSchematicVerifierType() == BlockInfoListType.RENDER_LAYERS;
            PrintReport.INSTANCE.requestFor(placement, layers);
            MessageUtils.setOverlayMessage(I18n.REPORT_VERIFIER_STARTED.getName());
        });
    }
}
