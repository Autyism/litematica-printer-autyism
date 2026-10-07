package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.ActionManager;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 记下每个真正发给服务端、会改变服务端视角的包（服务端头部朝向判断见 ActionManager.serverHeadDir）。
 * <p>
 * 以前只在 ClientCommonPacketListenerImpl.send 里看移动包，漏了两种：
 * <ul>
 *   <li>“使用物品”包：1.21.11 里它带着视角，服务端处理时会把玩家转过去（absSnapRotationTo）。
 *       打印机用桶倒水就是这样瞄准的——真实实例里 18W 的一个侦测器因此朝向放错（服务端头部被倒水的瞄准转到了南面，
 *       打印机却以为还朝西）；</li>
 *   <li>不经过监听器、直接走 Connection 发出的包（例如原版回应服务端传送时带着真实视角的移动包）。</li>
 * </ul>
 * 这里只记录，不改包；改包（假视角）仍在 MixinClientCommonPacketListener。
 */
@Mixin(Connection.class)
public abstract class MixinConnectionRotationNote {
    //? if <1.20.2 {
    /*@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void litematica_printer$noteRotation(Packet<?> packet, net.minecraft.network.PacketSendListener listener, CallbackInfo ci) {
    *///?} elif <1.21.6 {
    /*@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"))
    private void litematica_printer$noteRotation(Packet<?> packet, net.minecraft.network.PacketSendListener listener, boolean flush, CallbackInfo ci) {
    *///?} else {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void litematica_printer$noteRotation(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
    //?}
        if (packet instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            ActionManager.INSTANCE.noteSentRotation(move.getYRot(0));
        } else if (packet instanceof ServerboundUseItemPacket use) {
            //? if >=26.3 {
            /*ActionManager.INSTANCE.noteSentRotation(use.yRot());
            *///?} elif <1.21 {
            /*// 1.20.1 的使用物品包不带视角，视角在它前面的移动包里
            *///?} else {
            ActionManager.INSTANCE.noteSentRotation(use.getYRot());
            //?}
        }
    }
}
