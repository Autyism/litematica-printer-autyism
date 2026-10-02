package com.autyism.ale;

import fi.dy.masa.malilib.event.InitializationHandler;
import com.autyism.ale.utils.RemoteContainerUtils;
import net.fabricmc.api.ClientModInitializer;

public class LitematicaPrinterMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        RemoteContainerUtils.init();
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }
}
