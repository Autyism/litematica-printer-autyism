package com.autyism.ale.utils;

import com.autyism.ale.config.Configs;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 独立实现的挖掘自动切换工具（不依赖 Tweakeroo）。
 * <p>
 * 思路参考 Tweakeroo 的 {@code InventoryUtils.trySwitchToEffectiveTool}：在整个背包里按“挖掘该方块的速度”挑选最优物品，
 * 在快捷栏则直接切过去，否则用 Litematica 的可拾取槽位规则换到手上。
 * <p>
 * 额外加入耐久保护：剩余耐久不高于阈值的工具不会被选中；若手上正拿着这种工具，会换成不消耗耐久的物品（空手/方块），
 * 实在没有可换的就拒绝挖掘，并在屏幕上提示，保证工具绝不会被打印机用坏。
 */
public final class ToolSwitchUtils {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final long WARN_INTERVAL_MS = 3000L;
    private static long lastWarnTime;

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

    /**
     * 挖掘前调用：按配置自动换到最合适的工具，并保证手上的工具不会被用坏。
     *
     * @return true 表示可以继续挖掘；false 表示为了保护工具本次不要挖
     */
    public static boolean prepareToolForBreaking(BlockPos pos) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return false;
        if (player.getAbilities().instabuild) return true;

        BlockState state = level.getBlockState(pos);
        if (Configs.Break.AUTO_TOOL_SWITCH.getBooleanValue()) {
            trySwitchToEffectiveTool(player, state);
        }

        ItemStack held = player.getMainHandItem();
        if (protectEnabled() && isNearlyBroken(held)) {
            warnLowDurability(held);
            // 换成一个不会掉耐久的物品再挖
            return switchAwayFromDamageable(player);
        }
        return true;
    }

    /** 在整个背包中挑选挖掘该方块最快的物品并拿到主手。 */
    public static boolean trySwitchToEffectiveTool(LocalPlayer player, BlockState state) {
        Inventory inventory = player.getInventory();
        ItemStack held = player.getMainHandItem();
        boolean protect = protectEnabled();

        // 因耐久不足而被放弃、但本来是更好选择的工具：用于屏幕提示
        ItemStack skipped = ItemStack.EMPTY;
        float skippedScore = -1F;
        float heldScore;
        if (protect && isNearlyBroken(held)) {
            heldScore = -1F;
            skipped = held;
            skippedScore = score(player, state, held);
        } else {
            heldScore = score(player, state, held);
        }
        int bestSlot = -1;
        float bestScore = heldScore;
        for (int slot = 0; slot < 36; slot++) {
            if (slot == inventory.getSelectedSlot()) continue;
            ItemStack stack = inventory.getItem(slot);
            float s = score(player, state, stack);
            if (protect && isNearlyBroken(stack)) {
                if (s > skippedScore) {
                    skipped = stack;
                    skippedScore = s;
                }
                continue;
            }
            // 只有明显更好才切换，避免来回抖动；同速时优先不消耗耐久的物品
            if (s > bestScore + 1.0E-4F
                    || (Math.abs(s - bestScore) <= 1.0E-4F && bestSlot == -1 && held.isDamageableItem() && !stack.isDamageableItem() && s > 0)) {
                bestScore = s;
                bestSlot = slot;
            }
        }
        if (!skipped.isEmpty() && skippedScore > bestScore + 1.0E-4F) {
            warnLowDurability(skipped);
        }
        if (bestSlot == -1) return false;
        return moveSlotToHand(player, bestSlot);
    }

    /**
     * 评分：每 tick 的挖掘进度；能正确掉落的工具额外加权，避免为了快几分用手挖掉矿物。
     */
    private static float score(LocalPlayer player, BlockState state, ItemStack stack) {
        float progress = PlayerUtils.getDestroyProgressWithStack(player, state, stack);
        boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        return correct ? progress + 1000F : progress;
    }

    private static boolean switchAwayFromDamageable(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        // 先找快捷栏里不会损耗的物品（空格最好）
        int best = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                best = slot;
                break;
            }
            if (!stack.isDamageableItem() && best == -1) best = slot;
        }
        if (best != -1) {
            InventoryUtils.setHotbarSlot(best, inventory);
            return true;
        }
        // 快捷栏全是可损坏物品：从背包换一个不会损耗的物品到当前槽位
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                return moveSlotToHand(player, slot) && !isNearlyBroken(player.getMainHandItem());
            }
        }
        return false;
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

    public static void warnLowDurability(ItemStack stack) {
        long now = System.currentTimeMillis();
        if (now - lastWarnTime < WARN_INTERVAL_MS) return;
        lastWarnTime = now;
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        InfoUtils.showGuiAndInGameMessage(Message.MessageType.WARNING, 3000,
                "autyism-le.message.tool_low_durability", stack.getHoverName().getString(), remaining);
    }
}
