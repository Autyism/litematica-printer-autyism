package com.autyism.printer;

import lombok.Getter;
import com.autyism.printer.utils.MessageUtils;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

@Getter
public class I18n {
    public static final I18n MESSAGE_TOGGLED = of("message.toggled");
    public static final I18n MESSAGE_VALUE_OFF = of("message.value.off");
    public static final I18n MESSAGE_VALUE_ON = of("message.value.on");
    public static final I18n MESSAGE_ALL_MODES_CLOSED = of("message.all_modes_closed");
    public static final I18n MESSAGE_PRINTER_ON = of("message.printer_on");
    public static final I18n MESSAGE_PRINTER_OFF = of("message.printer_off");
    public static final I18n MESSAGE_CYCLE_ON = of("message.cycle_on");
    public static final I18n MESSAGE_CYCLE_OFF = of("message.cycle_off");

    public static final I18n AUTO_DISABLE_NOTICE = of("auto_disable_notice");

    public static final I18n BEDROCK_CREATIVE_MODE = of("bedrock.creative_mode");
    public static final I18n BEDROCK_MOD_MISSING = of("bedrock.mod_missing");
    public static final I18n BEDROCK_NOT_SUPPORT = of("bedrock.not_support");
    public static final I18n BEDROCK_NOGHOST_CONFLICT = of("bedrock.noghost_conflict");

    public static final I18n ICE_CREATIVE_MODE = of("ice.creative_mode");
    public static final I18n ICE_WATER_TIMEOUT = of("ice.water_timeout");

    public static final I18n INVENTORY_BACKPACK_FULL = of("inventory.backpack_full");

    public static final I18n BREWINGSTAND_LOWER = of("brewingstand.lower");
    public static final I18n BREWINGSTAND_RAISE = of("brewingstand.raise");

    public static final I18n BLOCK_NO_SUPPORT = of("block.no_support");
    public static final I18n BLOCK_MISMATCH = of("block.mismatch");
    public static final I18n SHULKER_CONTENT_MISMATCH = of("shulker.content_mismatch");
    public static final I18n LAYER_DONE_ALL = of("layer.done_all");
    public static final I18n LAYER_DONE_ISSUES = of("layer.done_issues");
    public static final I18n HUD_PRINT_PROGRESS = of("hud.print_progress");
    public static final I18n HUD_PRINT_ERRORS = of("hud.print_errors");
    public static final I18n HUD_LAYER = of("hud.layer");

    private static final String PREFIX_CONFIG = "config";
    private static final String PREFIX_COMMENT = "desc";

    private final @Nullable String prefix;
    private final String nameKey;
    private final String withPrefixNameKey;
    private final String descKey;
    private final String configNameKey;
    private final String configDescKey;

    private I18n(@Nullable String prefix, String nameKey) {
        this.prefix = prefix;
        this.nameKey = nameKey;
        this.withPrefixNameKey = prefix == null ? nameKey : prefix + "." + nameKey;
        this.descKey = withPrefixNameKey + "." + PREFIX_COMMENT;
        String configNameKey = prefix == null ? PREFIX_CONFIG : prefix + "." + PREFIX_CONFIG;
        this.configNameKey = configNameKey + "." + nameKey;
        this.configDescKey = configNameKey + "." + nameKey + "." + PREFIX_COMMENT;
    }

    public static I18n of(@Nullable String prefix, String key) {
        return new I18n(prefix, key);
    }

    public static I18n of(String key) {
        return new I18n(Reference.MOD_ID, key);
    }

    /*** 获取键名 ***/
    public MutableComponent getName() {
        return MessageUtils.translatable(this.withPrefixNameKey);
    }

    /*** 获取键名(带参数) ***/
    public MutableComponent getName(Object... objects) {
        return MessageUtils.translatable(this.withPrefixNameKey, objects);
    }

    /*** 获取描述 ***/
    public MutableComponent getDesc() {
        return MessageUtils.translatable(this.descKey);
    }

    /*** 获取描述(带参数) ***/
    public MutableComponent getDesc(Object... objects) {
        return MessageUtils.translatable(this.descKey, objects);
    }

    /*** 获取配置键名 ***/
    public MutableComponent getConfigName() {
        return MessageUtils.translatable(this.configNameKey);
    }

    /*** 获取配置键名(带参数) ***/
    public MutableComponent getConfigName(Object... objects) {
        return MessageUtils.translatable(this.configNameKey, objects);
    }

    /*** 获取配置描述 ***/
    public MutableComponent getConfigDesc() {
        return MessageUtils.translatable(this.configDescKey);
    }

    /*** 获取配置描述(带参数) ***/
    public MutableComponent getConfigDesc(Object... objects) {
        return MessageUtils.translatable(this.configDescKey, objects);
    }

    /*** 获取简易键名(一般用于枚举, 会取 "." 最后的文本) ***/
    public String getSimpleKey() {
        if (nameKey == null || nameKey.isEmpty()) {
            return nameKey == null ? "" : nameKey;
        }
        int lastDotIndex = nameKey.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return nameKey;
        }
        if (lastDotIndex == nameKey.length() - 1) {
            return "";
        }
        return nameKey.substring(lastDotIndex + 1);
    }
}