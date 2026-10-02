package com.autyism.printer.printer;

import lombok.Setter;
import com.autyism.printer.Reference;
import com.autyism.printer.config.Configs;
import com.autyism.printer.mixin.extension.MultiPlayerGameModeExtension;
import com.autyism.printer.utils.BlockUtils;
import com.autyism.printer.utils.LitematicaUtils;
import com.autyism.printer.utils.PacketUtils;
import com.autyism.printer.utils.PlayerUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

public class ActionManager {
    public static final ActionManager INSTANCE = new ActionManager();

    public BlockPos target;
    @Nullable
    public BlockPos workPos;
    public Direction side;
    public Vec3 hitModifier;
    public boolean useShift = false;
    public boolean useProtocol = false;
    @Setter
    @Nullable
    public PlayerLook look;
    public boolean needWaitModifyLook = false;
    private boolean actionRequiresWaitModifyLook = false;

    private ActionManager() {
    }

    public void queueClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift) {
        queueClick(target, side, hitModifier, useShift, null);
    }

    /**
     * @param workPos 本次操作针对的方块坐标（放置/交互的目标），延迟发送前用于复核渲染层与可达性
     */
    public void queueClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift, @Nullable BlockPos workPos) {
        // 旧队列未发送就被新操作覆盖：直接替换。
        // 原实现在此丢弃新操作，但调用方随后仍会写入新的 look/hitModifier 并发送，导致旧坐标配上新朝向 → 放错方块/方向。
        this.target = target;
        this.workPos = workPos == null ? null : workPos.immutable();
        this.side = side;
        this.hitModifier = hitModifier;
        this.useShift = useShift;
    }

    public ActionManager sendQueue(LocalPlayer player) {
        if (target == null || side == null || hitModifier == null) {
            clearQueue();
            return this;
        }
        // 等待转头期间玩家可能已跳跃/移动或切换了渲染层：发送前复核，避免把方块放到未选择的层或够不着的位置
        if (workPos != null && (!LitematicaUtils.isPositionWithinRange(workPos) || !PlayerUtils.canInteracted(workPos))) {
            clearQueue();
            return this;
        }
        if (look != null) {
            PacketUtils.sendLookPacket(player, look);
        }

        if (!useProtocol && !needWaitModifyLook && actionRequiresWaitModifyLook) {
            if (look != null) {
                Direction lookDirection = BlockUtils.orderedByNearest(look.yaw(), look.pitch())[0];
                if (lookDirection.getAxis().isHorizontal()) {
                    needWaitModifyLook = true;
                    return this;
                }
            }
        }

        if (needWaitModifyLook) {
            needWaitModifyLook = false;
        }

        Direction direction;
        if (look == null) {
            direction = side;
        } else {
            direction = BlockUtils.getHorizontalDirection(look.yaw());
        }
        Vec3 hitVec;
        if (!useProtocol) {
            Vec3 targetCenter = Vec3.atCenterOf(target);
            Vec3 sideOffset = Vec3.atLowerCornerOf(BlockUtils.getVector(side)).scale(0.5);
            Vec3 rotatedHitModifier = hitModifier.yRot((direction.toYRot() + 90) % 360).scale(0.5);
            hitVec = targetCenter.add(sideOffset).add(rotatedHitModifier);
        } else {
            hitVec = hitModifier;
        }
        boolean wasSneak = player.isShiftKeyDown();
        if (useShift && !wasSneak) {
            setShift(player, true);
        } else if (!useShift && wasSneak) {
            setShift(player, false);
        }
        MultiPlayerGameModeExtension gameModeExtension = (MultiPlayerGameModeExtension) Reference.MINECRAFT.gameMode;
        if (gameModeExtension != null) {
            boolean localPrediction = !Configs.Placement.PRINT_USE_PACKET.getBooleanValue();
            BlockHitResult blockHitResult = new BlockHitResult(hitVec, side, target, false);
            gameModeExtension.litematica_printer$useItemOn(localPrediction, InteractionHand.MAIN_HAND, blockHitResult);
        }
        if (useShift && !wasSneak) {
            setShift(player, false);
        } else if (!useShift && wasSneak) {
            setShift(player, true);
        }
        clearQueue();
        return this;
    }

    public void setNeedWaitModifyLookFromAction(boolean needWaitModifyLook) {
        this.actionRequiresWaitModifyLook = needWaitModifyLook;
    }

    public void setShift(LocalPlayer player, boolean shift) {
        Input input = new Input(player.input.keyPresses.forward(), player.input.keyPresses.backward(), player.input.keyPresses.left(), player.input.keyPresses.right(), player.input.keyPresses.jump(), shift, player.input.keyPresses.sprint());
        ServerboundPlayerInputPacket packet = new ServerboundPlayerInputPacket(input);
        player.setShiftKeyDown(shift);
        PacketUtils.sendPacket(packet);
    }

    public void clearQueue() {
        this.target = null;
        this.workPos = null;
        this.side = null;
        this.hitModifier = null;
        this.useShift = false;
        this.useProtocol = false;
        this.needWaitModifyLook = false;
        this.actionRequiresWaitModifyLook = false;
        this.look = null;
    }
}