package com.autyism.printer.printer.action;

import com.autyism.printer.printer.ActionManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ClickAction extends Action {
    /** 连续点几下（例如音符盒从 3 调到 16 要点 13 下） */
    private int clicks = 1;

    public ClickAction setClicks(int clicks) {
        this.clicks = Math.max(1, clicks);
        return this;
    }

    @Override
    public Action queueAction(@NotNull BlockPos blockPos, @NotNull Direction side, boolean useShift, @NotNull LocalPlayer player) {
        ActionManager.INSTANCE.queueClick(blockPos, side, getSides().get(side), false, blockPos);
        ActionManager.INSTANCE.setRepeat(clicks);
        return this;
    }

    @Override
    public @Nullable Item[] getRequiredItems(Block backup) {
        return this.clickItems;
    }

    /**
     * 获取有效的侧面。
     * <p>
     * 遍历所有侧面并返回第一个可用的方向，
     * 如果没有可用的侧面，则返回 null 。
     *
     * @param world 当前的 ClientLevel 实例
     * @param pos   块的位置
     * @return 第一个有效侧面，如果不存在则返回 null
     */
    @Override
    public @Nullable Direction getValidSide(ClientLevel world, BlockPos pos) {
        for (Direction side : getSides().keySet()) {
            return side;
        }
        return null;
    }
}
