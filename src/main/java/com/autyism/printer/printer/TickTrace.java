package com.autyism.printer.printer;

/**
 * 调试 / 测试用（-Dale.debugstall=true）：每 tick 打印机最后停在哪一步、这一 tick 发了几个放置 / 点击。
 * 用来找“为什么这几 tick 什么都没放”。关着时只是一次判断，不分配对象。
 */
public final class TickTrace {
    public static final boolean ENABLED = Boolean.getBoolean("ale.debugstall");

    /** 这一 tick 最后经过的停止点（常量字符串） */
    public static volatile String stop = "";
    /** 补充说明（例如正在调整的格子、换物品在等什么） */
    public static volatile String detail = "";
    /** 发出的放置 / 点击总数（测试每 tick 读差值） */
    public static volatile int actions;
    /** 这一 tick 打印模块扫描一共用的时间、其中执行动作（executeIteration）、发包（useItemOn）用的时间（纳秒） */
    public static volatile long iterNanos, execNanos, sendNanos;
    /** 打印模块正在扫描（测试的采样线程只在这时取主线程的调用栈） */
    public static volatile boolean inScan;

    private TickTrace() {
    }

    public static void stop(String where) {
        if (ENABLED) stop = where;
    }

    public static void stop(String where, String why) {
        if (ENABLED) {
            stop = where;
            detail = why;
        }
    }

    public static void detail(String why) {
        if (ENABLED) detail = why;
    }

    public static void action() {
        if (ENABLED) actions++;
    }

    /** 测试在每 tick 开始时调用 */
    public static void reset() {
        stop = "";
        detail = "";
        iterNanos = 0;
        execNanos = 0;
        sendNanos = 0;
    }
}
