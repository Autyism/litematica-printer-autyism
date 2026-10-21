package com.autyism.printer.gametest;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用：客户端发出放置包时手里拿的是什么（按动作序号），服务端处理时拿来对比。
 * 单人世界客户端和服务端在同一个进程里，所以可以直接共享。只在对不上时打印 [ITEM-MISMATCH]。
 */
public final class UseRecord {
    public record Entry(String item, int slot, int tick, String target,
                        @org.jetbrains.annotations.Nullable net.minecraft.core.BlockPos workPos,
                        @org.jetbrains.annotations.Nullable net.minecraft.world.level.block.state.BlockState wanted) {
    }

    /** 服务端最近收到的带视角的移动包：{tick, yRot, xRot, 类型} */
    public static final java.util.ArrayDeque<String> SERVER_ROTATIONS = new java.util.ArrayDeque<>();

    public static synchronized void noteServerRotation(String s) {
        SERVER_ROTATIONS.addLast(s);
        while (SERVER_ROTATIONS.size() > 30) SERVER_ROTATIONS.removeFirst();
    }

    public static synchronized String recentServerRotations() {
        return String.join(" | ", SERVER_ROTATIONS);
    }

    private static final ConcurrentHashMap<Integer, Entry> MAP = new ConcurrentHashMap<>();

    private UseRecord() {
    }

    public static void put(int sequence, Entry entry) {
        if (MAP.size() > 20000) MAP.clear();
        MAP.put(sequence, entry);
    }

    public static Entry take(int sequence) {
        return MAP.remove(sequence);
    }

    /** 最近发出的 n 个放置包（按序号） */
    public static java.util.List<String> recent(int n) {
        java.util.List<Integer> seqs = new java.util.ArrayList<>(MAP.keySet());
        java.util.Collections.sort(seqs);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = Math.max(0, seqs.size() - n); i < seqs.size(); i++) {
            Entry e = MAP.get(seqs.get(i));
            if (e == null) continue;
            out.add("#" + seqs.get(i) + " t" + e.tick() + " " + e.target() + " work=" + (e.workPos() == null ? null : e.workPos().toShortString())
                    + " " + (e.wanted() == null ? null : e.wanted().getBlock()));
        }
        return out;
    }
}
