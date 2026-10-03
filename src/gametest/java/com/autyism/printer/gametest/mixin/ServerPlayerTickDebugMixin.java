package com.autyism.printer.gametest.mixin;

import com.autyism.printer.gametest.UseRecord;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 测试用：每次服务端玩家 tick 完记下身体 / 头部朝向（[ORIENT-MISMATCH] 里一起打印，看头部朝向什么时候变的） */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTickDebugMixin {
    @Inject(method = "doTick", at = @At("TAIL"))
    private void gt$afterTick(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        UseRecord.noteServerRotation("t" + self.tickCount + " tick " + self.getYRot() + "/" + self.getXRot() + " head " + self.getYHeadRot()
                + String.format(java.util.Locale.ROOT, " at %.2f,%.2f,%.2f", self.getX(), self.getY(), self.getZ()));
    }
}
