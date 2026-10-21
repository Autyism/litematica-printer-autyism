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
        // 服务器开着地毯的 antiCheatDisabled（装了地毯的客户端会收到服务器的规则）：服务端最远只认眼睛到方块中心 32 格，
        // 再远的放置会被悄悄丢掉、只回一个确认，打印机以前一直在白放（工作半径设成 64 时 32 格外的方块每 3 tick 试一次）
        if (com.autyism.printer.compat.CarpetRules.antiCheatDisabled()) {
            return Math.min(configRange, Math.max(CARPET_ANTI_CHEAT_REACH, reach));
        }
        return configRange;
    }

    /**
     * 地毯 antiCheatDisabled 时打印机用的距离：打印机量的是眼睛到方块最近一点，服务端量到方块中心，
     * 所以 32 格减去半条方块对角线（0.87），再留 1 格给服务端看到的眼睛位置（比客户端慢一点）
     */
    private static final double CARPET_ANTI_CHEAT_REACH = 32 - 0.87 - 1.0;

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