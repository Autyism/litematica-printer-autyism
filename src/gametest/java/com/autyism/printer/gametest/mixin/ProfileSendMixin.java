package com.autyism.printer.gametest.mixin;

import com.autyism.printer.gametest.Prof;
//? if <1.20.2 {
/*import net.minecraft.client.multiplayer.ClientPacketListener;
*///?} else
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 测试用：客户端发包的耗时（见 Prof） */
//? if <1.20.2 {
/*@Mixin(ClientPacketListener.class)
*///?} else
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ProfileSendMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void gt$sendStart(Packet<?> packet, CallbackInfo ci) {
        Prof.sendStart();
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("RETURN"))
    private void gt$sendEnd(Packet<?> packet, CallbackInfo ci) {
        Prof.sendEnd();
    }
}
