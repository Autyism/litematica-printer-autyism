package com.autyism.printer.gametest.mixin;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 调试：服务端处理背包点击（同步编号是否一致、点击后快捷栏 0~8 的内容） */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerContainerClickDebugMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleContainerClick", at = @At("HEAD"))
    private void gt$before(ServerboundContainerClickPacket p, CallbackInfo ci) {
        if (Boolean.getBoolean("ale.debuglook") && this.player.level().getServer().isSameThread()) {
            //? if >=26.1 {
            /*System.out.println("[server-click] slot=" + p.slotNum() + " button=" + p.buttonNum() + " type=" + p.containerInput()
                    + " stateId(client)=" + p.stateId() + " stateId(server)=" + this.player.containerMenu.getStateId());
            *///?} elif <1.21.5 {
            /*System.out.println("[server-click] slot=" + p.getSlotNum() + " button=" + p.getButtonNum() + " type=" + p.getClickType()
                    + " stateId(client)=" + p.getStateId() + " stateId(server)=" + this.player.containerMenu.getStateId());
            *///?} else {
            System.out.println("[server-click] slot=" + p.slotNum() + " button=" + p.buttonNum() + " type=" + p.clickType()
                    + " stateId(client)=" + p.stateId() + " stateId(server)=" + this.player.containerMenu.getStateId());
            //?}
        }
    }

    @Inject(method = "handleContainerClick", at = @At("TAIL"))
    private void gt$after(ServerboundContainerClickPacket p, CallbackInfo ci) {
        if (Boolean.getBoolean("ale.debuglook") && this.player.level().getServer().isSameThread()) {
            StringBuilder sb = new StringBuilder("[server-click-after] hotbar=");
            for (int i = 0; i < 9; i++) {
                var s = this.player.getInventory().getItem(i);
                sb.append(i).append(':').append(s.isEmpty() ? "-" : s.getItem().toString().replace("minecraft:", "") + "x" + s.getCount()).append(' ');
            }
            System.out.println(sb);
        }
    }
}
