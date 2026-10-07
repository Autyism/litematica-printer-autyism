//? if <1.21 {
/*package com.autyism.printer.gametest.legacy;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;

// 1.20.1：客户端 gametest 的最小实现。和 Fabric 的一样：标题画面出现后在测试线程上依次运行所有 fabric-client-gametest 入口，
// 世界是平坦世界（种子 1、不生成结构、关昼夜/天气/刷怪）；全部通过就退出游戏，失败就打印异常并以 1 退出
public final class LegacyHarness implements ClientGameTestContext {
    private static final Object LOCK = new Object();
    private static long ticks;
    private static boolean started;
    private static boolean optionsSet;
    private static int worlds;

    private LegacyHarness() {
    }

    public static void init() {
        if (System.getProperty("ale.gt") == null) return;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            synchronized (LOCK) {
                ticks++;
                LOCK.notifyAll();
            }
            if (started) return;
            if (!optionsSet) {
                optionsSet = true;
                setOptions(client.options);
            }
            if (client.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) client.setScreen(new TitleScreen(true));
            if (client.screen instanceof TitleScreen && client.getOverlay() == null) {
                started = true;
                Thread thread = new Thread(LegacyHarness::runAll, "Test thread");
                thread.setDaemon(true);
                thread.start();
            }
        });
    }

    // 和 Fabric 的客户端 gametest 一样的默认设置；另外失去焦点时不暂停（Fabric 是直接不让游戏知道失去了焦点）
    private static void setOptions(Options options) {
        options.onboardAccessibility = false;
        options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
        options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        options.renderDistance().set(5);
        options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
        options.pauseOnLostFocus = false;
        options.save();
    }

    private static void runAll() {
        LegacyHarness context = new LegacyHarness();
        try {
            for (FabricClientGameTest test : FabricLoader.getInstance().getEntrypoints("fabric-client-gametest", FabricClientGameTest.class)) {
                test.runTest(context);
            }
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
        } catch (Throwable e) {
            System.out.println("Client gametests failed with an exception");
            e.printStackTrace(System.out);
            System.out.flush();
            Runtime.getRuntime().halt(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T join(CompletableFuture<T> future) throws E {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw (E) e.getCause();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <E extends Throwable> void runOnClient(FailableConsumer<Minecraft, E> action) throws E {
        computeOnClient(mc -> {
            action.accept(mc);
            return null;
        });
    }

    @Override
    public <T, E extends Throwable> T computeOnClient(FailableFunction<Minecraft, T, E> function) throws E {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(function.apply(mc));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return join(future);
    }

    @Override
    public void waitTick() {
        synchronized (LOCK) {
            long start = ticks;
            while (ticks == start) {
                try {
                    LOCK.wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public void waitTicks(int count) {
        for (int i = 0; i < count; i++) waitTick();
    }

    @Override
    public int waitFor(Predicate<Minecraft> predicate, int timeout) {
        for (int t = 0; t <= timeout; t++) {
            if (computeOnClient(predicate::test)) return t;
            waitTick();
        }
        throw new AssertionError("Timed out waiting for predicate");
    }

    @Override
    public Path takeScreenshot(String name) {
        waitTick();
        return computeOnClient(mc -> {
            Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), message -> {
            });
            return mc.gameDirectory.toPath().resolve("screenshots").resolve(name + ".png");
        });
    }

    @Override
    public TestWorldBuilder worldBuilder() {
        return () -> {
            String name = "ALE Test " + (++worlds);
            runOnClient(mc -> {
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.NORMAL, false, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(1L, false, false),
                        registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            });
            waitFor(mc -> mc.level != null && mc.player != null && mc.screen == null && mc.getSingleplayerServer() != null, 2400);
            return new Singleplayer(this, computeOnClient(Minecraft::getSingleplayerServer));
        };
    }

    @Override
    public TestInput getInput() {
        return new TestInput() {
            @Override
            public void holdKey(Function<Options, KeyMapping> key) {
                runOnClient(mc -> key.apply(mc.options).setDown(true));
            }

            @Override
            public void releaseKey(Function<Options, KeyMapping> key) {
                runOnClient(mc -> key.apply(mc.options).setDown(false));
            }
        };
    }

    private record Singleplayer(LegacyHarness context, MinecraftServer server) implements TestSingleplayerContext, TestSingleplayerContext.TestServerContext {
        @Override
        public TestServerContext getServer() {
            return this;
        }

        @Override
        public void close() {
            context.runOnClient(mc -> {
                if (mc.level != null) mc.level.disconnect();
                mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
                mc.setScreen(new TitleScreen());
            });
            context.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
        }

        @Override
        public void runCommand(String command) {
            runOnServer(s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command));
        }

        @Override
        public <E extends Throwable> void runOnServer(FailableConsumer<MinecraftServer, E> action) throws E {
            computeOnServer(s -> {
                action.accept(s);
                return null;
            });
        }

        @Override
        public <T, E extends Throwable> T computeOnServer(FailableFunction<MinecraftServer, T, E> function) throws E {
            CompletableFuture<T> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    future.complete(function.apply(server));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return join(future);
        }
    }
}
*///?}
