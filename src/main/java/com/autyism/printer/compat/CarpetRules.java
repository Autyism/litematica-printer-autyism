package com.autyism.printer.compat;

import org.jetbrains.annotations.Nullable;

/**
 * 服务器上地毯（Carpet）的规则。装了地毯的客户端连上地毯服务器时会收到服务器的全部规则，
 * 客户端 CarpetSettings 里的值就是服务器的。没装地毯、或者连的不是地毯服务器时什么都不知道。
 */
public final class CarpetRules {
    private static final boolean CARPET_LOADED = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("carpet");
    @Nullable
    private static java.lang.reflect.Field antiCheatDisabled;
    @Nullable
    private static java.lang.reflect.Method isCarpetServer;
    /** 找不到时隔一会儿再找（游戏刚启动、地毯还没初始化完时找会失败），不永远放弃 */
    private static long nextLookup;
    /** 上一次读到的值和读的时间：打印机每判断一格够不够得着都要问一次，反射调用不便宜，一秒内直接用上一次的 */
    private static boolean cached, cacheValid;
    private static long cachedUntil;

    private CarpetRules() {
    }

    /**
     * 连着的是地毯服务器、并且开着 antiCheatDisabled：这时服务端认眼睛到方块中心不到 32 格的放置和挖掘
     * （原版是交互距离 + 1，到方块最近的一点），再远的会被悄悄丢掉、只回一个确认。
     */
    public static boolean antiCheatDisabled() {
        if (!CARPET_LOADED) return false;
        long nanos = System.nanoTime();
        if (cacheValid && nanos - cachedUntil < 0) return cached;
        cached = read();
        cachedUntil = nanos + 1_000_000_000L;
        cacheValid = true;
        return cached;
    }

    private static boolean read() {
        if (antiCheatDisabled == null || isCarpetServer == null) {
            long now = System.currentTimeMillis();
            if (now < nextLookup) return false;
            nextLookup = now + 5000;
            try {
                java.lang.reflect.Field field = Class.forName("carpet.CarpetSettings").getField("antiCheatDisabled");
                java.lang.reflect.Method method = Class.forName("carpet.network.CarpetClient").getMethod("isCarpet");
                antiCheatDisabled = field;
                isCarpetServer = method;
            } catch (ReflectiveOperationException | LinkageError e) {
                return false;
            }
        }
        try {
            return Boolean.TRUE.equals(isCarpetServer.invoke(null)) && antiCheatDisabled.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
