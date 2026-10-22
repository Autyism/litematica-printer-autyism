package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.I18n;
import com.autyism.printer.config.Configs;
import com.autyism.printer.utils.BreakUtils;
import com.autyism.printer.utils.ConfigUtils;
import com.autyism.printer.utils.LitematicaUtils;
import com.autyism.printer.utils.QuickShulkerUtils;
import com.autyism.printer.utils.MessageUtils;
import com.autyism.printer.utils.ServerEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(method = "handleSetHealth", at = @At("RETURN"))
    private void injectHealthUpdate(ClientboundSetHealthPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (packet.getHealth() == 0 && Configs.Core.AUTO_DISABLE_PRINTER.getBooleanValue() && Configs.Core.WORK_SWITCH.getBooleanValue()) {
            MessageUtils.setOverlayMessage(I18n.AUTO_DISABLE_NOTICE.getName());
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
        }
    }

    // 服务器给这个玩家的效果（客户端模组自己加的效果不算），算挖掘速度用
    @Inject(method = "handleUpdateMobEffect", at = @At("RETURN"))
    private void onServerEffect(ClientboundUpdateMobEffectPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || packet.getEntityId() != player.getId()) return;
        //? if <1.21.5 {
        /*ServerEffects.set(packet.getEffect(), packet.getEffectAmplifier());
        *///?} else
        ServerEffects.set(packet.getEffect().value(), packet.getEffectAmplifier());
    }

    @Inject(method = "handleRemoveMobEffect", at = @At("RETURN"))
    private void onServerEffectRemoved(ClientboundRemoveMobEffectPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || packet.getEntity(mc.level) != mc.player) return;
        //? if <1.21.5 {
        /*ServerEffects.remove(packet.getEffect());
        *///?} else
        ServerEffects.remove(packet.effect().value());
    }

    // 进服、重生、换维度都是新的玩家，服务器会把效果重新发一遍
    @Inject(method = {"handleLogin", "handleRespawn"}, at = @At("RETURN"))
    private void onNewPlayer(CallbackInfo ci) {
        ServerEffects.clear();
    }

    // 服务端同步了背包里的物品数量：它只算到已经处理的动作，还没确认的放置的消耗不在这个数量里（见 ActionConfirm.unreflectedUses）
    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void onSlotSync(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        // 背包菜单（id 0）里快捷栏是第 36~44 格；-2 是老版本直接按背包下标发的；-1 是鼠标上拿着的东西（不影响快捷栏）。
        // 开着别的容器时不知道是哪一格，按整个背包算
        LocalPlayer player = Minecraft.getInstance().player;
        int id = packet.getContainerId(), slot = packet.getSlot();
        if (id == -1) return;
        int hotbar = -1;
        if (player != null && id == player.inventoryMenu.containerId) {
            if (slot < 36 || slot > 44) return;
            hotbar = slot - 36;
        } else if (id == -2) {
            if (slot < 0 || slot > 8) return;
            hotbar = slot;
        }
        com.autyism.printer.printer.ActionConfirm.onInventorySync(Minecraft.getInstance().level, hotbar);
    }

    //? if >=1.21.2 {
    @Inject(method = "handleSetPlayerInventory", at = @At("RETURN"))
    private void onPlayerInventorySync(net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
        // 按背包下标发的：0~8 是快捷栏，别的格子不影响打印机手上的东西
        if (packet.slot() < 0 || packet.slot() > 8) return;
        com.autyism.printer.printer.ActionConfirm.onInventorySync(Minecraft.getInstance().level, packet.slot());
    }
    //?}

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        com.autyism.printer.printer.ActionConfirm.onInventorySync(Minecraft.getInstance().level);
        LocalPlayer player = Minecraft.getInstance().player;
        if (QuickShulkerUtils.isOpenHandler() && player != null
                //? if <1.21.5 {
                /*&& packet.getContainerId() == player.containerMenu.containerId
                *///?} else
                && packet.containerId() == player.containerMenu.containerId
        ) {
            QuickShulkerUtils.switchFromShulker();
        }
    }
}
