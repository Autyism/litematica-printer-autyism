//? if <1.21 {
/*package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.ReachHelper;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 1.20.1：单人世界自动调高交互距离后，客户端的交互距离也跟着变（和 1.21 调高属性一样）
@Mixin(MultiPlayerGameMode.class)
public abstract class MixinLegacyReachPick {
    @Inject(method = "getPickRange", at = @At("RETURN"), cancellable = true)
    private void litematica_printer$pickRange(CallbackInfoReturnable<Float> cir) {
        int raised = ReachHelper.legacyRaised();
        if (raised > cir.getReturnValueF()) cir.setReturnValue((float) raised);
    }
}
*///?}
