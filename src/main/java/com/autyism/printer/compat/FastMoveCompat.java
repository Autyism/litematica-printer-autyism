package com.autyism.printer.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Litefastica（快速移动投影）：投影移动后，它要等停手约 1.5 秒才把 Litematica 的投影数据挪到新位置，
 * 这段时间投影世界里还是旧位置，打印机照着打会把方块放在旧位置上。它还没提交时打印机先等着。
 * 按名字找它的方法，打印机不依赖它；它改了名就当没装。
 */
public final class FastMoveCompat {
    private static final MethodHandle HAS_PENDING = find();

    private FastMoveCompat() {
    }

    public static boolean moveSettling() {
        if (HAS_PENDING == null) return false;
        try {
            return (boolean) HAS_PENDING.invokeExact();
        } catch (Throwable e) {
            return false;
        }
    }

    private static MethodHandle find() {
        if (!FabricLoader.getInstance().isModLoaded("litematicafastmove")) return null;
        try {
            Class<?> tracker = Class.forName("ml.mypals.litematicafastmove.sync.PendingMoveTracker");
            return MethodHandles.publicLookup().findStatic(tracker, "hasAnyPending", MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
