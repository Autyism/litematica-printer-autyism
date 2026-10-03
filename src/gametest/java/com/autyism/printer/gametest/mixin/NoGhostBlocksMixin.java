package com.autyism.printer.gametest.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 测试用：模拟 Meteor 的 NoGhostBlocks（防幽灵方块）。系统属性 ale.noghost 含 "place"（或为 "true"）时，
 * 客户端放方块不先显示，只等服务端发回来（和 Meteor 一样：BlockItem.placeBlock 直接返回成功，不改客户端世界）。
 * 我自己的实例里这个模块是开着的，打印机曾因此在同一格放两次。
 */
@Mixin(BlockItem.class)
public abstract class NoGhostBlocksMixin {
    @Unique
    private static final String MODE = System.getProperty("ale.noghost", "");
    @Unique
    private static final boolean PLACE = MODE.equals("true") || MODE.contains("place");

    @Inject(method = "placeBlock", at = @At("HEAD"), cancellable = true)
    private void ale$noGhostPlace(BlockPlaceContext ctx, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (PLACE && ctx.getLevel().isClientSide()) cir.setReturnValue(true);
    }
}
