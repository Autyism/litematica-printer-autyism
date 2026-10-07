package com.autyism.printer.mixin.printer.mc;

import com.autyism.printer.mixin.extension.BlockBreakResult;
import com.autyism.printer.config.Configs;
import com.autyism.printer.mixin.extension.MultiPlayerGameModeExtension;
import com.autyism.printer.utils.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@SuppressWarnings("DataFlowIssue")
@Mixin(value = MultiPlayerGameMode.class, priority = 1020)
public abstract class MixinMultiPlayerGameMode implements MultiPlayerGameModeExtension {
    // @formatter:off
    @Shadow private BlockPos destroyBlockPos;
    @Shadow private ItemStack destroyingItem;
    @Shadow private float destroyProgress;
    @Shadow private boolean isDestroying;
    @Shadow @Final private Minecraft minecraft;
    @Shadow public abstract boolean destroyBlock(final BlockPos pos);
    @Shadow protected abstract boolean sameDestroyTarget(final BlockPos pos);
    @Shadow protected abstract void ensureHasSentCarriedItem();
    @Shadow public abstract InteractionResult useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult blockHitResult);
    // @formatter:on

    @Override
    public BlockPos litematica_printer$destroyBlockPos() {
        return destroyBlockPos;
    }

    @Override
    public boolean litematica_printer$isDestroying() {
        return isDestroying;
    }

    @Override
    public void litematica_printer$startPrediction(PredictiveAction predictiveAction) {
        PacketUtils.sendPacket(predictiveAction);
    }

    @Override
    public InteractionResult litematica_printer$useItemOn(boolean localPrediction, InteractionHand hand, BlockHitResult blockHit) {
        if (localPrediction) {
            com.autyism.printer.printer.ContainerGuard.beginPrinterInteraction();
            try {
                return useItemOn(minecraft.player, hand, blockHit);
            } finally {
                com.autyism.printer.printer.ContainerGuard.endPrinterInteraction();
            }
        }
        this.ensureHasSentCarriedItem();
        if (!this.minecraft.level.getWorldBorder().isWithinBounds(blockHit.getBlockPos())) {
            return InteractionResult.FAIL;
        }
        litematica_printer$startPrediction((sequence) -> new ServerboundUseItemOnPacket(hand, blockHit, sequence));
        return InteractionResult.PASS;
    }

    // ---------------- 需求 1：记录玩家自己发起的、可能打开容器的交互 ----------------
    @org.spongepowered.asm.mixin.injection.Inject(method = "useItemOn", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void ale$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                 org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<InteractionResult> cir) {
        com.autyism.printer.printer.ContainerGuard.onUseItemOn(player, hit.getBlockPos());
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "useItem", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void ale$onUseItem(net.minecraft.world.entity.player.Player player, InteractionHand hand,
                               org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<InteractionResult> cir) {
        if (player instanceof LocalPlayer lp) com.autyism.printer.printer.ContainerGuard.onUseItem(lp);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "interact", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    //? if >=26.1 {
    /*private void ale$onInteract(net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.EntityHitResult hit, InteractionHand hand,
                                org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<InteractionResult> cir) {
    *///?} else {
    private void ale$onInteract(net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.Entity entity, InteractionHand hand,
                                org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<InteractionResult> cir) {
    //?}
        com.autyism.printer.printer.ContainerGuard.onInteractEntity(entity);
    }

    @Unique
    private float litematica_printer$GetBreakingProgressMax() {
        int value = Configs.Break.BREAK_PROGRESS_THRESHOLD.getIntegerValue();
        if (value < 70) {
            value = 70;
        } else if (value > 100) {
            value = 100;
        }
        return (float) value / 100;
    }

    @Unique
    private int litematica_printer$GetDestroyStage() {
        float breakingProgress = destroyProgress >= litematica_printer$GetBreakingProgressMax() ? 1.0F : destroyProgress;
        return breakingProgress > 0.0F ? (int) (breakingProgress * 10.0F) : -1;
    }

    @Unique
    private ServerboundPlayerActionPacket litematica_printer$GetServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action action, BlockPos blockPos, Direction direction, int sequence) {
        return new ServerboundPlayerActionPacket(action, blockPos, direction, sequence);
    }

    /**
     * 开始挖掘方块的核心方法
     * 处理权限检查、创造模式特殊逻辑、生存模式挖掘进度初始化
     * 
     * @param blockPos 目标方块位置
     * @param direction 挖掘方向
     * @param player 玩家实例
     * @param level 客户端世界
     * @param gameMode 游戏模式管理器
     * @param localPrediction 是否使用本地预测
     * @return 挖掘结果状态
     */
    @Unique
    private BlockBreakResult litematica_printer$startDestroyBlock(BlockPos blockPos, Direction direction, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode, boolean localPrediction) {
        if (player.blockActionRestricted(level, blockPos, gameMode.getPlayerMode())) {
            return BlockBreakResult.FAILED;
        }
        if (!level.getWorldBorder().isWithinBounds(blockPos)) {
            return BlockBreakResult.FAILED;
        }

        if (player.getAbilities().instabuild) {
            PacketUtils.sendPacket(i -> {
                if (localPrediction) {
                    destroyBlock(blockPos);
                }
                return litematica_printer$GetServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, direction, i);
            });
            return BlockBreakResult.COMPLETED;
        }

        if (this.isDestroying && !this.sameDestroyTarget(blockPos)) {
            PacketUtils.sendPacket(litematica_printer$GetServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos, direction, 0));
        }

        BlockState blockState = level.getBlockState(blockPos);
        boolean isSolidBlock = !blockState.isAir();
        
        // 空气方块无法破坏
        if (!isSolidBlock) {
            return BlockBreakResult.FAILED;
        }
        
        float destroyProgress = blockState.getDestroyProgress(player, level, blockPos);

        // 原版秒破：START 时服务端算出进度 >= 1 直接破坏，与原版客户端一致只发 START
        if (destroyProgress >= 1.0F) {
            if (localPrediction) {
                destroyBlock(blockPos);
            }
            PacketUtils.sendPacket(sequence -> litematica_printer$GetServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, direction, sequence));
            return BlockBreakResult.COMPLETED;
        }
        // 同 tick 快速破坏：服务端在 STOP 时按 进度 x (已过tick+1) >= 0.7 判定，同 tick 内即 进度 >= 0.7。
        // 原实现阈值为 0.5，进度在 0.5~0.7 的方块客户端先删了但服务端不认，变成幽灵方块。
        if (Configs.Break.BREAK_INSTANT_MINE.getBooleanValue() && destroyProgress >= MiningUtils.instantThreshold()) {
            if (localPrediction) {
                destroyBlock(blockPos);
            }
            PacketUtils.sendPacket(sequence -> litematica_printer$GetServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, direction, sequence));
            PacketUtils.sendPacket(sequence -> litematica_printer$GetServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence));
            return BlockBreakResult.COMPLETED;
        }
        
        // 渐进式破坏：初始化破坏状态
        this.isDestroying = true;
        this.destroyBlockPos = blockPos;
        this.destroyProgress = 0.0F;
        this.destroyingItem = player.getMainHandItem();

        if (localPrediction) {
            if (this.destroyProgress == 0.0F) {
                blockState.attack(level, blockPos, player);
            }
            level.destroyBlockProgress(player.getId(), this.destroyBlockPos, this.litematica_printer$GetDestroyStage());
        }
        
        // 发送开始破坏包
        PacketUtils.sendPacket(sequence -> litematica_printer$GetServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, direction, sequence));
        
        return BlockBreakResult.IN_PROGRESS;
    }

    @Override
    public BlockBreakResult litematica_printer$continueDestroyBlock(boolean localPrediction, BlockPos blockPos, Direction direction) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        MultiPlayerGameMode gameMode = minecraft.gameMode;

        if (player == null || level == null || gameMode == null) {
            return BlockBreakResult.FAILED;
        }

        if (player.getAbilities().instabuild && level.getWorldBorder().isWithinBounds(blockPos)) {
            PacketUtils.sendPacket(sequence -> {
                if (localPrediction) destroyBlock(blockPos);
                return litematica_printer$GetServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, direction, sequence);
            });
            return BlockBreakResult.COMPLETED;
        }

        // 自动换工具 + 耐久保护（独立实现，不依赖 Tweakeroo）：工具快坏且没有可替代物品时放弃本次挖掘
        if (!com.autyism.printer.utils.ToolSwitchUtils.prepareToolForBreaking(blockPos)) {
            if (this.sameDestroyTarget(blockPos)) this.isDestroying = false;
            return BlockBreakResult.FAILED;
        }
        // 切换工具后必须先把新的手持槽位发给服务端，再发破坏包；
        // 否则服务端按旧工具计算进度，客户端已预测破坏而服务端不破坏 → 大范围秒破时出现大量幽灵方块
        ensureHasSentCarriedItem();

        if (this.sameDestroyTarget(blockPos)) {
            BlockState blockState = level.getBlockState(blockPos);
            if (blockState.isAir()) {
                this.isDestroying = false;
                return BlockBreakResult.COMPLETED;
            }

            this.destroyProgress += blockState.getDestroyProgress(player, level, blockPos);
            boolean completed = this.destroyProgress >= litematica_printer$GetBreakingProgressMax();

            if (completed) {
                this.isDestroying = false;
                PacketUtils.sendPacket(sequence -> {
                    if (localPrediction) destroyBlock(blockPos);
                    return litematica_printer$GetServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence);
                });
                this.destroyProgress = 0.0F;
            }

            if (localPrediction) {
                level.destroyBlockProgress(player.getId(), this.destroyBlockPos, this.litematica_printer$GetDestroyStage());
            }

            return completed ? BlockBreakResult.COMPLETED : BlockBreakResult.IN_PROGRESS;
        }

        return this.litematica_printer$startDestroyBlock(blockPos, direction, player, level, gameMode, localPrediction);
    }
}