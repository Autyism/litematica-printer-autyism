package com.autyism.ale.gametest;

/** 测试用开关，由 gametest mixin 读取。 */
public final class TestHooks {
    /** >0 时，ClientboundOpenScreenPacket 会被延迟这么多 tick 再处理，模拟服务器网络延迟 */
    public static volatile int delayOpenScreenTicks = 0;

    private TestHooks() {
    }
}
