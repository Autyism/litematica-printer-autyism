package com.autyism.printer.handler;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.util.LayerMode;
//? if >=26.2 {
/*import fi.dy.masa.malilib.util.position.LayerRange;
*///?} else
import fi.dy.masa.malilib.util.LayerRange;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.IterationOrderType;
import com.autyism.printer.enums.RadiusShapeType;
import com.autyism.printer.enums.SelectionType;
import com.autyism.printer.printer.PrinterBox;
import com.autyism.printer.utils.ConfigUtils;
import com.autyism.printer.utils.PlayerUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 迭代管理器 — 从 Module 中分离出的迭代相关逻辑。
 * 负责：PrinterBox 生命周期、迭代器缓存、形状过滤、范围裁剪。
 */
public class IteratorManager {
    private PrinterBox box;
    private Iterator<BlockPos> cachedIterator;
    private RadiusShapeType shapeType;
    private Vec3 eyePos;
    private double effectiveRange;

    private BlockPos lastEyePos;
    private int lastExpandRange = -1;
    private int lastLayerMin = Integer.MIN_VALUE;
    private int lastLayerMax = Integer.MIN_VALUE;
    private int lastLayerSingle = Integer.MIN_VALUE;
    private int lastLayerAbove = Integer.MIN_VALUE;
    private int lastLayerBelow = Integer.MIN_VALUE;
    @Nullable
    private Direction.Axis lastLayerAxis = null;
    @Nullable
    private LayerMode lastLayerMode = null;
    @Nullable
    private SelectionType lastSelectionType = null;
    @Nullable
    private PrinterBox lastBox;

    /** 工作范围与投影/选区方块框的交集；null 表示没有区域信息，退回到遍历整个工作范围 */
    @Nullable
    private List<PrinterBox> regions;
    @Nullable
    private List<PrinterBox> lastAreaBoxes;
    @Nullable
    private Integer lastLayerClamp;

    /** 区块段过滤：返回 true 表示整个 16x16x16 区块段都不需要处理（例如投影里这一段全是空气） */
    @FunctionalInterface
    public interface SectionFilter {
        boolean skip(int sectionX, int sectionY, int sectionZ);
    }

    /** 超过这个体积的区域按区块段遍历，以便整段跳过空气（小区域保持原来的逐格顺序） */
    private static final long SECTIONED_VOLUME = 64L * 64 * 64;

    @Nullable
    private SectionFilter sectionFilter;

    public void setSectionFilter(@Nullable SectionFilter filter) {
        this.sectionFilter = filter;
    }

    private boolean needsRebuild;
    private boolean dirtyIterator;
    // 层范围与工作范围无交集：本轮不迭代任何方块
    private boolean emptyBox;

    public IteratorManager() {
        this.needsRebuild = true;
        this.dirtyIterator = true;
    }

    /**
     * 根据玩家位置和配置重建 PrinterBox，返回是否需要重置扫描状态。
     */
    public boolean tryBuildBox(LocalPlayer player, @Nullable Object selectionTypeObj) {
        return tryBuildBox(player, selectionTypeObj, true);
    }

    /**
     * @param respectRenderLayer false 时不按 Litematica 渲染层裁剪（例如破基岩模式处理整个框选范围）
     */
    public boolean tryBuildBox(LocalPlayer player, @Nullable Object selectionTypeObj, boolean respectRenderLayer) {
        return tryBuildBox(player, selectionTypeObj, respectRenderLayer, null, null);
    }

    /**
     * @param areaBoxes  投影放置/选区的方块框。不为 null 时只遍历“工作范围 ∩ 这些框”，
     *                   因此遍历量只取决于投影大小，与工作范围设多大无关（需求 13：超大范围）
     * @param layerClamp 不为 null 时只遍历这一个 Y 层（分层打印模式）
     */
    public boolean tryBuildBox(LocalPlayer player, @Nullable Object selectionTypeObj, boolean respectRenderLayer,
                               @Nullable List<PrinterBox> areaBoxes, @Nullable Integer layerClamp) {
        BlockPos eyeBP = new BlockPos(
                (int) Math.round(player.getX()),
                (int) Math.round(player.getEyeY()),
                (int) Math.round(player.getZ()));

        double effectiveRange = ConfigUtils.getEffectiveRange();
        int currentRange = (int) Math.ceil(effectiveRange);

        LayerRange layerRange = DataManager.getRenderLayerRange();
        LayerMode layerMode = respectRenderLayer ? layerRange.getLayerMode() : LayerMode.ALL;
        Direction.Axis layerAxis = layerRange.getAxis();
        //? if >=26.2 {
        /*int layerMin = layerRange.getMinLayerBoundary();
        int layerMax = layerRange.getMaxLayerBoundary();
        *///?} else {
        int layerMin = layerRange.getLayerMin();
        int layerMax = layerRange.getLayerMax();
        //?}
        int layerSingle = layerRange.getLayerSingle();
        int layerAbove = layerRange.getLayerAbove();
        int layerBelow = layerRange.getLayerBelow();

        SelectionType selectionType = selectionTypeObj instanceof SelectionType s ? s : null;

        boolean needRebuild = this.box == null
                || !this.box.equals(lastBox)
                || lastEyePos == null
                || !lastEyePos.closerThan(eyeBP, effectiveRange * 0.4)
                || lastExpandRange != currentRange
                || layerMin != lastLayerMin
                || layerMax != lastLayerMax
                || layerSingle != lastLayerSingle
                || layerAbove != lastLayerAbove
                || layerBelow != lastLayerBelow
                || layerAxis != lastLayerAxis
                || layerMode != lastLayerMode
                || selectionType != lastSelectionType
                || !java.util.Objects.equals(areaBoxes, lastAreaBoxes)
                || !java.util.Objects.equals(layerClamp, lastLayerClamp);

        if (needRebuild) {
            lastEyePos = eyeBP;
            lastExpandRange = currentRange;
            lastLayerMin = layerMin;
            lastLayerMax = layerMax;
            lastLayerSingle = layerSingle;
            lastLayerAbove = layerAbove;
            lastLayerBelow = layerBelow;
            lastLayerAxis = layerAxis;
            lastLayerMode = layerMode;
            lastSelectionType = selectionType;
            lastAreaBoxes = areaBoxes;
            lastLayerClamp = layerClamp;

            int minX = (int) Math.floor(player.getX() - effectiveRange);
            int maxX = (int) Math.ceil(player.getX() + effectiveRange);
            int minY = (int) Math.floor(player.getEyeY() - effectiveRange);
            int maxY = (int) Math.ceil(player.getEyeY() + effectiveRange);
            int minZ = (int) Math.floor(player.getZ() - effectiveRange);
            int maxZ = (int) Math.ceil(player.getZ() + effectiveRange);

            // 层范围裁剪应对所有选区模式生效，而非仅限"可见层"模式
            if (layerMode != LayerMode.ALL) {
                switch (layerMode) {
                    case SINGLE_LAYER -> {
                        switch (layerAxis) {
                            case Y -> { minY = Math.max(minY, layerSingle); maxY = Math.min(maxY, layerSingle); }
                            case X -> { minX = Math.max(minX, layerSingle); maxX = Math.min(maxX, layerSingle); }
                            case Z -> { minZ = Math.max(minZ, layerSingle); maxZ = Math.min(maxZ, layerSingle); }
                        }
                    }
                    case LAYER_RANGE -> {
                        switch (layerAxis) {
                            case Y -> { minY = Math.max(minY, layerMin); maxY = Math.min(maxY, layerMax); }
                            case X -> { minX = Math.max(minX, layerMin); maxX = Math.min(maxX, layerMax); }
                            case Z -> { minZ = Math.max(minZ, layerMin); maxZ = Math.min(maxZ, layerMax); }
                        }
                    }
                    case ALL_BELOW -> {
                        switch (layerAxis) {
                            case Y -> maxY = Math.min(maxY, layerBelow);
                            case X -> maxX = Math.min(maxX, layerBelow);
                            case Z -> maxZ = Math.min(maxZ, layerBelow);
                        }
                    }
                    case ALL_ABOVE -> {
                        switch (layerAxis) {
                            case Y -> minY = Math.max(minY, layerAbove);
                            case X -> minX = Math.max(minX, layerAbove);
                            case Z -> minZ = Math.max(minZ, layerAbove);
                        }
                    }
                }
            }

            if (selectionType != null) {
                if (selectionType == SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER) {
                    maxY = Math.min(maxY, (int) Math.floor(player.getY()));
                } else if (selectionType == SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER) {
                    minY = Math.max(minY, (int) Math.ceil(player.getY()));
                }
            }

            // 裁剪后 min > max 说明层范围与工作范围没有交集。
            // PrinterBox 构造器会把 min/max 交换，若直接构造会扫描到层范围之外的方块（跳跃/上下移动时误打其他层）。
            this.emptyBox = minX > maxX || minY > maxY || minZ > maxZ;
            if (!emptyBox && PrinterBox.client.level != null) {
                //? if <1.21.2 {
                /*emptyBox = maxY < PrinterBox.client.level.getMinBuildHeight() || minY > PrinterBox.client.level.getMaxBuildHeight() - 1;
                *///?} else
                emptyBox = maxY < PrinterBox.client.level.getMinY() || minY > PrinterBox.client.level.getMaxY();
            }

            if (layerClamp != null) {
                minY = Math.max(minY, layerClamp);
                maxY = Math.min(maxY, layerClamp);
                if (minY > maxY) emptyBox = true;
            }

            box = new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
            lastBox = box;

            if (areaBoxes != null && !emptyBox) {
                List<PrinterBox> list = new ArrayList<>();
                for (PrinterBox area : areaBoxes) {
                    int x0 = Math.max(minX, area.minX), x1 = Math.min(maxX, area.maxX);
                    int y0 = Math.max(minY, area.minY), y1 = Math.min(maxY, area.maxY);
                    int z0 = Math.max(minZ, area.minZ), z1 = Math.min(maxZ, area.maxZ);
                    if (x0 > x1 || y0 > y1 || z0 > z1) continue;
                    list.add(new PrinterBox(x0, y0, z0, x1, y1, z1));
                }
                regions = list;
            } else {
                regions = null;
            }

            List<PrinterBox> toConfigure = new ArrayList<>();
            toConfigure.add(box);
            if (regions != null) toConfigure.addAll(regions);
            for (PrinterBox b : toConfigure) {
                b.iterationMode = (IterationOrderType) Configs.Core.ITERATION_ORDER.getOptionListValue();
                b.xIncrement = !Configs.Core.X_REVERSE.getBooleanValue();
                b.yIncrement = !Configs.Core.Y_REVERSE.getBooleanValue();
                b.zIncrement = !Configs.Core.Z_REVERSE.getBooleanValue();
            }

            this.shapeType = Configs.Core.ITERATOR_SHAPE.getOptionListValue() instanceof RadiusShapeType s ? s : null;
            this.eyePos = player.getEyePosition();
            this.effectiveRange = effectiveRange;

            cachedIterator = null;
            dirtyIterator = true;

            this.needsRebuild = false;
            return true;
        }

        this.needsRebuild = false;
        return false;
    }

    public void markNeedsRebuild() {
        this.needsRebuild = true;
    }

    public boolean isNeedsRebuild() {
        return needsRebuild;
    }

    public boolean isDirtyIterator() {
        return dirtyIterator;
    }

    /**
     * 获取下一个需要迭代的位置（已过滤形状和可达性）。
     * 返回 null 表示迭代结束。
     */
    @Nullable
    public BlockPos next() {
        if (box == null || emptyBox) return null;

        if (cachedIterator == null) {
            cachedIterator = createIterator();
            dirtyIterator = false;
        }

        // 盒子只在移动较远时重建，距离判定必须用实时眼睛位置，否则跳跃后会尝试够不着的方块（服务端拒绝 → 幽灵方块）
        Vec3 liveEyePos = PrinterBox.client.player != null ? PrinterBox.client.player.getEyePosition() : eyePos;

        while (cachedIterator.hasNext()) {
            BlockPos pos = cachedIterator.next();
            if (pos == null) continue;

            if (shapeType != null) {
                if (!PlayerUtils.canInteracted(pos, liveEyePos, effectiveRange, shapeType)) continue;
            } else if (!PlayerUtils.canInteracted(pos)) continue;

            return pos;
        }

        cachedIterator = null;
        return null;
    }

    public boolean hasNext() {
        if (box == null || emptyBox) return false;
        if (cachedIterator == null) {
            cachedIterator = createIterator();
            dirtyIterator = false;
        }
        return cachedIterator.hasNext();
    }

    private Iterator<BlockPos> regionIterator(PrinterBox region) {
        long volume = (long) (region.maxX - region.minX + 1) * (region.maxY - region.minY + 1) * (region.maxZ - region.minZ + 1);
        if (sectionFilter == null || volume < SECTIONED_VOLUME) return region.iterator();
        return new SectionedIterator(region, sectionFilter);
    }

    /** 按区块段遍历一个大区域：整段被过滤的直接跳过，段内按区域配置的顺序逐格遍历 */
    private static final class SectionedIterator implements Iterator<BlockPos> {
        private final PrinterBox region;
        private final SectionFilter filter;
        private final int sx0, sx1, sy0, sy1, sz0, sz1;
        private int sx, sy, sz;
        private boolean started;
        private boolean finished;
        private Iterator<BlockPos> current = Collections.emptyIterator();

        SectionedIterator(PrinterBox region, SectionFilter filter) {
            this.region = region;
            this.filter = filter;
            this.sx0 = region.minX >> 4;
            this.sx1 = region.maxX >> 4;
            this.sy0 = region.minY >> 4;
            this.sy1 = region.maxY >> 4;
            this.sz0 = region.minZ >> 4;
            this.sz1 = region.maxZ >> 4;
        }

        /** 前进到下一个区块段；返回 false 表示遍历结束 */
        private boolean advanceSection() {
            if (!started) {
                started = true;
                sy = region.yIncrement ? sy0 : sy1;
                sx = region.xIncrement ? sx0 : sx1;
                sz = region.zIncrement ? sz0 : sz1;
                return true;
            }
            sz += region.zIncrement ? 1 : -1;
            if (region.zIncrement ? sz > sz1 : sz < sz0) {
                sz = region.zIncrement ? sz0 : sz1;
                sx += region.xIncrement ? 1 : -1;
                if (region.xIncrement ? sx > sx1 : sx < sx0) {
                    sx = region.xIncrement ? sx0 : sx1;
                    sy += region.yIncrement ? 1 : -1;
                    if (region.yIncrement ? sy > sy1 : sy < sy0) return false;
                }
            }
            return true;
        }

        @Override
        public boolean hasNext() {
            while (!current.hasNext()) {
                if (finished) return false;
                if (!advanceSection()) {
                    finished = true;
                    return false;
                }
                if (filter.skip(sx, sy, sz)) continue;
                PrinterBox sub = new PrinterBox(
                        Math.max(region.minX, sx << 4), Math.max(region.minY, sy << 4), Math.max(region.minZ, sz << 4),
                        Math.min(region.maxX, (sx << 4) + 15), Math.min(region.maxY, (sy << 4) + 15), Math.min(region.maxZ, (sz << 4) + 15));
                sub.iterationMode = region.iterationMode;
                sub.xIncrement = region.xIncrement;
                sub.yIncrement = region.yIncrement;
                sub.zIncrement = region.zIncrement;
                current = sub.iterator();
            }
            return true;
        }

        @Override
        public BlockPos next() {
            if (!hasNext()) throw new NoSuchElementException();
            return current.next();
        }
    }

    private Iterator<BlockPos> createIterator() {
        if (regions == null) return box.iterator();
        if (regions.isEmpty()) return Collections.emptyIterator();
        //? if <1.20.5 {
        /*if (regions.size() == 1) return regionIterator(regions.get(0));
        *///?} else
        if (regions.size() == 1) return regionIterator(regions.getFirst());
        List<PrinterBox> list = regions;
        return new Iterator<>() {
            private int index = 0;
            //? if <1.20.5 {
            /*private Iterator<BlockPos> current = regionIterator(list.get(0));
            *///?} else
            private Iterator<BlockPos> current = regionIterator(list.getFirst());

            @Override
            public boolean hasNext() {
                while (!current.hasNext()) {
                    if (++index >= list.size()) return false;
                    current = regionIterator(list.get(index));
                }
                return true;
            }

            @Override
            public BlockPos next() {
                if (!hasNext()) throw new NoSuchElementException();
                return current.next();
            }
        };
    }

    /** 本次迭代涉及的区域（工作范围与投影/选区的交集），没有区域信息时为 null */
    @Nullable
    public List<PrinterBox> getRegions() {
        return regions;
    }

    public void reset() {
        cachedIterator = null;
        dirtyIterator = true;
    }

    @Nullable
    public PrinterBox getBox() {
        return box;
    }

    public boolean hasBox() {
        return box != null;
    }

    /**
     * 使用脏区域迭代器替换当前迭代器（用于 PARTIAL 模式）。
     */
    public void setDirtyRegionIterator(Iterator<BlockPos> dirtyIter) {
        this.cachedIterator = dirtyIter;
        this.dirtyIterator = false;
    }
}
