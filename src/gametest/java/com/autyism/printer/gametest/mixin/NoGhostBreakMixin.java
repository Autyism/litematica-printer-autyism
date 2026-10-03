package com.autyism.printer.gametest.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 测试用：模拟 Meteor NoGhostBlocks 的“挖掘”部分（Meteor 只在连服务器时生效，单人世界不生效；测试里用 ale.noghost 含 "break" 强制打开）：
 * 客户端挖掉方块时不先删除，只播放破坏效果，等服务端发回来。
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class NoGhostBreakMixin {
    @Unique
    private static final String MODE = System.getProperty("ale.noghost", "");
    @Unique
    private static final boolean BREAK = MODE.equals("true") || MODE.contains("break");

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void ale$noGhostBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!BREAK) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        BlockState state = mc.level.getBlockState(pos);
        state.getBlock().playerWillDestroy(mc.level, pos, state, mc.player);
        cir.setReturnValue(false);
    }
}
