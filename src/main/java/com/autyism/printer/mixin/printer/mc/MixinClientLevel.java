package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.printer.ContainerGuard;
import com.autyism.printer.utils.PacketUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    /** 这一 tick 打印机的动作已经出过声了（见 ale$printerSound） */
    @Unique
    private static int ale$soundTick = Integer.MIN_VALUE;

    /**
     * 打印机放方块时客户端也会照常播放置声：一 tick 放几十块就是几十个声音，几秒就把游戏的声音通道（247 个）占满，
     * 之后每放一块都要报两条警告写进日志，主线程还要停下来等声音线程，打印机一 tick 能放的块数因此少了一截，
     * 别的声音也放不出来了。打印机的动作每 tick 只出一次声
     */
    @Inject(method = "playSeededSound", at = @At("HEAD"), cancellable = true)
    private void ale$printerSound(CallbackInfo ci) {
        if (!ContainerGuard.isPrinterInteraction()) return;
        var player = Minecraft.getInstance().player;
        int tick = player == null ? 0 : player.tickCount;
        if (tick == ale$soundTick) {
            ci.cancel();
        } else {
            ale$soundTick = tick;
        }
    }
}
