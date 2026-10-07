package com.autyism.printer.gametest.mixin;

import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 调试：服务端收到的 UseItem 包 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerUseItemDebugMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleUseItem", at = @At("HEAD"))
    private void gt$log(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (Boolean.getBoolean("ale.debuglook") && player != null && player.level().getServer() != null
                && player.level().getServer().isSameThread()) {
            System.out.println("[server-useitem] hand=" + player.getMainHandItem() + " menu=" + player.containerMenu.getClass().getSimpleName()
                    //? if >=26.3 {
                    /*+ " selected=" + player.getInventory().getSelectedSlot() + " seq=" + packet.sequence());
                    *///?} else
                    + " selected=" + player.getInventory().getSelectedSlot() + " seq=" + packet.getSequence());
        }
    }
}
