package com.autyism.printer.gametest.mixin;

import com.autyism.printer.gametest.Prof;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 测试用：BlockItem.place 的耗时（客户端） */
@Mixin(BlockItem.class)
public abstract class ProfilePlaceMixin {
    @Inject(method = "place", at = @At("HEAD"))
    private void gt$placeStart(BlockPlaceContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        if (ctx.getLevel().isClientSide()) Prof.placeStart();
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void gt$placeEnd(BlockPlaceContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        if (ctx.getLevel().isClientSide()) Prof.placeEnd();
    }
}
