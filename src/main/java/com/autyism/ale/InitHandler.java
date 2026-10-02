package com.autyism.ale;

import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import com.autyism.ale.config.Configs;
import com.autyism.ale.config.HotkeysCallback;
import fi.dy.masa.malilib.event.RenderEventHandler;
import com.autyism.ale.render.BlockHighlightRenderer;
import com.autyism.ale.render.MissingMaterialHudRenderer;

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