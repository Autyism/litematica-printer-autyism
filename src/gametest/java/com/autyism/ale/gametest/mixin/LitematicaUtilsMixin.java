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
    @Inject(method = "getSchematicWorkBoxes", at = @At("RETURN"))
    private static void addGameTestRegion(CallbackInfoReturnable<java.util.List<com.autyism.ale.printer.PrinterBox>> cir) {
        int[] b = TestSchematicRegion.bounds();
        if (b != null) cir.getReturnValue().add(new com.autyism.ale.printer.PrinterBox(b[0], b[1], b[2], b[3], b[4], b[5]));
    }

    @Inject(method = "isSchematicBlock", at = @At("HEAD"), cancellable = true)
    private static void acceptGameTestSchematicRegion(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (TestSchematicRegion.contains(pos)) {
            cir.setReturnValue(true);
        }
    }
}
