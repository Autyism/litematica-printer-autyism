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

    /** 最近几次“某层完成”的提示（不管有没有隐藏提示都记；调试 / 测试用） */
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
        LayerScanResult whole = layerScanResult;
        if (isWholeLayerRule() && whole != null && whole.layer == layer) stats = whole.stats();
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
    /**
     * 一次整层扫描的结果：整层的统计，以及当时还没完成的格子（之后只需要复查这些格子）。
     * 格子的分类和范围内的分层统计一样：对 / 状态错 / 方块错 / 缺失 / 跳过。
     */
    private static final class LayerScanResult {
        static final byte CORRECT = 0, WRONG_STATE = 1, WRONG_BLOCK = 2, MISSING = 3, SKIPPED = 4;
        final int layer;
        final int startTick;
        int endTick;
        final int[] counts = new int[5];
        it.unimi.dsi.fastutil.longs.LongArrayList blocking = new it.unimi.dsi.fastutil.longs.LongArrayList();
        it.unimi.dsi.fastutil.bytes.ByteArrayList blockingKind = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
        /** 没完成的格子太多、没记全：只知道“还没完成” */
        boolean overflow;

        LayerScanResult(int layer, int startTick) {
            this.layer = layer;
            this.startTick = startTick;
        }

        int total() {
            return counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
        }

        LayerStats stats() {
            return new LayerStats(layer, total(), counts[CORRECT], counts[WRONG_STATE], counts[WRONG_BLOCK], counts[MISSING], counts[SKIPPED]);
        }

        @Override
        public String toString() {
            return "y=" + layer + " total=" + total() + " correct=" + counts[CORRECT] + " blocking=" + blocking.size() + (overflow ? "+" : "")
                    + " age=" + startTick + ".." + endTick;
        }
    }

    /** 没完成的格子最多记这么多（超过了就只知道“还没完成”，等少了再重新扫） */
    private static final int LAYER_SCAN_MAX_BLOCKING = 16384;
    /** 后台每隔这么多 tick 重新扫一遍当前层（新加载的区块、HUD 上的统计） */
    private static final int LAYER_SCAN_REFRESH_TICKS = 20;
    /** 每 tick 最多用在整层扫描上的时间 */
    private static final long LAYER_SCAN_BUDGET_NANOS = 1_500_000L;

    @Nullable
    private LoadedAreaScan layerScan;
    @Nullable
    private LayerScanResult layerScanBuilding;
    @Nullable
    private volatile LayerScanResult layerScanResult;
    /** 这次扫描用的：跳过放置名单里的方块、缺材料的物品（扫描开始时取一次） */
    private final java.util.Map<Block, Boolean> scanSkipCache = new java.util.HashMap<>();
    private final java.util.Set<Item> scanMissingItems = new java.util.HashSet<>();

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

    @Override
    protected void preprocess() {
        // 整层判定：后台定期扫一遍当前层（判断这一层有没有整层打完，HUD 上的分层统计也用它）
        Integer layer = getCurrentLayer();
        if (layer == null || !isWholeLayerRule()) {
            layerScan = null;
            layerScanBuilding = null;
            layerScanResult = null;
            return;
        }
        advanceLayerScan(layer);
    }

    @Override
    protected boolean restOfLayerDone(int layer) {
        if (SchematicWorldHandler.getSchematicWorld() == null || level == null) return true;
        // 没有扫描结果就当场开始扫（小的层当场就扫完）；扫完之前不算完成
        advanceLayerScan(layer);
        LayerScanResult result = layerScanResult;
        if (result == null || result.layer != layer || result.overflow) return false;
        recheckLayerResult(result);
        return result.blocking.isEmpty();
    }

    private void advanceLayerScan(int layer) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null || level == null || player == null) return;
        int now = player.tickCount;
        LayerScanResult result = layerScanResult;
        if (result != null && result.layer != layer) layerScanResult = result = null;
        if (layerScan != null && (layerScanBuilding == null || layerScanBuilding.layer != layer)) {
            layerScan = null;
            layerScanBuilding = null;
        }
        if (layerScan == null && (result == null || now - result.endTick >= LAYER_SCAN_REFRESH_TICKS || now < result.endTick)) {
            layerScan = new LoadedAreaScan(level, schematic, LitematicaUtils.getSchematicWorkBoxes(), layer);
            layerScanBuilding = new LayerScanResult(layer, now);
            scanSkipCache.clear();
            scanMissingItems.clear();
            for (MissingMaterialTracker.Entry e : MissingMaterialTracker.getInstance().getMissing()) scanMissingItems.add(e.item);
        }
        if (layerScan != null && layerScan.run(System.nanoTime() + LAYER_SCAN_BUDGET_NANOS, this::visitLayerPosition)) {
            layerScanBuilding.endTick = now;
            layerScanResult = layerScanBuilding;
            layerScan = null;
            layerScanBuilding = null;
        }
    }

    private void visitLayerPosition(BlockPos pos, BlockState required, BlockState current) {
        if (!inPrintSelection(pos)) return;
        LayerScanResult result = layerScanBuilding;
        BlockMatchingType type = layerMatching(pos, required, current);
        byte kind = layerKind(type, pos);
        result.counts[kind]++;
        if (kind == LayerScanResult.CORRECT || kind == LayerScanResult.SKIPPED || isLayerSkipped(pos)) return;
        if (!blocksLayer(pos, required, current, type)) return;
        if (result.blocking.size() >= LAYER_SCAN_MAX_BLOCKING) {
            result.overflow = true;
            return;
        }
        result.blocking.add(pos.asLong());
        result.blockingKind.add(kind);
    }

    /** 复查上次扫描时还没完成的格子：完成了的从列表里去掉，统计跟着改 */
    private void recheckLayerResult(LayerScanResult result) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null || result.blocking.isEmpty()) return;
        var keep = new it.unimi.dsi.fastutil.longs.LongArrayList();
        var keepKind = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
        for (int i = 0; i < result.blocking.size(); i++) {
            BlockPos pos = BlockPos.of(result.blocking.getLong(i));
            BlockState required = schematic.getBlockState(pos);
            BlockState current = level.getBlockState(pos);
            BlockMatchingType type = layerMatching(pos, required, current);
            byte kind = required.isAir() ? LayerScanResult.CORRECT : layerKind(type, pos);
            result.counts[result.blockingKind.getByte(i)]--;
            result.counts[kind]++;
            if (kind == LayerScanResult.CORRECT || kind == LayerScanResult.SKIPPED || isLayerSkipped(pos)) continue;
            if (!blocksLayer(pos, required, current, type)) continue;
            keep.add(pos.asLong());
            keepKind.add(kind);
        }
        result.blocking = keep;
        result.blockingKind = keepKind;
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

    private byte layerKind(BlockMatchingType type, BlockPos pos) {
        return switch (type) {
            case CORRECT -> LayerScanResult.CORRECT;
            case ERROR_BLOCK_STATE -> LayerScanResult.WRONG_STATE;
            case ERROR_BLOCK -> LayerScanResult.WRONG_BLOCK;
            default -> isLayerSkipped(pos) ? LayerScanResult.SKIPPED : LayerScanResult.MISSING;
        };
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

    /** 范围外缺的方块打印机走近以后能不能放（只看方块本身；和 PlacementGuide 里直接放弃的情况一致） */
    private boolean looksPlaceable(BlockPos pos, BlockState required) {
        Block block = required.getBlock();
        if (Configs.Print.PRINT_SKIP.getBooleanValue() && scanSkipCache.computeIfAbsent(block, b -> {
            for (String s : Configs.Print.PRINT_SKIP_LIST.getStrings()) {
                if (PinYinSearchUtils.matchName(s, required)) return true;
            }
            return false;
        })) return false;
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
        return item == net.minecraft.world.item.Items.AIR || !scanMissingItems.contains(item);
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
        this.action = action;
        return true;
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
        placingIceForWater = false;
        lastOutcome = Outcome.NOT_POSSIBLE;
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
            return;
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
        if (hitModifier != null) {
            ActionManager.INSTANCE.hitModifier = hitModifier;
            ActionManager.INSTANCE.useProtocol = true;
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
        // 先调整完再放下一个：这一格接下来还要在原地点几下 / 再放几次（单人游戏里的 V3 协议已经直接按投影的状态放了）
        boolean protocolSetsState = hitModifier != null && mc.hasSingleplayerServer()
                && fi.dy.masa.litematica.util.PlacementHandler.getEffectiveProtocolVersion() == fi.dy.masa.litematica.util.EasyPlaceProtocol.V3;
        considerAdjustFocus(blockPos, usedItem, protocolSetsState);
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
    /** 连续几个动作被服务端确认了、这一格却没变 */
    private int adjustNoEffect;
    private int adjustLastProgressTick;
    /** 放弃过的格子和当时的状态：状态没变之前不再停下来等它（按旧的顺序处理） */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> adjustGaveUp = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final int ADJUST_MAX_NO_EFFECT = 3;
    private static final int ADJUST_MAX_IDLE_TICKS = 200;

    /** 调试 / 测试用：正在调整的格子 */
    @Nullable
    public BlockPos getAdjustPos() {
        return adjustPos;
    }

    /** 刚对 pos 发出了动作：如果这一格还要在原地接着调整，就停在这一格，调整完再放下一个方块 */
    private void considerAdjustFocus(BlockPos pos, Item usedItem, boolean protocolSetsState) {
        if (!Configs.Print.ADJUST_FIRST.getBooleanValue() || ctx == null || action == null) return;
        boolean adjusting = switch (BlockMatchingType.get(ctx.requiredState, ctx.currentState)) {
            // 方块对了、状态不对：这个动作就是在调整（点一下、再放一个）
            case ERROR_BLOCK_STATE -> true;
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
        // 上一个动作服务端还没回应：客户端看到的不一定是结果，等着
        if (isAwaitingServer(pos)) return true;
        int now = player.tickCount;
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
        if (lastOutcome == Outcome.SWITCH_TIMEOUT && isOnCooldown(pos)) return true;
        BlockMatchingType type = BlockMatchingType.get(schematic.getBlockState(pos), current);
        // 方块不见了（服务端没让放）或者成了别的方块：交回正常顺序
        if (type != BlockMatchingType.ERROR_BLOCK_STATE && type != BlockMatchingType.ERROR_BLOCK) {
            clearAdjust();
            return false;
        }
        // 没有能在原地做的了（例如只剩红石运行时的属性不同）、要用桶倒水（含水方块有自己的规则）、要拆掉重放：交回正常顺序
        if (!canProcessPos(pos) || fluidPlan != null || (type == BlockMatchingType.ERROR_BLOCK && !(action instanceof ClickAction))) {
            clearAdjust();
            return false;
        }
        executeIteration(pos, skipIteration);
        if (lastOutcome == Outcome.QUEUED || lastOutcome == Outcome.WAITING || lastOutcome == Outcome.SWITCH_TIMEOUT) return true;
        // 缺材料、没有能点的面……：先接着放别的，这一格以后照旧处理
        clearAdjust();
        return false;
    }

    private void clearAdjust() {
        adjustPos = null;
        adjustStateBefore = null;
        adjustActionSent = false;
        adjustNoEffect = 0;
    }

    @Override
    protected String debugExtra() {
        return " adjust=" + (adjustPos == null ? null : adjustPos.toShortString()) + " noEffect=" + adjustNoEffect
                + " layerScan=" + layerScanResult + (layerScan != null ? " (scanning)" : "");
    }

    @Override
    public void resetScanState() {
        super.resetScanState();
        announcedLayers.clear();
        watingForWaterPos = null;
        watingForWaterTicks = 0;
        placingIceForWater = false;
        switchWaitPos = null;
        switchWaitCount = 0;
        clearAdjust();
        adjustGaveUp.clear();
        layerScan = null;
        layerScanBuilding = null;
        layerScanResult = null;
    }

    private void ensureIceBreakQueued(BlockPos pos) {
        if (!BreakUtils.INSTANCE.inQueue(pos) && !BreakUtils.INSTANCE.isBreaking(pos)) {
            BreakUtils.INSTANCE.add(pos);
        }
    }

    private void recordMissingMaterial(Item[] reqItems) {
        if (reqItems != null && reqItems.length > 0 && reqItems[0] != null) {
            MissingMaterialTracker.getInstance()
                    .recordMissing(this, reqItems[0], ctx.getRequiredBlockName(), ConfigUtils.getPlaceCooldown());
        }
    }
}