//? if <1.21 {
/*package com.autyism.printer.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// 1.20.1：创造模式下客户端每改一次物品栏格子，服务端都会把结果发回来（1.21 起不再发回）。
// 延迟大的时候，旧的回包会在客户端已经换成新物品之后才到，把格子改回旧物品，打印机就会拿错方块。
// 这里记下发出去的修改；收到回包时，如果后面还有更新的修改没回来，就忽略这个旧回包
public final class LegacyCreativeEcho {
    private static final long TIMEOUT_MS = 5000;
    private static final Map<Integer, ArrayDeque<Entry>> PENDING = new HashMap<>();
    private static LocalPlayer owner;

    private record Entry(ItemStack stack, long time) {
    }

    private LegacyCreativeEcho() {
    }

    public static void sent(int slot, ItemStack stack) {
        check();
        PENDING.computeIfAbsent(slot, k -> new ArrayDeque<>()).addLast(new Entry(stack.copy(), System.currentTimeMillis()));
    }

    // 服务端发来某个格子的内容：是更早一次修改的回包、而且后面还有修改没回来时返回 true（这个包已经过时了）
    public static boolean isStaleEcho(int slot, ItemStack stack) {
        check();
        ArrayDeque<Entry> queue = PENDING.get(slot);
        if (queue == null || queue.isEmpty()) return false;
        long now = System.currentTimeMillis();
        queue.removeIf(e -> now - e.time() > TIMEOUT_MS);
        int index = 0;
        int match = -1;
        for (Iterator<Entry> it = queue.iterator(); it.hasNext(); index++) {
            if (ItemStack.matches(it.next().stack(), stack)) {
                match = index;
                break;
            }
        }
        if (match < 0) {
            // 不是回包：服务端自己改了这个格子，以服务端为准
            queue.clear();
            return false;
        }
        for (int i = 0; i <= match; i++) queue.removeFirst();
        return !queue.isEmpty();
    }

    private static void check() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != owner) {
            owner = player;
            PENDING.clear();
        }
    }
}
*///?}
