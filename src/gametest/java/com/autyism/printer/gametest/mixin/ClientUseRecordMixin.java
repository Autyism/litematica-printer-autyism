package com.autyism.printer.gametest.mixin;

import com.autyism.printer.gametest.UseRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 测试用：记下每个放置包发出时客户端手里的物品（见 UseRecord / ServerItemMismatchMixin） */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientUseRecordMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void gt$record(Packet<?> packet, CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) return;
        int carried = mc.gameMode == null ? -9 : ((GameModeCarriedAccessor) mc.gameMode).gt$carriedIndex();
        if (packet instanceof ServerboundSetCarriedItemPacket c && player.tickCount < 400) {
            // 每个世界开头的切换格子：谁发的（抓“第一次切换格子没到服务端”）
            StringBuilder by = new StringBuilder();
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 2, n = 0; i < st.length && n < 6; i++) {
                String cls = st[i].getClassName();
                if (cls.startsWith("java.") || cls.contains("ClientUseRecordMixin")) continue;
                by.append(cls.substring(cls.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName()).append(' ');
                n++;
            }
            System.out.println("[client-carried-send] slot=" + c.getSlot() + " selected=" + player.getInventory().getSelectedSlot()
                    + " carried=" + carried + " tick=" + player.tickCount + " by=" + by);
        }
        if (!(packet instanceof ServerboundUseItemOnPacket p)) return;
        UseRecord.put(p.getSequence(), new UseRecord.Entry(player.getItemInHand(p.getHand()).getItem().toString(),
                player.getInventory().getSelectedSlot(), player.tickCount, p.getHitResult().getBlockPos().toShortString()
                        + " " + p.getHitResult().getDirection() + " carried=" + carried));
    }
}
