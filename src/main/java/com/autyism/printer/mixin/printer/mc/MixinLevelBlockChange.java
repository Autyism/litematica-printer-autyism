package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.BlockChangeHub;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 客户端世界的方块变化交给 BlockChangeHub（增量统计只重算变了的地方） */
@Mixin(Level.class)
public abstract class MixinLevelBlockChange {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void litematica_printer$noteBlockChange(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (((Level) (Object) this).isClientSide()) BlockChangeHub.changed(pos);
    }
}
