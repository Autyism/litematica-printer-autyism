package com.autyism.printer.printer;

import java.util.ArrayDeque;

/**
 * Remaining time of a print, from how fast the printed count went up over the last minute. Starts over when the count
 * goes down or the total changes (other range, other layers, a block broken).
 */
public final class PrintEta {
    private static final long WINDOW_MS = 60_000L;
    private static final long MIN_SPAN_MS = 10_000L;

    private record Sample(long time, long finished) {
    }

    private static final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private static long lastTotal = -1;
    private static long lastFinished = -1;

    private PrintEta() {
    }

    /** Seconds left, or -1 when not known yet (too little time seen, nothing printed lately, or all done). */
    public static long secondsLeft(long finished, long total) {
        long now = System.currentTimeMillis();
        if (total != lastTotal || finished < lastFinished) samples.clear();
        lastTotal = total;
        lastFinished = finished;
        if (samples.isEmpty() || samples.peekLast().finished() != finished || now - samples.peekLast().time() > 1000) {
            samples.addLast(new Sample(now, finished));
        }
        while (samples.size() > 2 && now - samples.peekFirst().time() > WINDOW_MS) samples.pollFirst();
        if (finished >= total || samples.size() < 2) return -1;
        Sample first = samples.peekFirst();
        long span = now - first.time();
        long done = finished - first.finished();
        if (span < MIN_SPAN_MS || done <= 0) return -1;
        return (long) Math.ceil((total - finished) * (span / 1000.0) / done);
    }

    /** "1 h 05 min", "12 min", "40 s" */
    public static String format(long seconds) {
        if (seconds >= 3600) return String.format("%d h %02d min", seconds / 3600, (seconds % 3600) / 60);
        if (seconds >= 60) return (seconds + 30) / 60 + " min";
        return Math.max(1, seconds) + " s";
    }
}
