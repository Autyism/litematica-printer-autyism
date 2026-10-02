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
        player.connection.sendCommand("attribute @s minecraft:block_interaction_range base set " + target);
    }
}
