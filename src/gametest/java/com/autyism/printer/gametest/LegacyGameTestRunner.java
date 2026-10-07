package com.autyism.printer.gametest;

import net.fabricmc.api.ClientModInitializer;

/** 1.20.1：Fabric API 还没有客户端 gametest，用 LegacyHarness 跑同样的测试；其他版本什么都不做 */
public final class LegacyGameTestRunner implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        //? if <1.21 {
        /*com.autyism.printer.gametest.legacy.LegacyHarness.init();
        *///?}
    }
}
