package com.autyism.printer.gametest.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 调试：服务端处理放置时玩家的实际视角 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerUseItemOnDebugMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void gt$log(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (Boolean.getBoolean("ale.debuglook")) {
            System.out.println("[server-use] pos=" + hit.getBlockPos() + " face=" + hit.getDirection() + " item=" + stack.getItem()
                    + " rot=" + player.getYRot() + "/" + player.getXRot());
        }
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void gt$logAfter(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (Boolean.getBoolean("ale.debuglook")) {
            System.out.println("[server-use-after] pos=" + hit.getBlockPos() + " result=" + cir.getReturnValue() + " state=" + level.getBlockState(hit.getBlockPos())
                    + " rot=" + player.getYRot() + "/" + player.getXRot());
        }
    }
}
