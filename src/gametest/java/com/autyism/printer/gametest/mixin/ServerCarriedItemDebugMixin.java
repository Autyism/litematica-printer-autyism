package com.autyism.printer.gametest.mixin;

import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 调试：服务端处理“切换手持格子”的顺序 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerCarriedItemDebugMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCarriedItem", at = @At("TAIL"))
    private void gt$logCarried(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (Boolean.getBoolean("ale.debuglook") && this.player.level().getServer().isSameThread()) {
            System.out.println("[server-carried] slot=" + packet.getSlot() + " now=" + this.player.getInventory().getSelectedSlot()
                    + " item=" + this.player.getMainHandItem().getItem());
        }
    }
}
