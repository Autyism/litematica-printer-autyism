//? if <1.21 {
/*package net.fabricmc.fabric.api.client.gametest.v1.context;

import net.minecraft.server.MinecraftServer;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

// 1.20.1：测试用的单人世界（实现在 LegacyHarness）
public interface TestSingleplayerContext extends AutoCloseable {
    TestServerContext getServer();

    @Override
    void close();

    interface TestServerContext {
        void runCommand(String command);

        <E extends Throwable> void runOnServer(FailableConsumer<MinecraftServer, E> action) throws E;

        <T, E extends Throwable> T computeOnServer(FailableFunction<MinecraftServer, T, E> function) throws E;
    }
}
*///?}
