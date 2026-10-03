package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.utils.PacketUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements PacketUtils.SequenceExtension {

    @Final
    @Shadow
    private net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler blockStatePredictionHandler;

    @Override
    public int litematica_printer3$getSequence() {
        // 和原版 MultiPlayerGameMode.startPrediction 一样每次用一个新序号：服务端会对这个序号回确认，
        // 打印机靠它知道这次动作已经被服务端处理（之前直接复用上一个序号，确认分不清是哪一次）
        try (net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler pendingUpdateManager = blockStatePredictionHandler.startPredicting()) {
            return pendingUpdateManager.currentSequence();
        }
    }

    @Override
    public int litematica_printer3$currentSequence() {
        return blockStatePredictionHandler.currentSequence();
    }
}
