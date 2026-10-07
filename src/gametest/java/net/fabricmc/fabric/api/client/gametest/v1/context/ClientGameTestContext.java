//? if <1.21 {
/*package net.fabricmc.fabric.api.client.gametest.v1.context;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Predicate;

// 1.20.1：Fabric 客户端 gametest 里测试用到的方法（实现在 LegacyHarness）
public interface ClientGameTestContext {
    <E extends Throwable> void runOnClient(FailableConsumer<Minecraft, E> action) throws E;

    <T, E extends Throwable> T computeOnClient(FailableFunction<Minecraft, T, E> function) throws E;

    void waitTick();

    void waitTicks(int ticks);

    int waitFor(Predicate<Minecraft> predicate, int timeout);

    Path takeScreenshot(String name);

    TestWorldBuilder worldBuilder();

    TestInput getInput();

    interface TestWorldBuilder {
        TestSingleplayerContext create();
    }

    interface TestInput {
        void holdKey(Function<Options, KeyMapping> key);

        void releaseKey(Function<Options, KeyMapping> key);
    }
}
*///?}
