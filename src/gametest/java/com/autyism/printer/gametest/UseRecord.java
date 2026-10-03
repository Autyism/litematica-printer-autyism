package com.autyism.printer.gametest;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用：客户端发出放置包时手里拿的是什么（按动作序号），服务端处理时拿来对比。
 * 单人世界客户端和服务端在同一个进程里，所以可以直接共享。只在对不上时打印 [ITEM-MISMATCH]。
 */
public final class UseRecord {
    public record Entry(String item, int slot, int tick, String target) {
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
}
