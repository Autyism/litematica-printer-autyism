//? if <1.21 {
/*package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.ReachHelper;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// 1.20.1：单人世界自动调高交互距离后，远处刚放下的告示牌也能写字（原版离开 8 格就不让改；1.21 起按交互距离算）
@Mixin(SignBlockEntity.class)
public abstract class MixinLegacyReachSign {
    @ModifyExpressionValue(method = "playerIsTooFarAwayToEdit", at = @At(value = "CONSTANT", args = "doubleValue=64.0"))
    private double litematica_printer$editReach(double original, @Local Player player) {
        return player instanceof ServerPlayer serverPlayer ? ReachHelper.legacyServerDistanceSqr(serverPlayer, original) : original;
    }
}
*///?}
