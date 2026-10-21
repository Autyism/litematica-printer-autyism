package com.autyism.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import lombok.Getter;
import lombok.Setter;
import com.autyism.printer.I18n;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.BlockMatchingType;
import com.autyism.printer.enums.HighlightType;
import com.autyism.printer.handler.Module;
import com.autyism.printer.interfaces.Implementation;
import com.autyism.printer.printer.*;
import com.autyism.printer.printer.action.Action;
import com.autyism.printer.printer.ActionManager;
import com.autyism.printer.printer.action.ClickAction;
import com.autyism.printer.printer.MissingMaterialTracker;
import com.autyism.printer.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class Print extends Module {
    public final static String NAME = "print";

    private final PlacementGuide guide;

    @Getter
    @Setter
    private boolean pistonNeedFix;

    @Getter
    @Setter
    private boolean printerMemorySync;

    private Action action;

    private SchematicBlockContext ctx;

    // canProcessPos 缓存
    private List<String> lastSkipConfig = Collections.emptyList();
    private Set<String> skipSet = Collections.emptySet();
    private Block lastSkipBlock = null;
    private boolean lastSkipResult = false;

    // 等待水产生队列
    @Getter @Setter
    private BlockPos watingForWaterPos;

    // 等待水生成的最大tick数：超过仍未出水则警告并关闭打印机
    private static final int MAX_WAIT_WATER_TICKS = 60;
    // 等待水ack/冰块放置ack的宽限tick数：期间即使无冰无水也不清除标记，避免重放冰破坏刚生成的水源
    private static final int WAIT_ACK_GRACE_TICKS = 10;
    private int watingForWaterTicks;

    // 物品切换等待：同一坐标最多等待的 tick 数
    private static final int MAX_SWITCH_WAIT_TICKS = 5;
    @Nullable
    private BlockPos switchWaitPos;
    private int switchWaitCount;
    private boolean placingIceForWater;

    public Print() {
        super(NAME, Configs.Print.ENABLED, Configs.Print.PRINT_SELECTION_TYPE, true);
        this.guide = new PlacementGuide(client);
        this.needSchematic = true;
        this.traced = TickTrace.ENABLED;
        BlockChangeHub.register(this::onClientBlockChanged);
    }

    public com.autyism.printer.handler.IteratorManager.SectionFilter getPrintSectionFilter() {
        return getSectionFilter();
    }

    /** 投影里整段都是空气、且不需要清除多余方块时，整段跳过（大型投影 99% 是空气） */
    @Override
    protected com.autyism.printer.handler.IteratorManager.SectionFilter getSectionFilter() {
        if (Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()) return null;
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return null;
        return (sx, sy, sz) -> {
            //? if <1.21.11 {
            /*var chunk = schematic.getChunkProvider().getChunkIfExists(sx, sz);
            *///?} else
            var chunk = schematic.getChunkSource().getChunkIfExists(sx, sz);
            if (chunk == null) {
                // 世界里这个区块已经加载、投影世界里却还没有：投影还在后台载入，这一层不能算“已完成”
                if (level != null && level.getChunkSource().hasChunk(sx, sz)) schematicNotReady = true;
                return true;
            }
            int index = chunk.getSectionIndexFromSectionY(sy);
            if (index < 0 || index >= chunk.getSectionsCount()) return true;
            return chunk.getSection(index).hasOnlyAir();
        };
    }

    /** 本轮扫描中遇到了“投影还没载入完”的区块 */
    private boolean schematicNotReady;

    @Override
    protected boolean consumeAreaNotReady() {
        boolean r = schematicNotReady;
        schematicNotReady = false;
        return r;
    }

    // ---------------- 分层统计（每层完成时提示） ----------------
    public record LayerStats(int layer, int total, int correct, int wrongState, int wrongBlock, int missing, int skipped) {
    }

    private int lsTotal, lsCorrect, lsWrongState, lsWrongBlock, lsMissing, lsSkipped;
    @Getter
    @Nullable
    private volatile LayerStats lastLayerStats;
    private final java.util.Set<Integer> announcedLayers = new java.util.HashSet<>();

    /** 已完成（并提示过）的层数 */
    @Getter
    private int layersCompleted;

    @Override
    protected void onLayerPassPosition(BlockPos pos) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return;
        BlockState required = schematic.getBlockState(pos);
        if (required.isAir()) return;
        lsTotal++;
        BlockState current = level.getBlockState(pos);
        switch (BlockMatchingType.get(required, current)) {
            case CORRECT -> lsCorrect++;
            case ERROR_BLOCK_STATE -> {
                // 投影里这一侧开着、保持不含水的含水方块算对
                if (WaterlogPlacer.acceptsDry(level, schematic, player, pos, required, current)) lsCorrect++;
                else lsWrongState++;
            }
            case ERROR_BLOCK -> lsWrongBlock++;
            default -> {
                if (isLayerSkipped(pos)) lsSkipped++;
                else lsMissing++;
            }
        }
    }

    /** 打印机这次开启以来最近几次“某层完成”的提示（不管有没有隐藏提示都记；调试 / 测试用） */
    public record LayerAnnouncement(LayerStats stats, boolean shown, int tick) {
    }

    private final java.util.Deque<LayerAnnouncement> announcements = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public java.util.List<LayerAnnouncement> getAnnouncements() {
        return new java.util.ArrayList<>(announcements);
    }

    @Override
    protected void onLayerPassFinished(int layer, boolean layerDone) {
        LayerStats stats = new LayerStats(layer, lsTotal, lsCorrect, lsWrongState, lsWrongBlock, lsMissing, lsSkipped);
        // 整层判定：提示和 HUD 上的分层统计都按整层（已加载的部分）算
        if (isWholeLayerRule()) {
            LayerStats whole = wholeLayerStats(layer, layerDone);
            if (whole != null) stats = whole;
        }
        lastLayerStats = stats;
        lsTotal = lsCorrect = lsWrongState = lsWrongBlock = lsMissing = lsSkipped = 0;
        if (!layerDone) {
            // 这一层又有要补的方块：完成后需要重新提示
            announcedLayers.remove(layer);
            return;
        }
        // 到顶后回到底层复查时，已提示过的层不重复提示
        if (stats.total() > 0 && announcedLayers.add(layer)) {
            layersCompleted++;
            boolean hide = Configs.Print.HIDE_LAYER_MESSAGES.getBooleanValue();
            announcements.addLast(new LayerAnnouncement(stats, !hide, player == null ? 0 : player.tickCount));
            while (announcements.size() > 64) announcements.pollFirst();
            if (hide) return;
            if (stats.correct() == stats.total()) {
                fi.dy.masa.malilib.util.InfoUtils.showInGameMessage(fi.dy.masa.malilib.gui.Message.MessageType.SUCCESS, 4000,
                        I18n.LAYER_DONE_ALL.getWithPrefixNameKey(), layer, stats.total());
            } else {
                fi.dy.masa.malilib.util.InfoUtils.showInGameMessage(fi.dy.masa.malilib.gui.Message.MessageType.WARNING, 6000,
                        I18n.LAYER_DONE_ISSUES.getWithPrefixNameKey(), layer, stats.correct(), stats.total(),
                        stats.wrongState(), stats.wrongBlock(), stats.skipped() + stats.missing());
            }
        }
    }

    @Override
    protected boolean isObstructedForLayer(BlockPos pos) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return false;
        BlockState required = schematic.getBlockState(pos);
        return !level.isUnobstructed(required, pos, net.minecraft.world.phys.shapes.CollisionContext.empty());
    }

    @Override
    protected boolean isLayeredMode() {
        return Configs.Print.LAYERED_MODE.getBooleanValue();
    }

    // ---------------- 整层判定（分层打印：已加载区块里的整层都打完才算这一层完成） ----------------
    /** 每 tick 最多用在整层统计上的时间 */
    private static final long LAYER_INDEX_BUDGET_NANOS = 1_000_000L;
    /** 在等整层判定（统计还没算完）时每 tick 最多用的时间（不超过打印机自己每 tick 时间限制的一半） */
    private static final long LAYER_WAIT_BUDGET_NANOS = 8_000_000L;

    /**
     * 整个投影（已加载的区块）按区块段的统计，进度条（GUI）和分层判定共用这一份。
     * 段里每一层都记着总数和对的数：一层每一格都对时，整层判定不用再逐格重算，
     * 所以开打印机、到顶回到底层复查时，打完的层一下就过去。
     * 打印机开关时不清空：方块变化一直在记（BlockChangeHub），区块重新加载会被发现，下次开机还能直接用。
     */
    private final ProgressIndex loadedIndex = new ProgressIndex(new ProgressIndex.Rules() {
        @Override
        public boolean counts(BlockPos pos) {
            return inPrintSelection(pos);
        }

        @Override
        public BlockMatchingType matching(BlockPos pos, BlockState required, BlockState current) {
            BlockMatchingType type = BlockMatchingType.get(required, current);
            // 投影里这一侧开着、保持不含水的含水方块算对（和范围内的判断一样）。
            // 用当前的世界和玩家：打印机关着时进度条也会算
            if (type == BlockMatchingType.ERROR_BLOCK_STATE) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level != null && WaterlogPlacer.acceptsDry(mc.level, SchematicWorldHandler.getSchematicWorld(), mc.player, pos, required, current)) {
                    return BlockMatchingType.CORRECT;
                }
            }
            return type;
        }

        @Override
        public boolean countsWholeSection(int sectionX, int sectionY, int sectionZ) {
            return countsEverywhere();
        }
    });

    /** 进度条和分层判定共用的统计 */
    public ProgressIndex loadedProgressIndex() {
        return loadedIndex;
    }

    /** 每一格都算（渲染层是“全部”，选区类型不按玩家的高度）：统计时不用逐格判断（和 inPrintSelection 一样） */
    private static boolean countsEverywhere() {
        if (fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().getLayerMode() != fi.dy.masa.malilib.util.LayerMode.ALL) return false;
        Object selection = Configs.Print.PRINT_SELECTION_TYPE.getOptionListValue();
        return selection != com.autyism.printer.enums.SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER
                && selection != com.autyism.printer.enums.SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER;
    }

    /** 让共用的统计走一 tick（进度条和打印机都会调用；同一 tick 里只有第一次去找区块，之后的调用只多算一会儿） */
    public void tickLoadedIndex(long budgetNanos) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null || mc.level == null || mc.player == null) return;
        loadedIndex.tick(mc.level, schematic, LitematicaUtils.getSchematicWorkBoxes(), selectionSignature(), mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4,
                mc.options.getEffectiveRenderDistance() + 1, budgetNanos, mc.player.tickCount);
    }

    /** 等整层判定时每 tick 用的时间：打印机自己每 tick 时间限制的一半，在 1~8 毫秒之间 */
    private static long waitBudget() {
        int limitMs = Configs.Core.ITERATION_TIME_LIMIT.getIntegerValue();
        long nanos = limitMs > 0 ? limitMs * 1_000_000L / 2 : LAYER_WAIT_BUDGET_NANOS;
        return Math.max(LAYER_INDEX_BUDGET_NANOS, Math.min(LAYER_WAIT_BUDGET_NANOS, nanos));
    }

    /** 整层判定还没有结论（统计在算、或者自己的动作在等服务端确认）：主循环不歇 10 tick，下一 tick 再看，这期间统计多算一会儿 */
    private boolean layerVerdictWaiting;

    /** 范围内都打完了、这一层还剩够不着的格子时，最近一次挡住这一层的那一格（HUD 提示玩家走过去）；其他时候为 null */
    @Nullable
    private volatile BlockPos waitingFarPos;

    @Nullable
    public BlockPos getWaitingFarPos() {
        BlockPos far = waitingFarPos;
        Integer layer = getCurrentLayer();
        return far != null && layer != null && far.getY() == layer && isWholeLayerRule() ? far : null;
    }

    @Override
    protected boolean layerVerdictPending() {
        return layerVerdictWaiting;
    }

    /**
     * 当前层的增量统计（只重算有变化的区块列，见 LayerIndex）。
     * 判断用的规则：打印机在范围内看过的格子用它当时的结论，没看过的只看方块本身，都没有副作用。
     */
    private final LayerIndex layerIndex = new LayerIndex(new LayerIndex.Rules() {
        @Override
        public boolean counts(BlockPos pos) {
            return inPrintSelection(pos);
        }

        @Override
        public BlockMatchingType matching(BlockPos pos, BlockState required, BlockState current) {
            return layerMatching(pos, required, current);
        }

        @Override
        public boolean adjustable(BlockState required, BlockState current) {
            return AdjustFirst.canAdjustInPlace(required, current);
        }

        @Override
        public boolean skipped(BlockPos pos) {
            return isLayerSkipped(pos);
        }

        @Override
        public boolean blocking(BlockPos pos, BlockState required, BlockState current, BlockMatchingType type) {
            return blocksLayer(pos, required, current, type);
        }
    });
    /** HUD 上的整层统计（最多每 10 tick 重新数一次） */
    @Nullable
    private LayerStats wholeLayerStats;
    private int wholeLayerStatsTick = Integer.MIN_VALUE;

    @Override
    protected boolean isWholeLayerRule() {
        return Configs.Print.LAYER_DONE_SCOPE.getOptionListValue() == com.autyism.printer.enums.LayerDoneScope.WHOLE_LAYER;
    }

    /** 打印机会处理的格子：Litematica 的渲染层，以及“玩家下方 / 上方”的选区类型（和迭代框的裁剪一样） */
    public boolean inPrintSelection(BlockPos pos) {
        if (!LitematicaUtils.isPositionWithinRange(pos)) return false;
        Object selection = Configs.Print.PRINT_SELECTION_TYPE.getOptionListValue();
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null && selection == com.autyism.printer.enums.SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER) {
            return pos.getY() <= (int) Math.floor(player.getY());
        }
        if (player != null && selection == com.autyism.printer.enums.SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER) {
            return pos.getY() >= (int) Math.ceil(player.getY());
        }
        return true;
    }

    /**
     * 会改变“哪些格子算、算不算对、能不能在原地调整”的设置：增量统计在这些变了的时候从头算。
     * 渲染层、选区类型（玩家下方 / 上方时还有玩家的高度）、可替换方块、含水方块、音符盒调音、堆肥、催熟。
     */
    public Object selectionSignature() {
        Object selection = Configs.Print.PRINT_SELECTION_TYPE.getOptionListValue();
        var player = net.minecraft.client.Minecraft.getInstance().player;
        boolean byHeight = selection == com.autyism.printer.enums.SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER
                || selection == com.autyism.printer.enums.SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER;
        return java.util.Arrays.asList(LitematicaUtils.renderLayerKey(), selection, byHeight && player != null ? player.getBlockY() : 0,
                Configs.Print.PRINT_REPLACE.getBooleanValue(), Configs.Print.REPLACEABLE_LIST.getStrings(),
                Configs.Print.PRINT_WATERLOGGED.getBooleanValue(), Configs.Print.NOTE_BLOCK_TUNING.getBooleanValue(),
                Configs.Print.FILL_COMPOSTER.getBooleanValue(), Configs.Print.BONEMEAL_CROPS.getBooleanValue());
    }

    /** 客户端世界里的方块变了（BlockChangeHub） */
    public void onClientBlockChanged(BlockPos pos) {
        layerIndex.blockChanged(pos);
        // 打印机关着也记：下次开机时统计还是对的
        loadedIndex.blockChanged(pos);
        noteClientBlockChange(pos);
    }

    @Override
    protected boolean throttleWhenIdle() {
        return true;
    }

    @Override
    protected void preprocess() {
        // 整层判定：增量更新当前层的统计（只重算有变化的区块列）
        Integer layer = getCurrentLayer();
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (layer == null || !isWholeLayerRule() || schematic == null || level == null || player == null) {
            layerIndex.clear();
            wholeLayerStats = null;
            layerVerdictWaiting = false;
            return;
        }
        // 共用的统计这里只走一般的 1 毫秒；等整层判定时要的那一排在 restOfLayerDone 里优先多算（processRow）
        tickLoadedIndex(LAYER_INDEX_BUDGET_NANOS);
        long budget = layerVerdictWaiting ? waitBudget() : LAYER_INDEX_BUDGET_NANOS;
        long[] counts = loadedIndex.layerCounts(layer);
        // 这一层（已加载的部分）每一格都对：不用逐格的整层统计（restOfLayerDone 直接用按区块段的统计）
        if (counts != null && counts[0] == counts[1]) return;
        layerIndex.tick(level, schematic, layer, LitematicaUtils.getSchematicWorkBoxes(), selectionSignature(), player.getBlockX() >> 4, player.getBlockZ() >> 4,
                mc.options.getEffectiveRenderDistance() + 1, budget);
    }

    @Override
    protected boolean restOfLayerDone(int layer) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null || level == null) return true;
        layerVerdictWaiting = false;
        long budget = waitBudget();
        long[] counts = loadedIndex.layerCounts(layer);
        if (counts == null) {
            // 这一层所在的那一排区块段还没算完：先算这一排（开机时从最底层往上，要哪一排就先算哪一排）
            loadedIndex.processRow(layer >> 4, budget);
            counts = loadedIndex.layerCounts(layer);
        }
        boolean done;
        if (counts != null && counts[0] == counts[1]) {
            // 整层（已加载的部分）每一格都对（和打印机判断“打好了”的规则一样）
            done = true;
        } else if (counts == null) {
            // 统计还没算好：先不算完成
            layerVerdictWaiting = true;
            return false;
        } else {
            if (layerIndex.layer() != layer && player != null) {
                // 逐格的整层统计当场换到这一层。以前等下一 tick 的 preprocess 才换：有放不了的方块（跳过的侦测器、TNT、水……）的层，
                // 每过一层都要多等一 tick，几十层的机器开一次打印机光是往上走就要好几秒
                layerIndex.tick(level, schematic, layer, LitematicaUtils.getSchematicWorkBoxes(), selectionSignature(), player.getBlockX() >> 4,
                        player.getBlockZ() >> 4, mc.options.getEffectiveRenderDistance() + 1, budget);
            }
            // 有没打好的格子：逐格判断（打印机在范围内看过的用它的结论，范围外的看能不能放、能不能原地调对）
            done = layerIndex.done(schematic, level, budget);
            if (!done && !layerIndex.ready()) {
                layerVerdictWaiting = true;
                return false;
            }
        }
        // 挡住这一层的是够不着的格子（不在扫描范围里）：HUD 上提示玩家走过去（打印机在等，不是卡住了）
        BlockPos blocking = done ? null : layerIndex.lastBlocking();
        waitingFarPos = blocking != null && layerIndex.layer() == layer && !coveredByScan(blocking) ? blocking.immutable() : null;
        // 自己放的方块还没被服务端确认：客户端看到的可能只是预测（被撤回的话这一层还没完），确认了才往上
        if (done && ActionConfirm.anyPending(level, player == null ? 0 : player.tickCount)) {
            layerVerdictWaiting = true;
            return false;
        }
        return done;
    }

    /** 整层统计（HUD 和完成提示用）；fresh = 当场重新数（完成提示），否则最多每 10 tick 数一次 */
    @Nullable
    private LayerStats wholeLayerStats(int layer, boolean fresh) {
        long[] counts = loadedIndex.layerCounts(layer);
        // 每一格都对：按区块段的统计就是整层统计（这时逐格的整层统计不一定在这一层上）
        if (counts != null && counts[0] == counts[1]) return new LayerStats(layer, (int) counts[0], (int) counts[0], 0, 0, 0, 0);
        if (layerIndex.layer() != layer) return null;
        int now = player == null ? 0 : player.tickCount;
        if (fresh || wholeLayerStats == null || wholeLayerStats.layer() != layer || now - wholeLayerStatsTick >= 10 || now < wholeLayerStatsTick) {
            int[] s = layerIndex.stats();
            if (s == null) return wholeLayerStats != null && wholeLayerStats.layer() == layer ? wholeLayerStats : null;
            wholeLayerStats = new LayerStats(layer, s[0], s[1], s[2], s[3], s[4], s[5]);
            wholeLayerStatsTick = now;
        }
        return wholeLayerStats;
    }

    private BlockMatchingType layerMatching(BlockPos pos, BlockState required, BlockState current) {
        BlockMatchingType type = BlockMatchingType.get(required, current);
        // 投影里这一侧开着、保持不含水的含水方块算对（和范围内的统计一样）
        if (type == BlockMatchingType.ERROR_BLOCK_STATE
                && WaterlogPlacer.acceptsDry(level, SchematicWorldHandler.getSchematicWorld(), player, pos, required, current)) {
            return BlockMatchingType.CORRECT;
        }
        return type;
    }

    /**
     * 这一格（没打好、也没被放弃）会不会让这一层还不算完成。
     * 打印机在范围内看过这一格的，用它当时的结论（例如门在等旁边的方块、缺材料以外的原因放不了，就不算）；
     * 没看过的（范围外）只看方块本身：缺的方块能放、或者状态能在原地点对，就还没完成。不会有任何副作用（不发包、不排挖掘）。
     */
    private boolean blocksLayer(BlockPos pos, BlockState required, BlockState current, BlockMatchingType type) {
        Boolean verdict = layerVerdict(pos);
        if (verdict != null && (!verdict || coveredByScan(pos))) return verdict;
        return switch (type) {
            case MISSING_BLOCK -> looksPlaceable(pos, required);
            case ERROR_BLOCK_STATE, ERROR_BLOCK -> AdjustFirst.canAdjustInPlace(required, current);
            default -> false;
        };
    }

    /** 跳过放置名单里的方块（按名单内容缓存） */
    private final java.util.Map<Block, Boolean> skipListCache = new java.util.HashMap<>();
    private List<String> skipListCacheKey = Collections.emptyList();
    /** 缺的材料（每 tick 最多取一次） */
    private final java.util.Set<Item> missingItemsCache = new java.util.HashSet<>();
    private int missingItemsTick = Integer.MIN_VALUE;

    private boolean skipListed(BlockState required) {
        if (!Configs.Print.PRINT_SKIP.getBooleanValue()) return false;
        List<String> list = Configs.Print.PRINT_SKIP_LIST.getStrings();
        if (!list.equals(skipListCacheKey)) {
            skipListCache.clear();
            skipListCacheKey = new ArrayList<>(list);
        }
        return skipListCache.computeIfAbsent(required.getBlock(), b -> {
            for (String s : skipListCacheKey) {
                if (PinYinSearchUtils.matchName(s, required)) return true;
            }
            return false;
        });
    }

    private boolean materialMissing(Item item) {
        int now = player == null ? 0 : player.tickCount;
        if (now != missingItemsTick) {
            missingItemsTick = now;
            missingItemsCache.clear();
            for (MissingMaterialTracker.Entry e : MissingMaterialTracker.getInstance().getMissing()) missingItemsCache.add(e.item);
        }
        return missingItemsCache.contains(item);
    }

    /** 范围外缺的方块打印机走近以后能不能放（只看方块本身；和 PlacementGuide 里直接放弃的情况一致） */
    private boolean looksPlaceable(BlockPos pos, BlockState required) {
        Block block = required.getBlock();
        if (skipListed(required)) return false;
        if (Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue() && (BlockUtils.needsWater(required) || BlockUtils.isLiveCoral(required))) return false;
        if (block instanceof LiquidBlock) return Configs.Print.PRINT_FLUIDS_WITH_BUCKET.getBooleanValue() && required.getFluidState().isSource();
        if (block instanceof BubbleColumnBlock || block instanceof WaterlilyBlock || block instanceof BaseFireBlock || block instanceof NetherPortalBlock
                || block instanceof net.minecraft.world.level.block.piston.PistonHeadBlock || block instanceof net.minecraft.world.level.block.piston.MovingPistonBlock) {
            return false;
        }
        // 两格高的方块上半部分、床头：跟着另一半一起出现
        if ((block instanceof DoublePlantBlock || block instanceof DoorBlock)
                && required.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
            return false;
        }
        if (block instanceof BedBlock && required.getValue(BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD) return false;
        if (!required.canSurvive(level, pos)) return false;
        // 打印机已经发现缺这种材料：走过去也放不了，不让它卡住这一层（缺材料的提示里会列出来）
        Item item = block.asItem();
        // 自己没有物品的方块：只有打印机拿别的物品放的那几种能放（末地传送门、活塞头……放不了，和范围内的判断一致）
        if (item == net.minecraft.world.item.Items.AIR) return PlacementGuide.placedWithOtherItem(block);
        return !materialMissing(item);
    }

    @Override
    protected int getTickInterval() {
        return Configs.Placement.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxExecutions() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    public boolean canProcessPos(BlockPos blockPos) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return false;

        BlockState required = schematic.getBlockState(blockPos);
        BlockState current = level.getBlockState(blockPos);

        // 如果原理图为空气且不破坏多余方块，则无法处理
        if (required.isAir()) {
            if (!Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()) return false;
        }

        this.ctx = new SchematicBlockContext(client, level, schematic, blockPos, current, required);

        if (Configs.Print.PRINT_SKIP.getBooleanValue()) {
            List<String> currentConfig = Configs.Print.PRINT_SKIP_LIST.getStrings();
            if (!currentConfig.equals(lastSkipConfig)) {
                skipSet = new HashSet<>(currentConfig);
                lastSkipConfig = currentConfig;
                lastSkipBlock = null;
            }

            Block block = ctx.requiredState.getBlock();
            if (block != lastSkipBlock) {
                lastSkipBlock = block;
                lastSkipResult = false;
                for (String s : skipSet) {
                    if (PinYinSearchUtils.matchName(s, ctx.requiredState)) {
                        lastSkipResult = true;
                        break;
                    }
                }
            }
            if (lastSkipResult) return false;
        }

        // 打印含水方块：除了含水以外都对了的方块单独处理（不走下面“状态不对”的逻辑，不会被拆掉重放）
        WaterlogPlacer.Verdict waterlog = WaterlogPlacer.verdict(level, schematic, player, blockPos, required, current);
        if (waterlog != WaterlogPlacer.Verdict.NONE) {
            this.action = null;
            this.fluidPlan = waterlog == WaterlogPlacer.Verdict.READY && !WaterlogPlacer.actionPending(level, blockPos, player.tickCount)
                    ? WaterlogPlacer.plan(level, player, blockPos, current) : null;
            return this.fluidPlan != null;
        }

        // 用桶打印水源 / 岩浆源 / 装满的炼药锅
        this.fluidPlan = FluidPlacer.plan(level, schematic, player, blockPos, required, current);
        if (fluidPlan != null) {
            this.action = null;
            return true;
        }

        Action action = guide.getAction(ctx);
        if (action == null) {
            // “侦测器安全放置”判定现在不能放的侦测器/活塞：按规则跳过，分层模式下不让它卡住这一层
            if (Configs.Print.SAFELY_OBSERVER.getBooleanValue()
                    && (required.getBlock() instanceof net.minecraft.world.level.block.ObserverBlock
                    || required.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock)) {
                deferToUpperLayer(blockPos);
            }
            return false;
        }
        // 缺的方块没有能拿来放的物品（末地传送门、活塞头、移动中的活塞……）：打印机放不了。
        // 以前空着手对这一格点 6 次（每次隔 3 tick）才算这一层完成，每到这一层都要白等
        if (!hasPlacementItem(action, required) && BlockMatchingType.get(required, current) == BlockMatchingType.MISSING_BLOCK) {
            deferToUpperLayer(blockPos);
            return false;
        }
        // 有侦测器正盯着这一格（先于依附它的方块放下的；开着影响检测时任何一个）：这一格放上方块、或者点一下改状态，它都会亮一下。
        // 动手之前再确认一次没影响（侦测器放下以后，周围可能又放了别的东西）；有影响就先不放
        if (observerPulseWouldHurt(blockPos)) {
            deferToUpperLayer(blockPos);
            return false;
        }
        // 要用的材料已经缺了很久（见 longShortage），现在还是哪里都没有：先不算要做的事。以前每 3 tick 空试一次，
        // 机器别的地方都打完了，打印机还是从底层到顶层一圈一圈地转、一直占着 CPU。材料一有就照常放
        if (stillShort(action, required)) {
            deferToUpperLayer(blockPos);
            return false;
        }
        this.action = action;
        return true;
    }

    /** 因为“侦测器先于依附它的方块”先放下的侦测器（它面前那一格还没放） */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet earlyObservers = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** 见 {@link #earlyObservers}（PlacementGuide 决定先放侦测器时调用） */
    public void noteEarlyObserver(BlockPos pos) {
        if (earlyObservers.size() > 4096) earlyObservers.clear();
        earlyObservers.add(pos.asLong());
    }

    /**
     * 盯着 target 的侦测器现在亮一下会动到周围的红石。先放下的侦测器（见 earlyObservers）总是查；开着侦测器安全放置和影响检测时，
     * 世界里任何盯着它的侦测器都查（玩家自己放的、以前放下的）
     */
    private boolean observerPulseWouldHurt(BlockPos target) {
        boolean all = Configs.Print.SAFELY_OBSERVER.getBooleanValue() && Configs.Print.OBSERVER_IMPACT_CHECK.getBooleanValue();
        if (!all && earlyObservers.isEmpty()) return false;
        for (Direction d : Direction.values()) {
            BlockPos o = target.relative(d);
            BlockState s = level.getBlockState(o);
            if (!(s.getBlock() instanceof net.minecraft.world.level.block.ObserverBlock) || s.getValue(net.minecraft.world.level.block.ObserverBlock.FACING) != d.getOpposite()) {
                // 记下的侦测器还没出现（刚发出去、服务端还没放下）：先留着，那时这一格也还没有支撑；那一格成了别的方块才删掉
                if (!s.canBeReplaced()) earlyObservers.remove(o.asLong());
                continue;
            }
            if (!all && !earlyObservers.contains(o.asLong())) continue;
            String impact = ObserverImpact.pulseImpact(level, o, d.getOpposite());
            if (impact != null) {
                if (Boolean.getBoolean("ale.debugobserver")) System.out.println("[printer-observer] hold " + target.toShortString() + " in front of " + o.toShortString() + ": " + impact);
                return true;
            }
        }
        return false;
    }

    /** 这个动作要的材料都已经缺了很久，而且现在背包、潜影盒里还是没有（有了就从缺料记录里删掉） */
    private boolean stillShort(Action action, BlockState required) {
        if (shortages.isEmpty() || player == null || PlayerUtils.getAbilities(player).instabuild) return false;
        Item[] items = action.getRequiredItems(required.getBlock());
        if (items == null || items.length == 0) return false;
        int now = player.tickCount;
        for (Item item : items) {
            if (item == null || item == net.minecraft.world.item.Items.AIR) return false;
            Integer since = shortages.get(item);
            if (since == null || now < since || now - since < SHORTAGE_GRACE_TICKS) return false;
        }
        var inventory = player.getInventory();
        for (Item item : items) {
            boolean have = false;
            for (int i = 0; i < inventory.getContainerSize() && !have; i++) have = inventory.getItem(i).is(item);
            if (!have && Configs.Print.USE_QUICK_SHULKER.getBooleanValue()) have = QuickShulkerUtils.findShulkerWithItem(player, item) != -1;
            if (have) {
                for (Item i : items) shortages.remove(i);
                return false;
            }
        }
        return true;
    }

    /** 这个动作手上要拿的物品里有真的物品（不是空手） */
    private static boolean hasPlacementItem(Action action, BlockState required) {
        Item[] items = action.getRequiredItems(required.getBlock());
        if (items == null) return false;
        for (Item item : items) {
            if (item != null && item != net.minecraft.world.item.Items.AIR) return true;
        }
        return false;
    }

    @Nullable
    private FluidPlacer.Plan fluidPlan;

    private void executeFluid(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        FluidPlacer.Plan plan = fluidPlan;
        Item[] items = {plan.bucket()};
        InventoryUtils.ItemSwitchResult result = InventoryUtils.switchToItemsResult(player, items);
        if (result == InventoryUtils.ItemSwitchResult.WAITING) {
            if (blockPos.equals(switchWaitPos)) {
                switchWaitCount++;
            } else {
                switchWaitPos = blockPos.immutable();
                switchWaitCount = 1;
            }
            if (switchWaitCount <= MAX_SWITCH_WAIT_TICKS) {
                enterWaiting(blockPos);
                skipIteration.set(true);
                return;
            }
            switchWaitPos = null;
            switchWaitCount = 0;
        }
        if (result != InventoryUtils.ItemSwitchResult.READY) {
            setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
            if (plan.kind() == FluidPlacer.Kind.WATERLOG) {
                // 缺的是水桶本身，HUD 上显示“水桶”而不是方块名
                MissingMaterialTracker.getInstance().recordMissing(this, plan.bucket(), null, ConfigUtils.getPlaceCooldown());
            } else {
                recordMissingMaterial(items);
            }
            addHighlight(blockPos, HighlightType.FAILED);
            return;
        }
        if (FluidPlacer.execute(player, plan)) {
            if (plan.kind() == FluidPlacer.Kind.WATERLOG && level instanceof com.autyism.printer.utils.PacketUtils.SequenceExtension seq) {
                // 等服务端确认（确认之前不再碰这一格；确认后还是没含水就重试）
                ActionConfirm.sent(level, blockPos, plan.hit().getDirection(), seq.litematica_printer3$currentSequence(), player.tickCount, plan.bucket());
            }
            notePlacementAttempt(blockPos);
            InventoryUtils.markRecentlyUsed(plan.bucket());
            addHighlight(blockPos, HighlightType.PLACE);
        }
        // 流体靠视角定位：每 tick 只放一格，并给足时间等服务端确认
        setCooldown(blockPos, Math.max(ConfigUtils.getPlaceCooldown(), 5));
        skipIteration.set(true);
    }

    @Override
    protected boolean isAwaitingServer(BlockPos pos) {
        if (ownActionUnconfirmed(pos)) return true;
        // 含水方块：会影响水流的邻格还在等服务端确认时也算没完成（确认之后才能判断水会不会流出去）
        return WaterlogPlacer.enabled() && WaterlogPlacer.isCandidate(LitematicaUtils.getBlockState(pos), level.getBlockState(pos))
                && WaterlogPlacer.actionPending(level, pos, player == null ? 0 : player.tickCount);
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        BlockState required = LitematicaUtils.getBlockState(pos);
        BlockState current = level.getBlockState(pos);
        BlockMatchingType type = BlockMatchingType.get(required, current);
        if (type == BlockMatchingType.ERROR_BLOCK_STATE && WaterlogPlacer.acceptsDry(level, SchematicWorldHandler.getSchematicWorld(), player, pos, required, current)) {
            return true;
        }
        // 刚倒了水、服务端还没确认：客户端看到的含水只是预测，还不算完成
        if (type == BlockMatchingType.CORRECT && WaterlogPlacer.enabled() && WaterlogPlacer.wantsWater(required) && ownActionUnconfirmed(pos)) {
            return false;
        }
        return type == BlockMatchingType.CORRECT;
    }

    @Override
    @Nullable
    protected Item[] getRequiredItems(BlockPos pos) {
        // canProcessPos 已设置 this.action 和 this.ctx
        if (this.fluidPlan != null && this.fluidPlan.target().equals(pos)) {
            return new Item[]{this.fluidPlan.bucket()};
        }
        if (this.action != null && this.ctx != null) {
            return this.action.getRequiredItems(this.ctx.requiredState.getBlock());
        }
        return null;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (!TickTrace.ENABLED) {
            executeIterationNow(blockPos, skipIteration);
            return;
        }
        long start = System.nanoTime();
        try {
            executeIterationNow(blockPos, skipIteration);
        } finally {
            TickTrace.execNanos += System.nanoTime() - start;
        }
    }

    private void executeIterationNow(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (DEBUG_ADJUST && player != null && level != null) {
            System.out.println("[printer-exec] t" + player.tickCount + " path=" + execPath + " pos=" + blockPos.toShortString()
                    + " adjustPos=" + (adjustPos == null ? null : adjustPos.toShortString()) + " have=" + level.getBlockState(blockPos));
        }
        placingIceForWater = false;
        lastOutcome = Outcome.NOT_POSSIBLE;
        // 范围内又有事做了：不再提示“走过去”
        waitingFarPos = null;
        if (fluidPlan != null && fluidPlan.target().equals(blockPos)) {
            executeFluid(blockPos, skipIteration);
            return;
        }
        if (Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                && BlockUtils.needsWater(ctx.requiredState)) {
            boolean isWaitingHere = watingForWaterPos != null && watingForWaterPos.equals(blockPos);
            boolean isIce = ctx.currentState.getBlock() instanceof IceBlock;
            boolean matchesWaterRequest = BlockUtils.isWaterSource(ctx.currentState) || BlockUtils.isWaterlogged(ctx.currentState);
            // 等待标记过期：连续 WAIT_ACK_GRACE_TICKS 个等待tick内无冰无水且无待挖掘任务（如水被玩家/活塞移除）
            // 才清除标记。宽限期覆盖冰块放置/破坏后的 ack 往返，避免重放冰破坏刚生成的水源。
            if (isWaitingHere && !isIce && !matchesWaterRequest && !BreakUtils.INSTANCE.inQueue(blockPos)
                    && watingForWaterTicks >= WAIT_ACK_GRACE_TICKS) {
                watingForWaterPos = null;
                watingForWaterTicks = 0;
                isWaitingHere = false;
            }
            switch (IceForWaterFlow.decide(
                    true, isWaitingHere, isIce, matchesWaterRequest, watingForWaterTicks, MAX_WAIT_WATER_TICKS)) {
                case PLACE_BLOCK -> {
                    if (isWaitingHere) {
                        watingForWaterPos = null;
                        watingForWaterTicks = 0;
                    }
                    // 水已生成：走下方正常放置流程，放置含水方块（buildAction 已返回对应 Action）
                }
                case KEEP_WAITING -> {
                    if (isIce) {
                        ensureIceBreakQueued(blockPos);
                    }
                    watingForWaterTicks++;
                    enterWaiting(blockPos);
                    skipIteration.set(true);
                    lastOutcome = Outcome.WAITING;
                    return;
                }
                case WAIT_TIMEOUT -> {
                    // 等待 MAX_WAIT_WATER_TICKS tick 仍无水生成：警告并关闭打印机
                    watingForWaterPos = null;
                    watingForWaterTicks = 0;
                    MessageUtils.setOverlayMessage(I18n.ICE_WATER_TIMEOUT.getName());
                    Configs.Core.WORK_SWITCH.setBooleanValue(false);
                    return;
                }
                case BREAK_ICE_AND_WAIT -> {
                    ensureIceBreakQueued(blockPos);
                    watingForWaterPos = blockPos.immutable();
                    watingForWaterTicks = 0;
                    enterWaiting(blockPos);
                    skipIteration.set(true);
                    lastOutcome = Outcome.WAITING;
                    return;
                }
                case PLACE_ICE -> {
                    placingIceForWater = true; // 走下方正常放置流程放冰
                }
                case SKIP -> {
                }
            }
        }
        // 下落检查
        if (Configs.Placement.FALLING_CHECK.getBooleanValue()
                && ctx.requiredState.getBlock() instanceof FallingBlock) {
            BlockPos downPos = blockPos.below();

            BlockState downWorld = level.getBlockState(downPos);
            BlockState downSchematic = ctx.schematic.getBlockState(downPos);
            if (FallingBlock.isFree(downWorld)) {
                MessageUtils.setOverlayMessage(
                        I18n.BLOCK_NO_SUPPORT.getName(ctx.getRequiredBlockName().getString()));
                addHighlight(blockPos, HighlightType.FAILED);
                // 计入尝试次数并冷却：分层模式下不会因为这一格永远卡在本层
                setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
                notePlacementAttempt(blockPos);
                return;
            } else if (!downSchematic.isAir() && downWorld != downSchematic) {
                // 只有投影里下面本来就有方块、且世界里还不是那个方块时才等待；
                // 投影下面是空气（例如投影最底层的沙子/铁砧放在地面上）时，世界里有支撑就可以直接放
                MessageUtils.setOverlayMessage(
                        I18n.BLOCK_MISMATCH.getName(ctx.getRequiredBlockName().getString()));
                addHighlight(blockPos, HighlightType.FAILED);
                setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
                notePlacementAttempt(blockPos);
                return;
            }
        }
        Item[] reqItems = action.getRequiredItems(ctx.requiredState.getBlock());
        // 检查是否有待交换的物品
        if (RemoteContainerUtils.hasPendingExchange()) {
            enterWaiting(blockPos);
            skipIteration.set(true);
            lastOutcome = Outcome.WAITING;
            return;
        }
        Direction side = action.getValidSide(level, blockPos);
        if (side == null) {
            addHighlight(blockPos, HighlightType.FAILED);
            // 没有能贴的面（墙上的火把、告示牌、头颅、梯子……要贴的那一格还没放）。那一格这一层马上会放：等它；
            // 在上面几层、或者下面那层已经放弃了、投影里根本没有：现在放不了，不让它卡住这一层。
            // 以前不算尝试、也不放行，每到这一层都要等看门狗（约 4 秒）
            if (!supportComesThisLayer(blockPos)) deferToUpperLayer(blockPos);
            return;
        }
        // 需求 8：放置投影里的潜影盒时，手上潜影盒的内容物必须与投影一致（空盒对空盒，装着东西的必须一模一样）
        boolean placingShulker = ctx.requiredState.getBlock() instanceof ShulkerBoxBlock;
        if (placingShulker) {
            java.util.List<net.minecraft.world.item.ItemStack> wanted = ShulkerContentUtils.requiredContents(ctx.schematic, blockPos);
            InventoryUtils.setStackFilter(
                    s -> !ShulkerContentUtils.isShulkerItem(s) || ShulkerContentUtils.sameContents(ShulkerContentUtils.itemContents(s), wanted),
                    ShulkerContentUtils.withContents(new net.minecraft.world.item.ItemStack(ctx.requiredState.getBlock().asItem()), wanted));
        }
        InventoryUtils.ItemSwitchResult switchResult;
        try {
            switchResult = InventoryUtils.switchToItemsResult(player, reqItems);
        } finally {
            InventoryUtils.clearStackFilter();
        }
        if (placingShulker && switchResult == InventoryUtils.ItemSwitchResult.UNAVAILABLE) {
            MessageUtils.setOverlayMessage(I18n.SHULKER_CONTENT_MISMATCH.getName());
        }
        if (switchResult != InventoryUtils.ItemSwitchResult.READY) {
            if (switchResult == InventoryUtils.ItemSwitchResult.WAITING) {
                // 物品切换已发出：保留该坐标，下一 tick 确认主手后再放置
                if (blockPos.equals(switchWaitPos)) {
                    switchWaitCount++;
                } else {
                    switchWaitPos = blockPos.immutable();
                    switchWaitCount = 1;
                }
                // 交换迟迟不生效（被服务端拒绝等）时放弃该坐标，避免卡死在同一个方块上
                if (switchWaitCount <= MAX_SWITCH_WAIT_TICKS || QuickShulkerUtils.isBusy()) {
                    enterWaiting(blockPos);
                    skipIteration.set(true);
                    lastOutcome = Outcome.WAITING;
                    return;
                }
                switchWaitPos = null;
                switchWaitCount = 0;
                setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
                addHighlight(blockPos, HighlightType.FAILED);
                lastOutcome = Outcome.SWITCH_TIMEOUT;
                return;
            }
            setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
            recordMissingMaterial(reqItems);
            if (reqItems != null && reqItems.length > 0 && reqItems[0] != null
                    && !QuickShulkerUtils.isOpenHandler()
                    && Configs.Print.USE_REMOTE_CONTAINER.getBooleanValue()) {
                RemoteContainerUtils.tryGetItemFromContainers(reqItems[0]);
            }
            addHighlight(blockPos, HighlightType.FAILED);
            // 背包和潜影盒里都没有这种材料（缺材料的提示里会列出来）。刚缺的照旧在这一层等一会儿（等看门狗，约 4 秒：
            // 玩家可能马上就补上，先往上打会乱了顺序）；已经缺了这么久还没补上的（例如熄灭营火的铲子、填堆肥桶的海带），
            // 以后到别的层、到顶回来复查时就不再每层白等，和范围外缺材料的格子一样（见 looksPlaceable）。材料有了照常放
            boolean later = mayGetLater(reqItems);
            boolean longMissing = !later && longShortage(reqItems);
            if (longMissing) deferToUpperLayer(blockPos);
            if (TickTrace.ENABLED && Boolean.getBoolean("ale.debugmissing")) {
                System.out.println("[printer-missing] t" + player.tickCount + " " + java.util.Arrays.toString(reqItems) + " at " + blockPos.toShortString()
                        + " result=" + switchResult + " later=" + later + " long=" + longMissing + " shortages=" + shortages);
            }
            return;
        }
        if (reqItems != null) {
            for (Item item : reqItems) shortages.remove(item);
        }
        boolean useShift;
        if (placingShulker) {
            // 潜影盒总是潜行放置：装了 Advanced Shulkerboxes 时不潜行会打开潜影盒界面而不是放下
            useShift = true;
        } else if (action.getShift() == null) {
            useShift =
                    (Implementation.isInteractive(
                                            level.getBlockState(blockPos.relative(side)).getBlock())
                                    && !(action instanceof ClickAction))
                            || Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
        } else {
            useShift = action.getShift();
        }
        // 放下去用的物品（拿来判断这一格之后还要不要接着调整，例如先放没去皮的原木再去皮）
        Item usedItem = player.getMainHandItem().getItem();
        action.queueAction(blockPos, side, useShift, player);
        lastOutcome = Outcome.QUEUED;
        notePlacementAttempt(blockPos);
        if (reqItems != null && reqItems.length > 0 && reqItems[0] != null) InventoryUtils.markRecentlyUsed(reqItems[0]);
        // 放冰完成：入队挖掘并进入等待水生成
        if (placingIceForWater) {
            placingIceForWater = false;
            ActionManager.INSTANCE.setLook(action.getPlayerLook());
            ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
            ActionManager.INSTANCE.sendQueue(player);
            ensureIceBreakQueued(blockPos);
            watingForWaterPos = blockPos.immutable();
            watingForWaterTicks = 0;
            enterWaiting(blockPos);
            skipIteration.set(true);
            return;
        }
        // 轻松放置协议只用在“放新方块”上：在已有方块上再放一次（台阶合成双层、蜡烛 / 雪层再加一个）或点一下调整时，
        // 原版要按真实的点击位置判断，协议编码过的点击位置会让它失败（协议模式下双层台阶曾经一直合不上）
        boolean placingNewBlock = ctx.currentState.canBeReplaced() && ctx.currentState.getBlock() != ctx.requiredState.getBlock()
                && !(action instanceof ClickAction);
        Vec3 hitModifier = placingNewBlock ? LitematicaUtils.usePrecisionPlacement(blockPos, ctx.requiredState) : null;
        // 服务器上还没确认协议能用、已经有一块在试放：这一块照常转头放（见 ServerProtocol.mayUseNow）
        if (hitModifier != null && !ServerProtocol.mayUseNow()) hitModifier = null;
        // 铁轨交给 ALE 按投影形状放（服务端也有 ALE 时），不用协议
        if (placingNewBlock && ctx.requiredState.getBlock() instanceof BaseRailBlock && com.autyism.printer.compat.AleRails.available()) {
            hitModifier = null;
            ActionManager.INSTANCE.exactRail = ctx.requiredState;
        }
        if (hitModifier != null) {
            ActionManager.INSTANCE.hitModifier = hitModifier;
            ActionManager.INSTANCE.useProtocol = true;
            ActionManager.INSTANCE.protocolWanted = ctx.requiredState;
        }
        ActionManager.INSTANCE.setLook(action.getPlayerLook());
        ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
        boolean needWait = ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook;
        // 原来用协议放完一个方块就结束本 tick 的扫描：协议不需要转头、每个放置包各自带着状态，没有理由限速，
        // 和普通模式一样按“每 tick 放几个”的设置来（默认 1）
        if (needWait) {
            skipIteration.set(true);
        }
        setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
        if (reqItems != null)
            addHighlight(blockPos, HighlightType.PLACE);
        else
            addHighlight(blockPos, HighlightType.ADJUST);
        // 先调整完再放下一个：这一格接下来还要在原地点几下 / 再放几次（V3 协议已经直接按投影的状态放了的不用：
        // 单人游戏一定会；服务器上的 Servux 也会，确认过一次以后就不再每放一块都停下来等确认，见 ServerProtocol.setsState）
        boolean protocolSetsState = hitModifier != null && ServerProtocol.setsState(ctx.requiredState);
        considerAdjustFocus(blockPos, usedItem, protocolSetsState, hitModifier != null);
        if (needsSecondPlacement(ctx.requiredState, ctx.currentState)) {
            if (followUps.size() >= 256) followUps.removeFirstLong();
            followUps.add(blockPos.asLong());
        }
    }

    // ---------------- 同一格要再放一次的：第一次一被确认就接着放 ----------------
    /**
     * 双层台阶的第二块、藤蔓 / 发光地衣多贴的一面。以前要等下一轮扫描回到这一格才补（看起来就是“先放完别的再回来”），
     * 现在第一块被服务端确认后的那个 tick 就补上。等确认的时候不停下来（这些方块不影响机器怎么动，大建筑里又很多），照常放别的方块。
     */
    private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet followUps = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    private static final int MAX_FOLLOW_UPS_PER_TICK = 4;

    private static boolean needsSecondPlacement(BlockState required, BlockState current) {
        Block b = required.getBlock();
        if (b instanceof SlabBlock) {
            return required.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
                    && !(current.getBlock() == b && current.getValue(SlabBlock.TYPE) == SlabType.DOUBLE);
        }
        return (b instanceof MultifaceBlock || b instanceof VineBlock) && current != required;
    }

    @Override
    protected int revisitFollowUps(AtomicReference<Boolean> skipIteration) {
        if (followUps.isEmpty() || level == null || player == null) return 0;
        int done = 0;
        var it = followUps.iterator();
        while (it.hasNext() && done < MAX_FOLLOW_UPS_PER_TICK) {
            BlockPos pos = BlockPos.of(it.nextLong());
            if (isAwaitingServer(pos)) continue; // 第一块还没被确认：客户端看到的不一定是结果
            it.remove();
            if (!LitematicaUtils.isSchematicBlock(pos) || !LitematicaUtils.isPositionWithinRange(pos) || !PlayerUtils.canInteracted(pos)
                    || isCorrectBlock(pos) || !canProcessPos(pos) || fluidPlan != null) continue;
            execPath = "follow";
            executeIteration(pos, skipIteration);
            if (lastOutcome == Outcome.QUEUED) done++;
            if (skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook || adjustFocusActive()) break;
        }
        return done;
    }

    // ---------------- 先调整完再放下一个 ----------------
    /** executeIteration 最后的结果 */
    private enum Outcome {
        /** 动作发出去了 */
        QUEUED,
        /** 在等换物品 / 容器交换，下一 tick 接着做这一格 */
        WAITING,
        /** 换物品一直没生效，这一格冷却后再试 */
        SWITCH_TIMEOUT,
        /** 现在做不了（缺材料、没有能点的面……） */
        NOT_POSSIBLE
    }

    private Outcome lastOutcome = Outcome.NOT_POSSIBLE;
    /** 正在调整的格子：调整完之前不放别的方块 */
    @Nullable
    private BlockPos adjustPos;
    /** 上一个动作发出之前这一格的状态（服务端回应后对比，看这个动作有没有起作用） */
    @Nullable
    private BlockState adjustStateBefore;
    private boolean adjustActionSent;
    /** 音符盒音高 / 中继器延迟：这一轮还要接着点几下（每 tick 一下，点完再等回应核对） */
    private int adjustClicksLeft;
    /** 连续几个动作被服务端确认了、这一格却没变 */
    private int adjustNoEffect;
    private int adjustLastProgressTick;
    /** 放弃过的格子和当时的状态：状态没变之前不再停下来等它（按旧的顺序处理） */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> adjustGaveUp = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final int ADJUST_MAX_NO_EFFECT = 3;
    private static final boolean DEBUG_ADJUST = Boolean.getBoolean("ale.debugadjust");
    private static final int ADJUST_MAX_IDLE_TICKS = 200;

    /** 调试 / 测试用：正在调整的格子 */
    @Nullable
    public BlockPos getAdjustPos() {
        return adjustPos;
    }

    /**
     * 正在调整的这一格是用协议刚放下的、要调的属性协议能直接定（见 AdjustFirst.protocolProperty），而且还没点过：
     * 服务端确认后不用点就对了，说明服务器按协议放好了这个属性（ServerProtocol.stateConfirmed）
     */
    private boolean adjustCheckProtocol;

    /** 刚对 pos 发出了动作：如果这一格还要在原地接着调整，就停在这一格，调整完再放下一个方块 */
    private void considerAdjustFocus(BlockPos pos, Item usedItem, boolean protocolSetsState, boolean viaProtocol) {
        if (!Configs.Print.ADJUST_FIRST.getBooleanValue() || ctx == null || action == null) return;
        BlockMatchingType matching = BlockMatchingType.get(ctx.requiredState, ctx.currentState);
        boolean adjusting = switch (matching) {
            // 方块对了、状态不对：点一下调整（在同一格再放一块的双层台阶、多贴一面的藤蔓照旧的顺序）
            case ERROR_BLOCK_STATE -> action instanceof ClickAction;
            // 方块不对但能在原地转换（泥土锄成耕地、空花盆放花、原木去皮）
            case ERROR_BLOCK -> action instanceof ClickAction;
            // 刚放下：放好以后还要调整的方块（中继器延迟、音符盒音高、双层台阶、几根蜡烛……）
            case MISSING_BLOCK -> AdjustFirst.expectsFollowUp(ctx.requiredState, usedItem, protocolSetsState);
            default -> false;
        };
        if (!adjusting) return;
        long key = pos.asLong();
        BlockState gaveUpAt = adjustGaveUp.get(key);
        if (gaveUpAt != null) {
            if (gaveUpAt == ctx.currentState) return;
            adjustGaveUp.remove(key);
        }
        if (!pos.equals(adjustPos)) {
            adjustPos = pos.immutable();
            adjustNoEffect = 0;
            adjustLastProgressTick = player.tickCount;
            adjustCheckProtocol = viaProtocol && matching == BlockMatchingType.MISSING_BLOCK && AdjustFirst.protocolProperty(ctx.requiredState) != null;
        } else if (matching != BlockMatchingType.MISSING_BLOCK) {
            // 在原地点过了：之后对了不能说明是协议放好的
            adjustCheckProtocol = false;
        }
        adjustStateBefore = ctx.currentState;
        adjustActionSent = true;
    }

    @Override
    protected boolean adjustFocusActive() {
        return adjustPos != null;
    }

    /**
     * 正在调整一个方块时，每 tick 先处理它：上一个动作服务端还没回应就等（不放别的方块）；
     * 回应了、还没调好就接着点；调好了、或者现在没法在原地调整了就交回正常的扫描顺序。
     */
    @Override
    protected boolean holdForAdjust(AtomicReference<Boolean> skipIteration) {
        BlockPos pos = adjustPos;
        if (pos == null) return false;
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (!Configs.Print.ADJUST_FIRST.getBooleanValue() || schematic == null || level == null || player == null) {
            clearAdjust();
            return false;
        }
        // 不在投影里、不在选中的渲染层、够不着了：交回正常顺序
        if (!LitematicaUtils.isSchematicBlock(pos) || !LitematicaUtils.isPositionWithinRange(pos) || !PlayerUtils.canInteracted(pos)) {
            clearAdjust();
            return false;
        }
        int now = player.tickCount;
        if (DEBUG_ADJUST && now - adjustLastProgressTick > 0 && (now - adjustLastProgressTick) % 40 == 0) {
            System.out.println("[printer-adjust] held " + (now - adjustLastProgressTick) + " ticks at " + pos.toShortString()
                    + " want=" + SchematicWorldHandler.getSchematicWorld().getBlockState(pos) + " have=" + level.getBlockState(pos)
                    + " awaiting=" + isAwaitingServer(pos) + " cooldown=" + isOnCooldown(pos) + " outcome=" + lastOutcome
                    + " action=" + (action == null ? null : action.getClass().getSimpleName()) + " noEffect=" + adjustNoEffect);
        }
        // 音符盒音高、中继器延迟这一轮算好的次数还没点完：每 tick 接着点一下，不等每一下的回应
        if (adjustClicksLeft > 0) {
            boolean canClick;
            PlacementGuide.pipelinedClick = true;
            try {
                canClick = canProcessPos(pos) && fluidPlan == null && action instanceof ClickAction;
            } finally {
                PlacementGuide.pipelinedClick = false;
            }
            if (TickTrace.ENABLED) TickTrace.detail("pipelined " + pos.toShortString() + " left=" + adjustClicksLeft);
            // 客户端已经看到对了（中继器会预测结果）、或者现在点不了：先等回应，回来以后照常核对
            if (!canClick) {
                if (DEBUG_ADJUST) System.out.println("[printer-adjust] t" + now + " pipeline stops at " + pos.toShortString() + " left=" + adjustClicksLeft
                        + " action=" + (action == null ? null : action.getClass().getSimpleName()) + " fluidPlan=" + fluidPlan
                        + " have=" + level.getBlockState(pos) + " want=" + schematic.getBlockState(pos));
                adjustClicksLeft = 0;
                return true;
            }
            execPath = "adjust";
            executeIteration(pos, skipIteration);
            adjustClicksLeft = lastOutcome == Outcome.QUEUED ? adjustClicksLeft - 1 : 0;
            if (DEBUG_ADJUST) System.out.println("[printer-adjust] t" + now + " pipelined click at " + pos.toShortString() + " outcome=" + lastOutcome + " left=" + adjustClicksLeft);
            return true;
        }
        // 上一个动作服务端还没回应：客户端看到的不一定是结果，等着
        if (isAwaitingServer(pos)) {
            if (TickTrace.ENABLED) TickTrace.detail("await-ack " + pos.toShortString() + " " + schematic.getBlockState(pos).getBlock()
                    + " held=" + (now - adjustLastProgressTick));
            return true;
        }
        BlockState current = level.getBlockState(pos);
        if (adjustActionSent) {
            adjustActionSent = false;
            if (current == adjustStateBefore) {
                adjustNoEffect++;
            } else {
                adjustNoEffect = 0;
                adjustLastProgressTick = now;
            }
        }
        if (isCorrectBlock(pos)) {
            if (adjustCheckProtocol) ServerProtocol.stateConfirmed(schematic.getBlockState(pos));
            clearAdjust();
            return false;
        }
        // 服务端一直不接受（点了没反应）：放弃这一格，按旧的顺序以后再处理
        if (adjustNoEffect >= ADJUST_MAX_NO_EFFECT || now - adjustLastProgressTick > ADJUST_MAX_IDLE_TICKS || now < adjustLastProgressTick) {
            if (adjustGaveUp.size() > 4096) adjustGaveUp.clear();
            adjustGaveUp.put(pos.asLong(), current);
            clearAdjust();
            return false;
        }
        // 换物品一直没生效：等这一格的冷却过了再试
        if (lastOutcome == Outcome.SWITCH_TIMEOUT && isOnCooldown(pos)) {
            if (TickTrace.ENABLED) TickTrace.detail("switch-timeout " + pos.toShortString());
            return true;
        }
        BlockMatchingType type = BlockMatchingType.get(schematic.getBlockState(pos), current);
        // 方块不见了（服务端没让放）或者成了别的方块：交回正常顺序
        if (type != BlockMatchingType.ERROR_BLOCK_STATE && type != BlockMatchingType.ERROR_BLOCK) {
            clearAdjust();
            return false;
        }
        // 没有能在原地点的了（例如只剩红石运行时的属性不同）、要用桶倒水（含水方块有自己的规则）、要在同一格再放一块：交回正常顺序
        if (!canProcessPos(pos) || fluidPlan != null || !(action instanceof ClickAction)) {
            if (DEBUG_ADJUST) {
                boolean can = canProcessPos(pos);
                System.out.println("[printer-adjust] t" + now + " cannot click " + pos.toShortString() + " canProcess=" + can + " action="
                        + (action == null ? null : action.getClass().getSimpleName()) + " fluidPlan=" + fluidPlan + " pending=" + ownActionUnconfirmed(pos)
                        + " cooldown=" + isOnCooldown(pos) + " have=" + current + " want=" + schematic.getBlockState(pos)
                        + " ctxLevel=" + (ctx == null ? null : System.identityHashCode(ctx.level)) + " level=" + System.identityHashCode(level)
                        + " mcLevel=" + System.identityHashCode(net.minecraft.client.Minecraft.getInstance().level));
            }
            clearAdjust();
            return false;
        }
        // 音符盒音高、中继器延迟：每点一下只进一档，按服务端确认过的状态就能算出一共要点几下。
        // 不用每一下都等回应（以前一个音符盒要等十几个来回）：每 tick 点一下，点够了再等回应、核对，没对上就按新的状态重新算
        int clicks = AdjustFirst.cyclicClicks(schematic.getBlockState(pos), current);
        execPath = "adjust";
        executeIteration(pos, skipIteration);
        if (TickTrace.ENABLED) TickTrace.detail("click " + pos.toShortString() + " " + current.getBlock() + " outcome=" + lastOutcome);
        if (lastOutcome == Outcome.QUEUED && clicks > 1) adjustClicksLeft = clicks - 1;
        if (DEBUG_ADJUST) {
            System.out.println("[printer-adjust] t" + now + " click at " + pos.toShortString() + " clicks=" + clicks + " outcome=" + lastOutcome
                    + " left=" + adjustClicksLeft + " have=" + current);
        }
        if (lastOutcome == Outcome.QUEUED || lastOutcome == Outcome.WAITING || lastOutcome == Outcome.SWITCH_TIMEOUT) return true;
        // 缺材料、没有能点的面……：先接着放别的，这一格以后照旧处理
        clearAdjust();
        return false;
    }

    private void clearAdjust() {
        if (DEBUG_ADJUST && adjustPos != null) {
            StackTraceElement caller = new Throwable().getStackTrace()[1];
            System.out.println("[printer-adjust] t" + (player == null ? -1 : player.tickCount) + " release " + adjustPos.toShortString()
                    + " from line " + caller.getLineNumber() + " have=" + (level == null ? null : level.getBlockState(adjustPos)));
        }
        adjustClicksLeft = 0;
        adjustPos = null;
        adjustStateBefore = null;
        adjustActionSent = false;
        adjustNoEffect = 0;
        adjustCheckProtocol = false;
    }

    @Override
    protected String debugLayerWait() {
        Integer layer = getCurrentLayer();
        long[] counts = layer == null ? null : loadedIndex.layerCounts(layer);
        BlockPos blocking = layerIndex.lastBlocking();
        return "verdictWaiting=" + layerVerdictWaiting + " counts=" + (counts == null ? null : counts[1] + "/" + counts[0])
                + " index=" + (layerIndex.layer() == (layer == null ? Integer.MIN_VALUE : layer)) + " ready=" + layerIndex.ready()
                + " pendingAcks=" + (level != null && ActionConfirm.anyPending(level, player == null ? 0 : player.tickCount))
                + (blocking == null ? "" : " blocking=" + describePos(blocking) + " verdict=" + layerVerdict(blocking) + " scanned=" + coveredByScan(blocking));
    }

    @Override
    protected String debugExtra() {
        return " adjust=" + (adjustPos == null ? null : adjustPos.toShortString()) + " noEffect=" + adjustNoEffect
                + " layerIndex=" + layerIndex;
    }

    @Override
    public void resetScanState() {
        super.resetScanState();
        announcedLayers.clear();
        announcements.clear();
        watingForWaterPos = null;
        watingForWaterTicks = 0;
        placingIceForWater = false;
        switchWaitPos = null;
        switchWaitCount = 0;
        clearAdjust();
        adjustGaveUp.clear();
        layerIndex.clear();
        wholeLayerStats = null;
        layerVerdictWaiting = false;
        waitingFarPos = null;
        earlyObservers.clear();
        // 按区块段的统计（loadedIndex）不清空：方块变化一直在记，下次开机直接用
    }

    private void ensureIceBreakQueued(BlockPos pos) {
        if (!BreakUtils.INSTANCE.inQueue(pos) && !BreakUtils.INSTANCE.isBreaking(pos)) {
            BreakUtils.INSTANCE.add(pos);
        }
    }

    /**
     * 这一格要贴的面（action 的候选面）上，有投影里要放、世界里还没放、而且就在这一层的方块：打印机这一层会把它放上，等它。
     * 分层没开时总是等（和以前一样）。
     */
    private boolean supportComesThisLayer(BlockPos pos) {
        Integer layer = getCurrentLayer();
        if (layer == null || action == null || ctx == null) return true;
        for (Direction d : action.getSides().keySet()) {
            BlockPos n = pos.relative(d);
            if (n.getY() != layer) continue;
            BlockState want = ctx.schematic.getBlockState(n);
            if (want.isAir() || !level.getBlockState(n).canBeReplaced()) continue;
            if (!isLayerSkipped(n)) return true;
        }
        return false;
    }

    /** 缺着的材料：物品 → 第一次发现缺的 tick（换层、关开打印机都不清；手上 / 背包里又有了就删掉） */
    private final java.util.Map<Item, Integer> shortages = new java.util.HashMap<>();
    /** 缺了这么久还没补上，就不再每层等它（和 Module 的看门狗一样长） */
    private static final int SHORTAGE_GRACE_TICKS = 80;

    /** 这几种可用的物品都已经缺了至少 SHORTAGE_GRACE_TICKS（刚发现缺的从现在开始算） */
    private boolean longShortage(@Nullable Item[] items) {
        if (items == null || player == null) return false;
        int now = player.tickCount;
        boolean any = false;
        boolean allLong = true;
        // 每一种都要记上（不能记了第一种就返回，否则几种替代物品要一种一种地记，等的时间翻好几倍）
        for (Item item : items) {
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            any = true;
            Integer since = shortages.get(item);
            if (since == null || now < since) {
                shortages.put(item, now);
                allLong = false;
            } else if (now - since < SHORTAGE_GRACE_TICKS) {
                allLong = false;
            }
        }
        return any && allLong;
    }

    /** 现在拿不到，但打印机过一会儿自己能拿到：背包里的潜影盒里有（取物冷却中），或者开着从附近容器取物 */
    private boolean mayGetLater(@Nullable Item[] items) {
        if (items == null || player == null) return false;
        if (Configs.Print.USE_REMOTE_CONTAINER.getBooleanValue()) return true;
        if (!Configs.Print.USE_QUICK_SHULKER.getBooleanValue()) return false;
        for (Item item : items) {
            if (item != null && item != net.minecraft.world.item.Items.AIR && QuickShulkerUtils.findShulkerWithItem(player, item) != -1) return true;
        }
        return false;
    }

    /**
     * 这一格要用的物品不在快捷栏里（要从背包换过来，或者从潜影盒取）：这一轮先放快捷栏里有的，
     * 这一格留到这一轮最后再换（见 Module 的 swapDeferredPos）。以前按扫描顺序碰到就换，
     * 一层里十几种方块交错时几乎每放几块就要换一次，每次都要等服务端确认。canProcessPos(pos) 刚算好了 action。
     */
    @Override
    protected boolean deferForSwap(BlockPos pos) {
        if (player == null || PlayerUtils.getAbilities(player).instabuild || ctx == null) return false;
        Item[] items;
        if (fluidPlan != null && fluidPlan.target().equals(pos)) items = new Item[]{fluidPlan.bucket()};
        else if (action != null) items = action.getRequiredItems(ctx.requiredState.getBlock());
        else return false;
        if (items == null || items.length == 0) return false;
        var inventory = player.getInventory();
        for (Item item : items) {
            if (item == null || item == net.minecraft.world.item.Items.AIR) return false;
            for (int i = 0; i < 9; i++) {
                if (inventory.getItem(i).is(item)) return false;
            }
        }
        // 背包里有、或者背包里的潜影盒里有：要换。哪里都没有的照常处理（缺材料，见 executeIteration）
        for (Item item : items) {
            for (int i = 9; i < 36; i++) {
                if (inventory.getItem(i).is(item)) return true;
            }
            if (Configs.Print.USE_QUICK_SHULKER.getBooleanValue() && QuickShulkerUtils.findShulkerWithItem(player, item) != -1) return true;
        }
        return false;
    }

    private void recordMissingMaterial(Item[] reqItems) {
        if (reqItems != null && reqItems.length > 0 && reqItems[0] != null) {
            MissingMaterialTracker.getInstance()
                    .recordMissing(this, reqItems[0], ctx.getRequiredBlockName(), ConfigUtils.getPlaceCooldown());
        }
    }
}