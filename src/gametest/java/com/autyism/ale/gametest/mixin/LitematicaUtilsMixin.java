package com.autyism.ale.gametest.mixin;

import com.autyism.ale.gametest.TestSchematicRegion;
import com.autyism.ale.utils.LitematicaUtils;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LitematicaUtils.class, remap = false)
abstract class LitematicaUtilsMixin {
    @Inject(method = "isSchematicBlock", at = @At("HEAD"), cancellable = true)
    private static void acceptGameTestSchematicRegion(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (TestSchematicRegion.contains(pos)) {
            cir.setReturnValue(true);
        }
    }
}
