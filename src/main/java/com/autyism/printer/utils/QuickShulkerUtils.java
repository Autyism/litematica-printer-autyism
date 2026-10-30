package com.autyism.printer.utils;

import com.google.common.collect.Lists;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.ShulkerSource;
import com.autyism.printer.compat.QuickShulkerCompat;
import com.autyism.printer.compat.TakeItOutCompat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

//? if >=1.21.5
import net.minecraft.network.HashedStack;

import java.util.*;

public class QuickShulkerUtils {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final boolean QUICK_SHULKER_LOADED = FabricLoader.getInstance().isModLoaded("quickshulker");

    @Getter @Setter
    private static boolean isOpenHandler;
    @Setter
    @Getter
    private static int shulkerCooldown;
    @Getter @Setter
    private static int shulkerBoxSlot = -1;
    @Getter
    private static final Set<Item> lastNeedItemList = new HashSet<>();
    private static final LinkedList<ReturnRequest> itemsToReturn = new LinkedList<>();
    private static final List<TrackedShulker> trackedShulkers = new ArrayList<>();
    private static ReturnRequest activeReturnRequest;
    private static TrackedShulker activeShulker;
    private static int deferredCloseTicks;
    private static int deferredCloseRetries;
    private static final int MAX_DEFERRED_CLOSE_RETRIES = 10;
    /** 发出打开潜影盒之后，界面一直没打开就放弃这一次（以后再取）：以前会一直“忙”，打印机就一直停在等这个潜影盒 */
    private static final int OPEN_TIMEOUT_TICKS = 60;
    private static int openWaitTicks;
    /** 打印机拦下没显示的那个容器界面（潜影盒）的编号；-1 = 没有 */
    private static int hiddenMenuId = -1;
    private static int hiddenIdleTicks;
    /** 拦下没显示的潜影盒界面没人在处理、这么多 tick 还开着，就关掉 */
    private static final int HIDDEN_MENU_TIMEOUT_TICKS = 20;

    private QuickShulkerUtils() {}

    public static void tick() {
        com.autyism.printer.compat.AdvancedShulkerCompat.tick();
        if (shulkerCooldown > 0) {
            shulkerCooldown--;
        }
        LocalPlayer self = mc.player;
        if (isOpenHandler && self != null && self.containerMenu == self.inventoryMenu
                && !com.autyism.printer.compat.AdvancedShulkerCompat.isPending()) {
            if (++openWaitTicks > OPEN_TIMEOUT_TICKS) {
                PrinterTrace.log("qs open timed out");
                abortOpen();
            }
        } else {
            openWaitTicks = 0;
        }
        closeForgottenHiddenMenu(self);
        if (deferredCloseTicks > 0 && --deferredCloseTicks == 0) {
            LocalPlayer player = mc.player;
            if (player == null || player.containerMenu.equals(player.inventoryMenu)) {
                deferredCloseRetries = 0;
            } else if (player.containerMenu.getCarried().isEmpty()
                    || ++deferredCloseRetries >= MAX_DEFERRED_CLOSE_RETRIES) {
                closeAndResync(player);
                deferredCloseRetries = 0;
            } else {
                deferredCloseTicks = 1;
            }
        }
    }

    /**
     * 关掉打印机开的潜影盒，再请服务端把整个背包重发一次。
     * 盒子开着时打印机还在放方块，服务端把手上数量的变化发给那个潜影盒界面；客户端先关了盒子，
     * 晚到的这几条就被丢掉，服务端却以为客户端收到了、以后不再发：手上那一组成了幽灵物品（看着还有，其实没了），
     * 打印机拿着它一直白放，也不去潜影盒拿新的。服务端按顺序先关盒子、再处理重发请求，所以重发的一定是关盒之后的样子
     */
    private static void closeAndResync(LocalPlayer player) {
        player.closeContainer();
        InventoryUtils.requestInventoryResync(player);
        PrinterTrace.log("qs closed the box and asked for the inventory");
    }

    /** MixinMinecraft 拦下潜影盒界面时记下它的编号 */
    public static void noteHiddenMenu(int containerId) {
        hiddenMenuId = containerId;
        hiddenIdleTicks = 0;
    }

    /**
     * 保险：打印机拦下没显示的潜影盒界面还开着，可是已经没有在处理它（不在取物、不在等关闭、不在等打开）。
     * 例如服务端回包晚到、打开已经算超时放弃了。玩家看不见这个界面，也关不掉；它开着时打印机对背包的每一次点击都会被忽略，
     * 以前就一直卡在那里（玩家手动拿出材料也没用），只能重进
     */
    private static void closeForgottenHiddenMenu(@org.jetbrains.annotations.Nullable LocalPlayer player) {
        if (hiddenMenuId == -1) return;
        //? if >=26.2 {
        /*net.minecraft.client.gui.screens.Screen screen = mc.gui.screen();
        *///?} else
        net.minecraft.client.gui.screens.Screen screen = mc.screen;
        if (player == null || player.containerMenu == player.inventoryMenu || player.containerMenu.containerId != hiddenMenuId
                || screen != null) {
            hiddenMenuId = -1;
            return;
        }
        if (isBusy()) {
            hiddenIdleTicks = 0;
            return;
        }
        if (++hiddenIdleTicks > HIDDEN_MENU_TIMEOUT_TICKS) {
            PrinterTrace.log("qs closing a forgotten hidden menu id=" + hiddenMenuId);
            closeAndResync(player);
            hiddenMenuId = -1;
        }
    }

    public static void addLastNeedItem(Item item) {
        lastNeedItemList.add(item);
    }

    public static void clearLastNeedItems() {
        lastNeedItemList.clear();
    }

    // ========== 统一取物入口 ==========

    /**
     * 根据 ShulkerSource 配置分发潜影盒取物请求。
     * @return true 已发起请求（调用方应等待），false 无法处理
     */
    /**
     * 打开、处理或等待延迟关闭都算忙碌，期间不能再做背包交换。
     */
    public static boolean isBusy() {
        return isOpenHandler || deferredCloseTicks > 0 || com.autyism.printer.compat.AdvancedShulkerCompat.isPending();
    }

    public static boolean requestShulkerItem(LocalPlayer player, Item[] items) {
        if (!Configs.Print.USE_QUICK_SHULKER.getBooleanValue()) return false;

        ShulkerSource source = (ShulkerSource) Configs.Print.SHULKER_SOURCE.getOptionListValue();


        // “模组”来源：自动使用已安装的潜影盒模组（优先 Advanced Shulkerboxes，其次 QuickShulker）
        if (source == ShulkerSource.MOD && !QUICK_SHULKER_LOADED && !com.autyism.printer.compat.AdvancedShulkerCompat.isLoaded()) {
            return false;
        }

        // 上一个潜影盒还没关好（延迟关闭、等着打开）或者开着别的容器：不开下一个。以前延迟关闭的那两 tick 里又开了一个，
        // 服务端还开着上一个，两边的界面和背包对不上，打印机从此一块也放不下（拿出一两组就不动了）
        if (isBusy() || shulkerCooldown > 0 || player.containerMenu != player.inventoryMenu) {
            if (PrinterTrace.on()) traceRefuse("busy open=" + isOpenHandler + " deferredClose=" + deferredCloseTicks
                    + " pending=" + com.autyism.printer.compat.AdvancedShulkerCompat.isPending() + " cooldown=" + shulkerCooldown
                    + " menu=" + player.containerMenu.getClass().getSimpleName() + "#" + player.containerMenu.containerId);
            return false;
        }

        Inventory inventory = player.getInventory();

        // 背包里已经没有这种物品的归还请求（用完了 / 被玩家拿走了）已经没有意义：丢掉，否则会一直抢先打开潜影盒却什么都放不回去
        itemsToReturn.removeIf(r -> !inventoryHas(inventory, r.item()));
        if (Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.getBooleanValue()
                && isInventoryFull(inventory)) {
            ReturnRequest returnRequest = itemsToReturn.peekFirst();
            // 没有可归还的记录时，继续走下面的取物流程：打开潜影盒后会先把用不到的物品放回去腾出空位
            int shulkerSlot = returnRequest == null ? -1 : findReturnShulker(inventory, returnRequest);
            if (shulkerSlot != -1) {
                activeReturnRequest = returnRequest;
                activeShulker = returnRequest.shulker();
                return openSelectedShulker(inventory, shulkerSlot, source);
            }
        }

        // 不开启精确回塞时，只要有 itemsToReturn 就尝试回塞到任意有空位的潜影盒
        if (!Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.getBooleanValue()
                && isInventoryFull(inventory)) {
            ReturnRequest returnRequest = itemsToReturn.peekFirst();
            int shulkerSlot = returnRequest == null ? -1 : findAnyShulker(player);
            if (shulkerSlot != -1) {
                activeReturnRequest = returnRequest;
                activeShulker = null;
                return openSelectedShulker(inventory, shulkerSlot, source);
            }
        }

        // 背包满了，也没有能跟潜影盒里的材料对换的东西（全是潜影盒、工具、食物、这次要用的）：打开也取不出来，不开。
        // 以前每次都打开一个潜影盒（拿到手上）又什么都没取出来，几个潜影盒轮流在手上跳，打印机卡在那里
        if (isInventoryFull(inventory) && !canMakeRoom(inventory, items)) {
            MessageUtils.setOverlayMessage(com.autyism.printer.I18n.SHULKER_NO_ROOM.getName());
            if (PrinterTrace.on()) traceRefuse("inventory full, nothing to swap out");
            return false;
        }

        for (Item item : items) {
            int shulkerSlot = findShulkerWithItem(player, item);
            if (shulkerSlot != -1) {
                ItemStack shulkerStack = inventory.getItem(shulkerSlot);
                activeReturnRequest = null;
                activeShulker = findTrackedShulker(shulkerStack);
                if (activeShulker == null) {
                    activeShulker = new TrackedShulker(shulkerStack.getItem(), getShulkerContents(shulkerStack));
                    trackedShulkers.add(activeShulker);
                }
                activeShulker.setLastKnownSlot(shulkerSlot);
                clearLastNeedItems();
                addLastNeedItem(item);
                return openSelectedShulker(inventory, shulkerSlot, source);
            }
        }
        if (PrinterTrace.on()) traceRefuse("no box with " + Arrays.toString(items));
        return false;
    }

    private static String lastRefuse;
    private static long lastRefuseAt;

    /** 记录这次为什么没开潜影盒：同样的原因一秒最多记一次（每放一格都会来问一次） */
    private static void traceRefuse(String why) {
        long now = System.currentTimeMillis();
        if (why.equals(lastRefuse) && now - lastRefuseAt < 1000) return;
        lastRefuse = why;
        lastRefuseAt = now;
        PrinterTrace.log("qs not opening: " + why);
    }

    private static boolean inventoryHas(Inventory inventory, Item item) {
        for (int i = 0; i < Math.min(inventory.getContainerSize(), 36); i++) {
            if (inventory.getItem(i).is(item)) return true;
        }
        return false;
    }

    private static boolean openSelectedShulker(Inventory inventory, int shulkerSlot, ShulkerSource source) {
        ItemStack shulkerStack = inventory.getItem(shulkerSlot);
        if (PrinterTrace.on()) {
            System.out.println("[printer-trace] qs open slot=" + shulkerSlot + " stack=" + shulkerStack + " closeScreen=" + ModUtils.closeScreen
                    + " menu=" + (mc.player == null ? null : mc.player.containerMenu.getClass().getSimpleName()));
        }
        // 客户端此时没有打开任何容器：如果服务端那边还挂着一个（上一次打开的回包丢了 / 被打断），先关掉让两边同步，
        // 否则服务端一直认为潜影盒开着，后面的取物会全部失败
        LocalPlayer self = mc.player;
        if (self != null && self.containerMenu == self.inventoryMenu && self.connection != null) {
            self.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(0));
        }
        setShulkerBoxSlot(shulkerSlot);
        activeBulk = bulkRequest;
        ModUtils.closeScreen++;
        setOpenHandler(true);
        openWaitTicks = 0;
        setShulkerCooldown(Configs.Print.SHULKER_COOLDOWN.getIntegerValue());

        // 按来源打开潜影盒：PLUGIN 走右键模拟；MOD 优先 Advanced Shulkerboxes（拿到手上对空气使用），否则 QuickShulker API
        if (source == ShulkerSource.PLUGIN) {
            openShulkerByRightClick(shulkerSlot);
        } else if (com.autyism.printer.compat.AdvancedShulkerCompat.isLoaded()) {
            com.autyism.printer.compat.AdvancedShulkerCompat.open(shulkerSlot, QuickShulkerUtils::abortOpen);
        } else {
            QuickShulkerCompat.openShulker(shulkerStack, shulkerSlot);
        }
        return true;
    }

    // ========== 容器槽位点击 ==========

    public static void clickSlot(AbstractContainerMenu container, int slotIndex, int button, ClickType type) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) return;

        NonNullList<Slot> slots = container.slots;
        int totalSlots = slots.size();
        List<ItemStack> copies = Lists.newArrayListWithCapacity(totalSlots);
        for (Slot slotItem : slots) {
            copies.add(slotItem.getItem().copy());
        }

        //? if <1.21.5 {
        /*Int2ObjectMap<ItemStack> snapshot = new Int2ObjectOpenHashMap<>();
        *///?} else
        Int2ObjectMap<HashedStack> snapshot = new Int2ObjectOpenHashMap<>();

        for (int j = 0; j < totalSlots; j++) {
            ItemStack original = copies.get(j);
            ItemStack current = slots.get(j).getItem();
            if (!ItemStack.isSameItem(original, current)) {
                //? if <1.21.5 {
                /*snapshot.put(j, current.copy());
                *///?} else
                snapshot.put(j, HashedStack.create(current, connection.decoratedHashOpsGenenerator()));
            }
        }

        //? if <1.21.5 {
        /*connection.send(new ServerboundContainerClickPacket(
                container.containerId,
                container.getStateId(),
                slotIndex,
                button,
                type,
                container.getCarried().copy(),
                snapshot
        ));
        *///?} else {
        HashedStack carried = HashedStack.create(container.getCarried(), connection.decoratedHashOpsGenenerator());
        connection.send(new ServerboundContainerClickPacket(
                container.containerId,
                container.getStateId(),
                Shorts.checkedCast(slotIndex),
                SignedBytes.checkedCast(button),
                type,
                snapshot,
                carried
        ));
        //?}

        container.clicked(slotIndex, button, type, mc.player);
    }

    public static void pickupSlot(AbstractContainerMenu container, int slotIndex) {
        clickSlot(container, slotIndex, 0, ClickType.PICKUP);
    }

    /** button 即目标快捷栏槽位 0-8 */
    public static void swapWithHotbar(AbstractContainerMenu container, int slotIndex, int hotbarSlot) {
        clickSlot(container, slotIndex, hotbarSlot, ClickType.SWAP);
    }

    // ========== 插件服右键开箱 ==========

    public static void openShulkerByRightClick(int inventorySlot) {
        if (mc.player == null || mc.gameMode == null) return;
        mc.gameMode.handleInventoryMouseClick(
                mc.player.containerMenu.containerId,
                inventorySlot,
                1, // 右键
                ClickType.PICKUP,
                mc.player);
    }

    // ========== 潜影盒取物逻辑 ==========
    /**
     * 收到 ContainerSetContent 包时调用。
     * 背包满时只执行归还；背包未满时执行正常取物。
     */
    public static void switchFromShulker() {
        LocalPlayer player = mc.player;
        if (player == null || player.containerMenu.equals(player.inventoryMenu)) {
            isOpenHandler = false;
            return;
        }

        AbstractContainerMenu container = player.containerMenu;
        Inventory inventory = player.getInventory();
        if (PrinterTrace.on()) {
            int empty = 0;
            for (int i = 0; i < 36; i++) if (inventory.getItem(i).isEmpty()) empty++;
            System.out.println("[printer-trace] qs switchFromShulker menu=" + container.getClass().getSimpleName() + " slots=" + container.slots.size()
                    + " return=" + activeReturnRequest + " need=" + lastNeedItemList + " emptyInv=" + empty + " returns=" + itemsToReturn.size());
        }

        if (activeReturnRequest != null) {
            returnItemToShulker(player, container, inventory, activeReturnRequest);
            finishShulkerOperation(player);
            return;
        }

        int ownSlots = container.slots.size() - 36;
        // 智能：背包快满时，先把暂时用不到的物品放回这个潜影盒，腾出空间
        depositUnneeded(player, container, ownSlots, inventory);
        for (int slotIndex = 0; slotIndex < ownSlots; slotIndex++) {
            Slot slot = container.slots.get(slotIndex);
            if (!slot.hasItem()) continue;
            for (Item item : lastNeedItemList) {
                if (slot.getItem().getItem().equals(item)) {
                    Item returnItem = slot.getItem().getItem();
                    // 找背包空位（优先快捷栏，再主背包）
                    int emptyInvSlot = -1;
                    for (int i = 0; i < Math.min(inventory.getContainerSize(), 36); i++) {
                        if (inventory.getItem(i).isEmpty()) {
                            emptyInvSlot = i;
                            break;
                        }
                    }
                    if (emptyInvSlot != -1 && mc.gameMode != null) {
                        // 背包索引 → 容器槽位索引的转换
                        int containerTarget;
                        if (emptyInvSlot < 9) {
                            // 快捷栏在容器末尾
                            containerTarget = ownSlots + 27 + emptyInvSlot;
                        } else {
                            // 主背包紧接容器自身槽位
                            containerTarget = ownSlots + (emptyInvSlot - 9);
                        }
                        // 先拾取潜影盒槽位，再放到目标槽位
                        mc.gameMode.handleInventoryMouseClick(container.containerId, slot.index, 0, ClickType.PICKUP, player);
                        mc.gameMode.handleInventoryMouseClick(container.containerId, containerTarget, 0, ClickType.PICKUP, player);
                        if (activeBulk) takeMore(player, container, ownSlots, inventory, item);
                        if (activeShulker != null) {
                            activeShulker.updateContents(getContainerContents(container, ownSlots));
                            itemsToReturn.addLast(new ReturnRequest(returnItem, activeShulker));
                        }
                        finishShulkerOperation(null);
                    } else if (mc.gameMode != null && exchange(player, container, ownSlots, inventory, slot)) {
                        finishShulkerOperation(null);
                    } else {
                        finishShulkerOperation(player);
                    }
                    return;
                }
            }
        }

        finishShulkerOperation(player);
    }

    /** 这次是填流体 / 填充要的（只用一两种方块、一次要很多）：一次开盒多拿几组，不用每 64 个就开一次潜影盒 */
    private static boolean bulkRequest;
    private static boolean activeBulk;
    /** 一次开盒最多拿几组；背包至少留一格空的 */
    private static final int BULK_STACKS = 8;

    public static void setBulkRequest(boolean bulk) {
        bulkRequest = bulk;
    }

    /** 刚拿出第一组以后，同一种物品再拿几组到背包的空格里（快捷栏优先） */
    private static void takeMore(LocalPlayer player, AbstractContainerMenu container, int ownSlots, Inventory inventory, Item item) {
        int taken = 1;
        for (int slotIndex = 0; slotIndex < ownSlots && taken < BULK_STACKS; slotIndex++) {
            Slot slot = container.slots.get(slotIndex);
            if (!slot.hasItem() || !slot.getItem().is(item)) continue;
            int empty = -1, emptyCount = 0;
            for (int i = 0; i < Math.min(inventory.getContainerSize(), 36); i++) {
                if (inventory.getItem(i).isEmpty()) {
                    if (empty == -1) empty = i;
                    emptyCount++;
                }
            }
            if (emptyCount < 2) break;
            int target = empty < 9 ? ownSlots + 27 + empty : ownSlots + (empty - 9);
            mc.gameMode.handleInventoryMouseClick(container.containerId, slot.index, 0, ClickType.PICKUP, player);
            mc.gameMode.handleInventoryMouseClick(container.containerId, target, 0, ClickType.PICKUP, player);
            taken++;
        }
        PrinterTrace.log("qs took " + taken + " stacks of " + item);
    }

    private static void returnItemToShulker(LocalPlayer player, AbstractContainerMenu container,
                                            Inventory inventory, ReturnRequest returnRequest) {
        if (mc.gameMode == null) {
            itemsToReturn.removeFirstOccurrence(returnRequest);
            return;
        }

        int ownSlots = container.slots.size() - 36;
        if (!inventoryHas(inventory, returnRequest.item())) {
            itemsToReturn.removeFirstOccurrence(returnRequest);
            return;
        }
        for (int i = 0; i < Math.min(inventory.getContainerSize(), 36); i++) {
            if (!inventory.getItem(i).is(returnRequest.item())) continue;

            for (int shulkerSlot = 0; shulkerSlot < ownSlots; shulkerSlot++) {
                if (container.slots.get(shulkerSlot).hasItem()) continue;

                int containerSource = i < 9 ? ownSlots + 27 + i : ownSlots + i - 9;
                mc.gameMode.handleInventoryMouseClick(container.containerId, containerSource, 0, ClickType.PICKUP, player);
                mc.gameMode.handleInventoryMouseClick(container.containerId, shulkerSlot, 0, ClickType.PICKUP, player);
                itemsToReturn.removeFirstOccurrence(returnRequest);
                if (returnRequest.shulker() != null) {
                    returnRequest.shulker().updateContents(getContainerContents(container, ownSlots));
                }
                return;
            }
            // 潜影盒无空位 → 移除失效请求，避免死循环
            itemsToReturn.removeFirstOccurrence(returnRequest);
            return;
        }
        // 物品已不在背包（已被使用）→ 移除失效请求，避免死循环
        itemsToReturn.removeFirstOccurrence(returnRequest);
    }

    private static void finishShulkerOperation(LocalPlayer player) {
        boolean wasReturn = activeReturnRequest != null;
        if (player == null) {
            deferredCloseTicks = 2;
            deferredCloseRetries = 0;
        } else {
            closeAndResync(player);
            deferredCloseTicks = 0;
            deferredCloseRetries = 0;
        }
        shulkerBoxSlot = -1;
        isOpenHandler = false;
        activeReturnRequest = null;
        activeShulker = null;
        lastNeedItemList.clear();
        if (itemsToReturn.isEmpty()) trackedShulkers.clear();
        // 回塞完成后立即允许下一次潜影盒操作，避免当前打印位置因冷却被跳过
        if (wasReturn) shulkerCooldown = 0;
    }

    /** 打开失败（例如潜影盒拿不到手上）时恢复状态 */
    private static void abortOpen() {
        PrinterTrace.log("qs abortOpen closeScreen=" + ModUtils.closeScreen);
        if (ModUtils.closeScreen > 0) ModUtils.closeScreen--;
        isOpenHandler = false;
        shulkerBoxSlot = -1;
        activeReturnRequest = null;
        activeShulker = null;
        lastNeedItemList.clear();
    }

    /**
     * 背包空位少于 2 个时，把“暂时用不到”的物品快速移入当前打开的潜影盒：
     * 只动主背包（不动快捷栏），跳过工具/盔甲等会损耗的物品、潜影盒、食物、这次要取的物品和最近打印用过的物品；
     * 优先放潜影盒里已有同种物品的，最多移动 3 组。
     */
    private static void depositUnneeded(LocalPlayer player, AbstractContainerMenu container, int ownSlots, Inventory inventory) {
        if (mc.gameMode == null) return;
        int empty = 0;
        for (int i = 0; i < 36; i++) if (inventory.getItem(i).isEmpty()) empty++;
        if (empty >= 2) return;
        int moved = 0;
        // pass 0：潜影盒里已有同种物品的；pass 1：任何有空间的；
        // pass 2（前面一个都放不回时）：放宽“最近用过”的限制——总比背包满了什么都取不出来强
        for (int pass = 0; pass < 3 && moved < 3 && empty < 2; pass++) {
            if (pass == 2 && moved > 0) break;
            for (int i = 9; i < 36 && moved < 3 && empty < 2; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!isDepositCandidate(stack, pass == 2)) continue;
                boolean inShulker = false;
                boolean hasRoom = false;
                for (int s = 0; s < ownSlots; s++) {
                    ItemStack c = container.slots.get(s).getItem();
                    if (c.isEmpty()) hasRoom = true;
                    //? if <1.20.5 {
                    /*else if (ItemStack.isSameItemSameTags(c, stack)) {
                    *///?} else
                    else if (ItemStack.isSameItemSameComponents(c, stack)) {
                        inShulker = true;
                        if (c.getCount() < c.getMaxStackSize()) hasRoom = true;
                    }
                }
                if (!hasRoom || (pass == 0 && !inShulker)) continue;
                PrinterTrace.log("qs deposit " + stack + " pass=" + pass);
                int containerSlot = ownSlots + (i - 9);
                mc.gameMode.handleInventoryMouseClick(container.containerId, containerSlot, 0, ClickType.QUICK_MOVE, player);
                moved++;
                if (inventory.getItem(i).isEmpty()) empty++;
            }
        }
    }

    /**
     * 背包满、潜影盒里也放不下别的东西（depositUnneeded 腾不出空位）：拿背包里一组暂时用不到的东西跟要取的这一组对换，
     * 潜影盒里的格数不变。先挑主背包、没在用的，再挑快捷栏，最后才挑最近用过的。
     *
     * @return true 表示换了
     */
    private static boolean exchange(LocalPlayer player, AbstractContainerMenu container, int ownSlots, Inventory inventory, Slot shulkerSlot) {
        int pick = -1;
        for (int pass = 0; pass < 4 && pick == -1; pass++) {
            boolean recent = pass >= 2;
            int from = pass % 2 == 0 ? 9 : 0;
            int to = pass % 2 == 0 ? 36 : 9;
            for (int i = from; i < to; i++) {
                ItemStack stack = inventory.getItem(i);
                // 潜影盒里放不进的（潜影盒之类）不换：放不进去就会一直拿在光标上
                if (isDepositCandidate(stack, recent) && shulkerSlot.mayPlace(stack)) {
                    pick = i;
                    break;
                }
            }
        }
        if (pick == -1) return false;
        PrinterTrace.log("qs exchange " + shulkerSlot.getItem() + " with " + inventory.getItem(pick));
        int target = pick < 9 ? ownSlots + 27 + pick : ownSlots + (pick - 9);
        // 拿起潜影盒里的这一组 → 放到背包那一格（换出那一格的东西）→ 换出来的放进潜影盒刚空出的格子
        mc.gameMode.handleInventoryMouseClick(container.containerId, shulkerSlot.index, 0, ClickType.PICKUP, player);
        mc.gameMode.handleInventoryMouseClick(container.containerId, target, 0, ClickType.PICKUP, player);
        mc.gameMode.handleInventoryMouseClick(container.containerId, shulkerSlot.index, 0, ClickType.PICKUP, player);
        if (activeShulker != null) activeShulker.updateContents(getContainerContents(container, ownSlots));
        return true;
    }

    /** 背包满时，有没有东西能跟潜影盒里的材料对换（见 exchange）；items 是这次要取的 */
    private static boolean canMakeRoom(Inventory inventory, Item[] items) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || stack.isDamageableItem() || ShulkerContentUtils.isShulkerItem(stack)) continue;
            //? if <1.20.5 {
            /*if (stack.isEdible()) continue;
            *///?} else
            if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) continue;
            if (stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) continue;
            // 潜影盒（或别的盒子）里放不进的东西
            if (!stack.getItem().canFitInsideContainerItems()) continue;
            boolean needed = false;
            for (Item item : items) {
                if (item != null && stack.is(item)) needed = true;
            }
            if (!needed) return true;
        }
        return false;
    }

    private static boolean isDepositCandidate(ItemStack stack, boolean allowRecentlyUsed) {
        if (stack.isEmpty() || stack.isDamageableItem()) return false;
        if (com.autyism.printer.utils.ShulkerContentUtils.isShulkerItem(stack)) return false;
        //? if <1.20.5 {
        /*if (stack.isEdible()) return false;
        *///?} else
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) return false;
        if (stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) return false;
        if (lastNeedItemList.contains(stack.getItem())) return false;
        return allowRecentlyUsed || !InventoryUtils.isRecentlyUsed(stack.getItem());
    }

    /** 在玩家背包中找到包含目标物品的潜影盒，返回背包槽位索引，未找到返回 -1 */
    public static int findShulkerWithItem(LocalPlayer player, Item target) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            if (id.contains("shulker_box") && stack.getCount() == 1) {
                NonNullList<ItemStack> contents = fi.dy.masa.malilib.util.InventoryUtils
                        .getStoredItems(stack, -1);
                if (contents.stream().anyMatch(s -> s.getItem().equals(target))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findTrackedShulker(Inventory inventory, TrackedShulker trackedShulker) {
        for (int i = 9; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem().equals(trackedShulker.boxItem())
                    && sameContents(getShulkerContents(stack), trackedShulker.contents())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 三级查找回塞目标潜影盒：
     * 1. 精确内容匹配（最准）
     * 2. 检查记录的 lastKnownSlot 是否还是同类型潜影盒（防NBT漂移）
     * 3. 查找背包里装有同种物品的其他潜影盒（兜底）
     * 关闭精确回塞时退化为"只要有空位就塞"
     */
    private static int findReturnShulker(Inventory inventory, ReturnRequest request) {
        TrackedShulker tracked = request.shulker();

        // L1: 精确匹配
        int slot = findTrackedShulker(inventory, tracked);
        if (slot != -1) return slot;

        // L2: 检查记录的槽位
        int lastSlot = tracked.lastKnownSlot();
        if (lastSlot >= 9 && lastSlot < inventory.getContainerSize()) {
            ItemStack stack = inventory.getItem(lastSlot);
            if (stack.getItem().equals(tracked.boxItem())) {
                return lastSlot;
            }
        }

        // L3: 查找装有同种物品且有空位的潜影盒
        for (int i = 9; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            if (!id.contains("shulker_box") || stack.getCount() != 1) continue;
            List<ItemStack> contents = getShulkerContents(stack);
            if (contents.size() >= 27) continue;
            if (contents.stream().anyMatch(s -> s.getItem().equals(request.item()))) {
                return i;
            }
        }

        return -1;
    }

    private static int findAnyShulker(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 9; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            if (id.contains("shulker_box") && stack.getCount() == 1) {
                return i;
            }
        }
        return -1;
    }

    private static TrackedShulker findTrackedShulker(ItemStack stack) {
        List<ItemStack> contents = getShulkerContents(stack);
        for (TrackedShulker trackedShulker : trackedShulkers) {
            if (stack.getItem().equals(trackedShulker.boxItem())
                    && sameContents(contents, trackedShulker.contents())) {
                return trackedShulker;
            }
        }
        return null;
    }

    private static List<ItemStack> getShulkerContents(ItemStack stack) {
        return copyNonEmptyStacks(fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1));
    }

    private static List<ItemStack> getContainerContents(AbstractContainerMenu container, int ownSlots) {
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < ownSlots; i++) {
            ItemStack stack = container.slots.get(i).getItem();
            if (!stack.isEmpty()) contents.add(stack.copy());
        }
        return contents;
    }

    private static List<ItemStack> copyNonEmptyStacks(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) copies.add(stack.copy());
        }
        return copies;
    }

    private static boolean sameContents(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;

        boolean[] matched = new boolean[second.size()];
        for (ItemStack firstStack : first) {
            boolean found = false;
            for (int i = 0; i < second.size(); i++) {
                if (!matched[i] && fi.dy.masa.malilib.util.InventoryUtils
                        .areStacksEqual(firstStack, second.get(i))) {
                    matched[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean isInventoryFull(Inventory inventory) {
        for (int i = 0; i < Math.min(inventory.getContainerSize(), 36); i++) {
            if (inventory.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private record ReturnRequest(Item item, TrackedShulker shulker) {}

    private static final class TrackedShulker {
        private final Item boxItem;
        private List<ItemStack> contents;
        private int lastKnownSlot = -1;

        private TrackedShulker(Item boxItem, List<ItemStack> contents) {
            this.boxItem = boxItem;
            this.contents = contents;
        }

        private Item boxItem() {
            return boxItem;
        }

        private List<ItemStack> contents() {
            return contents;
        }

        private int lastKnownSlot() {
            return lastKnownSlot;
        }

        private void setLastKnownSlot(int slot) {
            this.lastKnownSlot = slot;
        }

        private void updateContents(List<ItemStack> contents) {
            this.contents = contents;
        }
    }
}