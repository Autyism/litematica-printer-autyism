package com.autyism.ale.utils;

import com.autyism.ale.config.Configs;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 独立实现的挖掘自动切换工具（不依赖 Tweakeroo）。
 * <p>
 * 思路参考 Tweakeroo 的 {@code InventoryUtils.trySwitchToEffectiveTool}：在整个背包里按“挖掘该方块的速度”挑选最优物品，
 * 在快捷栏则直接切过去，否则用 Litematica 的可拾取槽位规则换到手上。
 * <p>
 * 耐久保护：如果要用的工具（自动挑选出的最佳工具，或关闭自动切换时手上的工具）剩余耐久不高于阈值，
 * 不会改用别的工具凑合，而是直接停止打印机的所有挖掘，把手上换成不会损坏的物品（优先方块），并在屏幕上提示。
 * 修理/更换工具后，关闭再打开打印机即可继续。
 */
public final class ToolSwitchUtils {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final long WARN_INTERVAL_MS = 4000L;
    private static long lastWarnTime;
    /** 已因工具耐久不足而停止挖掘 */
    private static boolean halted;
    private static String haltedToolName = "";
    private static int haltedRemaining;

    private ToolSwitchUtils() {
    }

    /** 物品是否是“快坏了”的可损坏物品（剩余耐久 <= 阈值）。 */
    public static boolean isNearlyBroken(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return false;
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        return remaining <= Configs.Break.TOOL_DURABILITY_THRESHOLD.getIntegerValue();
    }

    private static boolean protectEnabled() {
        return Configs.Break.TOOL_DURABILITY_PROTECT.getBooleanValue();
    }

    public static boolean isHalted() {
        return halted;
    }

    /** 打印机重新开启时调用：解除停止状态 */
    public static void resetHalt() {
        halted = false;
    }

    /**
     * 挖掘前调用：按配置自动换到最合适的工具，并保证工具不会被用坏。
     *
     * @return true 表示可以继续挖掘；false 表示已停止挖掘
     */
    public static boolean prepareToolForBreaking(BlockPos pos) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return false;
        if (player.getAbilities().instabuild) return true;

        if (halted) {
            ensureSafeItemInHand(player);
            warnHalted();
            return false;
        }

        BlockState state = level.getBlockState(pos);
        ItemStack tool;
        if (Configs.Break.AUTO_TOOL_SWITCH.getBooleanValue()) {
            int best = findBestSlot(player, state);
            tool = best == -1 ? player.getMainHandItem() : player.getInventory().getItem(best);
            if (protectEnabled() && isNearlyBroken(tool)) {
                halt(player, tool);
                return false;
            }
            if (best != -1 && best != player.getInventory().getSelectedSlot()) {
                moveSlotToHand(player, best);
            }
        } else {
            tool = player.getMainHandItem();
            if (protectEnabled() && isNearlyBroken(tool)) {
                halt(player, tool);
                return false;
            }
        }
        return true;
    }

    private static void halt(LocalPlayer player, ItemStack tool) {
        halted = true;
        haltedToolName = tool.getHoverName().getString();
        haltedRemaining = tool.getMaxDamage() - tool.getDamageValue();
        lastWarnTime = 0;
        // 停止当前正在进行的挖掘
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        ensureSafeItemInHand(player);
        warnHalted();
    }

    /** 保险起见：手上若是可损坏的物品，换成不会损坏的物品（优先方块，其次其他物品/空手） */
    private static void ensureSafeItemInHand(LocalPlayer player) {
        if (!player.getMainHandItem().isDamageableItem()) return;
        Inventory inventory = player.getInventory();
        int blockSlot = -1, otherSlot = -1, emptySlot = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                if (emptySlot == -1 || (Inventory.isHotbarSlot(slot) && !Inventory.isHotbarSlot(emptySlot))) emptySlot = slot;
            } else if (!stack.isDamageableItem()) {
                if (stack.getItem() instanceof BlockItem) {
                    if (blockSlot == -1 || (Inventory.isHotbarSlot(slot) && !Inventory.isHotbarSlot(blockSlot))) blockSlot = slot;
                } else if (otherSlot == -1 || (Inventory.isHotbarSlot(slot) && !Inventory.isHotbarSlot(otherSlot))) {
                    otherSlot = slot;
                }
            }
        }
        int target = blockSlot != -1 ? blockSlot : otherSlot != -1 ? otherSlot : emptySlot;
        if (target != -1) moveSlotToHand(player, target);
    }

    /** 在整个背包中挑选挖掘该方块最快的物品，返回槽位（-1 = 手上的已经最好） */
    private static int findBestSlot(LocalPlayer player, BlockState state) {
        Inventory inventory = player.getInventory();
        ItemStack held = player.getMainHandItem();
        float bestScore = score(player, state, held);
        int bestSlot = -1;
        for (int slot = 0; slot < 36; slot++) {
            if (slot == inventory.getSelectedSlot()) continue;
            ItemStack stack = inventory.getItem(slot);
            float s = score(player, state, stack);
            // 只有明显更好才切换，避免来回抖动；同速时优先不消耗耐久的物品
            if (s > bestScore + 1.0E-4F
                    || (Math.abs(s - bestScore) <= 1.0E-4F && bestSlot == -1 && held.isDamageableItem() && !stack.isDamageableItem() && s > 0)) {
                bestScore = s;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /** 在整个背包中挑选挖掘该方块最快的物品并拿到主手（不做耐久判断）。 */
    public static boolean trySwitchToEffectiveTool(LocalPlayer player, BlockState state) {
        int best = findBestSlot(player, state);
        return best != -1 && moveSlotToHand(player, best);
    }

    /**
     * 评分：每 tick 的挖掘进度；能正确掉落的工具额外加权，避免为了快几分用手挖掉矿物。
     */
    private static float score(LocalPlayer player, BlockState state, ItemStack stack) {
        float progress = PlayerUtils.getDestroyProgressWithStack(player, state, stack);
        boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        return correct ? progress + 1000F : progress;
    }

    private static boolean moveSlotToHand(LocalPlayer player, int slot) {
        Inventory inventory = player.getInventory();
        if (Inventory.isHotbarSlot(slot)) {
            InventoryUtils.setHotbarSlot(slot, inventory);
            return true;
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            // 背包里的空格：把当前手上的东西放进去
            if (mc.gameMode == null) return false;
            mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, inventory.getSelectedSlot(),
                    net.minecraft.world.inventory.ClickType.SWAP, player);
            return true;
        }
        return InventoryUtils.setPickedItemToHand(slot, stack, mc);
    }

    /** 最近一次耐久警告的时间（测试/调试用） */
    public static long getLastWarnTime() {
        return lastWarnTime;
    }

    private static void warnHalted() {
        long now = System.currentTimeMillis();
        if (now - lastWarnTime < WARN_INTERVAL_MS) return;
        lastWarnTime = now;
        InfoUtils.showGuiAndInGameMessage(Message.MessageType.WARNING, 4000,
                "autyism-le.message.tool_low_durability", haltedToolName, haltedRemaining);
    }
}
