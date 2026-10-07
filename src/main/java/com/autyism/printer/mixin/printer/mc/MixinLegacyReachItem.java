//? if <1.21 {
/*package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.ReachHelper;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// 1.20.1：单人世界自动调高交互距离（用桶时找方块的距离，原版固定 5 格）。见 ReachHelper.legacyRayReach
@Mixin(Item.class)
public abstract class MixinLegacyReachItem {
    @ModifyExpressionValue(method = "getPlayerPOVHitResult", at = @At(value = "CONSTANT", args = "doubleValue=5.0"))
    private static double litematica_printer$rayReach(double original, @Local(argsOnly = true) Player player) {
        return ReachHelper.legacyRayReach(player, original);
    }
}
*///?}
