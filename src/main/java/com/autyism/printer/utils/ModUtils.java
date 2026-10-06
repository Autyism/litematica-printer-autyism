package com.autyism.printer.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class ModUtils {
    // 阻止 UI 显示 如果此时已经在 UI 中 请设置为 2 因为关闭 UI 也会调用一次
    public static int closeScreen = 0;

    public static boolean isLoadMod(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static boolean isBedrockMinerLoaded() {
        return isLoadMod("bedrockminer");
    }

    public static boolean isBlockMinerLoaded() {
        return isLoadMod("blockminer");
    }

    public static boolean isTweakerooLoaded() {
        return isLoadMod("tweakeroo");
    }

    public static boolean isRemoteInventoryNextLoaded() {
        return isLoadMod("remote-inventory-next");
    }

    public static boolean isQuickShulkerLoaded() {
        return isLoadMod("quickshulker");
    }

    public static boolean isTakeItOutLoaded() {
        return isLoadMod("takeitout");
    }

    // 本地版本（从fabric.mod.json读取）
    public static final String LOCAL_VERSION = getVersionFromModJson();

    /**
     * 从 fabric.mod.json 读取版本号
     * @return 版本号字符串，如果读取失败则返回 "unknown"
     */
    private static String getVersionFromModJson() {
        try {
            Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer(com.autyism.printer.Reference.MOD_ID);
            if (modContainer.isPresent()) {
                Optional<Path> modJsonPath = modContainer.get().findPath("fabric.mod.json");
                if (modJsonPath.isPresent() && Files.exists(modJsonPath.get())) {
                    try (InputStream inputStream = Files.newInputStream(modJsonPath.get());
                         InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                        JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                        return jsonObject.get("version").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "unknown";
    }
}
