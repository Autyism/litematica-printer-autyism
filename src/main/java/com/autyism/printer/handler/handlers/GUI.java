package com.autyism.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigBase;
import lombok.Getter;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.BlockMatchingType;
import com.autyism.printer.handler.Module;
import com.autyism.printer.handler.ModuleManager;
import com.autyism.printer.printer.SchematicBlockContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GUI 统计处理器 — 增量遍历 box 计算进度，每 tick 分摊扫描量避免卡顿。
 */
public class GUI extends Module {
    public final static String NAME = "gui";
    private static final int SCAN_BUDGET_MS = 2;

    @Getter
    private final Progress totalProgress = new Progress(Configs.Print.ENABLED);
    @Getter
    private final Progress printProgress = new Progress(Configs.Print.ENABLED);
    @Getter
    private final Progress fluidProgress = new Progress(Configs.Fluid.ENABLED);
    @Getter
    private final Progress fillProgress = new Progress(Configs.Fill.ENABLED);
    @Getter
    private final Progress mineProgress = new Progress(Configs.Mine.ENABLED);

    private boolean scanning = false;

    public GUI() {
        super(NAME, Configs.Core.RENDER_HUD, null, true);
    }

    /** 打印模式下只统计“工作范围 ∩ 投影”的区域（与打印机实际处理的范围一致），其他模式统计整个工作范围 */
    @Override
    protected boolean needsAreaCheck() {
        return Configs.Print.ENABLED.getBooleanValue() && !Configs.Fluid.ENABLED.getBooleanValue()
                && !Configs.Fill.ENABLED.getBooleanValue() && !Configs.Mine.ENABLED.getBooleanValue();
    }

    @Override
    protected java.util.List<com.autyism.printer.printer.PrinterBox> getWorkAreaBoxes() {
        return com.autyism.printer.utils.LitematicaUtils.getSchematicWorkBoxes();
    }

    @Override
    protected com.autyism.printer.handler.IteratorManager.SectionFilter getSectionFilter() {
        return ModuleManager.PRINT.getPrintSectionFilter();
    }

    /** 打印模式下错误统计（最近一次完整扫描的结果） */
    @Getter
    private long wrongState, wrongBlock;
    private long scanWrongState, scanWrongBlock;

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

        if (!scanning) {
            startScan();
        }

        long deadline = System.currentTimeMillis() + SCAN_BUDGET_MS;
        BlockPos pos;
        while ((pos = iteratorManager.next()) != null) {
            countPosition(pos);
            if (System.currentTimeMillis() >= deadline) return;
        }

        finishScan();
    }

    @org.jetbrains.annotations.Nullable
    private net.minecraft.client.multiplayer.ClientLevel lastLevel;

    @Override
    public void resetScanState() {
        super.resetScanState();
        scanning = false;
        wrongState = wrongBlock = 0;
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
                    if (type == BlockMatchingType.ERROR_BLOCK_STATE && com.autyism.printer.printer.WaterlogPlacer.acceptsDry(level, schematic, mc.player,
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
            shownTotal = total;
            shownFinished = finished;
            progress = total < 1 ? 1.0 : (double) finished / total;
        }
    }
}