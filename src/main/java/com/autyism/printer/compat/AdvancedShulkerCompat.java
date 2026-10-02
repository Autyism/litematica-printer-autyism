package com.autyism.printer.compat;

import com.autyism.printer.printer.ContainerGuard;
import com.autyism.printer.utils.InventoryUtils;
import com.autyism.printer.utils.ModUtils;
import com.autyism.printer.utils.ShulkerContentUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 需求 7：Advanced Shulkerboxes（mod id: shulkerbox，Max Henkel）联动。
 * <p>
 * 该模组在服务端拦截“对空气使用物品”（Item#use）：手上拿着单个潜影盒时打开潜影盒界面。
 * 因此这里直接调用 {@code MultiPlayerGameMode#useItem}（只发 UseItem 包，不是对方块右键），
 * 不会误触旁边可交互的方块。流程：把潜影盒拿到主手 → 下一 tick 确认后使用 → 界面被打印机拦截不显示，
 * 由 {@code QuickShulkerUtils#switchFromShulker} 在收到容器内容后完成取物/回塞并关闭。
 */
public final class AdvancedShulkerCompat {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final int MAX_WAIT_TICKS = 10;

    private static int pendingSlot = -1;
    private static List<ItemStack> pendingContents = List.of();
    private static int waitTicks;
    private static Runnable onFail = () -> {
    };

    private AdvancedShulkerCompat() {
    }

    public static boolean isLoaded() {
        return ModUtils.isLoadMod("shulkerbox");
    }

    public static boolean isPending() {
        return pendingSlot != -1;
    }

    /**
     * 开始打开背包某格里的潜影盒。
     *
     * @param failure 拿不到手上（例如被服务端拒绝）时的回调
     */
    public static void open(int inventorySlot, Runnable failure) {
        LocalPlayer player = mc.player;
        if (player == null) {
            failure.run();
            return;
        }
        Inventory inv = player.getInventory();
        ItemStack stack = inv.getItem(inventorySlot);
        pendingContents = ShulkerContentUtils.itemContents(stack);
        pendingSlot = inventorySlot;
        waitTicks = 0;
        onFail = failure;
        if (Inventory.isHotbarSlot(inventorySlot)) {
            InventoryUtils.setHotbarSlot(inventorySlot, inv);
        } else {
            InventoryUtils.setPickedItemToHand(inventorySlot, stack, mc);
        }
        tick();
    }

    /** 每 tick 调用：潜影盒到手后使用它 */
    public static void tick() {
        if (pendingSlot == -1) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            cancel(true);
            return;
        }
        ItemStack hand = player.getMainHandItem();
        if (ShulkerContentUtils.isShulkerItem(hand) && hand.getCount() == 1
                && ShulkerContentUtils.sameContents(ShulkerContentUtils.itemContents(hand), pendingContents)) {
            pendingSlot = -1;
            ContainerGuard.beginPrinterInteraction();
            try {
                mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            } finally {
                ContainerGuard.endPrinterInteraction();
            }
            return;
        }
        if (++waitTicks > MAX_WAIT_TICKS) {
            cancel(true);
        }
    }

    public static void cancel(boolean notify) {
        if (pendingSlot == -1) return;
        pendingSlot = -1;
        if (notify) onFail.run();
    }
}
