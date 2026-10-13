package com.autyism.printer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用：按服务端实际处理的先后顺序记下一片区域里的每一次方块变化（ServerBlockLogMixin 调用）。
 * 用来检查“调整完再放下一个”：一个方块从放下到调整好之间，服务端有没有放过别的方块。
 */
public final class BlockLog {
    /** index = 服务端处理的先后顺序；tick = 服务端 tick 数 */
    public record Change(int index, int tick, BlockPos pos, BlockState before, BlockState after) {
    }

    private static volatile int[] bounds;
    private static final List<Change> CHANGES = new ArrayList<>();

    private BlockLog() {
    }

    public static synchronized void start(BlockPos a, BlockPos b) {
        CHANGES.clear();
        bounds = new int[]{
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())};
    }

    public static synchronized void stop() {
        bounds = null;
    }

    public static synchronized List<Change> changes() {
        return new ArrayList<>(CHANGES);
    }

    /** 服务端线程调用：pos 在记录范围内、方块真的变了才记 */
    public static void record(int tick, BlockPos pos, BlockState before, BlockState after) {
        int[] c = bounds;
        if (c == null || before == after) return;
        if (pos.getX() < c[0] || pos.getX() > c[3] || pos.getY() < c[1] || pos.getY() > c[4] || pos.getZ() < c[2] || pos.getZ() > c[5]) return;
        synchronized (BlockLog.class) {
            if (bounds == null) return;
            CHANGES.add(new Change(CHANGES.size(), tick, pos.immutable(), before, after));
        }
    }
}
