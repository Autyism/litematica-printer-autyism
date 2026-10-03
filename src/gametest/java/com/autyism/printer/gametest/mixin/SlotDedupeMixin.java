package com.autyism.printer.gametest.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 测试用：模拟用户实例里 Genyo 插件的 InventoryManager（-Pslotdedupe=true）：
 * 发出的“切换快捷栏格子”包如果和它记下的格子一样就直接拦掉；它记下的格子换世界后不会重置
 * （真实实例里观察到的情况：新世界第一次切到上一个世界最后用的格子，这个包被拦，服务端还停在 0 号格子）。
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class SlotDedupeMixin {
    @Unique
    private static final boolean ENABLED = Boolean.getBoolean("ale.slotdedupe");
    @Unique
    private static int gt$lastSlot = 0;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void gt$dedupe(Packet<?> packet, CallbackInfo ci) {
        if (!ENABLED || !(packet instanceof ServerboundSetCarriedItemPacket p)) return;
        if (p.getSlot() == gt$lastSlot) {
            ci.cancel();
            return;
        }
        gt$lastSlot = p.getSlot();
    }
}
