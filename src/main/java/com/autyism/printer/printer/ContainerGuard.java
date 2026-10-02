package com.autyism.printer.printer;

import com.autyism.printer.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 需求 1：打印机开着时去箱子里拿材料，打印会变得不准。
 * <p>
 * 原因：玩家右键箱子后，服务端立刻把当前容器切换成箱子，但客户端要等 OpenScreen 包到达（网络延迟）才知道。
 * 这段时间里打印机对玩家背包（容器 id 0）发出的换物品点击会被服务端直接忽略，客户端却已经本地预测了交换，
 * 双方对“手里拿的是什么”产生分歧，于是放出错误的方块。容器打开期间玩家自己搬动物品、以及关闭后服务端
 * 同步背包的短暂窗口里，同样会和打印机的换物品操作互相踩踏。
 * <p>
 * 处理：从玩家与可能打开容器的方块/实体/物品交互的那一刻起暂停打印机（含挖掘、换工具），
 * 直到容器界面关闭并再等几 tick 让服务端完成背包同步后再恢复。打印机自己打开的潜影盒等容器不受影响。
 */
public final class ContainerGuard {
    private static final Minecraft mc = Minecraft.getInstance();
    /** 交互后等待 OpenScreen 包的最长 tick 数（超时说明没有打开容器，例如被挡住或只是普通交互） */
    private static final int OPEN_WAIT_TICKS = 20;
    /** 容器界面关闭后继续暂停的 tick 数，等待服务端背包同步 */
    private static final int RESUME_DELAY_TICKS = 6;

    private static int pendingOpenTicks;
    private static int resumeDelayTicks;
    private static boolean wasPaused;
    /** 打印机自身发出的交互不算玩家打开容器 */
    private static int printerInteractionDepth;

    private ContainerGuard() {
    }

    private static boolean enabled() {
        return Configs.Core.PAUSE_ON_CONTAINER.getBooleanValue();
    }

    public static void beginPrinterInteraction() {
        printerInteractionDepth++;
    }

    public static void endPrinterInteraction() {
        if (printerInteractionDepth > 0) printerInteractionDepth--;
    }

    public static boolean isPrinterInteraction() {
        return printerInteractionDepth > 0;
    }

    /** 玩家右键方块（vanilla useItemOn）。 */
    public static void onUseItemOn(LocalPlayer player, BlockPos pos) {
        if (isPrinterInteraction() || player == null || player.level() == null) return;
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        boolean sneakWithItem = player.isSecondaryUseActive()
                && !(player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty());
        if (sneakWithItem) return;
        BlockEntity be = level.getBlockEntity(pos);
        boolean mayOpen = be instanceof MenuProvider || state.getMenuProvider(level, pos) != null;
        if (!mayOpen) {
            // 手持潜影盒点方块：装了 Advanced Shulkerboxes 时不潜行会打开潜影盒界面而不是放置
            mayOpen = isHandShulkerOpen(player.getMainHandItem());
        }
        if (mayOpen) markPendingOpen();
    }

    /** 玩家对空气使用物品（vanilla useItem）。 */
    public static void onUseItem(LocalPlayer player) {
        if (isPrinterInteraction() || player == null) return;
        if (isHandShulkerOpen(player.getMainHandItem()) || isHandShulkerOpen(player.getOffhandItem())) {
            markPendingOpen();
        }
    }

    /** 玩家右键实体（vanilla interact / interactAt）。 */
    public static void onInteractEntity(Entity entity) {
        if (isPrinterInteraction()) return;
        if (entity instanceof ContainerEntity || entity instanceof AbstractHorse
                || entity instanceof Merchant || entity instanceof InventoryCarrier) {
            markPendingOpen();
        }
    }

    private static boolean isHandShulkerOpen(ItemStack stack) {
        return stack.getCount() == 1 && stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock
                && com.autyism.printer.utils.ModUtils.isLoadMod("shulkerbox");
    }

    private static void markPendingOpen() {
        if (!enabled()) return;
        pendingOpenTicks = OPEN_WAIT_TICKS;
    }

    /** 每 tick 调用一次，返回本 tick 打印机是否应暂停。 */
    public static boolean tick() {
        if (!enabled() || mc.player == null) {
            pendingOpenTicks = 0;
            resumeDelayTicks = 0;
            wasPaused = false;
            return false;
        }
        boolean paused;
        if (isUserContainerScreenOpen()) {
            pendingOpenTicks = 0;
            resumeDelayTicks = RESUME_DELAY_TICKS;
            paused = true;
        } else if (pendingOpenTicks > 0) {
            pendingOpenTicks--;
            resumeDelayTicks = RESUME_DELAY_TICKS;
            paused = true;
        } else if (resumeDelayTicks > 0) {
            resumeDelayTicks--;
            paused = true;
        } else {
            paused = false;
        }
        if (paused && !wasPaused) {
            // 暂停时丢弃尚未发送的放置，避免恢复时用过期的朝向/物品放置
            ActionManager.INSTANCE.clearQueue();
        }
        wasPaused = paused;
        return paused;
    }

    public static boolean isPaused() {
        return wasPaused;
    }

    /** 玩家能看见的容器界面（打印机自己打开的潜影盒界面会被拦截不显示，因此不会算在内）。 */
    public static boolean isUserContainerScreenOpen() {
        return mc.screen instanceof AbstractContainerScreen<?>;
    }
}
