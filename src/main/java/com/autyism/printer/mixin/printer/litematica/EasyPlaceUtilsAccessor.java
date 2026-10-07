package com.autyism.printer.mixin.printer.litematica;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

//? if <1.21 {
/*@Mixin(value = fi.dy.masa.litematica.util.WorldUtils.class, remap = false)
*///?} else
@Mixin(value = fi.dy.masa.litematica.util.EasyPlaceUtils.class, remap = false)
public interface EasyPlaceUtilsAccessor {
    @Invoker("setEasyPlaceLastPickBlockTime")
    static void callSetEasyPlaceLastPickBlockTime() {
        throw new UnsupportedOperationException();
    }
}

