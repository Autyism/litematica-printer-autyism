package com.autyism.printer.printer;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Iterator;

/**
 * 动作确认：真服务器有延迟，客户端在服务端回应之前看到的只是“预测”（甚至根本没变，例如点音符盒客户端不会改音高；
 * 用假视角放的方块，客户端是按玩家真实视角预测朝向的）。
 * <p>
 * 原版机制：每次放置 / 点击都带一个序号，服务端处理完后先发方块变化，再发“已处理到第几号”的确认
 * （ClientboundBlockChangedAckPacket）。所以收到确认时，客户端看到的就是服务端的真实结果。
 * 打印机记住每个动作的序号，确认回来之前不再碰这个格子（以及需要看邻居才能决定的格子的邻居），
 * 不根据客户端的预测做任何决定。被服务端拒绝的动作，确认回来后客户端会恢复原状，打印机再重试。
 */
public final class ActionConfirm {
    /** 收不到确认时（掉线、服务器丢包）最多等多久 */
    private static final int TIMEOUT_TICKS = 200;

    /** 位置 → {序号, 发出时的 tick} */
    private static final Long2ObjectOpenHashMap<int[]> PENDING = new Long2ObjectOpenHashMap<>();
    /** 序号 → 这次动作手里拿的物品（用来算某种物品还有几次放置没被确认） */
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<net.minecraft.world.item.Item> ITEMS = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    private static int lastAcked = -1;
    private static ClientLevel trackedLevel;

    private ActionConfirm() {
    }

    /** 记录一个发出去的动作：被点的格子和方块会出现的格子 */
    public static void sent(ClientLevel level, BlockPos clicked, Direction side, int sequence, int tick, net.minecraft.world.item.Item item) {
        sync(level);
        int[] entry = {sequence, tick};
        PENDING.put(clicked.asLong(), entry);
        PENDING.put(clicked.relative(side).asLong(), entry);
        ITEMS.put(sequence, item);
        if (ITEMS.size() > 4096) ITEMS.clear();
    }

    /** 服务端确认处理到了 sequence（由 BlockStatePredictionHandler.endPredictionsUpTo 调用） */
    public static void onAck(ClientLevel level, int sequence) {
        sync(level);
        if (sequence > lastAcked) lastAcked = sequence;
        PENDING.values().removeIf(e -> e[0] <= lastAcked);
        ITEMS.int2ObjectEntrySet().removeIf(e -> e.getIntKey() <= lastAcked);
    }

    /** 用这种物品、已经发出但还没被服务端确认的动作数（按序号去重；超时的不算） */
    public static int inFlightCount(ClientLevel level, int tick, net.minecraft.world.item.Item item) {
        sync(level);
        PENDING.values().removeIf(e -> e[0] <= lastAcked || tick - e[1] > TIMEOUT_TICKS || tick < e[1]);
        java.util.HashSet<Integer> seqs = new java.util.HashSet<>();
        for (int[] e : PENDING.values()) {
            if (ITEMS.get(e[0]) == item) seqs.add(e[0]);
        }
        return seqs.size();
    }

    /** 服务端确认处理到的最大序号 */
    public static int lastAcked(ClientLevel level) {
        sync(level);
        return lastAcked;
    }

    /** 这个格子上有还没被服务端确认的动作 */
    public static boolean pending(ClientLevel level, BlockPos pos, int tick) {
        sync(level);
        int[] e = PENDING.get(pos.asLong());
        if (e == null) return false;
        if (e[0] <= lastAcked || tick - e[1] > TIMEOUT_TICKS || tick < e[1]) {
            PENDING.remove(pos.asLong());
            return false;
        }
        return true;
    }

    /** 这个格子或者它旁边（6 个方向）有还没被确认的动作 */
    public static boolean pendingNear(ClientLevel level, BlockPos pos, int tick) {
        if (pending(level, pos, tick)) return true;
        for (Direction d : Direction.values()) {
            if (pending(level, pos.relative(d), tick)) return true;
        }
        return false;
    }

    // ---------------- 从背包换物品到手上（生存模式的背包点击）----------------
    /** 上一次背包交换时最近的动作序号；它之后发出的某个动作被确认，就说明这次交换已经被服务端处理了 */
    private static int inventoryClickSeq = Integer.MIN_VALUE;
    private static int inventoryClickTick;
    private static final int INVENTORY_TIMEOUT_TICKS = 40;

    /**
     * 现在能不能做背包交换。原版服务端在背包同步编号对不上时会把整个背包重发给客户端，
     * 有延迟时这份旧背包会盖掉客户端已经做完、但服务端还没处理的交换，打印机就会按错的背包选格子放错方块。
     * 所以：上一次背包交换已经被服务端处理过（它之后发出的动作被确认），并且之前的放置 / 点击都已确认，才做下一次。
     */
    public static boolean readyForInventoryClick(ClientLevel level, int sequenceNow, int tick) {
        sync(level);
        if (inventoryClickSeq != Integer.MIN_VALUE) {
            boolean processed = lastAcked > inventoryClickSeq || tick - inventoryClickTick > INVENTORY_TIMEOUT_TICKS || tick < inventoryClickTick;
            if (!processed) return false;
            inventoryClickSeq = Integer.MIN_VALUE;
        }
        // 并且打印机之前发出的放置 / 点击都已经被确认（这样客户端的背包和同步编号都是最新的，超时的不算）。
        // 只要求“上一次交换已处理”的轻量版本在 60~140ms 延迟、12 种材料的测试里 1024 次放错了 2 次，所以两条都要
        PENDING.values().removeIf(e -> e[0] <= lastAcked || tick - e[1] > TIMEOUT_TICKS || tick < e[1]);
        return PENDING.isEmpty();
    }

    /** 记录一次背包交换（sequenceNow = 当时最近的动作序号） */
    public static void noteInventoryClick(ClientLevel level, int sequenceNow, int tick) {
        sync(level);
        inventoryClickSeq = sequenceNow;
        inventoryClickTick = tick;
    }

    /** 换世界 / 重新进服时清空（序号从头开始） */
    private static void sync(ClientLevel level) {
        if (level != trackedLevel) {
            trackedLevel = level;
            PENDING.clear();
            ITEMS.clear();
            lastAcked = -1;
            inventoryClickSeq = Integer.MIN_VALUE;
        }
    }

    public static void reset() {
        PENDING.clear();
    }

    /** 调试用 */
    public static int size() {
        int n = 0;
        for (Iterator<Long2ObjectMap.Entry<int[]>> it = PENDING.long2ObjectEntrySet().iterator(); it.hasNext(); it.next()) n++;
        return n;
    }
}
