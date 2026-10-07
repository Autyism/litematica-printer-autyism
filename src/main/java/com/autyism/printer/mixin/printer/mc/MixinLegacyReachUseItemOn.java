//? if <1.21 {
/*package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.ReachHelper;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

// 1.20.1：单人世界自动调高交互距离（放置）。见 ReachHelper.legacyServerDistanceSqr
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinLegacyReachUseItemOn {
    @Shadow
    public ServerPlayer player;

    // 眼睛到方块中心
    @ModifyExpressionValue(method = "handleUseItemOn", at = @At(value = "FIELD", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;MAX_INTERACTION_DISTANCE:D"))
    private double litematica_printer$eyeReach(double original) {
        return ReachHelper.legacyServerDistanceSqr(this.player, original);
    }

    // 脚下到方块中心（原版 8 格）
    @ModifyExpressionValue(method = "handleUseItemOn", at = @At(value = "CONSTANT", args = "doubleValue=64.0"))
    private double litematica_printer$feetReach(double original) {
        return ReachHelper.legacyServerDistanceSqr(this.player, original);
    }
}
*///?}
