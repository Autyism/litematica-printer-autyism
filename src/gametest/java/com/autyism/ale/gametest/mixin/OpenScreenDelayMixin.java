package com.autyism.ale.gametest.mixin;

import com.autyism.ale.gametest.TestHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 模拟网络延迟：把打开容器界面的包推迟若干 tick 处理。 */
@Mixin(ClientPacketListener.class)
abstract class OpenScreenDelayMixin {
    @Unique
    private static boolean ale$replaying;

    @Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true)
    private void ale$delay(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        int delay = TestHooks.delayOpenScreenTicks;
        if (delay <= 0 || ale$replaying) return;
        ci.cancel();
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delay * 50L);
            } catch (InterruptedException ignored) {
            }
            Minecraft.getInstance().execute(() -> {
                ale$replaying = true;
                try {
                    self.handleOpenScreen(packet);
                } finally {
                    ale$replaying = false;
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }
}
