package com.autyism.printer.gametest;

/** 测试用：客户端放一块方块的各个步骤各用了多少时间（ProfileMixin 记，MachineGameTest 每 tick 读完清零） */
public final class Prof {
    public static long useItemOn, perform, place, send, sound, sends;
    static long useStart, performStart, placeStart, sendStart, soundStart;

    private Prof() {
    }

    public static String takeLine() {
        String s = String.format(java.util.Locale.ROOT, "useItemOn=%.1fms perform=%.1fms place=%.1fms send=%.1fms(%d) sound=%.1fms",
                useItemOn / 1e6, perform / 1e6, place / 1e6, send / 1e6, sends, sound / 1e6);
        useItemOn = perform = place = send = sound = sends = 0;
        return s;
    }

    public static void useStart() { useStart = System.nanoTime(); }
    public static void useEnd() { useItemOn += System.nanoTime() - useStart; }
    public static void performStart() { performStart = System.nanoTime(); }
    public static void performEnd() { perform += System.nanoTime() - performStart; }
    public static void placeStart() { placeStart = System.nanoTime(); }
    public static void placeEnd() { place += System.nanoTime() - placeStart; }
    public static void sendStart() { sendStart = System.nanoTime(); }
    public static void sendEnd() { send += System.nanoTime() - sendStart; sends++; }
    public static void soundStart() { soundStart = System.nanoTime(); }
    public static void soundEnd() { sound += System.nanoTime() - soundStart; }
}
