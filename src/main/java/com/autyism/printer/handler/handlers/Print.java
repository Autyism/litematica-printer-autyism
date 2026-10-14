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
            return;
        }
        net.minecraft.world.level.ChunkPos center = player.chunkPosition();
        layerIndex.tick(level, schematic, layer, LitematicaUtils.getSchematicWorkBoxes(), selectionSignature(), center.x, center.z,
                mc.options.getEffectiveRenderDistance() + 1, LAYER_INDEX_BUDGET_NANOS);
    }

    @Override
    protected boolean restOfLayerDone(int layer) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null || level == null) return true;
        if (layerIndex.layer() != layer) return false;
        // 还没算完的部分当场算一会儿（小的层当场就算完）；算不完就当作还没完成
        return layerIndex.done(schematic, level, LAYER_INDEX_BUDGET_NANOS);
    }

    /** 整层统计（HUD 和完成提示用）；fresh = 当场重新数（完成提示），否则最多每 10 tick 数一次 */
    @Nullable
    private LayerStats wholeLayerStats(int layer, boolean fresh) {
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
        return item == net.minecraft.world.item.Items.AIR || !materialMissing(item);
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
        // 先调整完再放下一个：这一格接下来还要在原地点几下 / 再放几次（单人游戏里的 V3 协议已经直接按投影的状态放了）
        boolean protocolSetsState = hitModifier != null && mc.hasSingleplayerServer()
                && fi.dy.masa.litematica.util.PlacementHandler.getEffectiveProtocolVersion() == fi.dy.masa.litematica.util.EasyPlaceProtocol.V3;
        considerAdjustFocus(blockPos, usedItem, protocolSetsState);
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

    /** 刚对 pos 发出了动作：如果这一格还要在原地接着调整，就停在这一格，调整完再放下一个方块 */
    private void considerAdjustFocus(BlockPos pos, Item usedItem, boolean protocolSetsState) {
        if (!Configs.Print.ADJUST_FIRST.getBooleanValue() || ctx == null || action == null) return;
        boolean adjusting = switch (BlockMatchingType.get(ctx.requiredState, ctx.currentState)) {
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
            // 客户端已经看到对了（中继器会预测结果）、或者现在点不了：先等回应，回来以后照常核对
            if (!canClick) {
                if (DEBUG_ADJUST) System.out.println("[printer-adjust] t" + now + " pipeline stops at " + pos.toShortString() + " left=" + adjustClicksLeft);
                adjustClicksLeft = 0;
                return true;
            }
            executeIteration(pos, skipIteration);
            adjustClicksLeft = lastOutcome == Outcome.QUEUED ? adjustClicksLeft - 1 : 0;
            if (DEBUG_ADJUST) System.out.println("[printer-adjust] t" + now + " pipelined click at " + pos.toShortString() + " outcome=" + lastOutcome + " left=" + adjustClicksLeft);
            return true;
        }
        // 上一个动作服务端还没回应：客户端看到的不一定是结果，等着
        if (isAwaitingServer(pos)) return true;
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
        // 没有能在原地点的了（例如只剩红石运行时的属性不同）、要用桶倒水（含水方块有自己的规则）、要在同一格再放一块：交回正常顺序
        if (!canProcessPos(pos) || fluidPlan != null || !(action instanceof ClickAction)) {
            clearAdjust();
            return false;
        }
        // 音符盒音高、中继器延迟：每点一下只进一档，按服务端确认过的状态就能算出一共要点几下。
        // 不用每一下都等回应（以前一个音符盒要等十几个来回）：每 tick 点一下，点够了再等回应、核对，没对上就按新的状态重新算
        int clicks = AdjustFirst.cyclicClicks(schematic.getBlockState(pos), current);
        executeIteration(pos, skipIteration);
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
        adjustClicksLeft = 0;
        adjustPos = null;
        adjustStateBefore = null;
        adjustActionSent = false;
        adjustNoEffect = 0;
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