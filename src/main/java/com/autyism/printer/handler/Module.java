package com.autyism.printer.handler;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import lombok.Getter;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.*;
import com.autyism.printer.printer.*;
import com.autyism.printer.Reference;
import com.autyism.printer.utils.BreakUtils;
import com.autyism.printer.utils.ConfigUtils;
import com.autyism.printer.utils.LitematicaUtils;
import com.autyism.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public abstract class Module extends ConfigUtils {
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Printer-TimeoutGuard");
                t.setDaemon(true);
                return t;
            });
    @Getter
    @Nullable
    public final AtomicReference<PrinterBox> box;
    protected final IteratorManager iteratorManager = new IteratorManager();
    @Getter
    private final String id;
    @Getter
    @Nullable
    private final ConfigBoolean enableConfig;
    @Getter
    @Nullable
    private final ConfigOptionList selectionType;
    private final AtomicReference<Boolean> skipIteration = new AtomicReference<>(false);
    private final AtomicBoolean timeLimitExceeded = new AtomicBoolean(false);
    @Getter
    private final Queue<PendingHighlight> pendingHighlights = new ConcurrentLinkedQueue<>();
    protected Minecraft mc;
    protected ClientLevel level;
    protected LocalPlayer player;
    protected ClientPacketListener connection;
    protected MultiPlayerGameMode gameMode;
    protected GameType gameType;
    @Nullable
    protected HitResult hitResult;
    @Nullable
    protected BlockHitResult blockHitResult;
    protected boolean needSchematic = false;
    private long lastTickTime = -1L;
    @Getter
    private ScanState scanState = ScanState.RUNNING;

    @Nullable
    private BlockPos waitingPos = null;

    // 按方块分类：本轮扫描锁定的方块类型（null = 未锁定/普通迭代）
    @Nullable
    private Item currentCycleItem = null;

    private volatile GuiBlockInfo currentGuiInfo = null;

    protected Module(String id, @Nullable ConfigBoolean enableConfig, @Nullable ConfigOptionList selectionType, boolean useBox) {
        this.id = id;
        this.enableConfig = enableConfig;
        this.selectionType = selectionType;
        this.box = useBox ? new AtomicReference<>() : null;
        updateVariables();
    }

    protected void updateVariables() {
        mc = Minecraft.getInstance();
        level = mc.level;
        player = mc.player;
        connection = mc.getConnection();
        gameMode = mc.gameMode;
        gameType = gameMode == null ? null : gameMode.getPlayerMode();
        hitResult = mc.hitResult;
        blockHitResult = (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK)
                ? (BlockHitResult) hitResult : null;
    }

    private int dbgTicks, dbgIters, dbgPasses, dbgRebuilds;

    /** 调试：这个模块的停止点记进 TickTrace（只有打印模块记） */
    protected boolean traced;

    private void trace(String where) {
        if (traced) TickTrace.stop(where);
    }

    public void tick() {
        dbgTicks++;
        int tickInterval = getTickInterval();
        if (tickInterval > 0) {
            long currentTickTime = ModuleManager.getCurrentHandlerTime();
            if (lastTickTime != -1L && currentTickTime - lastTickTime < tickInterval) {
                trace("interval");
                return;
            }
            lastTickTime = currentTickTime;
        }

        if (!isConfigAllowed()) {
            pendingHighlights.clear();
            return;
        }

        updateVariables();
        if (mc == null || level == null || player == null || connection == null || gameMode == null || gameType == null) {
            return;
        }

        if (box == null) return;
        rebuildIteration();

        preprocess();

        skipIteration.set(false);
        int remainingExecs = Math.max(getMaxExecutions(), 0);
        if (!canExecute() || !canIterate()) {
            trace("cannot-execute");
            return;
        }

        // 高亮渐隐
        long cutoff = System.currentTimeMillis() - Configs.Highlight.HIGHLIGHT_FADE_DURATION.getIntegerValue() * 100L;
        pendingHighlights.removeIf(ph -> ph.time() < cutoff);

        // 远离工作区时提前退出，避免空跑卡顿
        if (needsAreaCheck() && !isPlayerRangeInWorkArea()) {
            trace("away");
            return;
        }

        iterateBlocks(remainingExecs);
    }

    /** 按玩家的位置和设置重建遍历框（分层时只有当前这一层） */
    private void rebuildIteration() {
        List<PrinterBox> areaBoxes = needsAreaCheck() ? getWorkAreaBoxes() : null;
        iteratorManager.setSectionFilter(getSectionFilter());
        Integer layerClamp = updateLayerClamp(areaBoxes);
        if (iteratorManager.tryBuildBox(player, selectionType != null ? selectionType.getOptionListValue() : null, respectsRenderLayer(),
                areaBoxes, layerClamp)) {
            box.set(iteratorManager.getBox());
            dbgRebuilds++;
            scanState = ScanState.RUNNING;
            waitingPos = null;
            currentCycleItem = null;
            swapDeferred.clear();
            iteratorManager.reset();
        }
    }

    /** 分层：一 tick 里最多接着扫几层（每一层都照常从头扫到尾、照常判定，只是不用等到下一 tick） */
    private static final int MAX_LAYER_STEPS_PER_TICK = 64;

    /** 这一 tick 最后看的一格（调试面板用，见 iterateBlocks 的 finally） */
    @Nullable
    private BlockPos guiPos;
    private boolean guiExecuted;

    /** 调试用：这一次 executeIteration 是从哪里来的（wait = 等换物品的那一格，scan = 扫描，子类还有 follow / adjust） */
    protected String execPath = "scan";

    private void iterateBlocks(int maxExecs) {
        long traceStart = traced ? System.nanoTime() : 0;
        if (traced) TickTrace.inScan = true;
        int execCount = 0;
        layerIdleTicks++;
        dbgIters++;
        int timeLimitMs = getIterationTimeLimit();

        skipIteration.set(false);
        timeLimitExceeded.set(false);

        // 超时保护
        ScheduledFuture<?> timeoutTask = null;
        if (timeLimitMs > 0) {
            timeoutTask = TIMEOUT_SCHEDULER.schedule(
                    () -> timeLimitExceeded.set(true),
                    timeLimitMs, TimeUnit.MILLISECONDS);
        }

        try {
            if (scanState == ScanState.WAITING) {
                BlockPos pos = waitingPos;
                waitingPos = null;
                scanState = ScanState.RUNNING;
                if (pos != null && needsWork(pos)) {
                    execPath = "wait";
                    executeIteration(pos, skipIteration);
                    passDidWork = true;
                    execCount++;
                    if (maxExecs > 0 && execCount >= maxExecs) {
                        trace("max-execs");
                        return;
                    }
                }
                if (skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) {
                    trace("waiting-pos");
                    return;
                }
            }

            // 先调整完再放下一个：正在把一个方块点到投影里的状态时，这一 tick 不去碰别的格子
            if (holdForAdjust(skipIteration)) {
                trace("adjust-hold");
                layerIdleTicks = 0;
                passDidWork = true;
                idle = false;
                return;
            }

            // 生存：上一轮最后要一起换、在等服务端确认的物品：确认回来了就先换（这一 tick 不再放别的，下一 tick 一起放）；
            // 等了几 tick 还没好就先不放别的，让确认回来（一直放别的，确认就一直回不完）
            int prefetch = prefetchBeforeScan();
            if (prefetch != PREFETCH_NONE) {
                trace(prefetch == PREFETCH_DONE ? "prefetch" : "prefetch-hold");
                idle = false;
                return;
            }

            // 双层台阶的第二块之类：第一块一被确认就接着放（不停下来等，等的时候照常放别的方块）
            int revisited = revisitFollowUps(skipIteration);
            if (revisited > 0) {
                layerIdleTicks = 0;
                passDidWork = true;
                idle = false;
                execCount += revisited;
                if (maxExecs > 0 && execCount >= maxExecs) {
                    trace("max-execs");
                    return;
                }
            }
            if (skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) {
                trace("follow-ups");
                return;
            }

            // 上一轮从头扫到尾什么都没得做：没有变化就先不重扫（见 idle）
            if (idle && !wakeFromIdle()) {
                trace("idle");
                return;
            }

            boolean wholeLayer = layerY != null && isWholeLayerRule();
            int layerSteps = 0;
            while (true) {
                if (timeLimitExceeded.get()) {
                    trace("time-limit");
                    return;
                }
                if (skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) {
                    trace("scan-stopped");
                    return;
                }

                BlockPos pos = iteratorManager.next();
                if (pos == null) {
                    currentCycleItem = null; // 一轮扫描耗尽，重置方块分类
                    // 这一轮手边能放的都放完了：现在才按扫描顺序处理先跳过的格子。一开始换物品就停（换好以后下一轮
                    // 把这种方块一起放掉）；别的原因放不了的（下面还没有支撑……）照常算一次尝试，和没跳过时一样
                    boolean focused = false;
                    // 生存：要换的几种物品一起换进快捷栏，下一 tick 一起放（以前一次换一种，每换一种都要等服务端确认一次）。
                    // 还要等确认时这一 tick 先不换，下一 tick 照常重扫（能放的照放），确认回来再一起换
                    if (!swapDeferred.isEmpty() && !skipIteration.get() && !ActionManager.INSTANCE.needWaitModifyLook
                            && prefetchForDeferred(swapDeferred) != PREFETCH_NONE) {
                        skipIteration.set(true);
                        passDidWork = true; // 有物品要换：这一轮不算“没事做”，下一 tick 照常重扫（不进入空闲）
                    }
                    for (int i = 0; i < swapDeferred.size(); i++) {
                        if (timeLimitExceeded.get() || skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) break;
                        BlockPos deferred = BlockPos.of(swapDeferred.getLong(i));
                        if (!needsWork(deferred)) continue;
                        execPath = "swap";
                        executeIteration(deferred, skipIteration);
                        passDidWork = true;
                        if (adjustFocusActive()) {
                            focused = true;
                            break;
                        }
                        if (maxExecs > 0 && ++execCount >= maxExecs) break;
                    }
                    swapDeferred.clear();
                    if (focused) {
                        onPassFinished();
                        trace("adjust-start");
                        return;
                    }
                    Integer passLayer = layerY;
                    onPassFinished();
                    // 分层：这一层判定完成、往上换了一层，这一 tick 还有时间就接着扫新的一层。
                    // 以前每 tick 最多过一层：开打印机、到顶回到底层时，几十层打完了的层要一层一层地等
                    if (passLayer != null && layerY != null && layerY > passLayer && !idle && ++layerSteps < MAX_LAYER_STEPS_PER_TICK) {
                        rebuildIteration();
                        wholeLayer = layerY != null && isWholeLayerRule();
                        continue;
                    }
                    trace(idle ? "pass-end-rest" : "pass-end");
                    return;
                }

                if (needsAreaCheck() && !isPosInWorkspace(pos)) continue;

                boolean executed = false;
                boolean work = needsWork(pos);
                if (layerY != null && pos.getY() == layerY) onLayerPassPosition(pos);
                // 整层判定要知道范围内每一格的结论，所以每一格都要判断；只看范围内时找到一格没完成就够了
                if (layerY != null && (!layerPending || wholeLayer)) {
                    // 分层模式：本层还有没完成的方块（包括刚尝试过、处于冷却中的）就不能进入上一层。
                    // 例外：被实体挡住（例如玩家自己站在那格）或反复尝试仍放不上的格子，不能卡住整层
                    boolean unfinished = work || (isOnCooldown(pos) && LitematicaUtils.isPositionWithinRange(pos) && !isCorrectBlock(pos)
                            && canProcessPos(pos))
                            // 放过、还在等服务端回应的格子也没完成（回应之后要么已经对了，要么被拒绝了再放）
                            || (isAwaitingServer(pos) && LitematicaUtils.isPositionWithinRange(pos) && !isCorrectBlock(pos));
                    boolean pendingHere = unfinished && layerAttempts.getOrDefault(pos.asLong(), 0) < LAYER_MAX_ATTEMPTS
                            && !isObstructedForLayer(pos) && !layerDeferred.contains(pos.asLong());
                    if (wholeLayer && pos.getY() == layerY) {
                        long key = pos.asLong();
                        if (pendingHere) {
                            layerVerdicts.put(key, true);
                            if (passPending.size() < MAX_PASS_PENDING) passPending.add(key);
                        } else if (unfinished || isCorrectBlock(pos)) {
                            // 打好了；或者只是这次在分层里被跳过（尝试太多次、被实体挡住、等上一层）：整层判定时现查，不记结论
                            layerVerdicts.remove(key);
                        } else {
                            layerVerdicts.put(key, false);
                        }
                    }
                    if (pendingHere && !layerPending) {
                        layerPending = true;
                        lastPendingPos = pos.immutable();
                    }
                }
                if (work) {
                    // 按方块分类：一轮扫描仅处理一种方块类型（可选开关）
                    if (!Configs.Core.CLASSIFY_BY_BLOCK.getBooleanValue() || isCycleItemMatch(pos)) {
                        // 要从背包换物品的先跳过，这一轮把快捷栏里有的放完再换（见 deferForSwap）
                        if (swapDeferred.size() < MAX_SWAP_DEFERRED && deferForSwap(pos)) {
                            swapDeferred.add(pos.asLong());
                            guiPos = pos;
                            guiExecuted = false;
                            continue;
                        }
                        execPath = "scan";
                        executeIteration(pos, skipIteration);
                        passDidWork = true;
                        executed = true;
                        // 刚放下（或点了）一个还要调整的方块：先把它调整完，这一 tick 不再放别的
                        if (adjustFocusActive()) {
                            trace("adjust-start");
                            return;
                        }
                        if (maxExecs > 0 && ++execCount >= maxExecs) {
                            trace("max-execs");
                            return;
                        }
                    }
                }

                guiPos = pos;
                guiExecuted = executed;
            }
        } finally {
            if (timeoutTask != null) timeoutTask.cancel(false);
            timeLimitExceeded.set(false);
            if (traced) {
                TickTrace.iterNanos += System.nanoTime() - traceStart;
                TickTrace.inScan = false;
            }
            // 调试面板只显示这一 tick 最后看的一格：只在最后算一次（以前每一格都新建一次，还要查两遍可交互）
            if (guiPos != null && level != null) {
                currentGuiInfo = new GuiBlockInfo(guiPos.immutable(), level.getBlockState(guiPos), LitematicaUtils.getBlockState(guiPos),
                        PlayerUtils.canInteracted(guiPos), guiExecuted, isPosInWorkspace(guiPos) && PlayerUtils.canInteracted(guiPos));
                guiPos = null;
            }
        }
    }

    // ---------------- 需求 13：分层打印 ----------------
    /** 分层模式下当前正在打印的层；null = 未启用分层 */
    @Nullable
    private Integer layerY;
    private boolean layerPending;
    /** 分层模式下每个格子的放置尝试次数（换层时清空） */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap layerAttempts = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static final int LAYER_MAX_ATTEMPTS = 6;

    /** 只对要分几步才能做好的方块（音符盒、中继器、雪层、蜡烛……见 StepProgress）：上一次尝试时离目标还差几步 */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap layerAttemptRemaining = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    /** 记录一次放置尝试（由具体模块在真正发出放置时调用） */
    protected void notePlacementAttempt(BlockPos pos) {
        if (layerY != null) {
            long key = pos.asLong();
            int remaining = -1;
            var schematic = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
            if (level != null && schematic != null) {
                remaining = com.autyism.printer.printer.StepProgress.remaining(schematic.getBlockState(pos), level.getBlockState(pos));
            }
            if (remaining >= 0 && layerAttemptRemaining.containsKey(key) && remaining < layerAttemptRemaining.get(key)) {
                // 上一次尝试让它离目标更近了（音符盒高了一个音、多放了一层雪……）：有进展，重新计数
                layerAttempts.put(key, 1);
            } else {
                // 其他方块照原来的规则：每次尝试都算一次
                layerAttempts.addTo(key, 1);
            }
            if (remaining >= 0) layerAttemptRemaining.put(key, remaining);
            else layerAttemptRemaining.remove(key);
        }
        layerIdleTicks = 0;
    }

    /** 分层模式：本层里要等上一层的方块放好之后才能放的格子（例如上坡铁轨要等高处那一节），不阻塞换层，回到这一层复查时再放 */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet layerDeferred = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** 见 {@link #layerDeferred} */
    public void deferToUpperLayer(BlockPos pos) {
        if (layerY != null) layerDeferred.add(pos.asLong());
    }

    // ---------------- 整层判定（一层要在已加载的区块里整层打完才算完成） ----------------
    /**
     * 主循环看过的本层格子当时的结论：true = 还没完成、打印机要处理；false = 没打好但现在没什么能做的
     * （例如门要等旁边的方块、缺材料以外的原因放不了）。打好了的格子不记。换层时清空。
     * 整层判定扫到工作范围外的格子时，用打印机自己在范围内时得出的结论，而不是只看方块。
     */
    private final it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap layerVerdicts = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
    /** 这一轮扫描里范围内还没完成的格子（整层判定时看门狗用） */
    private final it.unimi.dsi.fastutil.longs.LongArrayList passPending = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static final int MAX_PASS_PENDING = 65536;

    /** 一层要整层打完（已加载的部分）才算完成；false = 工作范围内的部分打完就算 */
    protected boolean isWholeLayerRule() {
        return false;
    }

    /**
     * 整层判定：工作范围内这一轮已经没有没完成的格子了，本层其余部分（已加载的区块）是不是也都完成了。
     * 子类实现；默认没有范围外的部分。
     */
    protected boolean restOfLayerDone(int layer) {
        return true;
    }

    /** 上一次 restOfLayerDone 还没有结论（统计还没算完、在等服务端确认），不是真的“还没完成”：下一 tick 就再判断 */
    protected boolean layerVerdictPending() {
        return false;
    }

    /** 见 {@link #layerVerdicts}；null = 主循环在这一层还没看过这一格（或者它已经打好了） */
    @Nullable
    protected final Boolean layerVerdict(BlockPos pos) {
        long key = pos.asLong();
        return layerVerdicts.containsKey(key) ? layerVerdicts.get(key) : null;
    }

    /** 这一格现在在主循环的扫描范围内（工作范围 ∩ 投影 ∩ 当前层，按实时的眼睛位置） */
    protected final boolean coveredByScan(BlockPos pos) {
        return iteratorManager.covers(pos);
    }

    // ---------------- 没事可做时少扫几遍 ----------------
    /**
     * 范围内从头扫到尾什么都没得做（没有放置、点击，也没有没完成的格子；分层时这一层在等范围外的部分，或者所有层都看过一遍）。
     * 这时不用每 tick 重新扫一遍：范围里的方块变了、玩家移动了、或者过了 IDLE_RECHECK_TICKS 才再扫。
     * 以前打印机做完了也每 tick 扫一遍工作范围（范围大时每 tick 几毫秒）。
     */
    private boolean idle;
    private boolean passDidWork;
    /** 分层：从上一次回到最底层起，有没有做过事 */
    private boolean cycleDidWork;
    private volatile boolean changedSinceIdle;
    private int idleSinceTick;
    @Nullable
    private Integer idleLayer;
    private double idleEyeX, idleEyeY, idleEyeZ;
    /** 这次歇多久（没有变化时）再重新扫一遍 */
    private int idleRecheckTicks;
    /** 范围内做完了、在等别的（例如整层判定在等玩家走过去）：歇 10 tick */
    private static final int IDLE_RECHECK_TICKS = 10;
    /** 所有层都看过一遍、一件事都没做：歇 40 tick（方块有变化会马上叫醒） */
    private static final int IDLE_CYCLE_RECHECK_TICKS = 40;

    /** 这个模块没事做时可以少扫几遍（打印模块） */
    protected boolean throttleWhenIdle() {
        return false;
    }

    /** 客户端世界里的方块变了：落在扫描范围里（含旁边一格）就叫醒 */
    protected final void noteClientBlockChange(BlockPos pos) {
        if (!idle) return;
        PrinterBox b = iteratorManager.getBox();
        if (b == null || (pos.getX() >= b.minX - 1 && pos.getX() <= b.maxX + 1 && pos.getY() >= b.minY - 1 && pos.getY() <= b.maxY + 1
                && pos.getZ() >= b.minZ - 1 && pos.getZ() <= b.maxZ + 1)) {
            changedSinceIdle = true;
        }
    }

    private void enterIdle(int recheckTicks) {
        // 自己的动作还在等服务端回应、或者还有格子在冷却：结果回来之前随时可能又有事做，不歇
        if (level == null || BlockPosCooldownManager.INSTANCE.hasAny() || ActionConfirm.anyPending(level, nowTick())) return;
        idle = true;
        idleRecheckTicks = recheckTicks;
        changedSinceIdle = false;
        idleSinceTick = nowTick();
        idleLayer = layerY;
        if (player != null) {
            idleEyeX = player.getX();
            idleEyeY = player.getY();
            idleEyeZ = player.getZ();
        }
    }

    /** 没事做的时候，要不要现在重新扫一遍 */
    private boolean wakeFromIdle() {
        int now = nowTick();
        boolean moved = player != null && player.distanceToSqr(idleEyeX, idleEyeY, idleEyeZ) >= 1.0;
        if (changedSinceIdle || moved || !java.util.Objects.equals(layerY, idleLayer)
                || now - idleSinceTick >= idleRecheckTicks || now < idleSinceTick) {
            idle = false;
            return true;
        }
        return false;
    }

    // ---------------- 先调整完再放下一个（子类实现） ----------------
    /** 返回 true：正在把一个方块调整到投影里的状态，这一 tick 不去处理别的格子 */
    protected boolean holdForAdjust(AtomicReference<Boolean> skipIteration) {
        return false;
    }

    /** 这一轮里因为要从背包换物品而先跳过的格子（按扫描顺序）：这一轮扫完、手边能放的都放了，再处理它们 */
    private final it.unimi.dsi.fastutil.longs.LongArrayList swapDeferred = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static final int MAX_SWAP_DEFERRED = 4096;

    /** 这一格要用的物品现在不在手边（要从背包换过来）：先跳过，这一轮最后再换（子类实现；needsWork(pos) 之后调用） */
    protected boolean deferForSwap(BlockPos pos) {
        return false;
    }

    /** prefetchForDeferred 的结果：不管（照旧一格一格换）/ 要等服务端确认 / 换好了 */
    protected static final int PREFETCH_NONE = 0, PREFETCH_WAIT = 1, PREFETCH_DONE = 2;

    /** 每 tick 扫描之前（子类实现）：上一轮最后没换成的物品现在换（DONE）、先停下来等确认（WAIT），或者照常扫描（NONE） */
    protected int prefetchBeforeScan() {
        return PREFETCH_NONE;
    }

    /** 这一轮跳过的格子要用的物品，一起换进快捷栏（子类实现）；不是 PREFETCH_NONE 时这一 tick 不再处理跳过的格子 */
    protected int prefetchForDeferred(it.unimi.dsi.fastutil.longs.LongList deferred) {
        return PREFETCH_NONE;
    }

    /** 刚执行的动作让一个方块进入“先调整完”的状态 */
    protected boolean adjustFocusActive() {
        return false;
    }

    /** 每 tick 扫描之前先处理“放了一半、等确认后马上补完”的格子；返回这次做了几个 */
    protected int revisitFollowUps(AtomicReference<Boolean> skipIteration) {
        return 0;
    }


    /** 当前这一轮分层是在多大的可达范围下开始的（范围变大就回到新的最底层） */
    private double layerRange = Double.NaN;

    /** 当前层连续多少个“实际在工作的 tick”没有任何放置尝试（暂停、离开范围时不计） */
    private int layerIdleTicks;
    @Nullable
    private int[] lastLayerBounds;
    /** 当前层还有没完成的格子、但连续这么多工作 tick 都没有放置尝试，就先打上一层，防止整机卡死 */
    private static final int LAYER_STALL_TICKS = 80;

    /** 该格子是否被实体挡住而无法放置（分层模式下不阻塞换层） */
    protected boolean isObstructedForLayer(BlockPos pos) {
        return false;
    }

    /** 是否启用分层打印（从下往上一层一层打） */
    protected boolean isLayeredMode() {
        return false;
    }

    /** 最近一次让当前层保持“未完成”的格子（调试 / 测试用） */
    @Nullable
    private BlockPos lastPendingPos;

    /** 调试 / 测试用：某个坐标为什么（不）需要处理 */
    public String debugPos(BlockPos pos) {
        return "withinRange=" + LitematicaUtils.isPositionWithinRange(pos) + " cooldown=" + isOnCooldown(pos)
                + " canProcess=" + canProcessPos(pos) + " correct=" + isCorrectBlock(pos) + " inWorkspace=" + isPosInWorkspace(pos)
                + " canInteract=" + PlayerUtils.canInteracted(pos) + " layerSkipped=" + isLayerSkipped(pos);
    }

    /** 调试 / 测试用：当前内部状态 */
    public String debugState() {
        return "ticks=" + dbgTicks + " iters=" + dbgIters + " passes=" + dbgPasses + " rebuilds=" + dbgRebuilds + " scan=" + scanState + " waiting=" + waitingPos + " layer=" + layerY + " pending=" + layerPending
                + " idle=" + layerIdleTicks + " box=" + (box == null ? null : box.get())
                + " breakQueue=" + com.autyism.printer.utils.BreakUtils.INSTANCE.isNeedHandle()
                + " lookWait=" + ActionManager.INSTANCE.needWaitModifyLook
                + " canExec=" + canExecute() + " canIter=" + canIterate() + " allowed=" + isConfigAllowed()
                //? if >=26.2 {
                /*+ " paused=" + ContainerGuard.isPaused() + " screen=" + (mc == null ? null : mc.gui.screen())
                *///?} else
                + " paused=" + ContainerGuard.isPaused() + " screen=" + (mc == null ? null : mc.screen)
                + " iterBox=" + iteratorManager.getBox() + " effRange=" + ConfigUtils.getEffectiveRange()
                + " workRange=" + Configs.Core.WORK_RANGE.getDoubleValue()
                //? if <1.20.5 {
                /*+ " reach=" + (player == null || mc.gameMode == null ? null : mc.gameMode.getPickRange()) + " eyeY=" + (player == null ? null : player.getEyeY())
                *///?} else
                + " reach=" + (player == null ? null : player.blockInteractionRange()) + " eyeY=" + (player == null ? null : player.getEyeY())
                + " areaBoxes=" + getWorkAreaBoxes() + " resting=" + idle + debugExtra();
    }

    /** 调试 / 测试用：完整扫完的轮数 */
    public int debugPasses() {
        return dbgPasses;
    }

    /** 子类补充的调试信息 */
    protected String debugExtra() {
        return "";
    }

    /** 调试：整层判定为什么还没完成（子类补充） */
    protected String debugLayerWait() {
        return "";
    }

    /** 调试：一格的情况（投影 / 世界 / 冷却 / 等确认 / 尝试次数） */
    protected String describePos(@Nullable BlockPos pos) {
        if (pos == null || level == null) return "null";
        return pos.toShortString() + " want=" + LitematicaUtils.getBlockState(pos) + " have=" + level.getBlockState(pos)
                + " cooldown=" + isOnCooldown(pos) + " awaiting=" + isAwaitingServer(pos) + " attempts=" + layerAttempts.getOrDefault(pos.asLong(), 0)
                + " obstructed=" + isObstructedForLayer(pos);
    }

    private int forcedLayerSkips;
    @Nullable
    private BlockPos lastForcedSkipPos;

    public int getForcedLayerSkips() {
        return forcedLayerSkips;
    }

    @Nullable
    public BlockPos getLastForcedSkipPos() {
        return lastForcedSkipPos;
    }

    @Nullable
    public BlockPos getLastPendingPos() {
        return lastPendingPos;
    }

    @Nullable
    public Integer getCurrentLayer() {
        return layerY;
    }

    /** 计算本 tick 的分层 Y；区域发生变化时把层号限制在区域范围内 */
    @Nullable
    private Integer updateLayerClamp(@Nullable List<PrinterBox> areaBoxes) {
        if (!isLayeredMode() || areaBoxes == null || areaBoxes.isEmpty() || player == null) {
            layerY = null;
            return null;
        }
        int[] bounds = layerBounds(areaBoxes);
        if (bounds == null) {
            layerY = null;
            return null;
        }
        // 投影 / 选区的高度范围变了（换了投影、移动了投影）就从新的最底层重新开始；
        // 只看区域本身，不看玩家可达范围，否则玩家跳一下就会重头扫一遍
        int[] areaKey = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (PrinterBox b : areaBoxes) {
            areaKey[0] = Math.min(areaKey[0], b.minY);
            areaKey[1] = Math.max(areaKey[1], b.maxY);
        }
        // 可达范围变大了（单人世界自动调高交互距离生效、或者调大了工作半径）：下面多出来的层要先打，回到新的最底层。
        // 以前这里不管，开打印机的头几 tick 还是原版 4.5 格的范围，站在建筑上方时会先打靠近眼睛的上层、再绕回最底层
        //（红石机器先放了上面的活塞 / 侦测器，下面搭起来时电路会动）
        double range = ConfigUtils.getEffectiveRange();
        boolean rangeGrew = !Double.isNaN(layerRange) && range > layerRange + 0.25;
        if (Double.isNaN(layerRange) || range < layerRange) layerRange = range;
        if (layerY == null || layerY < bounds[0] || layerY > bounds[1] || !java.util.Arrays.equals(areaKey, lastLayerBounds) || rangeGrew) {
            layerY = bounds[0];
            layerRange = range;
            layerPending = false;
            layerAttempts.clear();
            layerAttemptRemaining.clear();
            layerDeferred.clear();
            layerVerdicts.clear();
            passPending.clear();

            layerIdleTicks = 0;
            lastLayerBounds = areaKey;
        }
        return layerY;
    }

    /** 工作范围内投影/选区的最低层与最高层 */
    @Nullable
    private int[] layerBounds(List<PrinterBox> areaBoxes) {
        double range = ConfigUtils.getEffectiveRange();
        int minY = (int) Math.floor(player.getEyeY() - range);
        int maxY = (int) Math.ceil(player.getEyeY() + range);
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (PrinterBox b : areaBoxes) {
            int y0 = Math.max(b.minY, minY), y1 = Math.min(b.maxY, maxY);
            if (y0 > y1) continue;
            lo = Math.min(lo, y0);
            hi = Math.max(hi, y1);
        }
        return lo > hi ? null : new int[]{lo, hi};
    }

    /** 一轮遍历结束：分层模式下本层已全部完成则进入上一层（到顶后回到最底层复查） */
    /** 一轮遍历结束时调用（子类可覆盖） */
    protected void onScanPassFinished() {
    }

    /** 分层模式：本轮遍历到当前层的一个工作区坐标（用于统计） */
    protected void onLayerPassPosition(BlockPos pos) {
    }

    /** 分层模式：本轮遍历结束，layerDone 表示当前层已完成即将进入上一层 */
    protected void onLayerPassFinished(int layer, boolean layerDone) {
    }

    /** 该坐标在分层模式下是否已被判定为“跳过”（被实体挡住或多次尝试失败） */
    protected boolean isLayerSkipped(BlockPos pos) {
        return layerAttempts.getOrDefault(pos.asLong(), 0) >= LAYER_MAX_ATTEMPTS || isObstructedForLayer(pos)
                || layerDeferred.contains(pos.asLong());
    }

    /** 本轮扫描是否遇到了还没载入完的投影区域（子类实现；分层模式下这样的层不算完成） */
    protected boolean consumeAreaNotReady() {
        return false;
    }

    /** 当前这一轮扫描开始的 tick（缺失材料列表按整轮扫描更新，见 MissingMaterialTracker） */
    private int passStartTick;

    private int nowTick() {
        return player == null ? 0 : player.tickCount;
    }

    private void onPassFinished() {
        dbgPasses++;
        boolean didWork = passDidWork;
        passDidWork = false;
        if (didWork) cycleDidWork = true;
        com.autyism.printer.printer.MissingMaterialTracker.getInstance().passFinished(this, passStartTick);
        passStartTick = nowTick();
        onScanPassFinished();
        // 注意：曾尝试“投影区块没载入完的层不算完成”，但会让层长时间挂起再被看门狗强制跳层，反而打乱顺序，已撤回
        consumeAreaNotReady();
        if (layerY == null) {
            // 不分层：这一轮整个范围都没事做
            if (throttleWhenIdle() && !didWork) enterIdle(IDLE_RECHECK_TICKS);
            return;
        }
        boolean wholeLayer = isWholeLayerRule();
        if (layerPending && layerIdleTicks > LAYER_STALL_TICKS) {
            // 本层剩下的格子一直放不了（缺材料、无处可贴、需要先有别的方块……）：先打上面的层，到顶后会回来复查
            forcedLayerSkips++;
            lastForcedSkipPos = lastPendingPos;
            if (wholeLayer) {
                // 整层判定：范围内这些一直放不了的格子先放弃（这次在这一层时不再等它们），
                // 本层其余的部分（包括范围外的）还是要打完才往上走
                for (int i = 0; i < passPending.size(); i++) layerAttempts.put(passPending.getLong(i), LAYER_MAX_ATTEMPTS);
            }
            layerPending = false;
        }
        boolean layerDone = !layerPending;
        // 整层判定：范围内这一轮都完成了，还要本层在已加载区块里的其余部分也都完成
        if (layerDone && wholeLayer) layerDone = restOfLayerDone(layerY);
        if (traced && TickTrace.ENABLED) {
            TickTrace.detail("layer " + layerY + (layerDone ? " done" : layerPending ? " unfinished at " + describePos(lastPendingPos) : " rest: " + debugLayerWait()));
        }
        // 看门狗只数“范围内有没完成的格子、却一直没有放置尝试”的时间：等玩家走到范围外那部分时不算
        if (!layerPending) layerIdleTicks = 0;
        onLayerPassFinished(layerY, layerDone);
        boolean nothingPending = !layerPending;
        if (!layerDone && nothingPending && !didWork && throttleWhenIdle()) {
            // 整层判定：范围内的都做完了，在等范围外的部分（玩家走过去）。
            // 判定还没有结论（统计还在算、在等服务端确认）时只歇 1 tick：以前也歇 10 tick，大投影每一层都要白等半秒
            enterIdle(layerVerdictPending() ? 1 : IDLE_RECHECK_TICKS);
        }
        if (layerDone) {
            layerIdleTicks = 0;
            List<PrinterBox> areaBoxes = getWorkAreaBoxes();
            int[] bounds = areaBoxes == null ? null : layerBounds(areaBoxes);
            if (bounds != null) {
                boolean wrap = layerY + 1 > bounds[1];
                boolean idleCycle = wrap && !cycleDidWork && throttleWhenIdle();
                if (wrap) cycleDidWork = false;
                layerY = wrap ? bounds[0] : layerY + 1;
                // 所有层都看过一遍、一件事都没做：先歇一会儿（换层之后再记，免得马上又被“层变了”叫醒）
                if (idleCycle) enterIdle(IDLE_CYCLE_RECHECK_TICKS);
                layerAttempts.clear();
                layerAttemptRemaining.clear();
                layerDeferred.clear();
                layerVerdicts.clear();
            }
        }
        layerPending = false;
        passPending.clear();
    }

    /** 可整段跳过的区块段（默认不跳过） */
    @Nullable
    protected IteratorManager.SectionFilter getSectionFilter() {
        return null;
    }

    /**
     * 工作区域的方块框：投影模式为所有启用的投影放置子区域，选区模式为 Litematica 选区。
     * 迭代只会在“工作范围 ∩ 这些框”里进行。返回 null 表示不限制（遍历整个工作范围）。
     */
    @Nullable
    protected List<PrinterBox> getWorkAreaBoxes() {
        return needSchematic ? LitematicaUtils.getSchematicWorkBoxes() : LitematicaUtils.getSelectionWorkBoxes();
    }

    private boolean isCycleItemMatch(BlockPos pos) {
        Item[] items = getRequiredItems(pos);
        Item item = items != null && items.length > 0 ? items[0] : null;
        if (item == null) return true; // 无物品需求的位置始终处理（对应旧 noItemPositions）
        if (currentCycleItem == null) {
            currentCycleItem = item; // 锁定本轮首个所需物品
            return true;
        }
        return item.equals(currentCycleItem);
    }

    /**
     * 粗筛：玩家可达范围是否与工作区有交集。
     * 投影模式 isSchematicBlock 已够快，无需提前退出；
     * 选区模式用选区边界盒做 O(1) 排空判断。
     */
    private boolean isPlayerRangeInWorkArea() {
        if (needSchematic) return true;
        if (player == null) return false;
        PrinterBox selectBounds = LitematicaUtils.getSelectionBounds();
        if (selectBounds == null) return false;
        double r = ConfigUtils.getEffectiveRange();
        double px = player.getX(), py = player.getEyeY(), pz = player.getZ();
        return Math.floor(px - r) <= selectBounds.maxX && Math.ceil(px + r) >= selectBounds.minX
            && Math.floor(py - r) <= selectBounds.maxY && Math.ceil(py + r) >= selectBounds.minY
            && Math.floor(pz - r) <= selectBounds.maxZ && Math.ceil(pz + r) >= selectBounds.minZ;
    }

    protected void enterWaiting(@Nullable BlockPos pos) {
        scanState = ScanState.WAITING;
        waitingPos = pos;
    }

    private boolean needsWork(BlockPos pos) {
        // 逐方块复核渲染层：盒子裁剪之外的兜底，覆盖等待重试的坐标和层范围在两次重建之间变化的情况
        if (respectsRenderLayer() && !LitematicaUtils.isPositionWithinRange(pos)) return false;
        // 先看是不是已经对了（只比两个方块状态）：打印的 canProcessPos 要算放置方式（PlacementGuide），
        // 以前范围里每一格打好了的方块每一轮都要算一遍，范围大、层数多时一层要扫好几 tick
        return !isOnCooldown(pos) && !isAwaitingServer(pos) && !isCorrectBlock(pos) && canProcessPos(pos);
    }

    /**
     * 打印机自己往这一格（或者点击可能落到这一格）的放置 / 点击，服务端还没回应。
     * 这时客户端看到的不一定是结果：开着 Meteor 的 NoGhostBlocks（防幽灵方块，默认对放置生效）、或者用数据包放置时，
     * 客户端根本不先显示放下的方块，要等服务端发回来。有延迟时打印机会以为“还没放”再放一次，
     * 第二次的方块落到旁边（真实实例里 CommandLeo 打包机的侦测器上面多出一个侦测器）。
     * 只有放置类模块（打印、填充、清除流体）等；客户端正常预测时放过的格子已经不是空的，这里不起作用。
     */
    protected boolean isAwaitingServer(BlockPos pos) {
        return false;
    }

    /** 见 {@link #isAwaitingServer}：打印机自己的动作还没被服务端确认（ActionConfirm） */
    protected final boolean ownActionUnconfirmed(BlockPos pos) {
        return level != null && ActionConfirm.pending(level, pos, player == null ? 0 : player.tickCount);
    }

    private boolean isPosInWorkspace(BlockPos pos) {
        return needSchematic
                ? LitematicaUtils.isSchematicBlock(pos)
                : LitematicaUtils.inSelection(pos);
    }

    @Nullable
    protected Item[] getRequiredItems(BlockPos pos) {
        return null;
    }

    public void resetScanState() {
        idle = false;
        passDidWork = false;
        cycleDidWork = false;
        passStartTick = nowTick();
        layerY = null;
        layerRange = Double.NaN;
        layerAttempts.clear();
        layerAttemptRemaining.clear();
        layerDeferred.clear();
        layerVerdicts.clear();
        passPending.clear();
        layerPending = false;
        scanState = ScanState.RUNNING;
        waitingPos = null;
        currentCycleItem = null;
        swapDeferred.clear();
        iteratorManager.reset();
    }

    @Nullable
    public GuiBlockInfo getGuiInfo() {
        return currentGuiInfo;
    }

    private boolean isConfigAllowed() {
        if (!ConfigUtils.isPrinterEnable()) return false;
        return enableConfig == null || enableConfig.getBooleanValue();
    }

    private boolean wasAllowed;

    /** 这个模式现在在工作（每 tick 由 pollActivated 更新） */
    boolean isActive() {
        return wasAllowed;
    }

    /** 这个模式刚开始工作（上次调用时还没在工作）。每 tick 调用一次 */
    boolean pollActivated() {
        boolean allowed = isConfigAllowed();
        boolean activated = allowed && !wasAllowed;
        wasAllowed = allowed;
        return activated;
    }

    protected int getTickInterval() {
        return -1;
    }

    protected int getMaxExecutions() {
        return -1;
    }

    protected int getIterationTimeLimit() {
        return Configs.Core.ITERATION_TIME_LIMIT.getIntegerValue();
    }

    protected void preprocess() {
    }

    protected boolean canExecute() {
        return true;
    }

    protected boolean canIterate() {
        return true;
    }

    public abstract boolean canProcessPos(BlockPos pos);

    public abstract boolean isCorrectBlock(BlockPos pos);

    protected void addHighlight(BlockPos pos, HighlightType type) {
        // 高亮关着（默认）时不记：画的时候也不画。以前照样每放一块记一条，还要先在整个队列里找同一格的旧记录删掉，
        // 一 tick 放上百块时这一项占了扫描时间的近一成
        if (!Configs.Highlight.HIGHLIGHT_ENABLED.getBooleanValue()) return;
        BlockPos immutable = pos.immutable();
        pendingHighlights.removeIf(ph -> ph.pos().equals(immutable));
        pendingHighlights.add(new PendingHighlight(immutable, System.currentTimeMillis(), type));
    }

    protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skipIteration) {
    }

    public boolean isOnCooldown(@Nullable BlockPos pos) {
        if (level == null || pos == null) return true;
        return BlockPosCooldownManager.INSTANCE.isOnCooldown(level, id, pos);
    }

    public void setCooldown(@Nullable BlockPos pos, int ticks) {
        if (level == null || pos == null || ticks < 1) return;
        BlockPosCooldownManager.INSTANCE.setCooldown(level, id, pos, ticks);
    }

    protected Direction getPlayerPlacementDirection() {
        return Direction.orderedByNearest(player)[0].getOpposite();
    }

    /** 是否按 Litematica 渲染层过滤要处理的方块 */
    protected boolean respectsRenderLayer() {
        return true;
    }

    protected boolean needsAreaCheck() {
        return true;
    }

    public record PendingHighlight(BlockPos pos, long time, HighlightType type) {
    }
}