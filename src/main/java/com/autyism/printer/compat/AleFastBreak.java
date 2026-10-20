package com.autyism.printer.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * ALE（Autyism's Litematica Enhancement）1.2.0 起的“秒破深板岩”：服务端也装了 ALE（或者单人游戏）时，
 * 一 tick 进度至少 50% 的深板岩（服务器的 autyism-le:fast_break 标签里的方块）可以和能秒破的方块一样一下挖掉，
 * 不用一个接一个挖。只对打印机自己带着请求发的挖掘生效，玩家自己挖照原版。
 * <p>
 * 打印机不依赖 ALE：用反射找它的公开接口 com.autyism.ale.api.FastBlockBreaking，没装、版本不对或者服务端没有 ALE 时一律当作不可用。
 */
public final class AleFastBreak {
    private static boolean looked;
    @Nullable
    private static MethodHandle canBreakNow, breakNow;

    private AleFastBreak() {
    }

    private static void lookUp() {
        if (looked) return;
        looked = true;
        if (!FabricLoader.getInstance().isModLoaded("autyism-le")) return;
        try {
            Class<?> api = Class.forName("com.autyism.ale.api.FastBlockBreaking");
            if (api.getField("API_VERSION").getInt(null) < 1) return;
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            canBreakNow = lookup.findStatic(api, "canBreakNow", MethodType.methodType(boolean.class, BlockState.class, float.class));
            breakNow = lookup.findStatic(api, "breakNow", MethodType.methodType(void.class, BlockPos.class, Runnable.class));
        } catch (Throwable t) {
            canBreakNow = breakNow = null;
        }
    }

    /** 服务器会不会替一 tick 挖 progressPerTick 的打印机一下挖掉这个方块 */
    public static boolean canBreakNow(BlockState state, float progressPerTick) {
        lookUp();
        if (canBreakNow == null || breakNow == null) return false;
        try {
            return (boolean) canBreakNow.invoke(state, progressPerTick);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 带着秒破请求执行 start（里面发这一格的开始挖掘的包）；不可用时照常执行 */
    public static void breakNow(BlockPos pos, Runnable start) {
        lookUp();
        if (breakNow == null) {
            start.run();
            return;
        }
        boolean[] ran = {false};
        Runnable once = () -> {
            ran[0] = true;
            start.run();
        };
        try {
            breakNow.invoke(pos, once);
        } catch (Throwable t) {
            // 包已经发出去了就不能再发一次
            if (!ran[0]) start.run();
        }
    }
}
