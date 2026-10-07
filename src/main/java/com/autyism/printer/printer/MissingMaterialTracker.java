package com.autyism.printer.printer;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MissingMaterialTracker {
    private static final MissingMaterialTracker INSTANCE = new MissingMaterialTracker();

    public static class Entry {
        public final Item item;
        public Component displayName;
        /** 记录它的模块 */
        private Object source;
        /** 最后一次记录的 tick */
        private int lastSeenTick;
        /** 那一格冷却结束、会被重新尝试的 tick */
        private int retryTick;

        public Entry(Item item, Component displayName) {
            this.item = item;
            this.displayName = displayName;
        }
    }

    private final Map<Item, Entry> missingMap = new ConcurrentHashMap<>();

    public static MissingMaterialTracker getInstance() {
        return INSTANCE;
    }

    private static int now() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return player == null ? 0 : player.tickCount;
    }

    /**
     * 记录缺少的材料。source 是记录它的模块，retryTicks 是那一格过多久会被重新尝试（冷却）。
     * 条目一直保留，直到 source 完整扫描一轮（并且这一轮开始时那一格已经可以重新尝试）都没再记录它：
     * 以前每 tick 清掉一代以前的条目，那一格还在冷却时列表就空了，HUD 一闪一闪
     */
    public void recordMissing(Object source, Item item, Component displayName, int retryTicks) {
        if (item == null || item == Items.AIR) return;
        int now = now();
        missingMap.compute(item, (k, existing) -> {
            Entry entry = existing != null ? existing : new Entry(item, displayName);
            entry.displayName = displayName;
            entry.source = source;
            entry.lastSeenTick = now;
            entry.retryTick = Math.max(existing != null ? entry.retryTick : 0, now + Math.max(0, retryTicks));
            return entry;
        });
    }

    /** source 完整扫描完一轮（这一轮从 passStartTick 开始）：这一轮里没再缺的材料移除 */
    public void passFinished(Object source, int passStartTick) {
        if (missingMap.isEmpty()) return;
        missingMap.values().removeIf(e -> e.source == source && e.lastSeenTick < passStartTick && e.retryTick <= passStartTick);
    }

    /** source 停止工作：它记录的材料都移除 */
    public void dropSource(Object source) {
        if (missingMap.isEmpty()) return;
        missingMap.values().removeIf(e -> e.source == source);
    }

    public List<Entry> getMissing() {
        return new ArrayList<>(missingMap.values());
    }

    public boolean hasMissing() {
        return !missingMap.isEmpty();
    }

    public int size() {
        return missingMap.size();
    }

    public void reset() {
        missingMap.clear();
    }
}
