package com.autyism.printer.gametest.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 测试用：服务端让客户端切换快捷栏格子（很少发生，每次都打印） */
@Mixin(ClientPacketListener.class)
public abstract class ClientHeldSlotDebugMixin {
    @Inject(method = "handleSetHeldSlot", at = @At("HEAD"))
    private void gt$log(ClientboundSetHeldSlotPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null) return;
        int carried = mc.gameMode == null ? -9 : ((GameModeCarriedAccessor) mc.gameMode).gt$carriedIndex();
        System.out.println("[client-held-slot] slot=" + packet.slot() + " selected=" + mc.player.getInventory().getSelectedSlot()
                + " carried=" + carried + " tick=" + mc.player.tickCount);
    }
}
