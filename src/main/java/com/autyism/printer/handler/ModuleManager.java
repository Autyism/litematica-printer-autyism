package com.autyism.printer.handler;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.Setter;
import com.autyism.printer.config.Configs;
import com.autyism.printer.handler.handlers.*;
import com.autyism.printer.printer.ActionManager;
import com.autyism.printer.printer.ContainerGuard;
import com.autyism.printer.printer.MissingMaterialTracker;
import com.autyism.printer.utils.BreakUtils;
import com.autyism.printer.utils.ConfigUtils;
import com.autyism.printer.utils.ModUtils;
import com.autyism.printer.utils.QuickShulkerUtils;
import com.autyism.printer.utils.RemoteContainerUtils;
import com.autyism.printer.compat.TakeItOutCompat;
import net.minecraft.client.Minecraft;

public class ModuleManager {
    public static final Minecraft mc = Minecraft.getInstance();

    public static final GUI GUI = new GUI();
    public static final Print PRINT = new Print();
    public static final Fill FILL = new Fill();
    public static final Mine MINE = new Mine();
    public static final FluidRemoval FLUID_REMOVAL = new FluidRemoval();
    public static final Bedrock BEDROCK = new Bedrock();

    @Getter
    @Setter
    private static int packetTick;
    @Getter
    private static long currentHandlerTime;

    public static final ImmutableList<Module> VALUES = ImmutableList.of(
            GUI, PRINT, FILL, FLUID_REMOVAL, MINE, BEDROCK
    );

    private static boolean lastPrinterEnabled = false;

    public static void tick() {
        // If TakeItOut is waiting for a server-side shulker extraction, skip
        // all processing so the printer does not interfere.
        if (TakeItOutCompat.isAwaitingItem()) return;

        QuickShulkerUtils.tick();
        if (ModUtils.isRemoteInventoryNextLoaded()) {
            RemoteContainerUtils.tick();
        }
        com.autyism.printer.compat.BedrockCompat.syncWithModule(ConfigUtils.isPrinterEnable() && Configs.Bedrock.ENABLED.getBooleanValue());
        com.autyism.printer.printer.ReachHelper.tick();
        // 需求 1：玩家打开/正在打开容器时暂停打印机，避免和服务端的背包状态不同步
        if (ContainerGuard.isPaused()) return;
        boolean printerEnabled = ConfigUtils.isPrinterEnable();
        if (printerEnabled && !lastPrinterEnabled) {
            com.autyism.printer.utils.ToolSwitchUtils.resetHalt();
            MissingMaterialTracker.getInstance().reset();
            for (Module module : VALUES) {
                module.resetScanState();
            }
        }
        lastPrinterEnabled = printerEnabled;

        MissingMaterialTracker.getInstance().startCycle();

        if (ActionManager.INSTANCE.sendQueue(mc.player).needWaitModifyLook) {
            return;
        }

        if (Configs.Core.LAG_CHECK.getBooleanValue()) {
            if (packetTick > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
                return;
            }
            packetTick++;
        }

        for (Module module : VALUES) {
            if (!(module instanceof GUI)) {
                if (BreakUtils.INSTANCE.isNeedHandle()) {
                    return;
                }
                if (ActionManager.INSTANCE.needWaitModifyLook) {
                    return;
                }
            }
            module.tick();
        }
    }

    public static void updateTickHandlerTime() {
        currentHandlerTime++;
    }
}