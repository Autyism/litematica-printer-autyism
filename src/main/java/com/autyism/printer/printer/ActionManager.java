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
    /**
     * 服务端判断活塞、侦测器、发射器、贴墙方块等的朝向时用的是“头部朝向”，而头部朝向要等服务端处理这个玩家的
     * 下一个 tick 才跟上视角包。固定等几个 tick 不可靠：网络卡一下时转头包和放置包会一起到达、挤进同一次处理。
     * <p>
     * 可靠的办法：转头之后发一个“停止挖掘一个够不着的格子”的包当标记（服务端什么都不做，只回确认），
     * 收到它的确认再放。服务端回确认之后才会在同一个 tick 里更新头部朝向，而我们之后发的放置包
     * 要等这个 tick 结束才会被处理，所以那时头一定已经转过去了。
     */
    @Nullable
    private Direction serverHeadDir;
    /** 最后一次改变水平朝向的视角包发出时的动作序号：之后发出的动作被确认 = 服务端已经按新朝向 tick 过 */
    private int headRotationSeq = Integer.MIN_VALUE;
    private boolean headMarkerSent;
    private int headWaitStart;
    /** 收不到确认时最多等多久（之后照常放，避免卡住） */
    private static final int HEAD_WAIT_TIMEOUT_TICKS = 40;

    private static int tickNow() {
        var p = net.minecraft.client.Minecraft.getInstance().player;
        return p == null ? 0 : p.tickCount;
    }

    private static int currentSequence() {
        return Reference.MINECRAFT.level instanceof PacketUtils.SequenceExtension seq ? seq.litematica_printer3$currentSequence() : 0;
    }

    /** 每个发出去的带视角的移动包都会调用（见 PacketUtils.getFixedPacket） */
    public void noteSentRotation(float yaw) {
        Direction d = Direction.fromYRot(yaw);
        if (d != serverHeadDir) {
            serverHeadDir = d;
            headRotationSeq = currentSequence();
            headMarkerSent = false;
        }
    }

    /** 服务端的头已经确定朝 d 了 */
    private boolean headReady(Direction d) {
        return serverHeadDir == d && Reference.MINECRAFT.level != null
                && ActionConfirm.lastAcked(Reference.MINECRAFT.level) > headRotationSeq;
    }

    /** 发标记包：停止挖掘一个远在触及范围之外的格子（服务端判定“太远”，什么都不做，只回确认） */
    private void sendHeadMarker(LocalPlayer player) {
        BlockPos far = player.blockPosition().offset(512, 0, 0);
        PacketUtils.sendPacket(sequence -> new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, far, Direction.DOWN, sequence));
    }

    private static final boolean DEBUG_LOOK = Boolean.getBoolean("ale.debuglook");
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
        if (DEBUG_LOOK) {
            System.out.println("[printer-look] target=" + target + " side=" + side + " look=" + look + " needWait=" + needWaitModifyLook
                    + " requiresWait=" + actionRequiresWaitModifyLook + " playerRot=" + player.getYRot() + "/" + player.getXRot());
        }

        if (!useProtocol && actionRequiresWaitModifyLook && look != null) {
            Direction lookDirection = BlockUtils.orderedByNearest(look.yaw(), look.pitch())[0];
            // 水平朝向：等服务端确认头已经转过去（见 serverHeadDir 的说明）；连续放同朝向的方块不用再等
            if (lookDirection.getAxis().isHorizontal() && !headReady(lookDirection)) {
                if (!headMarkerSent) {
                    sendHeadMarker(player); // 在上面的视角包之后发，服务端按顺序处理
                    headMarkerSent = true;
                    headWaitStart = tickNow();
                }
                if (tickNow() - headWaitStart <= HEAD_WAIT_TIMEOUT_TICKS && tickNow() >= headWaitStart) {
                    needWaitModifyLook = true;
                    return this;
                }
            }
        }
        needWaitModifyLook = false;

        Vec3 hitVec;
        if (!useProtocol) {
            Vec3 targetCenter = Vec3.atCenterOf(target);
            Vec3 sideOffset = Vec3.atLowerCornerOf(BlockUtils.getVector(side)).scale(0.5);
            // hitModifier 是世界坐标系下的偏移（只有门的门轴用到了水平分量，其余都是竖直分量）。
            // 原来这里按 (朝向角度+90) 去 yRot，但 yRot 的参数是弧度，结果是一个毫无意义的随机旋转，门轴因此会放反
            Vec3 rotatedHitModifier = hitModifier.scale(0.5);
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
            if (DEBUG_LOOK) {
                System.out.println("[printer-use] target=" + target.toShortString() + " hand=" + player.getMainHandItem().getItem()
                        + " slot=" + player.getInventory().getSelectedSlot() + " stateId=" + player.inventoryMenu.getStateId()
                        + " tick=" + player.tickCount);
            }
            gameModeExtension.litematica_printer$useItemOn(localPrediction, InteractionHand.MAIN_HAND, blockHitResult);
            // 记下这次动作的序号：服务端确认之前不再碰这个格子（见 ActionConfirm）
            if (Reference.MINECRAFT.level instanceof com.autyism.printer.utils.PacketUtils.SequenceExtension seq) {
                ActionConfirm.sent(Reference.MINECRAFT.level, target, side, seq.litematica_printer3$currentSequence(), player.tickCount,
                        player.getMainHandItem().getItem());
            }
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