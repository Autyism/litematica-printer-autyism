package com.autyism.printer.utils;

import com.google.common.collect.Lists;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import com.autyism.printer.config.Configs;
import com.autyism.printer.mixin.printer.litematica.EasyPlaceUtilsAccessor;
import com.autyism.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

//? if >=1.21.5
import net.minecraft.network.HashedStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryUtils {
    private static final Minecraft client = Minecraft.getInstance();
    private static final int OFFHAND_SLOT_INDEX = 40;
    private static final long MESSAGE_COOLDOWN_MS = 5000L;
    private static final Map<String, Long> LAST_MESSAGE_SEND_TIME = new ConcurrentHashMap<>();
    @Getter
    @Setter
    private static ItemStack orderlyStoreItem;



    // 需求 8：额外的物品筛选（例如潜影盒内容物必须与投影一致）；为 null 时只按物品种类匹配
    @org.jetbrains.annotations.Nullable
    private static java.util.function.Predicate<ItemStack> stackFilter;
    // 创造模式下直接生成的物品（例如带内容物的潜影盒）
    @org.jetbrains.annotations.Nullable
    private static ItemStack creativeOverride;
    // 最近放置用过的物品（智能回塞时不把它们放回潜影盒）
    private static final java.util.LinkedHashSet<Item> RECENTLY_USED = new java.util.LinkedHashSet<>();

    public static void setStackFilter(@org.jetbrains.annotations.Nullable java.util.function.Predicate<ItemStack> filter,
                                      @org.jetbrains.annotations.Nullable ItemStack creativeStack) {
        stackFilter = filter;
        creativeOverride = creativeStack;
    }

    public static void clearStackFilter() {
        stackFilter = null;
        creativeOverride = null;
    }

    private static boolean passesFilter(ItemStack stack) {
        return stackFilter == null || stackFilter.test(stack);
    }

    /** 换世界 / 重新开启打印机时清空 */
    public static void clearRecentlyUsed() {
        RECENTLY_USED.clear();
        java.util.Arrays.fill(HOTBAR_LAST_USE, 0L);
    }

    /** 快捷栏每一格最近一次被打印机用来放东西（或者刚换进物品）的先后，数大的是最近的 */
    private static final long[] HOTBAR_LAST_USE = new long[9];
    private static long hotbarUseCounter;

    /** 打印机刚用快捷栏第 slot 格放了东西，或者刚往这一格换进了物品 */
    public static void noteHotbarSlotUsed(int slot) {
        if (slot >= 0 && slot < 9) HOTBAR_LAST_USE[slot] = ++hotbarUseCounter;
    }

    /**
     * 打印机从背包换物品进快捷栏时能用的格子，按先用哪格排好：Litematica 设置里允许换进方块的格子
     * （pickBlockableSlots；装着工具、会掉耐久的东西时按 Litematica 的设置跳过），空格子先用，
     * 其余的先换掉打印机最久没用过的那格。以前按 Litematica 的轮流顺序换，一层里一直要用的方块（例如石头）
     * 也会轮到被换走，下次用到又得换回来。keepSelected：不动手上正拿着的那格
     */
    public static List<Integer> pickTargetSlots(Player player, boolean keepSelected) {
        Inventory inventory = player.getInventory();
        int selected = getSelectedSlot(inventory);
        List<Integer> empty = new java.util.ArrayList<>();
        List<Integer> used = new java.util.ArrayList<>();
        for (int slot : InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS()) {
            if (slot < 0 || slot > 8 || (keepSelected && slot == selected)) continue;
            if (!InventoryUtilsAccessor.canPickToSlot(inventory, slot)) continue;
            if (inventory.getItem(slot).isEmpty()) empty.add(slot);
            else used.add(slot);
        }
        used.sort(java.util.Comparator.comparingLong(s -> HOTBAR_LAST_USE[s]));
        empty.addAll(used);
        return empty;
    }

    public static void markRecentlyUsed(Item item) {
        RECENTLY_USED.remove(item);
        RECENTLY_USED.add(item);
        while (RECENTLY_USED.size() > 12) {
            RECENTLY_USED.remove(RECENTLY_USED.iterator().next());
        }
    }

    public static boolean isRecentlyUsed(Item item) {
        return RECENTLY_USED.contains(item);
    }

    public static int getSelectedSlot(Inventory inventory) {
        //? if <1.21.5 {
        /*return inventory.selected;
        *///?} else
        return inventory.getSelectedSlot();
    }

    public static void setSelectedSlot(Inventory inventory, int slot) {
        //? if <1.21.5 {
        /*inventory.selected = slot;
        *///?} else
        inventory.setSelectedSlot(slot);
    }

    public static NonNullList<ItemStack> getMainStacks(Inventory inventory) {
        //? if <1.21.5 {
        /*return inventory.items;
        *///?} else
        return inventory.getNonEquipmentItems();
    }

    public static boolean playerHasAccessToItem(LocalPlayer playerEntity, Item item) {
        return playerHasAccessToItems(playerEntity, item);
    }

    public static boolean playerHasAccessToItems(LocalPlayer playerEntity, Item... items) {
        if (items == null || items.length == 0) return true;
        if (PlayerUtils.getAbilities(playerEntity).mayBuild) return true;
        if (!playerEntity.containerMenu.equals(playerEntity.inventoryMenu)) return false;
        Inventory inventory = playerEntity.getInventory();
        for (Item item : items) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (inventory.getItem(i).getItem() == item) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean setPickedItemToHand(ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        int slotNum = mc.player.getInventory().findSlotMatchingItem(stack);
        return setPickedItemToHand(slotNum, stack, mc);
    }

    public static void setHotbarSlot(int slot, Inventory inventory) {
        boolean usePacket = Configs.Placement.PRINT_USE_PACKET.getBooleanValue();
        if (usePacket) {
            client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
        setSelectedSlot(inventory, slot);
    }

    /**
     * 检查是否有可用的 Pick 槽位
     *
     * @param sourceSlot 源槽位（-1表示自动寻找）
     * @param mc         Minecraft实例
     * @return PickResult 枚举结果
     */
    public static PickResult checkPickSlotAvailable(int sourceSlot, Minecraft mc) {
        // 基础校验失败 → 返回通用FAIL
        if (mc.player == null) return PickResult.FAIL;
        Player player = mc.player;
        Inventory inventory = player.getInventory();
        // 源槽位是快捷栏 → 成功
        if (Inventory.isHotbarSlot(sourceSlot)) return PickResult.SUCCESS;
        // 无配置可拾取槽位 → 精准失败类型
        if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
            return PickResult.FAIL_NO_PICK_SLOTS_CONFIGURED;
        }
        // 寻找可用槽位（和真正换的时候挑法一样，见 pickTargetSlots）
        // 无可用槽位 → 精准失败类型；否则成功
        return !pickTargetSlots(player, false).isEmpty() ? PickResult.SUCCESS : PickResult.FAIL_NO_SUITABLE_SLOT_FOUND;
    }

    public static boolean setPickedItemToHand(int sourceSlot, ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        Player player = mc.player;
        Inventory inventory = player.getInventory();
        // 目标物品在热键栏中
        if (Inventory.isHotbarSlot(sourceSlot)) {
            setHotbarSlot(sourceSlot, inventory);
            return true;
        }
        if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
            showMessageWithCooldown(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_valid_slots_configured");
            return false;
        }
        // 换进哪一格：空格子先用，没有空的就换掉打印机最久没用过的那格（见 pickTargetSlots）
        List<Integer> targets = pickTargetSlots(player, false);
        int hotbarSlot = targets.isEmpty() ? -1 : targets.get(0);
        if (hotbarSlot != -1) {
            setHotbarSlot(hotbarSlot, inventory);
            noteHotbarSlotUsed(hotbarSlot);
            if (EntityUtils.isCreativeMode(player)) {
                getMainStacks(inventory).set(hotbarSlot, stack.copy());
                client.gameMode.handleCreativeModeItemAdd(client.player.getMainHandItem(), 36 + hotbarSlot);
                return true;
            }
            EasyPlaceUtilsAccessor.callSetEasyPlaceLastPickBlockTime();
            return swapItemToMainHand(stack.copy(), mc);
        } else {
            showMessageWithCooldown(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_suitable_slot_found");
            return false;
        }
    }

    /**
     * 让服务端把整个背包重发一次：一个什么都不做的点击（点在界面外、光标是空的），带一个对不上的同步编号；
     * 原版服务端发现编号对不上，处理完就把完整的背包发回来。用来清掉客户端以为还有、服务端早就没有的“幽灵”物品
     */
    public static void requestInventoryResync(LocalPlayer player) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) return;
        //? if <1.21.5 {
        /*connection.send(new ServerboundContainerClickPacket(player.inventoryMenu.containerId, -1, -999, 0, ClickType.PICKUP,
                ItemStack.EMPTY, new Int2ObjectOpenHashMap<>()));
        *///?} else
        connection.send(new ServerboundContainerClickPacket(player.inventoryMenu.containerId, -1, Shorts.checkedCast(-999), SignedBytes.checkedCast(0),
                ClickType.PICKUP, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
    }

    public static boolean swapItemToMainHand(ItemStack stackReference, Minecraft mc) {
        Player player = mc.player;
        if (player == null) return false;

        //? if <1.21 {
        /*boolean b = ItemStack.isSameItem(stackReference, player.getMainHandItem());
        *///?} else
        boolean b = fi.dy.masa.malilib.util.InventoryUtils.areStacksEqualIgnoreNbt(stackReference, player.getMainHandItem());
        if (b) {
            return false;
        }

        int slot = fi.dy.masa.malilib.util.InventoryUtils.findSlotWithItem(player.inventoryMenu, stackReference, true);
        if (slot != -1) {
            return swapIntoHotbar(player, slot, getSelectedSlot(player.getInventory()));
        }
        return false;
    }

    /**
     * 背包菜单里的第 slot 格和快捷栏第 hotbarSlot 格对换（不改手上选中的格子）。
     * 和按数字键把鼠标下的物品换到快捷栏一样，客户端马上改好，服务端按顺序照做
     */
    public static boolean swapIntoHotbar(Player player, int slot, int hotbarSlot) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            return false;
        }
        if (Configs.Placement.PRINT_USE_PACKET.getBooleanValue()) {
            NonNullList<Slot> slots = player.inventoryMenu.slots;
            int totalSlots = slots.size();
            List<ItemStack> copies = Lists.newArrayListWithCapacity(totalSlots);
            for (Slot slotItem : slots) {
                copies.add(slotItem.getItem().copy());
            }
            // 和原版一样：先在客户端换好，再把换过以后变了的格子一起发给服务端。以前先算再换，发过去的“变了的格子”
            // 总是空的，服务端每次都要把这几格重发一遍、同步编号跟着变，同一 tick 里接着换就会对不上
            player.inventoryMenu.clicked(slot, hotbarSlot, ClickType.SWAP, player);

            //? if <1.21.5 {
            /*Int2ObjectMap<ItemStack> snapshot = new Int2ObjectOpenHashMap<>();
            *///?} else
            Int2ObjectMap<HashedStack> snapshot = new Int2ObjectOpenHashMap<>();

            for (int j = 0; j < totalSlots; j++) {
                ItemStack original = copies.get(j);
                ItemStack current = slots.get(j).getItem();
                if (!ItemStack.matches(original, current)) {
                    //? if <1.21.5 {
                    /*snapshot.put(j, current.copy());
                    *///?} else
                    snapshot.put(j, HashedStack.create(current, connection.decoratedHashOpsGenenerator()));
                }
            }

            //? if <1.21.5 {
            /*connection.send(new ServerboundContainerClickPacket(
                    player.inventoryMenu.containerId,
                    player.inventoryMenu.getStateId(),
                    slot,
                    hotbarSlot,
                    ClickType.SWAP,
                    player.inventoryMenu.getCarried().copy(),
                    snapshot
            ));
            *///?} else {
            HashedStack hashedStack = HashedStack.create(player.inventoryMenu.getCarried(), connection.decoratedHashOpsGenenerator());
            connection.send(new ServerboundContainerClickPacket(
                    player.inventoryMenu.containerId,
                    player.inventoryMenu.getStateId(),
                    Shorts.checkedCast(slot),
                    SignedBytes.checkedCast(hotbarSlot),
                    ClickType.SWAP,
                    snapshot,
                    hashedStack
            ));
            //?}
        } else {
            if (client.gameMode != null) {
                client.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, hotbarSlot, ClickType.SWAP, player);
            }
        }
        return true;
    }

    /**
     * 获取玩家副手的物品栈（全版本通用，极简实现）
     *
     * @param player 玩家实例
     * @return 副手物品栈
     */
    public static ItemStack getOffhandStack(Player player) {
        // 直接通过固定槽位40获取，无版本专属字段依赖
        return player.getInventory().getItem(OFFHAND_SLOT_INDEX);
    }

    /**
     * 将指定物品切换/设置到副手（核心方法，无选中格子逻辑）
     *
     * @param stack 要放到副手的物品栈
     * @param mc    Minecraft实例
     * @return 是否切换成功
     */
    public static boolean setItemToOffhand(ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        Player player = mc.player;

        // 1. 检查副手已有该物品，直接返回成功（避免重复操作）
        boolean isAlreadyInOffhand = fi.dy.masa.malilib.util.InventoryUtils.areStacksEqual(stack, getOffhandStack(player));
        if (isAlreadyInOffhand) {
            return true;
        }

        // 2. 创造模式：直接设置副手物品（无需交换）
        if (EntityUtils.isCreativeMode(player)) {
            player.getInventory().setItem(OFFHAND_SLOT_INDEX, stack.copy());
            client.gameMode.handleCreativeModeItemAdd(getOffhandStack(player), OFFHAND_SLOT_INDEX);
            return true;
        }

        // 3. 生存模式：找到物品所在槽位，交换到副手
        int sourceSlot = fi.dy.masa.malilib.util.InventoryUtils.findSlotWithItem(player.inventoryMenu, stack, true);
        if (sourceSlot == -1) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_suitable_slot_found");
            return false;
        }

        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;

        // 4. 发送交换数据包（复用原有配置，目标槽位固定为40）
        if (Configs.Placement.PRINT_USE_PACKET.getBooleanValue()) {
            NonNullList<Slot> slots = player.inventoryMenu.slots;
            int totalSlots = slots.size();
            List<ItemStack> copies = Lists.newArrayListWithCapacity(totalSlots);
            for (Slot slotItem : slots) {
                copies.add(slotItem.getItem().copy());
            }
            // 和原版一样：先在客户端换好，再把换过以后变了的格子一起发给服务端。以前先算再换，发过去的“变了的格子”
            // 总是空的，服务端每次都要把这几格重发一遍、同步编号跟着变，同一 tick 里接着换就会对不上
            player.inventoryMenu.clicked(sourceSlot, OFFHAND_SLOT_INDEX, ClickType.SWAP, player);

            // 版本兼容的快照对象
            //? if <1.21.5 {
            /*Int2ObjectMap<ItemStack> snapshot = new Int2ObjectOpenHashMap<>();
            *///?} else
            Int2ObjectMap<HashedStack> snapshot = new Int2ObjectOpenHashMap<>();

            // 构建库存快照
            for (int j = 0; j < totalSlots; j++) {
                ItemStack original = copies.get(j);
                ItemStack current = slots.get(j).getItem();
                if (!ItemStack.matches(original, current)) {
                    //? if <1.21.5 {
                    /*snapshot.put(j, current.copy());
                    *///?} else
                    snapshot.put(j, HashedStack.create(current, connection.decoratedHashOpsGenenerator()));
                }
            }

            // 发送SWAP数据包到副手槽位40
            //? if <1.21.5 {
            /*connection.send(new ServerboundContainerClickPacket(
                    player.inventoryMenu.containerId,
                    player.inventoryMenu.getStateId(),
                    sourceSlot,
                    OFFHAND_SLOT_INDEX, // 目标：副手槽位40
                    ClickType.SWAP,
                    player.inventoryMenu.getCarried().copy(),
                    snapshot
            ));
            *///?} else {
            HashedStack hashedStack = HashedStack.create(player.inventoryMenu.getCarried(), connection.decoratedHashOpsGenenerator());
            connection.send(new ServerboundContainerClickPacket(
                    player.inventoryMenu.containerId,
                    player.inventoryMenu.getStateId(),
                    Shorts.checkedCast(sourceSlot),
                    SignedBytes.checkedCast(OFFHAND_SLOT_INDEX), // 目标：副手槽位40
                    ClickType.SWAP,
                    snapshot,
                    hashedStack
            ));
            //?}
        } else {
            // 不使用数据包：本地直接交换到副手
            if (client.gameMode != null) {
                client.gameMode.handleInventoryMouseClick(
                        player.inventoryMenu.containerId,
                        sourceSlot,
                        OFFHAND_SLOT_INDEX, // 目标：副手槽位40
                        ClickType.SWAP,
                        player
                );
            }
        }
        return true;
    }


    // ========== 新增：副手核心方法（无选中格子逻辑） ==========

    private static void showMessageWithCooldown(Message.MessageType type, String messageKey) {
        long currentTime = System.currentTimeMillis();
        // 核心修改：通过消息Key获取最后发送时间，而非消息类型
        long lastSendTime = LAST_MESSAGE_SEND_TIME.getOrDefault(messageKey, 0L);

        // 未超过冷却时间，直接返回不发送
        if (currentTime - lastSendTime < MESSAGE_COOLDOWN_MS) {
            return;
        }

        // 超过冷却时间，发送消息并更新【该Key】的最后发送时间
        InfoUtils.showGuiOrInGameMessage(type, messageKey);
        LAST_MESSAGE_SEND_TIME.put(messageKey, currentTime);
    }

    public static boolean switchToItems(LocalPlayer player, Item[] items) {
        if (items == null || items.length == 0) {
            return true;
        }
        Inventory inventory = player.getInventory();
        boolean isCreativeMode = PlayerUtils.getAbilities(player).instabuild;
        if (isCreativeMode) {
            ItemStack stack = new ItemStack(items[0]);
            return InventoryUtils.setPickedItemToHand(stack, client);
        }
        // 查找物品在背包中的槽位
        for (Item item : items) {
            int slot = findItemInInventory(inventory, item);
            if (slot != -1) {
                ItemStack itemStack = inventory.getItem(slot);
                orderlyStoreItem = itemStack.copy();
                // 找到，切换到主手
                return InventoryUtils.setPickedItemToHand(slot, itemStack, client);
            }
        }
        // 没找到，尝试使用快捷潜影盒
        //
        QuickShulkerUtils.requestShulkerItem(player, items);
        return false;
    }

    /**
     * 打印前准备主手物品的结果。
     * READY：主手已持有所需物品，可以立即放置；
     * WAITING：已发出背包交换或潜影盒取物请求，调用方应保留该坐标，下一 tick 复核主手后再放置；
     * UNAVAILABLE：背包与潜影盒中都没有所需物品。
     */
    public enum ItemSwitchResult {
        READY,
        WAITING,
        UNAVAILABLE
    }

    /**
     * 与 {@link #switchToItems} 相同的取物流程，但放置前确认主手确实是所需物品（移植自四改）。
     * <p>
     * 热键栏切换只改选中槽位，服务端会先收到 SetCarriedItem 再收到 UseItemOn，可在同一 tick 放置；
     * 从背包交换到热键栏则需要等一 tick，避免交换与放置挤在同一 tick 被服务端按旧物品处理（放错方块）。
     */
    public static ItemSwitchResult switchToItemsResult(LocalPlayer player, Item[] items) {
        if (items == null || items.length == 0) {
            return ItemSwitchResult.READY;
        }
        // 正在从潜影盒取物（开盒到关盒）：先不放。盒子开着时服务端把手上数量的变化发给盒子的界面，
        // 客户端关盒以后才到的那几条会被丢掉，可放置的确认照样回来，打印机就以为数量已经对上，
        // 拿着服务端早就没有的一组接着放（幽灵物品：看着还有，放下去什么都没有）
        if (QuickShulkerUtils.isBusy() && !PlayerUtils.getAbilities(player).instabuild) {
            com.autyism.printer.printer.TickTrace.detail("item-wait: shulker box open");
            return ItemSwitchResult.WAITING;
        }
        ItemStack mainHand = player.getMainHandItem();
        Inventory inventory = player.getInventory();
        for (Item item : items) {
            if (mainHand.is(item) && passesFilter(mainHand)) {
                if (stackMayBeUsedUp(player, mainHand)) {
                    // 手上这一组可能已经用完了（服务端还没算到的几次）：快捷栏里还有一组同样的就切过去，不用停下来等
                    int other = usableHotbarStack(player, inventory, items);
                    if (other != -1) {
                        setHotbarSlot(other, inventory);
                        return ItemSwitchResult.READY;
                    }
                    traceUsedUp(player, mainHand);
                    return ItemSwitchResult.WAITING;
                }
                return ItemSwitchResult.READY;
            }
        }
        if (PlayerUtils.getAbilities(player).instabuild) {
            ItemStack wanted = creativeOverride != null ? creativeOverride.copy() : new ItemStack(items[0]);
            InventoryUtils.setPickedItemToHand(wanted, client);
            return player.getMainHandItem().is(items[0]) && passesFilter(player.getMainHandItem())
                    ? ItemSwitchResult.READY : ItemSwitchResult.UNAVAILABLE;
        }
        for (Item item : items) {
            int slot = findItemInInventory(inventory, item);
            if (slot == -1) continue;
            if (Inventory.isHotbarSlot(slot)) {
                // 快捷栏里有几组：挑一组不会已经用完的（找到的第一组可能是刚用完、服务端还没算到的那一组）
                int usable = usableHotbarStack(player, inventory, new Item[]{item});
                if (usable != -1) slot = usable;
            }
            ItemStack itemStack = inventory.getItem(slot);
            orderlyStoreItem = itemStack.copy();
            if (Inventory.isHotbarSlot(slot)) {
                setHotbarSlot(slot, inventory);
                if (!player.getMainHandItem().is(item) || !passesFilter(player.getMainHandItem())) return ItemSwitchResult.UNAVAILABLE;
                if (stackMayBeUsedUp(player, player.getMainHandItem())) {
                    traceUsedUp(player, player.getMainHandItem());
                    return ItemSwitchResult.WAITING;
                }
                return ItemSwitchResult.READY;
            }
            // 打开着其他容器（如快捷潜影盒延迟关闭）时不能对玩家背包做交换
            if (player.containerMenu != player.inventoryMenu) {
                com.autyism.printer.printer.TickTrace.detail("item-wait: another container is open");
                return ItemSwitchResult.WAITING;
            }
            // 有延迟时：之前的动作还没被服务端确认、或上一次交换还没被处理，先等（见 ActionConfirm.readyForInventoryClick）
            int sequenceNow = client.level instanceof PacketUtils.SequenceExtension seq ? seq.litematica_printer3$currentSequence() : 0;
            if (!com.autyism.printer.printer.ActionConfirm.readyForInventoryClick(client.level, sequenceNow, player.tickCount)) {
                com.autyism.printer.printer.TickTrace.detail("item-wait: swap waits for confirmations");
                return ItemSwitchResult.WAITING;
            }
            boolean switched = InventoryUtils.setPickedItemToHand(slot, itemStack, client);
            if (switched) com.autyism.printer.printer.ActionConfirm.noteInventoryClick(client.level, sequenceNow, player.tickCount);
            if (Boolean.getBoolean("ale.debuglook")) {
                StringBuilder sb = new StringBuilder("[printer-swap] from=" + slot + " item=" + item + " switched=" + switched
                        //? if <1.21.5 {
                        /*+ " selected=" + inventory.selected + " stateId=" + player.inventoryMenu.getStateId() + " tick=" + player.tickCount + " hotbar=");
                        *///?} else
                        + " selected=" + inventory.getSelectedSlot() + " stateId=" + player.inventoryMenu.getStateId() + " tick=" + player.tickCount + " hotbar=");
                for (int i = 0; i < 9; i++) {
                    ItemStack h = inventory.getItem(i);
                    sb.append(i).append(':').append(h.isEmpty() ? "-" : h.getItem().toString().replace("minecraft:", "") + "x" + h.getCount()).append(' ');
                }
                System.out.println(sb);
            }
            com.autyism.printer.printer.TickTrace.detail("item-wait: swapped from the inventory");
            return switched || player.getMainHandItem().is(item) ? ItemSwitchResult.WAITING : ItemSwitchResult.UNAVAILABLE;
        }
        boolean requestStarted = QuickShulkerUtils.requestShulkerItem(player, items);
        if (requestStarted || QuickShulkerUtils.isBusy()) {
            com.autyism.printer.printer.TickTrace.detail("item-wait: shulker box");
            return ItemSwitchResult.WAITING;
        }
        return ItemSwitchResult.UNAVAILABLE;
    }

    /** 快捷栏里装着这些物品之一、而且不会已经用完的一格（见 stackMayBeUsedUp），没有时 -1 */
    private static int usableHotbarStack(LocalPlayer player, Inventory inventory, Item[] items) {
        for (int h = 0; h < 9; h++) {
            ItemStack stack = inventory.getItem(h);
            if (stack.isEmpty() || !passesFilter(stack)) continue;
            for (Item item : items) {
                if (item != null && stack.is(item) && !stackMayBeUsedUp(player, stack, h)) return h;
            }
        }
        return -1;
    }

    private static void traceUsedUp(LocalPlayer player, ItemStack stack) {
        if (!com.autyism.printer.printer.TickTrace.ENABLED || client.level == null) return;
        com.autyism.printer.printer.TickTrace.detail("item-wait: stack may be used up " + stack.getItem() + " count=" + stack.getCount()
                + " unreflected=" + com.autyism.printer.printer.ActionConfirm.unreflectedUses(client.level, player.tickCount, stack.getItem(),
                getSelectedSlot(player.getInventory()))
                + " (all slots " + com.autyism.printer.printer.ActionConfirm.unreflectedUses(client.level, player.tickCount, stack.getItem()) + ")"
                + " inFlight=" + com.autyism.printer.printer.ActionConfirm.inFlightCount(client.level, player.tickCount, stack.getItem()));
    }

    /**
     * 生存模式这一组可能已经用完：服务端发来的“剩几个”有延迟，会把客户端自己算好的数量改大，
     * 客户端以为还有、服务端其实已经用完（放置被拒、方块闪一下又消失）。
     * 客户端显示的数量最多比真实数量多出“已发出、还没确认、而且显示的数量里还没扣掉的”那几次（见 ActionConfirm.unreflectedUses），
     * 所以数量不多于这个数时先等确认回来。客户端自己扣过的不再扣第二遍：以前一组放到一半就要停下来等。
     */
    private static boolean stackMayBeUsedUp(LocalPlayer player, ItemStack stack) {
        return stackMayBeUsedUp(player, stack, getSelectedSlot(player.getInventory()));
    }

    /** 快捷栏第 slot 格的这一组：只算从这一格用掉、服务端同步的数量里还没扣的那几次（见 ActionConfirm.unreflectedUses） */
    private static boolean stackMayBeUsedUp(LocalPlayer player, ItemStack stack, int slot) {
        // 空手（例如空手点红石粉把十字点成点）不消耗任何东西。以前这里按“0 个不多于 0 次”算成用完了，
        // 一直等到换物品超时、调整放弃（每个红石点要卡 10 秒），这一层后面的方块都要等它
        if (stack.isEmpty()) return false;
        if (PlayerUtils.getAbilities(player).instabuild || client.level == null) return false;
        return stack.getCount() <= com.autyism.printer.printer.ActionConfirm.unreflectedUses(client.level, player.tickCount, stack.getItem(), slot);
    }

    private static int findItemInInventory(Inventory inventory, Item item) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem().equals(item) && passesFilter(stack)) {
                return i;
            }
        }
        return -1;
    }

    public PickResult checkCanSwitchToItems(LocalPlayer player, Item[] items) {
        if (player == null) {
            return PickResult.FAIL;
        }
        Item[] targetItems = items;
        if (targetItems == null || targetItems.length == 0) {
            targetItems = new Item[]{Items.AIR};
        }
        Inventory inv = player.getInventory();
        boolean isCreativeMode = PlayerUtils.getAbilities(player).instabuild;
        if (isCreativeMode) {
            return InventoryUtils.checkPickSlotAvailable(-1, client);
        }
        for (Item item : targetItems) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack itemStack = inv.getItem(i);
                if (itemStack.getItem().equals(item)) {
                    return InventoryUtils.checkPickSlotAvailable(i, client);
                }
            }
        }
        return PickResult.FAIL;
    }

    public enum PickResult {
        SUCCESS,
        FAIL,
        FAIL_NO_PICK_SLOTS_CONFIGURED,
        FAIL_NO_SUITABLE_SLOT_FOUND;

        public boolean isNoPickSlotsConfigured() {
            return this == FAIL_NO_PICK_SLOTS_CONFIGURED;
        }

        public boolean isNoSuitableSlotFound() {
            return this == FAIL_NO_SUITABLE_SLOT_FOUND;
        }

        public boolean isNoAvailableSlot() {
            return isNoPickSlotsConfigured() || isNoSuitableSlotFound();
        }

        public boolean isAvailable() {
            return this == SUCCESS;
        }
    }
}