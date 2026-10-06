package com.autyism.printer.utils;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.FillModeFacingType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;

public class ConfigUtils {
    @NotNull
    public static final Minecraft client = Minecraft.getInstance();

    public static boolean isPrinterEnable() {
        return Configs.Core.WORK_SWITCH.getBooleanValue();
    }

    /** 打开着、并且至少有一个模式在工作 */
    public static boolean isAnyModeRunning() {
        return isPrinterEnable() && (isPrintEnabled() || isMineEnabled() || isFillEnabled() || isFluidEnabled() || isBedrockEnabled());
    }

    public static boolean isPrintEnabled() {
        return Configs.Print.ENABLED.getBooleanValue();
    }

    public static boolean isMineEnabled() {
        return Configs.Mine.ENABLED.getBooleanValue();
    }

    public static boolean isFillEnabled() {
        return Configs.Fill.ENABLED.getBooleanValue();
    }

    public static boolean isFluidEnabled() {
        return Configs.Fluid.ENABLED.getBooleanValue();
    }

    public static boolean isBedrockEnabled() {
        return Configs.Bedrock.ENABLED.getBooleanValue();
    }

    public static int getPlaceCooldown() {
        return Configs.Placement.PLACE_COOLDOWN.getIntegerValue();
    }

    public static int getBreakCooldown() {
        return Configs.Break.BREAK_COOLDOWN.getIntegerValue();
    }

    public static int getWorkRange() {
        return (int) Configs.Core.WORK_RANGE.getDoubleValue();
    }

    public static double getEffectiveRange() {
        double configRange = Configs.Core.WORK_RANGE.getDoubleValue();
        double reach = PlayerUtils.getInteractionRange(4.5);
        if (configRange <= 0) {
            return reach;
        }
        // 单人世界的内置服务端一定会按原版交互距离校验，超出的放置会被撤回（看起来像打印机罢工），这里限制在可交互范围内。
        // 想要更大的范围就靠“单人世界自动调高交互距离”把交互距离本身调高
        if (client.hasSingleplayerServer()) {
            return Math.min(configRange, reach);
        }
        return configRange;
    }

    public static Direction getFillModeFacing() {
        if (Configs.Fill.FILL_BLOCK_FACING.getOptionListValue() instanceof FillModeFacingType fillModeFacingType) {
            return switch (fillModeFacingType) {
                case DOWN -> Direction.DOWN;
                case UP -> Direction.UP;
                case WEST -> Direction.WEST;
                case EAST -> Direction.EAST;
                case NORTH -> Direction.NORTH;
                case SOUTH -> Direction.SOUTH;
                default -> null;
            };
        }
        return null;
    }
}