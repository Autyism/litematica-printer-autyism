package com.autyism.printer.gametest.mixin;

import com.autyism.printer.gametest.BlockLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 测试用：服务端每次改方块都交给 BlockLog（只在测试打开记录时记，范围外的不记） */
@Mixin(Level.class)
public abstract class ServerBlockLogMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void gt$logBlockChange(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide() || !(self instanceof net.minecraft.server.level.ServerLevel server)) return;
        BlockLog.record(server.getServer().getTickCount(), pos, self.getBlockState(pos), state);
    }
}
