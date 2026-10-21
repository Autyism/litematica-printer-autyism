package com.autyism.printer.gametest.mixin;

import com.autyism.printer.gametest.Prof;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 测试用：客户端 useItemOn / performUseItemOn 的耗时（见 Prof） */
@Mixin(MultiPlayerGameMode.class)
public abstract class ProfileMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void gt$useStart(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        Prof.useStart();
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void gt$useEnd(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        Prof.useEnd();
    }

    @Inject(method = "performUseItemOn", at = @At("HEAD"))
    private void gt$performStart(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        Prof.performStart();
    }

    @Inject(method = "performUseItemOn", at = @At("RETURN"))
    private void gt$performEnd(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        Prof.performEnd();
    }
}
