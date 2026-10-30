package com.autyism.printer.utils;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 排查用的记录：游戏的 config 文件夹里有一个叫 litematica-printer-autyism.trace 的文件（内容随意）时，
 * 潜影盒取物、填流体 / 填充换物品的每一步写进游戏日志（[printer-trace] 开头）。平时没有这个文件，什么都不写。
 * 每 5 秒看一次文件在不在，不用重启游戏就能开关
 */
public final class PrinterTrace {
    private static final Path FLAG = FabricLoader.getInstance().getConfigDir().resolve("litematica-printer-autyism.trace");
    private static long nextCheck;
    private static boolean on;

    private PrinterTrace() {
    }

    public static boolean on() {
        if (Boolean.getBoolean("ale.debuglook")) return true;
        long now = System.currentTimeMillis();
        if (now >= nextCheck) {
            nextCheck = now + 5000;
            on = Files.exists(FLAG);
        }
        return on;
    }

    public static void log(String msg) {
        if (on()) System.out.println("[printer-trace] " + msg);
    }
}
