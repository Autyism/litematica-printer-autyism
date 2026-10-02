package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.utils.ModUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    //shit. In mapping-26.1.2-26.2.txt replaces setScreen to screen
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        if (ModUtils.closeScreen > 0 && screen instanceof AbstractContainerScreen<?>) {
            ModUtils.closeScreen--;
            ci.cancel();
        }
    }
}
