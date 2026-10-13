package com.autyism.printer.printer;

import net.minecraft.core.BlockPos;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 客户端世界里的方块变了（服务端发来的、客户端自己预测的都算，见 MixinLevelBlockChange）。
 * 增量统计（进度条、整层判定）只把变了的那一块标记成要重算，不用每 tick 重新扫整个投影。只做哈希表查找，很便宜。
 */
public final class BlockChangeHub {
    private static final CopyOnWriteArrayList<Consumer<BlockPos>> LISTENERS = new CopyOnWriteArrayList<>();

    private BlockChangeHub() {
    }

    public static void register(Consumer<BlockPos> listener) {
        LISTENERS.add(listener);
    }

    public static void changed(BlockPos pos) {
        for (Consumer<BlockPos> listener : LISTENERS) listener.accept(pos);
    }
}
