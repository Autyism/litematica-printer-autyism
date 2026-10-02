package com.autyism.ale.handler.handlers;

import com.autyism.ale.I18n;
import com.autyism.ale.utils.ModUtils;
import com.autyism.ale.config.Configs;
import com.autyism.ale.enums.HighlightType;
import com.autyism.ale.handler.Module;
import com.autyism.ale.compat.BedrockCompat;
import com.autyism.ale.utils.MessageUtils;
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
        BedrockCompat.ensureWorking();
        return true;
    }

    @Override
    protected boolean respectsRenderLayer() {
        return !Configs.Bedrock.IGNORE_RENDER_LAYER.getBooleanValue();
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.BEDROCK);
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        return !level.getBlockState(pos).is(Blocks.BEDROCK);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        BedrockCompat.addToBreakList(blockPos, client.level);
        addHighlight(blockPos, HighlightType.BREAK);
        setCooldown(blockPos, 100);
    }
}