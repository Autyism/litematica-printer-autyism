package com.autyism.printer.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;

/**
 * ALE（Autyism's Litematica Enhancement）1.2.0 起的“铁轨按投影形状放”：服务端也装了 ALE（或者单人游戏）时，
 * 放下的铁轨直接是投影里的形状，不会按旁边的铁轨自动拐弯，也不会把已经放好的铁轨拉歪。
 * <p>
 * 打印机不依赖 ALE：用反射找它的公开接口 com.autyism.ale.api.ExactRailPlacement，没装、版本不对或者服务端没有 ALE 时一律当作不可用，
 * 打印机照旧用自己的铁轨安全放置（按顺序放、模拟原版会不会拐歪）。
 */
public final class AleRails {
    private static boolean looked;
    @Nullable
    private static MethodHandle isAvailable, canStay, place;

    private AleRails() {
    }

    private static void lookUp() {
        if (looked) return;
        looked = true;
        if (!FabricLoader.getInstance().isModLoaded("autyism-le")) return;
        try {
            Class<?> api = Class.forName("com.autyism.ale.api.ExactRailPlacement");
            if (api.getField("API_VERSION").getInt(null) < 1) return;
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            isAvailable = lookup.findStatic(api, "isAvailable", MethodType.methodType(boolean.class));
            canStay = lookup.findStatic(api, "canStay", MethodType.methodType(boolean.class, BlockGetter.class, BlockPos.class, BlockState.class));
            place = lookup.findStatic(api, "place", MethodType.methodType(InteractionResult.class, BlockPos.class, BlockState.class, Supplier.class));
        } catch (Throwable t) {
            isAvailable = canStay = place = null;
        }
    }

    /** 现在连着的服务器（或单人世界）能按投影形状放铁轨 */
    public static boolean available() {
        lookUp();
        if (isAvailable == null) return false;
        try {
            return (boolean) isAvailable.invoke();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这个形状的铁轨现在放在 pos 能不能留得住（下面有支撑；上坡的高处一边有实心方块） */
    public static boolean canStay(BlockGetter level, BlockPos pos, BlockState rail) {
        lookUp();
        if (canStay == null) return false;
        try {
            return (boolean) canStay.invoke(level, pos, rail);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 让 placement 里放下的这节铁轨就是 rail 的形状；不可用时直接照常放 */
    public static InteractionResult place(BlockPos pos, BlockState rail, Supplier<InteractionResult> placement) {
        lookUp();
        if (place == null) return placement.get();
        boolean[] ran = {false};
        Supplier<InteractionResult> once = () -> {
            ran[0] = true;
            return placement.get();
        };
        try {
            return (InteractionResult) place.invoke(pos, rail, once);
        } catch (Throwable t) {
            // 放置已经发出去了就不能再放一次
            return ran[0] ? InteractionResult.FAIL : placement.get();
        }
    }
}
