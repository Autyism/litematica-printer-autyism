package com.autyism.printer.utils;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.PlacementHandler;
//? if >=26.2 {
/*import fi.dy.masa.litematica.util.EasyPlaceUtils;
*///?} else
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import com.autyism.printer.config.Configs;
import com.autyism.printer.printer.PrinterBox;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;


@Environment(EnvType.CLIENT)
public class LitematicaUtils {
    public static final Minecraft client = Minecraft.getInstance();

    public static boolean isPositionWithinRange(BlockPos pos) {
        return DataManager.getRenderLayerRange().isPositionWithinRange(pos);
    }

    public static Vec3 usePrecisionPlacement(BlockPos pos, BlockState stateSchematic) {
        if (Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue()) {
            EasyPlaceProtocol protocol = PlacementHandler.getEffectiveProtocolVersion();
            Vec3 hitPos = Vec3.atLowerCornerOf(pos);
            if (protocol == EasyPlaceProtocol.V3) {
                //? if >=26.2 {
                /*return EasyPlaceUtils.applyPlacementProtocolV3(pos, stateSchematic, hitPos);
                *///?} else
                return WorldUtils.applyPlacementProtocolV3(pos, stateSchematic, hitPos);
            } else if (protocol == EasyPlaceProtocol.V2
                    && fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_PROTOCOL.getOptionListValue() == EasyPlaceProtocol.V2) {
                // Carpet 的协议要服务器打开 accurateBlockPlacement 规则，没开时服务端会直接拒绝这种放置：
                // 只有玩家在 Litematica 里明确选了 V2 才用，“自动”时只用确定可用的 V3（单人 / Servux）
                // Carpet Accurate Block placements protocol support, plus slab support
                //? if >=26.2 {
                /*return EasyPlaceUtils.applyCarpetProtocolHitVec(pos, stateSchematic, hitPos);
                *///?} else
                return WorldUtils.applyCarpetProtocolHitVec(pos, stateSchematic, hitPos);
            }
        }
        return null;
    }

    public static boolean isSchematicBlock(BlockPos pos) {
        SchematicPlacementManager schematicPlacementManager = DataManager.getSchematicPlacementManager();
        List<SchematicPlacementManager.PlacementPart> allPlacementsTouchingChunk = schematicPlacementManager.getAllPlacementsTouchingChunk(pos);

        for (SchematicPlacementManager.PlacementPart placementPart : allPlacementsTouchingChunk) {
            //? if >=26.2 {
            /*if (placementPart.getBox().contains(pos)) {
            *///?} else
            if (placementPart.getBox().containsPos(pos)) {
                SubRegionPlacement subRegion = getSubRegionForPlacementPart(placementPart);
                if (subRegion != null) {
                    if (subRegion.isEnabled()) {
                        return true;
                    }
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    /** 所有启用的投影放置中、启用的子区域的世界坐标方块框（迭代范围裁剪用） */
    public static List<PrinterBox> getSchematicWorkBoxes() {
        List<PrinterBox> result = new java.util.ArrayList<>();
        for (fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement
                : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!placement.isEnabled()) continue;
            for (Box box : placement.getSubRegionBoxes(fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values()) {
                PrinterBox pb = toPrinterBox(box);
                if (pb != null) result.add(pb);
            }
        }
        return result;
    }

    /** 当前 Litematica 选区的方块框 */
    public static List<PrinterBox> getSelectionWorkBoxes() {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        return new java.util.ArrayList<>(getSelectionBoxes(selection));
    }

    @Nullable
    private static SubRegionPlacement getSubRegionForPlacementPart(SchematicPlacementManager.PlacementPart part) {
        SchematicPlacement placement = part.getPlacement();
        String subName = part.getSubRegionName();
        if (placement != null && subName != null) {
            return placement.getRelativeSubRegionPlacement(subName);
        }
        return null;
    }

    public static boolean inSelection(BlockPos pos) {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) return false;
        if (DataManager.getSelectionManager().getSelectionMode() == SelectionMode.NORMAL) {
            // 普通选区
            List<Box> arr = selection.getAllSubRegionBoxes();
            for (Box box : arr) {
                if (isPosInBox(box, pos)) {
                    return true;
                }
            }
            return false;
        } else {
            // 简单选区
            Box box = selection.getSubRegionBox(DataManager.getSimpleArea().getName());
            return isPosInBox(box, pos);
        }
    }

    private static boolean isPosInBox(Box box, BlockPos pos) {
        if (box == null || box.getPos1() == null || box.getPos2() == null || pos == null) return false;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return x >= Math.min(box.getPos1().getX(), box.getPos2().getX())
            && x <= Math.max(box.getPos1().getX(), box.getPos2().getX())
            && y >= Math.min(box.getPos1().getY(), box.getPos2().getY())
            && y <= Math.max(box.getPos1().getY(), box.getPos2().getY())
            && z >= Math.min(box.getPos1().getZ(), box.getPos2().getZ())
            && z <= Math.max(box.getPos1().getZ(), box.getPos2().getZ());
    }

    private static List<PrinterBox> getSelectionBoxes(AreaSelection selection) {
        if (selection == null) {
            return Collections.emptyList();
        }

        if (DataManager.getSelectionManager().getSelectionMode() == SelectionMode.NORMAL) {
            return selection.getAllSubRegionBoxes().stream().map(LitematicaUtils::toPrinterBox).toList();
        }

        Box box = selection.getSubRegionBox(DataManager.getSimpleArea().getName());
        PrinterBox printerBox = toPrinterBox(box);
        return printerBox != null ? Collections.singletonList(printerBox) : Collections.emptyList();
    }

    /**
     * 获取当前投影选区的联合边界，用于裁剪迭代盒子。
     * @return 选区边界，无选区时返回 null
     */
    @Nullable
    public static PrinterBox getSelectionBounds() {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) return null;

        List<PrinterBox> boxes = getSelectionBoxes(selection);
        if (boxes.isEmpty()) return null;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (PrinterBox box : boxes) {
            if (box.minX < minX) minX = box.minX;
            if (box.minY < minY) minY = box.minY;
            if (box.minZ < minZ) minZ = box.minZ;
            if (box.maxX > maxX) maxX = box.maxX;
            if (box.maxY > maxY) maxY = box.maxY;
            if (box.maxZ > maxZ) maxZ = box.maxZ;
        }

        return new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static PrinterBox toPrinterBox(Box box) {
        if (box == null || box.getPos1() == null || box.getPos2() == null) {
            return null;
        }
        return new PrinterBox(box.getPos1(), box.getPos2());
    }

    public static BlockState getBlockState(BlockPos pos) {
        return SchematicWorldHandler.getSchematicWorld().getBlockState(pos);
    }
}