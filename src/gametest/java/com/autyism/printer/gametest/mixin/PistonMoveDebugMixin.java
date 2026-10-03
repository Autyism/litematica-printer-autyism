package com.autyism.printer.gametest.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 调试：服务端活塞推 / 拉方块（用来判断投影里的机器是不是在打印过程中自己动了起来） */
@Mixin(PistonBaseBlock.class)
public abstract class PistonMoveDebugMixin {
    @Inject(method = "moveBlocks", at = @At("HEAD"))
    private void gt$logMove(Level level, BlockPos pos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.getBoolean("ale.debuglook") && !level.isClientSide()) {
            System.out.println("[server-piston] pos=" + pos.toShortString() + " dir=" + direction + " extending=" + extending
                    + " front=" + level.getBlockState(pos.relative(direction)).getBlock());
        }
    }
}
