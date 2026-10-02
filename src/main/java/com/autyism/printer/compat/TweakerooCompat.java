package com.autyism.printer.compat;

import com.autyism.printer.utils.PinYinSearchUtils;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tweakeroo 可选联动：仅在 Tweakeroo 已加载时才会被调用（调用方先判断 ModUtils.isTweakerooLoaded()），
 * 因此本类引用 Tweakeroo 的类不会在未安装时触发类加载错误。
 */
public final class TweakerooCompat {
    private TweakerooCompat() {
    }

    public static boolean isBreakAllowed(BlockState blockState) {
        UsageRestriction.ListType listType = fi.dy.masa.tweakeroo.tweaks.PlacementTweaks.BLOCK_TYPE_BREAK_RESTRICTION.getListType();
        if (listType == UsageRestriction.ListType.BLACKLIST) {
            return fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST.getStrings().stream()
                    .noneMatch(string -> PinYinSearchUtils.matchBlockName(string, blockState));
        } else if (listType == UsageRestriction.ListType.WHITELIST) {
            return fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST.getStrings().stream()
                    .anyMatch(string -> PinYinSearchUtils.matchBlockName(string, blockState));
        }
        return true;
    }
}
