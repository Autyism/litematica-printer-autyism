package com.autyism.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigBase;
import lombok.Getter;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.BlockMatchingType;
import com.autyism.printer.handler.Module;
import com.autyism.printer.handler.ModuleManager;
import com.autyism.printer.printer.PrinterBox;
import com.autyism.printer.printer.ProgressIndex;
import com.autyism.printer.printer.SchematicBlockContext;
import com.autyism.printer.printer.WaterlogPlacer;
import com.autyism.printer.utils.LitematicaUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * GUI 统计处理器。
 * <ul>
 *   <li>整个投影（默认）：按区块段增量统计（ProgressIndex），只重算方块有变化的区块段；</li>
 *   <li>工作范围内：遍历工作范围计算进度，每 tick 分摊扫描量；只有范围里的方块变了、范围变了、或者隔一段时间才重新扫一遍。</li>
 * </ul>
 */
public class GUI extends Module {
    public final static String NAME = "gui";
    private static final int SCAN_BUDGET_MS = 2;
    private static final long LOADED_BUDGET_NANOS = SCAN_BUDGET_MS * 1_000_000L;
    /** 范围内的统计没有任何变化时，隔多久再重新扫一遍（兜底） */
    private static final int RANGE_RESCAN_TICKS = 40;

    @Getter
    private final Progress totalProgress = new Progress(Configs.Print.ENABLED);
    /** 打印：工作范围内的进度 */
    private final Progress printProgress = new Progress(Configs.Print.ENABLED);
    /** 打印：整个投影（已加载的区块）的进度 */
    private final Progress loadedPrintProgress = new Progress(Configs.Print.ENABLED);
    @Getter
    private final Progress fluidProgress = new Progress(Configs.Fluid.ENABLED);
    @Getter
    private final Progress fillProgress = new Progress(Configs.Fill.ENABLED);
    @Getter
    private final Progress mineProgress = new Progress(Configs.Mine.ENABLED);

    private boolean scanning = false;

    public GUI() {
        super(NAME, Configs.Core.RENDER_HUD, null, true);
        com.autyism.printer.printer.BlockChangeHub.register(this::onClientBlockChanged);
    }

    /** 打印模式下只统计“工作范围 ∩ 投影”的区域（与打印机实际处理的范围一致），其他模式统计整个工作范围 */
    @Override
    protected boolean needsAreaCheck() {
        return Configs.Print.ENABLED.getBooleanValue() && !Configs.Fluid.ENABLED.getBooleanValue()
                && !Configs.Fill.ENABLED.getBooleanValue() && !Configs.Mine.ENABLED.getBooleanValue();
    }

    @Override
    protected List<PrinterBox> getWorkAreaBoxes() {
        return LitematicaUtils.getSchematicWorkBoxes();
    }

    @Override
    protected com.autyism.printer.handler.IteratorManager.SectionFilter getSectionFilter() {
        return ModuleManager.PRINT.getPrintSectionFilter();
    }

    /** 打印模式下错误统计（最近一次完整扫描的结果） */
    private long wrongState, wrongBlock;
    private long scanWrongState, scanWrongBlock;

    // ---------------- 工作范围内：只在有变化时重扫 ----------------
    /** 上一轮扫描之后，范围里有方块变了 */
    private boolean rangeDirty = true;
    /** 这一轮 / 上一轮扫描用的范围（模块重建范围时换成新对象） */
    @Nullable
    private PrinterBox scanBox, lastScanBox;
    @Nullable
    private List<Boolean> lastModes;
    private int rangeScanDoneTick;

    // ---------------- 整个投影的进度（已加载的区块，增量统计） ----------------
    private final ProgressIndex loadedIndex = new ProgressIndex(new ProgressIndex.Rules() {
        @Override
        public boolean counts(BlockPos pos) {
            return ModuleManager.PRINT.inPrintSelection(pos);
        }

        @Override
        public BlockMatchingType matching(BlockPos pos, BlockState required, BlockState current) {
            BlockMatchingType type = BlockMatchingType.get(required, current);
            // 投影里这一侧开着、保持不含水的含水方块算完成
            if (type == BlockMatchingType.ERROR_BLOCK_STATE
                    && WaterlogPlacer.acceptsDry(level, SchematicWorldHandler.getSchematicWorld(), mc.player, pos, required, current)) {
                return BlockMatchingType.CORRECT;
            }
            return type;
        }
    });
    private boolean loadedActive;

    /** 进度条统计整个投影（已加载的区块），而不是只算工作范围内 */
    public static boolean countsLoadedArea() {
        return Configs.Core.PROGRESS_SCOPE.getOptionListValue() == com.autyism.printer.enums.ProgressScope.LOADED_AREA;
    }

    /** HUD 上显示的打印进度（按“进度条统计范围”的设置） */
    public Progress getPrintProgress() {
        return countsLoadedArea() ? loadedPrintProgress : printProgress;
    }

    /** 工作范围内的打印进度 */
    public Progress getRangePrintProgress() {
        return printProgress;
    }

    /** 整个投影（已加载的区块）的打印进度 */
    public Progress getLoadedPrintProgress() {
        return loadedPrintProgress;
    }

    public long getWrongState() {
        return countsLoadedArea() && Configs.Print.ENABLED.getBooleanValue() ? loadedIndex.shownWrongState() : wrongState;
    }

    public long getWrongBlock() {
        return countsLoadedArea() && Configs.Print.ENABLED.getBooleanValue() ? loadedIndex.shownWrongBlock() : wrongBlock;
    }

    /** 客户端世界里的方块变了（BlockChangeHub）：只做标记 */
    public void onClientBlockChanged(BlockPos pos) {
        if (loadedActive) loadedIndex.blockChanged(pos);
        PrinterBox current = box == null ? null : box.get();
        if (current != null && current.contains(pos)) rangeDirty = true;
    }

    @Override
    protected boolean canExecute() {
        return false;
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        return true;
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        return true;
    }

    @Override
    protected void preprocess() {
        if (box == null || box.get() == null || level == null) return;
        if (level != lastLevel) {
            // 换了世界 / 服务器：上一个世界的进度不再有意义
            lastLevel = level;
            resetScanState();
        }

        boolean loaded = Configs.Print.ENABLED.getBooleanValue() && countsLoadedArea();
        if (loaded) {
            tickLoadedIndex();
            // 只开着打印时，工作范围内的统计用不上
            if (!Configs.Fluid.ENABLED.getBooleanValue() && !Configs.Fill.ENABLED.getBooleanValue() && !Configs.Mine.ENABLED.getBooleanValue()) return;
        } else if (loadedActive) {
            loadedActive = false;
            loadedIndex.clear();
        }

        PrinterBox current = box.get();
        int now = player == null ? 0 : player.tickCount;
        // 扫到一半范围重建了（玩家走远了、换了范围）：重新开始，否则会把一部分格子数两遍
        if (scanning && current != scanBox) scanning = false;
        if (!scanning) {
            List<Boolean> modes = List.of(Configs.Print.ENABLED.getBooleanValue(), Configs.Fluid.ENABLED.getBooleanValue(),
                    Configs.Fill.ENABLED.getBooleanValue(), Configs.Mine.ENABLED.getBooleanValue());
            boolean needed = rangeDirty || current != lastScanBox || !modes.equals(lastModes)
                    || now - rangeScanDoneTick >= RANGE_RESCAN_TICKS || now < rangeScanDoneTick;
            if (!needed) return;
            startScan();
            scanBox = current;
            lastModes = modes;
            rangeDirty = false;
        }

        long deadline = System.currentTimeMillis() + SCAN_BUDGET_MS;
        BlockPos pos;
        while ((pos = iteratorManager.next()) != null) {
            countPosition(pos);
            if (System.currentTimeMillis() >= deadline) return;
        }

        finishScan();
        lastScanBox = scanBox;
        rangeScanDoneTick = now;
    }

    private void tickLoadedIndex() {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null || player == null) return;
        loadedActive = true;
        net.minecraft.world.level.ChunkPos center = player.chunkPosition();
        loadedIndex.tick(level, schematic, LitematicaUtils.getSchematicWorkBoxes(), ModuleManager.PRINT.selectionSignature(),
                center.x, center.z, mc.options.getEffectiveRenderDistance() + 1, LOADED_BUDGET_NANOS);
        if (loadedIndex.hasShown()) loadedPrintProgress.setShown(loadedIndex.shownCorrect(), loadedIndex.shownTotal());
    }

    @Nullable
    private net.minecraft.client.multiplayer.ClientLevel lastLevel;

    @Override
    public void resetScanState() {
        super.resetScanState();
        scanning = false;
        rangeDirty = true;
        scanBox = lastScanBox = null;
        lastModes = null;
        wrongState = wrongBlock = 0;
        loadedActive = false;
        loadedIndex.clear();
        loadedPrintProgress.clearShown();
        totalProgress.clearShown();
        printProgress.clearShown();
        fluidProgress.clearShown();
        fillProgress.clearShown();
        mineProgress.clearShown();
    }

    private void startScan() {
        scanning = true;
        scanWrongState = scanWrongBlock = 0;
        totalProgress.resetCounters();
        printProgress.resetCounters();
        fluidProgress.resetCounters();
        fillProgress.resetCounters();
        mineProgress.resetCounters();
        iteratorManager.reset();
    }

    private void finishScan() {
        scanning = false;
        wrongState = scanWrongState;
        wrongBlock = scanWrongBlock;
        printProgress.calculateProgress();
        fluidProgress.calculateProgress();
        fillProgress.calculateProgress();
        mineProgress.calculateProgress();
        totalProgress.calculateProgress();
    }

    private void countPosition(BlockPos blockPos) {
        if (Configs.Print.ENABLED.getBooleanValue()) {
            WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
            if (schematic != null) {
                SchematicBlockContext context = new SchematicBlockContext(mc, level, schematic, blockPos);
                if (!context.requiredState.isAir()) {
                    BlockMatchingType type = BlockMatchingType.get(context);
                    // 投影里这一侧开着、保持不含水的含水方块算完成
                    if (type == BlockMatchingType.ERROR_BLOCK_STATE && WaterlogPlacer.acceptsDry(level, schematic, mc.player,
                            blockPos, context.requiredState, context.currentState)) {
                        type = BlockMatchingType.CORRECT;
                    }
                    if (type == BlockMatchingType.CORRECT) {
                        printProgress.finished++;
                        totalProgress.finished++;
                    } else if (type == BlockMatchingType.ERROR_BLOCK_STATE) {
                        scanWrongState++;
                    } else if (type == BlockMatchingType.ERROR_BLOCK) {
                        scanWrongBlock++;
                    }
                    printProgress.total++;
                    totalProgress.total++;
                }
            }
        }
        if (Configs.Fluid.ENABLED.getBooleanValue()) {
            if (!(level.getBlockState(blockPos).getBlock() instanceof LiquidBlock)) {
                fluidProgress.finished++;
                totalProgress.finished++;
            }
            fluidProgress.total++;
            totalProgress.total++;
        }
        if (Configs.Fill.ENABLED.getBooleanValue()) {
            if (Arrays.asList(ModuleManager.FILL.getFillModeItemList()).contains(level.getBlockState(blockPos).getBlock().asItem())) {
                fillProgress.finished++;
                totalProgress.finished++;
            }
            fillProgress.total++;
            totalProgress.total++;
        }
        if (Configs.Mine.ENABLED.getBooleanValue()) {
            if (level.getBlockState(blockPos).isAir()) {
                mineProgress.finished++;
                totalProgress.finished++;
            }
            mineProgress.total++;
            totalProgress.total++;
        }
    }

    /**
     * 进度：扫描中的计数与显示用的结果分开（双缓冲），只有一轮扫描完整结束才更新显示，
     * 避免扫描中途 / 玩家移动重建范围时数字忽大忽小。
     */
    @Getter
    public static class Progress {
        private final ConfigBase<?> config;
        private long total;
        private long finished;
        /** 最近一次完整扫描的结果 */
        private long shownTotal;
        private long shownFinished;
        private double progress;

        public Progress(ConfigBase<?> config) {
            this.config = config;
        }

        public double getProgress() {
            return progress;
        }

        public void resetCounters() {
            this.total = 0;
            this.finished = 0;
        }

        /** 重新开始（打印机重新开启 / 换了世界）：不再显示上一次打印的进度 */
        public void clearShown() {
            resetCounters();
            shownTotal = shownFinished = 0;
            progress = 0;
        }

        public void calculateProgress() {
            setShown(finished, total);
        }

        /** 直接设置显示的结果（增量统计用） */
        public void setShown(long finished, long total) {
            shownTotal = total;
            shownFinished = finished;
            progress = total < 1 ? 1.0 : (double) finished / total;
        }
    }
}
