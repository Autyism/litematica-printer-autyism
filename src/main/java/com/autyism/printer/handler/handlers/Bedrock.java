package com.autyism.printer.handler.handlers;

import com.autyism.printer.I18n;
import com.autyism.printer.utils.ModUtils;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.HighlightType;
import com.autyism.printer.handler.Module;
import com.autyism.printer.compat.BedrockCompat;
import com.autyism.printer.utils.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicReference;

public class Bedrock extends Module {
    public final static String NAME = "bedrock";

    public Bedrock() {
        super(NAME, Configs.Bedrock.ENABLED, null, true);
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
    protected boolean canExecute() {
        if (player.isCreative()) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_CREATIVE_MODE.getName());
            return false;
        }
        if (!BedrockCompat.isAvailable()) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_MOD_MISSING.getName());
            return false;
        }
        // bunnyi116 的破基岩模组在 Meteor 的 NoGhostBlocks（防幽灵方块）开着时什么都破不掉（测试过放置、挖掘两部分都会这样），
        // lxyan2333 的版本没问题。不拦着（也许以后的版本修好了），只提示
        if (BedrockCompat.isBunnyiBackend() && com.autyism.printer.compat.MeteorCompat.isNoGhostBlocksActive()) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_NOGHOST_CONFLICT.getName());
        }
        BedrockCompat.ensureWorking();
        BedrockCompat.syncAllowList();
        return true;
    }

    @Override
    protected boolean respectsRenderLayer() {
        return !Configs.Bedrock.IGNORE_RENDER_LAYER.getBooleanValue();
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        return BedrockCompat.isTargetBlock(level.getBlockState(pos));
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        return !BedrockCompat.isTargetBlock(level.getBlockState(pos));
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        BedrockCompat.addToBreakList(blockPos, client.level);
        addHighlight(blockPos, HighlightType.BREAK);
        setCooldown(blockPos, 100);
    }
}