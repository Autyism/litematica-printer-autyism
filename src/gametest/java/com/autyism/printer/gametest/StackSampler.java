package com.autyism.printer.gametest;

import com.autyism.printer.printer.TickTrace;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 测试用的简易采样：打印机扫描时（TickTrace.inScan）每毫秒取一次客户端主线程的调用栈，统计每个方法出现在多少次采样里 */
final class StackSampler implements Runnable {
    private final Thread target;
    private final Map<String, Integer> inclusive = new HashMap<>();
    private final Map<String, Integer> self = new HashMap<>();
    private volatile boolean running = true;
    private int total;
    private final Thread thread;

    private StackSampler(Thread target) {
        this.target = target;
        this.thread = new Thread(this, "ALE-GT-sampler");
        this.thread.setDaemon(true);
    }

    static StackSampler start(Thread target) {
        StackSampler s = new StackSampler(target);
        s.thread.start();
        return s;
    }

    @Override
    public void run() {
        while (running) {
            if (TickTrace.inScan) {
                StackTraceElement[] st = target.getStackTrace();
                if (TickTrace.inScan && st.length > 0) {
                    total++;
                    self.merge(name(st[0]), 1, Integer::sum);
                    Set<String> seen = new HashSet<>();
                    for (StackTraceElement e : st) {
                        String n = name(e);
                        if (seen.add(n)) inclusive.merge(n, 1, Integer::sum);
                    }
                }
            }
            try {
                Thread.sleep(0, 500_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String name(StackTraceElement e) {
        return e.getClassName() + "." + e.getMethodName();
    }

    void stopAndReport() {
        running = false;
        try {
            thread.join(1000);
        } catch (InterruptedException ignored) {
        }
        GT.log("[machine] profile: " + total + " samples while scanning");
        inclusive.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(90)
                .forEach(e -> GT.log("[machine]   incl " + e.getValue() + " " + e.getKey()));
        self.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(30)
                .forEach(e -> GT.log("[machine]   self " + e.getValue() + " " + e.getKey()));
    }
}
