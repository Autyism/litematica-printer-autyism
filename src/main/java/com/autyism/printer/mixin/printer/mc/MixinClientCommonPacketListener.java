package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.utils.PacketUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

//? if <1.20.2 {
/*@Mixin(value = net.minecraft.client.multiplayer.ClientPacketListener.class)
public class MixinClientCommonPacketListener {
    @Final
    @Shadow
    private Connection connection;

    @Final
    @Shadow
    private Minecraft minecraft;
*///?} else {
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
@Mixin(value = ClientCommonPacketListenerImpl.class)
public class MixinClientCommonPacketListener {
    @Final
    @Shadow
    protected Connection connection;

    @Final
    @Shadow
    protected Minecraft minecraft;
//?}

    /**
     * @author BiliXWhite
     * @reason 修改移动视角数据包，以实现欺骗服务器的效果
     */
    @ModifyArg(at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"), method = "send")
    private Packet<?> modifySendPacket(Packet<?> packet) {
        return PacketUtils.getFixedPacket(packet);
    }

    // 挖掘包（打印机的、破基岩模组的）：记下来给耐久保护算没确认的磨损；破基岩模式下手上的工具快坏了就拦下（见 ToolSwitchUtils）
    @org.spongepowered.asm.mixin.injection.Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void litematica_printer$guardBreak(Packet<?> packet, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (packet instanceof net.minecraft.network.protocol.game.ServerboundPlayerActionPacket action
                && com.autyism.printer.utils.ToolSwitchUtils.guardBreakPacket(action)) {
            ci.cancel();
        }
    }
}
