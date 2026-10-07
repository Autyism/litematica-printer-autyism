//? if <1.21 {
/*package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.LegacyCreativeEcho;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.20.1：记下创造模式下发给服务端的物品栏修改（见 LegacyCreativeEcho）
@Mixin(MultiPlayerGameMode.class)
public abstract class MixinLegacyCreativeSend {
    @Inject(method = "handleCreativeModeItemAdd", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void litematica_printer$noteCreativeSlot(ItemStack stack, int slot, CallbackInfo ci) {
        LegacyCreativeEcho.sent(slot, stack);
    }
}
*///?}
