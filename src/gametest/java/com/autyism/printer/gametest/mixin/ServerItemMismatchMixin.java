package com.autyism.printer.gametest.mixin;

import com.autyism.printer.gametest.UseRecord;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 测试用：服务端处理放置包时手里的物品和客户端发包时的不一样，就打印 [ITEM-MISMATCH]（平时什么都不打）。
 * 用来抓“偶尔放错成别的方块”这种很难复现的问题：不用开大量调试日志（日志一多时序就变了，问题就不出现了）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerItemMismatchMixin {
    @Shadow
    public ServerPlayer player;

    /** 每个世界开头的“切换格子”服务端都打印出来（和客户端的 [client-carried-send] 对照） */
    @Inject(method = "handleSetCarriedItem", at = @At("TAIL"))
    private void gt$logEarlyCarried(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread() || this.player.tickCount >= 400) return;
        System.out.println("[server-carried-early] slot=" + packet.getSlot() + " now=" + this.player.getInventory().getSelectedSlot()
                + " tick=" + this.player.tickCount);
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void gt$checkItem(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread()) return; // 网络线程那一次只是转交给主线程
        UseRecord.Entry client = UseRecord.take(packet.getSequence());
        if (client == null || client.item().equals("minecraft:air")) return; // 生存模式最后一个用掉后客户端手里是空的：不算
        String server = this.player.getItemInHand(packet.getHand()).getItem().toString();
        if (server.equals(client.item())) return;
        StringBuilder hotbar = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            var s = this.player.getInventory().getItem(i);
            hotbar.append(i).append(':').append(s.isEmpty() ? "-" : s.getItem().toString().replace("minecraft:", "")).append(' ');
        }
        System.out.println("[ITEM-MISMATCH] seq=" + packet.getSequence() + " target=" + client.target() + " client=" + client.item()
                + " (slot " + client.slot() + ", tick " + client.tick() + ") server=" + server + " (slot "
                + this.player.getInventory().getSelectedSlot() + ") serverHotbar=" + hotbar);
    }
}
