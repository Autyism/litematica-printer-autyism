package com.autyism.printer.printer;

//? if >=1.21.5 {
import com.autyism.printer.I18n;
import com.autyism.printer.config.Configs;
import com.autyism.printer.utils.MessageUtils;
import fi.dy.masa.litematica.interfaces.ISchematicPlacementEventListener;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementEventFlag;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementEventHandler;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.List;
//?}

/**
 * 安全设置“投影或框选范围移动时关闭打印机”（默认开）的投影部分：投影或它的子区域一被移动、旋转、镜像或重置，就立刻关掉打印机，
 * 免得打印机还按旧位置放方块。用 Litematica 自己的投影事件，只有位置真的变了它才会发出（重置除外，那是玩家主动点的）。
 */
public final class PlacementMoveGuard
        //? if >=1.21.5
        implements ISchematicPlacementEventListener
{
    //? if >=1.21.5 {
    private static final PlacementMoveGuard INSTANCE = new PlacementMoveGuard();

    private PlacementMoveGuard() {
    }

    public static void register() {
        SchematicPlacementEventHandler.getInstance().registerSchematicPlacementEventListener(INSTANCE, List.of(
                SchematicPlacementEventFlag.SET_ORIGIN, SchematicPlacementEventFlag.SET_ROTATION, SchematicPlacementEventFlag.SET_MIRROR,
                SchematicPlacementEventFlag.ON_RESET, SchematicPlacementEventFlag.SET_SUBREGION_ORIGIN,
                SchematicPlacementEventFlag.SET_SUBREGION_ROTATION, SchematicPlacementEventFlag.SET_SUBREGION_MIRROR,
                SchematicPlacementEventFlag.ON_SUBREGION_RESET));
    }

    private static void moved() {
        if (!Configs.Core.STOP_ON_PLACEMENT_MOVE.getBooleanValue() || !Configs.Core.WORK_SWITCH.getBooleanValue()) return;
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        MessageUtils.setOverlayMessage(I18n.PLACEMENT_MOVED_NOTICE.getName());
    }

    @Override
    public void onSetOrigin(SchematicPlacement placement, BlockPos origin) {
        moved();
    }

    @Override
    public void onSetRotation(SchematicPlacement placement, Rotation rotation) {
        moved();
    }

    @Override
    public void onSetMirror(SchematicPlacement placement, Mirror mirror) {
        moved();
    }

    @Override
    public void onPlacementReset(SchematicPlacement placement) {
        moved();
    }

    @Override
    public void onSetSubRegionOrigin(SubRegionPlacement subRegion, BlockPos origin) {
        moved();
    }

    @Override
    public void onSetSubRegionRotation(SubRegionPlacement subRegion, Rotation rotation) {
        moved();
    }

    @Override
    public void onSetSubRegionMirror(SubRegionPlacement subRegion, Mirror mirror) {
        moved();
    }

    @Override
    public void onSubRegionReset(SubRegionPlacement subRegion) {
        moved();
    }

    @Override
    public void onPlacementAdded(SchematicPlacement placement) {
    }

    @Override
    public void onPlacementRemoved(SchematicPlacement placement) {
    }
    //?} else {
    /*// Litematica for 1.20.1 has no placement events
    public static void register() {
    }
    *///?}
}
