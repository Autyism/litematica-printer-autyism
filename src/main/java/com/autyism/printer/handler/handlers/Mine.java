package com.autyism.printer.handler.handlers;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.HighlightType;
import com.autyism.printer.enums.MiningFilterType;
import com.autyism.printer.handler.Module;
import com.autyism.printer.printer.BlockPosCooldownManager;
import com.autyism.printer.mixin.extension.BlockBreakResult;
import com.autyism.printer.utils.BreakUtils;
import com.autyism.printer.utils.ModUtils;
import com.autyism.printer.utils.PinYinSearchUtils;
import com.autyism.printer.utils.PlayerUtils;
import com.autyism.printer.utils.ToolSwitchUtils;
import com.autyism.printer.utils.MiningUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicReference;

public class Mine extends Module {
    public final static String NAME = "mine";
    // 仅秒破模式下，不能秒破的方块的重试间隔（换了工具/药水效果后会重新判断）
    private static final int INSTANT_ONLY_SKIP_COOLDOWN = 4;

    public Mine() {
        super(NAME, Configs.Mine.ENABLED, Configs.Mine.MINE_SELECTION_TYPE, true);
    }

    public static boolean mineRestriction(BlockState blockState) {
        if (!BreakUtils.breakRestriction(blockState)) {
            return false;
        }
        if (Configs.Mine.EXCAVATE_LIMITER.getOptionListValue().equals(MiningFilterType.TWEAKEROO)) {
            if (!ModUtils.isTweakerooLoaded()) return true;
            return com.autyism.printer.compat.TweakerooCompat.isBreakAllowed(blockState);
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

    // ---------------- 秒破优先 ----------------
    /** 当前处于“只挖可秒破”的阶段（上一轮遍历发现了可秒破的方块） */
    private boolean instantPhase = true;
    private boolean sawInstantThisPass;

    private boolean isInstantFirstActive() {
        if (player.getAbilities().instabuild) return false;
        return !Configs.Mine.MINE_INSTANT_FIRST_DETECT.getBooleanValue() || MiningUtils.hasInstantMiningSetup(player);
    }

    @Override
    protected void onScanPassFinished() {
        // 本轮没有可秒破的方块了 → 开始挖不能秒破的；一旦又出现可秒破的（例如走动了），下一轮重新优先秒破
        instantPhase = sawInstantThisPass;
        sawInstantThisPass = false;
    }

    @Override
    public void resetScanState() {
        super.resetScanState();
        instantPhase = true;
        sawInstantThisPass = false;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (isInstantFirstActive()) {
            BlockState state = level.getBlockState(blockPos);
            if (MiningUtils.canInstantBreak(player, state)) {
                sawInstantThisPass = true;
                instantPhase = true;
            } else if (instantPhase) {
                // 范围内还有可秒破的方块：这个先跳过
                this.setCooldown(blockPos, INSTANT_ONLY_SKIP_COOLDOWN);
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
