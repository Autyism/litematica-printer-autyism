//? if <1.21 {
/*package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.LegacyCreativeEcho;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.20.1：忽略过时的创造模式物品栏回包（见 LegacyCreativeEcho），只更新状态编号
@Mixin(ClientPacketListener.class)
public abstract class MixinLegacyCreativeEcho {
    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true)
    private void litematica_printer$skipStaleEcho(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        // 这个方法先在网络线程上被调用一次，随后转到主线程再处理：只在主线程上判断
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null || packet.getContainerId() != 0) return;
        if (!LegacyCreativeEcho.isStaleEcho(packet.getSlot(), packet.getItem())) return;
        InventoryMenu menu = mc.player.inventoryMenu;
        menu.setItem(packet.getSlot(), packet.getStateId(), menu.getSlot(packet.getSlot()).getItem());
        ci.cancel();
    }
}
*///?}
