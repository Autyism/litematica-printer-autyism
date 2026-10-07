package com.autyism.printer.printer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import org.jetbrains.annotations.Nullable;

/**
 * 进入一个世界后打印机第一次工作时（以及每次打开打印机时），让服务端选中的快捷栏格子和客户端对齐。
 * <p>
 * 原版只在“客户端记下的服务端格子”和当前格子不一样时才发切换包。用户实例里 Genyo 插件的 InventoryManager
 * 会把“和它记下的格子一样”的切换包直接拦掉，而它记下的格子换世界 / 重进服务器后还是上一次的：
 * 新世界里第一次切到那个格子的包被拦，服务端还停在原来的格子，打印机就用错的物品放了方块
 * （真实实例：Notebot 的灵魂沙放成了沙子，掉下去多出 3 个沙子；CommandLeo 的南瓜灯放成了漏斗）。
 * <p>
 * 办法：先切到旁边的格子再切回来。两个包都和前一个不一样，这类“去重”拦不住；服务端最后停在当前格子。
 * 只在每个世界开头 / 打开打印机时发这两个包。
 */
public final class SlotResync {
    @Nullable
    private static ClientLevel syncedLevel;

    private SlotResync() {
    }

    /** 每 tick 调用；needed = 刚打开打印机 */
    public static void ensure(@Nullable LocalPlayer player, @Nullable ClientLevel level, boolean justEnabled) {
        if (player == null || level == null) return;
        if (!justEnabled && level == syncedLevel) return;
        syncedLevel = level;
        //? if <1.21.5 {
        /*int current = player.getInventory().selected;
        *///?} else
        int current = player.getInventory().getSelectedSlot();
        int other = current == 8 ? 7 : current + 1;
        player.connection.send(new ServerboundSetCarriedItemPacket(other));
        player.connection.send(new ServerboundSetCarriedItemPacket(current));
    }
}
