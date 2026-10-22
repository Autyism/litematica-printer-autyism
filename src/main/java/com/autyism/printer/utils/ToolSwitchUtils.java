package com.autyism.printer.utils;

import com.autyism.printer.config.Configs;
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
    /** 挖掘包被拦下了，停下破基岩的其余动作留到打印机下一 tick 做（拦包时还在破基岩模组自己的循环里） */
    private static boolean bedrockHaltPending;

    private ToolSwitchUtils() {
    }

    /** 物品是否是“快坏了”的可损坏物品（剩余耐久 <= 阈值）。 */
    public static boolean isNearlyBroken(ItemStack stack) {
        return isNearlyBroken(stack, 0);
    }

    /**
     * 同上，再减去还没被服务端确认的磨损：急迫信标下大范围秒挖时，一次往返里可能已经挖了几十格，
     * 客户端看到的耐久比服务端多出这么多，只按客户端的数字判断会把工具用坏。
     */
    private static boolean isNearlyBroken(ItemStack stack, int unconfirmedWear) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return false;
        int remaining = stack.getMaxDamage() - stack.getDamageValue() - unconfirmedWear;
        return remaining <= Configs.Break.TOOL_DURABILITY_THRESHOLD.getIntegerValue();
    }

    /** 破基岩一格最多磨损几次镐（拆活塞，可能再拆一个支撑方块，留点余量） */
    private static final int BEDROCK_WEAR_PER_TARGET = 3;

    /**
     * 破基岩模式交下一格之前：按破基岩模组的选法找出它挖活塞会用的镐（手上的能秒破活塞就用手上的，
     * 否则背包里第一把能秒破的），快坏了就停下，不交这一格。
     *
     * @return true 表示可以继续
     */
    public static boolean checkBedrockTool(LocalPlayer player) {
        if (player == null || player.getAbilities().instabuild || !protectEnabled()) return true;
        if (halted) {
            warnHalted();
            return false;
        }
        BlockState piston = net.minecraft.world.level.block.Blocks.PISTON.defaultBlockState();
        ItemStack tool = player.getMainHandItem();
        int wear = heldWear(mc.level);
        if (PlayerUtils.getDestroyProgressWithStack(player, piston, tool) < 1.0F) {
            // 背包里第一把能秒破活塞的；一把都没有时（破基岩模组可以设成跳过秒破检查）按最快的算
            ItemStack best = ItemStack.EMPTY;
            float bestProgress = 0;
            tool = ItemStack.EMPTY;
            wear = 0;
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) continue;
                float progress = PlayerUtils.getDestroyProgressWithStack(player, piston, stack);
                if (progress >= 1.0F) {
                    tool = stack;
                    break;
                }
                if (progress > bestProgress) {
                    bestProgress = progress;
                    best = stack;
                }
            }
            if (tool.isEmpty()) tool = best;
        }
        if (isNearlyBroken(tool, wear + BEDROCK_WEAR_PER_TARGET)) {
            haltBedrock(player, tool);
            return false;
        }
        return true;
    }

    /**
     * 每个挖掘包发出之前（打印机的、破基岩模组的都走这里）：记下来给耐久保护算没确认的磨损；
     * 打印机的破基岩模式开着时，手上的工具快坏了就拦下这个包并停下（破基岩模组自己选镐，不看耐久）。
     *
     * @return true 表示拦下这个包
     */
    public static boolean guardBreakPacket(net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet) {
        var action = packet.getAction();
        if (action != net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && action != net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) return false;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return false;
        if (!player.getAbilities().instabuild && protectEnabled() && Configs.Core.WORK_SWITCH.getBooleanValue()
                && Configs.Bedrock.ENABLED.getBooleanValue()) {
            ItemStack hand = player.getMainHandItem();
            // 破基岩模组闲着时这是玩家自己在挖，不管
            if (hand.isDamageableItem() && isNearlyBroken(hand, heldWear(level))
                    && !com.autyism.printer.compat.BedrockCompat.isIdle()) {
                if (!halted) markHalted(hand);
                bedrockHaltPending = true;
                return true;
            }
        }
        com.autyism.printer.printer.ActionConfirm.breakSent(level, packet.getPos(), packet.getSequence());
        // 手上这格正用来挖：从背包换物品进快捷栏时先不换掉它（见 InventoryUtils.pickTargetSlots）
        InventoryUtils.noteHotbarSlotUsed(InventoryUtils.getSelectedSlot(player.getInventory()));
        return false;
    }

    /** 停下破基岩：清掉破基岩模组手上的活，打印机的挖掘也停（和平时的耐久保护一样，重开打印机才恢复） */
    private static void haltBedrock(LocalPlayer player, ItemStack tool) {
        com.autyism.printer.compat.BedrockCompat.clearTasks();
        halt(player, tool);
    }

    /** 打印机每 tick 开头调用：做完拦包时留下的停止动作 */
    public static void tick() {
        if (!bedrockHaltPending) return;
        bedrockHaltPending = false;
        LocalPlayer player = mc.player;
        if (player == null || !halted) return;
        com.autyism.printer.compat.BedrockCompat.clearTasks();
        stopAndSecure(player);
    }

    /** 手上这把工具还没被确认的磨损（只算手上的：换上来的工具没挖过这些格子） */
    private static int heldWear(ClientLevel level) {
        return com.autyism.printer.printer.ActionConfirm.breaksInFlight(level);
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
        bedrockHaltPending = false;
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
            if (protectEnabled() && isNearlyBroken(tool, best == -1 ? heldWear(level) : 0)) {
                halt(player, tool);
                return false;
            }
            //? if <1.21.5 {
            /*if (best != -1 && best != player.getInventory().selected) {
            *///?} else
            if (best != -1 && best != player.getInventory().getSelectedSlot()) {
                moveSlotToHand(player, best);
            }
        } else {
            tool = player.getMainHandItem();
            if (protectEnabled() && isNearlyBroken(tool, heldWear(level))) {
                halt(player, tool);
                return false;
            }
        }
        return true;
    }

    private static void halt(LocalPlayer player, ItemStack tool) {
        markHalted(tool);
        stopAndSecure(player);
    }

    private static void markHalted(ItemStack tool) {
        halted = true;
        haltedToolName = tool.getHoverName().getString();
        haltedRemaining = tool.getMaxDamage() - tool.getDamageValue();
        lastWarnTime = 0;
    }

    private static void stopAndSecure(LocalPlayer player) {
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
            //? if <1.21.5 {
            /*if (slot == inventory.selected) continue;
            *///?} else
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
            //? if <1.21.5 {
            /*mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, inventory.selected,
            *///?} else
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
                "litematica-printer-autyism.message.tool_low_durability", haltedToolName, haltedRemaining);
    }
}
