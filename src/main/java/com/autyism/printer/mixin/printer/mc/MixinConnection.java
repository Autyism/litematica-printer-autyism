package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.config.Configs;
import com.autyism.printer.handler.ModuleManager;
import com.autyism.printer.utils.ConfigUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(Connection.class)
public class MixinConnection {
    @Inject(method = "genericsFtw", at = @At("HEAD"), require = 1)
    private static void hookGenericsFtw(Packet<?> packet, PacketListener packetListener, CallbackInfo ci) {
        // 延迟检测：只算服务端发给客户端的包。单人游戏里内置服务端处理客户端发来的包也会走到这里，
        // 以前把它们也算成“收到了服务端的数据”，计数一直被清零，单人游戏里延迟检测等于没有
        if (ConfigUtils.isPrinterEnable() && packetListener.flow() == net.minecraft.network.protocol.PacketFlow.CLIENTBOUND) {
            ModuleManager.setPacketTick(0);   // 用于延迟检测
        }
    }

    @Inject(method = "disconnect*", at = {@At("HEAD")})
    public void disconnect(Component component, CallbackInfo ci) {
        if (Configs.Core.AUTO_DISABLE_PRINTER.getBooleanValue() && Configs.Core.WORK_SWITCH.getBooleanValue()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
        }
    }
}