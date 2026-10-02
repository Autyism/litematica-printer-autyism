package com.autyism.ale.handler.handlers;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import com.autyism.ale.config.Configs;
import com.autyism.ale.enums.HighlightType;
import com.autyism.ale.enums.MiningFilterType;
import com.autyism.ale.handler.Module;
import com.autyism.ale.printer.BlockPosCooldownManager;
import com.autyism.ale.mixin.extension.BlockBreakResult;
import com.autyism.ale.utils.BreakUtils;
import com.autyism.ale.utils.ModUtils;
import com.autyism.ale.utils.PinYinSearchUtils;
import com.autyism.ale.utils.PlayerUtils;
import com.autyism.ale.utils.ToolSwitchUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicReference;

public class Mine extends Module {
    public final static String NAME = "mine";
    // 仅秒破模式下，不能秒破的方块的重试间隔（换了工具/药水效果后会重新判断）
    private static final int INSTANT_ONLY_SKIP_COOLDOWN = 20;

    public Mine() {
        super(NAME, Configs.Mine.ENABLED, Configs.Mine.MINE_SELECTION_TYPE, true);
    }

    public static boolean mineRestriction(BlockState blockState) {
        if (!BreakUtils.breakRestriction(blockState)) {
            return false;
        }
        if (Configs.Mine.EXCAVATE_LIMITER.getOptionListValue().equals(MiningFilterType.TWEAKEROO)) {
            if (!ModUtils.isTweakerooLoaded()) return true;
            return com.autyism.ale.compat.TweakerooCompat.isBreakAllowed(blockState);
        } else {
            IConfigOptionListEntry optionListValue = Configs.Mine.EXCAVATE_LIMIT.getOptionListValue();
            if (optionListValue == UsageRestriction.ListType.BLACKLIST) {
                return Configs.Mine.EXCAVATE_BLACKLIST.getStrings().stream()
                        .noneMatch(string -> PinYinSearchUtils.matchBlockName(string, blockState));
            } else if (optionListValue == UsageRestriction.ListType.WHITELIST) {
                return Configs.Mine.EXCAVATE_WHITELIST.getStrings().stream()
                        .anyMatch(string -> PinYinSearchUtils.matchBlockName(string, blockState));
            } else {
                return true;
            }
        }
    }

    @Override
    protected int getTickInterval() {
        return Configs.Break.BREAK_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxExecutions() {
        return Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        if (isOnCooldown(pos) || BlockPosCooldownManager.INSTANCE.isOnCooldown(level, FluidRemoval.NAME, pos)) {
            return false;
        }
        return BreakUtils.canBreakBlock(pos) && mineRestriction(level.getBlockState(pos));
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        return level.getBlockState(pos).isAir();
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (Configs.Mine.MINE_INSTANT_ONLY.getBooleanValue() && !player.getAbilities().instabuild) {
            // 先换到最合适的工具，再判断能否秒破
            if (!ToolSwitchUtils.prepareToolForBreaking(blockPos)) {
                this.setCooldown(blockPos, INSTANT_ONLY_SKIP_COOLDOWN);
                return;
            }
            BlockState state = level.getBlockState(blockPos);
            float required = Configs.Break.BREAK_INSTANT_MINE.getBooleanValue() ? 0.7F : 1.0F;
            // 实时进度：与原版一致，使用服务端同步下来的属性（效率附魔在 1.21 是属性），即服务端此刻的算法
            float progress = state.getDestroyProgress(player, level, blockPos);
            if (progress < required) {
                // 稳态进度：按工具自身附魔计算。换工具后服务端要过 1~3 tick 才同步新属性，这期间实时进度偏低。
                // 稳态能秒破 → 只是属性未同步，下一 tick 重试；否则确实不能秒破，跳过且不进入持续挖掘阻塞其他方块
                float steady = PlayerUtils.getDestroyProgress(player, state);
                this.setCooldown(blockPos, steady >= required ? 1 : INSTANT_ONLY_SKIP_COOLDOWN);
                return;
            }
        }
        BlockBreakResult result = BreakUtils.INSTANCE.continueDestroyBlock(blockPos);
        addHighlight(blockPos, HighlightType.BREAK);
        if (result == BlockBreakResult.IN_PROGRESS || result == BlockBreakResult.COMPLETED_WAIT) {
            skipIteration.set(true);
        }
        this.setCooldown(blockPos, getBreakCooldown());
    }

}