package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.ActionConfirm;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 服务端确认“已处理到第几号动作”：在方块状态按服务端结果更新之后通知 ActionConfirm */
@Mixin(BlockStatePredictionHandler.class)
public abstract class MixinBlockStatePredictionHandler {
    @Inject(method = "endPredictionsUpTo", at = @At("TAIL"))
    private void litematica_printer$onAck(int sequence, ClientLevel level, CallbackInfo ci) {
        ActionConfirm.onAck(level, sequence);
    }
}
