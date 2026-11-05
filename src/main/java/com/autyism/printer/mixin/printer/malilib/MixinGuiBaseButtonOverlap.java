package com.autyism.printer.mixin.printer.malilib;

import com.autyism.printer.gui.MainMenuButtonOverlap;
import fi.dy.masa.malilib.gui.GuiBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Litematica's main menu (M): several mods add a button to its right column (this mod's printer settings,
 * Litematica Container Filler, QuickCraft's schematic folder button, ...), each at a spot of its own choosing, so two
 * could land on the same place and one of them could not be clicked. Before the buttons are drawn, a button that
 * overlaps an earlier one moves down to the next free spot.
 */
@Mixin(value = GuiBase.class, remap = false)
public abstract class MixinGuiBaseButtonOverlap {
    @Inject(method = "drawContents", at = @At("HEAD"), remap = false, require = 0)
    private void printer$separateMainMenuButtons(CallbackInfo ci) {
        MainMenuButtonOverlap.resolve((GuiBase) (Object) this);
    }
}
