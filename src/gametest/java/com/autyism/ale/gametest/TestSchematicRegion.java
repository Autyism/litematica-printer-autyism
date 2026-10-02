package com.autyism.ale.gametest;

import net.minecraft.core.BlockPos;

/**
 * 测试用投影区域：不创建真正的 Litematica 放置，只让打印机把该区域视为投影方块。
 */
public final class TestSchematicRegion {
    private static volatile int[] bounds;

    private TestSchematicRegion() {
    }

    public static void activate(BlockPos a, BlockPos b) {
        bounds = new int[]{
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())};
    }

    public static void clear() {
        bounds = null;
    }

    @org.jetbrains.annotations.Nullable
    public static int[] bounds() {
        return bounds;
    }

    public static boolean contains(BlockPos pos) {
        int[] c = bounds;
        return c != null
                && pos.getX() >= c[0] && pos.getX() <= c[3]
                && pos.getY() >= c[1] && pos.getY() <= c[4]
                && pos.getZ() >= c[2] && pos.getZ() <= c[5];
    }
}
