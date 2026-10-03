package com.autyism.printer.compat;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Meteor Client 联动（全部反射，没装 Meteor 时安全）。
 * <p>
 * 目前只用来检查 Meteor 的 NoGhostBlocks（防幽灵方块）是否开着：它让客户端不先显示放下 / 挖掉的方块，
 * bunnyi116 的破基岩模组在这种情况下什么都破不掉（lxyan2333 的版本没问题）。
 */
public final class MeteorCompat {
    private static boolean initAttempted;
    @Nullable private static Object modules;
    @Nullable private static Method modulesGet;
    @Nullable private static Class<?> noGhostBlocksClass;
    @Nullable private static Method isActive;

    private MeteorCompat() {
    }

    private static void init() {
        if (initAttempted) return;
        initAttempted = true;
        try {
            Class<?> modulesClass = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
            modulesGet = modulesClass.getMethod("get", Class.class);
            modules = modulesClass.getMethod("get").invoke(null);
            noGhostBlocksClass = Class.forName("meteordevelopment.meteorclient.systems.modules.world.NoGhostBlocks");
            isActive = Class.forName("meteordevelopment.meteorclient.systems.modules.Module").getMethod("isActive");
        } catch (Throwable ignored) {
            modules = null;
        }
    }

    /** Meteor 的 NoGhostBlocks 模块是否开着（没装 Meteor 或出错时返回 false） */
    public static boolean isNoGhostBlocksActive() {
        init();
        if (modules == null || modulesGet == null || noGhostBlocksClass == null || isActive == null) return false;
        try {
            Object module = modulesGet.invoke(modules, noGhostBlocksClass);
            return module != null && Boolean.TRUE.equals(isActive.invoke(module));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
