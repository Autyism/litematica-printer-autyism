package com.autyism.printer;

import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import com.autyism.printer.config.Configs;
import com.autyism.printer.config.HotkeysCallback;
import fi.dy.masa.malilib.event.RenderEventHandler;
import com.autyism.printer.render.BlockHighlightRenderer;
import com.autyism.printer.render.MissingMaterialHudRenderer;

public class InitHandler implements IInitializationHandler {
    @Override
    public void registerModHandlers() {
        Configs.init();
        HotkeysCallback.initCallbacks();
        fi.dy.masa.litematica.render.infohud.InfoHud.getInstance()
                .addInfoHudRenderer(MissingMaterialHudRenderer.INSTANCE, true);

        RenderEventHandler.getInstance().registerWorldLastRenderer(new BlockHighlightRenderer());
    }
}