package com.autyism.printer.printer;

import com.autyism.printer.config.Configs;
import com.autyism.printer.utils.ConfigUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 需求 13：单人世界里，工作半径大于原版交互距离时自动调高 block_interaction_range 属性（上限 64），
 * 否则内置服务端会撤回超出距离的放置。只在单人世界、且工作半径确实更大时执行，每个目标值只发一次指令。
 */
public final class ReachHelper {
    private static final Minecraft mc = Minecraft.getInstance();
    public static final int VANILLA_ATTRIBUTE_MAX = 64;
    private static int requestedValue = -1;
    /** 发出调高指令时玩家的 tickCount */
    private static int requestedTick;
    /** 指令发出后最多等多久生效（没开作弊时指令会失败，不能一直等） */
    private static final int RAISE_WAIT_TICKS = 40;
    /** 换世界 / 重生后是新的玩家实体，属性回到默认值，需要重新设置 */
    private static LocalPlayer requestedFor;

    private ReachHelper() {
    }

    public static void tick() {
        LocalPlayer player = mc.player;
        if (player == null || !mc.hasSingleplayerServer()) {
            requestedValue = -1;
            return;
        }
        if (!ConfigUtils.isPrinterEnable() || !Configs.Core.AUTO_RAISE_REACH.getBooleanValue()) return;
        double wanted = Configs.Core.WORK_RANGE.getDoubleValue();
        if (wanted <= 0) return;
        if (player != requestedFor) {
            requestedFor = player;
            requestedValue = -1;
        }
        AttributeInstance attr = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        if (attr == null) return;
        int target = (int) Math.min(VANILLA_ATTRIBUTE_MAX, Math.ceil(wanted - 1));
        if (attr.getBaseValue() >= target || requestedValue == target) return;
        requestedValue = target;
        requestedTick = player.tickCount;
        player.connection.sendCommand("attribute @s minecraft:block_interaction_range base set " + target);
    }

    /**
     * 刚发出调高交互距离的指令、还没生效（最多等 {@link #RAISE_WAIT_TICKS} tick）。
     * 这几 tick 打印机先不动：范围还是原版的 4.5 格，分层打印会从眼睛附近的层开始，而不是最底层。
     */
    public static boolean isRaising() {
        LocalPlayer player = mc.player;
        if (player == null || requestedValue < 0 || player != requestedFor || !mc.hasSingleplayerServer()) return false;
        if (!ConfigUtils.isPrinterEnable() || !Configs.Core.AUTO_RAISE_REACH.getBooleanValue()) return false;
        AttributeInstance attr = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        if (attr == null || attr.getBaseValue() >= requestedValue) return false;
        int waited = player.tickCount - requestedTick;
        return waited >= 0 && waited <= RAISE_WAIT_TICKS;
    }
}
