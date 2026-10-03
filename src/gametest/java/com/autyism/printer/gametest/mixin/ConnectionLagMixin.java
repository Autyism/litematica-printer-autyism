package com.autyism.printer.gametest.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 测试用：模拟真实服务器的网络延迟。系统属性 ale.lag = "基础毫秒,抖动毫秒"（例如 "60,80"）时，
 * 双向的每个游戏包都延迟 基础 + 随机(0..抖动) 毫秒再处理。和真实 TCP 连接一样，包的先后顺序绝不改变：
 * 每个连接一个先进先出队列，后面的包不会早于前面的包被处理（之前用“每个包单独定时”，
 * 放行时间相同的包会被计时误差打乱顺序，测出了不存在的问题）。
 * 抖动会让客户端相隔几个 tick 发出的包挤进服务端同一个 tick，也会让客户端很晚才看到服务端的结果。
 */
@Mixin(Connection.class)
public abstract class ConnectionLagMixin {
    @Unique
    private static final int[] LAG = parse(System.getProperty("ale.lag", ""));
    @Unique
    private static final ThreadLocal<Boolean> REPLAY = ThreadLocal.withInitial(() -> false);

    /** 等待处理的包：{放行时间(纳秒), ctx, packet}，按到达顺序 */
    @Unique
    private final ArrayDeque<Object[]> ale$queue = new ArrayDeque<>();
    @Unique
    private long ale$lastRelease;

    @Invoker("channelRead0")
    abstract void ale$invokeChannelRead0(ChannelHandlerContext ctx, Packet<?> packet);

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void ale$lag(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (LAG == null || REPLAY.get()) return;
        boolean game = packet.getClass().getName().startsWith("net.minecraft.network.protocol.game.");
        // 进世界 5 秒后才开始延迟（区块加载要来回确认，延迟下进世界会超时）；退出世界时停止
        var mc = net.minecraft.client.Minecraft.getInstance();
        boolean active = game && mc.player != null && mc.player.tickCount >= 100;
        if (!active && ale$queue.isEmpty()) return;
        // 队列里还有包时，后来的包（哪怕本身不需要延迟）也必须排在后面，保证顺序
        long now = System.nanoTime();
        long delay = active ? TimeUnit.MILLISECONDS.toNanos(LAG[0] + (LAG[1] > 0 ? ThreadLocalRandom.current().nextInt(LAG[1] + 1) : 0)) : 0;
        long release = Math.max(now + delay, ale$lastRelease);
        ale$lastRelease = release;
        ale$queue.addLast(new Object[]{release, ctx, packet});
        ci.cancel();
        ctx.channel().eventLoop().schedule(this::ale$drain, Math.max(0, release - now), TimeUnit.NANOSECONDS);
    }

    /** 按到达顺序处理所有已经到放行时间的包 */
    @Unique
    private void ale$drain() {
        long now = System.nanoTime();
        while (!ale$queue.isEmpty() && (long) ale$queue.peekFirst()[0] <= now) {
            Object[] e = ale$queue.pollFirst();
            REPLAY.set(true);
            try {
                ale$invokeChannelRead0((ChannelHandlerContext) e[1], (Packet<?>) e[2]);
            } finally {
                REPLAY.set(false);
            }
        }
    }

    @Unique
    private static int[] parse(String s) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split(",");
        try {
            int base = Integer.parseInt(parts[0].trim());
            int jitter = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            return base <= 0 && jitter <= 0 ? null : new int[]{base, jitter};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
