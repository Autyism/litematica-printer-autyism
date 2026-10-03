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
    /** 水平转头后至少等这么多 tick 再放置 */
    private static final int LOOK_WAIT_TICKS = 2;
    private int lookWaitTicks;
    /** 服务端最后收到的水平朝向，以及从哪个 tick 开始一直是这个朝向 */
    @Nullable
    private Direction serverHeadDir;
    private int serverHeadDirSince;

    private static int tickNow() {
        var p = net.minecraft.client.Minecraft.getInstance().player;
        return p == null ? 0 : p.tickCount;
    }

    /** 每个发出去的带视角的移动包都会调用（见 PacketUtils.getFixedPacket） */
    public void noteSentRotation(float yaw) {
        Direction d = Direction.fromYRot(yaw);
        if (d != serverHeadDir) {
            serverHeadDir = d;
            serverHeadDirSince = tickNow();
        }
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
        this.repeat = 1;
    }

    /** 同一次点击连续发几下（音符盒调音、中继器调延迟：一次把需要的次数点完，不用一下一下等冷却） */
    private int repeat = 1;

    public void setRepeat(int repeat) {
        this.repeat = Math.max(1, repeat);
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

        if (!useProtocol && !needWaitModifyLook && actionRequiresWaitModifyLook) {
            if (look != null) {
                Direction lookDirection = BlockUtils.orderedByNearest(look.yaw(), look.pitch())[0];
                // 服务端按“头部朝向”(yHeadRot) 判断水平朝向，而头部朝向要等服务端给玩家 tick 之后才跟上转头包；
                // 只等 1 tick 时转头包和放置包常常落在同一个服务端 tick 里，结果用的是上一个方块的朝向。
                // （不能用“客户端视角已经朝那边”来省掉等待：打印机自己发的转头包会把服务端的头转走）
                // 服务端的头已经朝这个水平方向至少 LOOK_WAIT_TICKS 个 tick 了（例如连续放同朝向的楼梯）就不用再等
                boolean headReady = serverHeadDir == lookDirection && tickNow() - serverHeadDirSince >= LOOK_WAIT_TICKS;
                if (lookDirection.getAxis().isHorizontal() && !headReady) {
                    needWaitModifyLook = true;
                    lookWaitTicks = LOOK_WAIT_TICKS;
                    return this;
                }
            }
        }

        if (needWaitModifyLook) {
            if (--lookWaitTicks > 0) return this;
            needWaitModifyLook = false;
        }

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
            for (int i = 0; i < repeat; i++) {
                gameModeExtension.litematica_printer$useItemOn(localPrediction, InteractionHand.MAIN_HAND, blockHitResult);
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
        this.repeat = 1;
    }
}