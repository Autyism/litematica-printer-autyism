package com.autyism.printer;

import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import com.autyism.printer.config.Configs;
import com.autyism.printer.config.HotkeysCallback;
import fi.dy.masa.malilib.event.RenderEventHandler;
//? if >=1.21
import com.autyism.printer.render.BlockHighlightRenderer;
import com.autyism.printer.render.MissingMaterialHudRenderer;

public class InitHandler implements IInitializationHandler {
    @Override
    public void registerModHandlers() {
        Configs.init();
        HotkeysCallback.initCallbacks();
        fi.dy.masa.litematica.render.infohud.InfoHud.getInstance()
                .addInfoHudRenderer(MissingMaterialHudRenderer.INSTANCE, true);

        //? if <1.21 {
        /*RenderEventHandler.getInstance().registerWorldLastRenderer(new com.autyism.printer.render.LegacyBlockHighlightRenderer());
        *///?} else
        RenderEventHandler.getInstance().registerWorldLastRenderer(new BlockHighlightRenderer());
    }
}