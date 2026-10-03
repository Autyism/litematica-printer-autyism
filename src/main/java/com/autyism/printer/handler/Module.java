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

    public void tick() {
        dbgTicks++;
        int tickInterval = getTickInterval();
        if (tickInterval > 0) {
            long currentTickTime = ModuleManager.getCurrentHandlerTime();
            if (lastTickTime != -1L && currentTickTime - lastTickTime < tickInterval) {
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
            iteratorManager.reset();
        }

        preprocess();

        skipIteration.set(false);
        int remainingExecs = Math.max(getMaxExecutions(), 0);
        if (!canExecute() || !canIterate()) return;

        // 高亮渐隐
        long cutoff = System.currentTimeMillis() - Configs.Highlight.HIGHLIGHT_FADE_DURATION.getIntegerValue() * 100L;
        pendingHighlights.removeIf(ph -> ph.time() < cutoff);

        // 远离工作区时提前退出，避免空跑卡顿
        if (needsAreaCheck() && !isPlayerRangeInWorkArea()) return;

        iterateBlocks(remainingExecs);
    }

    private void iterateBlocks(int maxExecs) {
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
                    executeIteration(pos, skipIteration);
                    execCount++;
                    if (maxExecs > 0 && execCount >= maxExecs) return;
                }
                if (skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) return;
            }

            while (true) {
                if (timeLimitExceeded.get()) return;
                if (skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) return;

                BlockPos pos = iteratorManager.next();
                if (pos == null) {
                    currentCycleItem = null; // 一轮扫描耗尽，重置方块分类
                    onPassFinished();
                    return;
                }

                if (needsAreaCheck() && !isPosInWorkspace(pos)) continue;

                boolean executed = false;
                boolean work = needsWork(pos);
                if (layerY != null && pos.getY() == layerY) onLayerPassPosition(pos);
                if (layerY != null && !layerPending) {
                    // 分层模式：本层还有没完成的方块（包括刚尝试过、处于冷却中的）就不能进入上一层。
                    // 例外：被实体挡住（例如玩家自己站在那格）或反复尝试仍放不上的格子，不能卡住整层
                    boolean unfinished = work || (isOnCooldown(pos) && LitematicaUtils.isPositionWithinRange(pos) && !isCorrectBlock(pos)
                            && canProcessPos(pos));
                    layerPending = unfinished && layerAttempts.getOrDefault(pos.asLong(), 0) < LAYER_MAX_ATTEMPTS
                            && !isObstructedForLayer(pos);
                    if (layerPending) lastPendingPos = pos.immutable();
                }
                if (work) {
                    // 按方块分类：一轮扫描仅处理一种方块类型（可选开关）
                    if (!Configs.Core.CLASSIFY_BY_BLOCK.getBooleanValue() || isCycleItemMatch(pos)) {
                        executeIteration(pos, skipIteration);
                        executed = true;
                        if (maxExecs > 0 && ++execCount >= maxExecs) return;
                    }
                }

                currentGuiInfo = new GuiBlockInfo(pos,
                        level.getBlockState(pos), LitematicaUtils.getBlockState(pos),
                        PlayerUtils.canInteracted(pos), executed,
                        isPosInWorkspace(pos) && PlayerUtils.canInteracted(pos));
            }
        } finally {
            if (timeoutTask != null) timeoutTask.cancel(false);
            timeLimitExceeded.set(false);
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

    /** 记录一次放置尝试（由具体模块在真正发出放置时调用） */
    protected void notePlacementAttempt(BlockPos pos) {
        if (layerY != null) layerAttempts.addTo(pos.asLong(), 1);
        layerIdleTicks = 0;
    }

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
                + " paused=" + ContainerGuard.isPaused() + " screen=" + (mc == null ? null : mc.screen)
                + " iterBox=" + iteratorManager.getBox() + " effRange=" + ConfigUtils.getEffectiveRange()
                + " workRange=" + Configs.Core.WORK_RANGE.getDoubleValue()
                + " reach=" + (player == null ? null : player.blockInteractionRange()) + " eyeY=" + (player == null ? null : player.getEyeY())
                + " areaBoxes=" + getWorkAreaBoxes();
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
        if (layerY == null || layerY < bounds[0] || layerY > bounds[1] || !java.util.Arrays.equals(areaKey, lastLayerBounds)) {
            layerY = bounds[0];
            layerPending = false;
            layerAttempts.clear();
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
        return layerAttempts.getOrDefault(pos.asLong(), 0) >= LAYER_MAX_ATTEMPTS || isObstructedForLayer(pos);
    }

    /** 本轮扫描是否遇到了还没载入完的投影区域（子类实现；分层模式下这样的层不算完成） */
    protected boolean consumeAreaNotReady() {
        return false;
    }

    private void onPassFinished() {
        dbgPasses++;
        onScanPassFinished();
        // 注意：曾尝试“投影区块没载入完的层不算完成”，但会让层长时间挂起再被看门狗强制跳层，反而打乱顺序，已撤回
        consumeAreaNotReady();
        if (layerY == null) return;
        if (layerPending && layerIdleTicks > LAYER_STALL_TICKS) {
            // 本层剩下的格子一直放不了（缺材料、无处可贴、需要先有别的方块……）：先打上面的层，到顶后会回来复查
            layerPending = false;
            forcedLayerSkips++;
            lastForcedSkipPos = lastPendingPos;
        }
        onLayerPassFinished(layerY, !layerPending);
        if (!layerPending) {
            layerIdleTicks = 0;
            List<PrinterBox> areaBoxes = getWorkAreaBoxes();
            int[] bounds = areaBoxes == null ? null : layerBounds(areaBoxes);
            if (bounds != null) {
                layerY = layerY + 1 > bounds[1] ? bounds[0] : layerY + 1;
                layerAttempts.clear();
            }
        }
        layerPending = false;
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
        return !isOnCooldown(pos) && canProcessPos(pos) && !isCorrectBlock(pos);
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
        layerY = null;
        layerAttempts.clear();
        layerPending = false;
        scanState = ScanState.RUNNING;
        waitingPos = null;
        currentCycleItem = null;
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