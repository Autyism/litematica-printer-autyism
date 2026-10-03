package com.autyism.printer.gametest.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 测试用：客户端认为服务端现在选中的快捷栏格子 */
@Mixin(MultiPlayerGameMode.class)
public interface GameModeCarriedAccessor {
    @Accessor("carriedIndex")
    int gt$carriedIndex();
}
