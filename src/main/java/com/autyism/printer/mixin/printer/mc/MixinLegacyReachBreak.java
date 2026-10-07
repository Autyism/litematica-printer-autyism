//? if <1.21 {
/*package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.ReachHelper;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

// 1.20.1：单人世界自动调高交互距离（挖方块）。见 ReachHelper.legacyServerDistanceSqr
@Mixin(ServerPlayerGameMode.class)
public abstract class MixinLegacyReachBreak {
    @Shadow
    @Final
    protected ServerPlayer player;

    @ModifyExpressionValue(method = "handleBlockBreakAction", at = @At(value = "FIELD", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;MAX_INTERACTION_DISTANCE:D"))
    private double litematica_printer$breakReach(double original) {
        return ReachHelper.legacyServerDistanceSqr(this.player, original);
    }
}
*///?}
