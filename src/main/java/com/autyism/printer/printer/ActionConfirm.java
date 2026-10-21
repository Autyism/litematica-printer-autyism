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
    /** 序号 → 发出时的 tick（和 ITEMS 一起：按序号记，不会像 PENDING 那样被同一格后来的动作盖掉） */
    private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap SENT_TICKS = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
    /**
     * 还没确认、而且客户端手上显示的数量里没有扣掉它这一次消耗的动作（序号）。
     * 发出时客户端自己扣了一个（正常预测放置）就不在这里；服务端发来背包数量（只算到它已经处理的动作）以后，
     * 当时还没确认的都放进来：那个数量里没有它们。
     */
    private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet UNREFLECTED = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
    /**
     * unreflectedUses 按物品数好的结果，只在 countTick 这一 tick 有效。打印机每放一块都要问一次，以前每次都把所有没确认的动作数一遍
     * （一 tick 放上百块时这一项就占了扫描时间的一成）。确认、背包同步都在 tick 之间处理，tick 里只有 sent 会改变它，sent 时顺手加上
     */
    private static final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<net.minecraft.world.item.Item> UNREFLECTED_COUNT =
            new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
    private static int countTick;
    private static boolean countValid;
    private static int lastAcked = -1;
    /** 挖掘：位置 → {这一格最后一个破坏包的序号, 发出时的游戏时间}（同一格的开始、结束只算一格） */
    private static final Long2ObjectOpenHashMap<long[]> BREAKS = new Long2ObjectOpenHashMap<>();
    /** 收不到确认的挖掘（掉线、没有序号的老版本服务器）最多算这么久，免得耐久保护一直卡着 */
    private static final long BREAK_TIMEOUT_TICKS = 100;
    private static ClientLevel trackedLevel;

    private ActionConfirm() {
    }

    /** 记录一个发出去的动作：被点的格子和方块会出现的格子 */
    public static void sent(ClientLevel level, BlockPos clicked, Direction side, int sequence, int tick, net.minecraft.world.item.Item item) {
        sent(level, clicked, side, sequence, tick, item, false);
    }

    /**
     * @param consumedLocally 发出时客户端已经从手上扣掉了这一次的消耗（正常预测的放置）；
     *                        没扣的（点击调整、客户端没预测成功的放置）按“服务端可能会扣”算，见 {@link #unreflectedUses}
     */
    public static void sent(ClientLevel level, BlockPos clicked, Direction side, int sequence, int tick, net.minecraft.world.item.Item item,
                            boolean consumedLocally) {
        sync(level);
        int[] entry = {sequence, tick};
        PENDING.put(clicked.asLong(), entry);
        PENDING.put(clicked.relative(side).asLong(), entry);
        if (ITEMS.size() > 4096) {
            ITEMS.clear();
            SENT_TICKS.clear();
            UNREFLECTED.clear();
            countValid = false;
        }
        ITEMS.put(sequence, item);
        SENT_TICKS.put(sequence, tick);
        if (consumedLocally) {
            if (UNREFLECTED.remove(sequence)) countValid = false;
        } else if (UNREFLECTED.add(sequence)) {
            if (countValid && countTick == tick) UNREFLECTED_COUNT.addTo(item, 1);
            else countValid = false;
        }
    }

    /** 服务端确认处理到了 sequence（由 BlockStatePredictionHandler.endPredictionsUpTo 调用） */
    public static void onAck(ClientLevel level, int sequence) {
        sync(level);
        if (sequence > lastAcked) lastAcked = sequence;
        PENDING.values().removeIf(e -> e[0] <= lastAcked);
        ITEMS.int2ObjectEntrySet().removeIf(e -> e.getIntKey() <= lastAcked);
        SENT_TICKS.int2IntEntrySet().removeIf(e -> e.getIntKey() <= lastAcked);
        UNREFLECTED.removeIf(s -> s <= lastAcked);
        countValid = false;
        if (!BREAKS.isEmpty()) BREAKS.values().removeIf(e -> e[0] <= lastAcked);
        ServerProtocol.onAck(level, lastAcked);
    }

    /**
     * 服务端发来了背包里的物品数量（由 ClientPacketListener 收到格子 / 整个背包的同步时调用）。
     * 服务端只算到它已经处理的动作：这时还没确认的动作，它们的消耗都不在客户端现在显示的数量里。
     */
    public static void onInventorySync(ClientLevel level) {
        if (level == null) return;
        sync(level);
        for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<net.minecraft.world.item.Item> e : ITEMS.int2ObjectEntrySet()) {
            if (e.getIntKey() > lastAcked) UNREFLECTED.add(e.getIntKey());
        }
        countValid = false;
    }

    /**
     * 用这种物品、已经发出、服务端还没确认、而且客户端显示的数量里还没扣掉的动作数（超时的不算）。
     * 客户端显示的数量减去这个数，就是服务端处理完这些动作以后最少还剩几个（服务端拒绝的动作不扣，只会更多）。
     * 以前直接减去所有没确认的动作：客户端每放一个已经自己扣了一个，等于扣了两遍，一组 64 个放到 32 个就停下来等，
     * 然后 16、8、4……越往后越是一块一块地放。
     */
    public static int unreflectedUses(ClientLevel level, int tick, net.minecraft.world.item.Item item) {
        sync(level);
        if (!countValid || countTick != tick) {
            UNREFLECTED_COUNT.clear();
            for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<net.minecraft.world.item.Item> e : ITEMS.int2ObjectEntrySet()) {
                int seq = e.getIntKey();
                if (seq <= lastAcked || !UNREFLECTED.contains(seq)) continue;
                int sentTick = SENT_TICKS.getOrDefault(seq, tick);
                if (tick - sentTick > TIMEOUT_TICKS || tick < sentTick) continue;
                UNREFLECTED_COUNT.addTo(e.getValue(), 1);
            }
            countTick = tick;
            countValid = true;
        }
        return UNREFLECTED_COUNT.getInt(item);
    }

    /** 发出了一个破坏包（开始或结束挖掘） */
    public static void breakSent(ClientLevel level, BlockPos pos, int sequence) {
        if (level == null) return;
        sync(level);
        BREAKS.put(pos.asLong(), new long[]{sequence, level.getGameTime()});
    }

    /**
     * 已经发出、服务端还没确认的挖掘格数。服务端处理挖掘时就扣了工具耐久，确认回来之前客户端的工具还是旧耐久，
     * 最坏情况下每格扣 1 点（耐久附魔只会更少）。确认时一起清掉，所以这里只是数一下，不额外计算。
     */
    public static int breaksInFlight(ClientLevel level) {
        if (level == null) return 0;
        sync(level);
        if (!BREAKS.isEmpty()) {
            long now = level.getGameTime();
            BREAKS.values().removeIf(e -> now - e[1] > BREAK_TIMEOUT_TICKS || now < e[1]);
        }
        return BREAKS.size();
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

    /** 还有没被服务端确认的动作（超时的不算） */
    public static boolean anyPending(ClientLevel level, int tick) {
        sync(level);
        PENDING.values().removeIf(e -> e[0] <= lastAcked || tick - e[1] > TIMEOUT_TICKS || tick < e[1]);
        return !PENDING.isEmpty();
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
            SENT_TICKS.clear();
            UNREFLECTED.clear();
            countValid = false;
            BREAKS.clear();
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
