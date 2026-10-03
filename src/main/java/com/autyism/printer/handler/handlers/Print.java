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
        switch (BlockMatchingType.get(required, level.getBlockState(pos))) {
            case CORRECT -> lsCorrect++;
            case ERROR_BLOCK_STATE -> lsWrongState++;
            case ERROR_BLOCK -> lsWrongBlock++;
            default -> {
                if (isLayerSkipped(pos)) lsSkipped++;
                else lsMissing++;
            }
        }
    }

    @Override
    protected void onLayerPassFinished(int layer, boolean layerDone) {
        LayerStats stats = new LayerStats(layer, lsTotal, lsCorrect, lsWrongState, lsWrongBlock, lsMissing, lsSkipped);
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
            recordMissingMaterial(items);
            addHighlight(blockPos, HighlightType.FAILED);
            return;
        }
        if (FluidPlacer.execute(player, plan)) {
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
        return ownActionUnconfirmed(pos);
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        BlockState required = LitematicaUtils.getBlockState(pos);
        BlockState current = level.getBlockState(pos);
        return BlockMatchingType.get(required, current) == BlockMatchingType.CORRECT;
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
                    return;
                }
                switchWaitPos = null;
                switchWaitCount = 0;
                setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
                addHighlight(blockPos, HighlightType.FAILED);
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
        action.queueAction(blockPos, side, useShift, player);
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
    }

    private void ensureIceBreakQueued(BlockPos pos) {
        if (!BreakUtils.INSTANCE.inQueue(pos) && !BreakUtils.INSTANCE.isBreaking(pos)) {
            BreakUtils.INSTANCE.add(pos);
        }
    }

    private void recordMissingMaterial(Item[] reqItems) {
        if (reqItems != null && reqItems.length > 0 && reqItems[0] != null) {
            MissingMaterialTracker.getInstance()
                    .recordMissing(reqItems[0], ctx.getRequiredBlockName());
        }
    }
}