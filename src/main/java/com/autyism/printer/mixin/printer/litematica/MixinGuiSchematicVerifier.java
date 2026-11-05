package com.autyism.printer.mixin.printer.litematica;

import com.autyism.printer.gui.VerifierReportButton;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds "Print report" to Litematica's Schematic Verifier (see VerifierReportButton). */
@Mixin(value = GuiSchematicVerifier.class, remap = false)
public abstract class MixinGuiSchematicVerifier {
    @Inject(method = "initGui", at = @At("TAIL"), remap = false, require = 0)
    private void printer$addReportButton(CallbackInfo ci) {
        VerifierReportButton.add((GuiSchematicVerifier) (Object) this);
    }
}
