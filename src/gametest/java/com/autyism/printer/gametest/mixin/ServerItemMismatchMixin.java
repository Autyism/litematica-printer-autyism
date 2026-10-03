package com.autyism.printer.gametest.mixin;

import com.autyism.printer.gametest.UseRecord;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 测试用：服务端处理放置包时手里的物品和客户端发包时的不一样，就打印 [ITEM-MISMATCH]（平时什么都不打）。
 * 用来抓“偶尔放错成别的方块”这种很难复现的问题：不用开大量调试日志（日志一多时序就变了，问题就不出现了）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerItemMismatchMixin {
    @Shadow
    public ServerPlayer player;

    @org.spongepowered.asm.mixin.Unique
    private UseRecord.Entry gt$current;

    /** 方向类属性：只有这些不一样时才算“朝向放错” */
    @org.spongepowered.asm.mixin.Unique
    private static final java.util.Set<String> GT_ORIENT_PROPS = java.util.Set.of("facing", "axis", "rotation", "orientation", "hinge", "half", "face");

    /** 放完之后：方块对了但朝向类属性和投影不一样 → 打印 [ORIENT-MISMATCH]（服务端此刻的视角 + 最近收到的视角包） */
    @Inject(method = "handleUseItemOn", at = @At("TAIL"))
    private void gt$checkOrientation(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread()) return;
        UseRecord.Entry client = gt$current;
        gt$current = null;
        if (client == null || client.workPos() == null || client.wanted() == null) return;
        var placed = this.player.level().getBlockState(client.workPos());
        var wanted = client.wanted();
        if (placed.getBlock() != wanted.getBlock()) return;
        StringBuilder diff = new StringBuilder();
        for (var prop : wanted.getProperties()) {
            if (!GT_ORIENT_PROPS.contains(prop.getName())) continue;
            if (!placed.getValue(prop).equals(wanted.getValue(prop))) {
                diff.append(prop.getName()).append('=').append(wanted.getValue(prop)).append("->").append(placed.getValue(prop)).append(' ');
            }
        }
        if (diff.isEmpty()) return;
        System.out.println("[ORIENT-MISMATCH] seq=" + packet.getSequence() + " pos=" + client.workPos().toShortString() + " " + diff
                + "click=" + client.target() + " serverRot=" + this.player.getYRot() + "/" + this.player.getXRot() + " head=" + this.player.getYHeadRot()
                + " tick=" + this.player.tickCount + " recentRot=[" + UseRecord.recentServerRotations() + "]");
    }

    /** 服务端收到的视角（最近 8 个） */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void gt$noteRotation(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread() || !packet.hasRotation()) return;
        UseRecord.noteServerRotation("t" + this.player.tickCount + " " + packet.getClass().getSimpleName() + " " + packet.getYRot(0f) + "/" + packet.getXRot(0f));
    }

    /** 每个世界开头的“切换格子”服务端都打印出来（和客户端的 [client-carried-send] 对照） */
    @Inject(method = "handleSetCarriedItem", at = @At("TAIL"))
    private void gt$logEarlyCarried(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread() || this.player.tickCount >= 400) return;
        System.out.println("[server-carried-early] slot=" + packet.getSlot() + " now=" + this.player.getInventory().getSelectedSlot()
                + " tick=" + this.player.tickCount);
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void gt$checkItem(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread()) return; // 网络线程那一次只是转交给主线程
        UseRecord.Entry client = UseRecord.take(packet.getSequence());
        gt$current = client;
        if (client == null || client.item().equals("minecraft:air")) return; // 生存模式最后一个用掉后客户端手里是空的：不算
        String server = this.player.getItemInHand(packet.getHand()).getItem().toString();
        if (server.equals(client.item())) return;
        StringBuilder hotbar = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            var s = this.player.getInventory().getItem(i);
            hotbar.append(i).append(':').append(s.isEmpty() ? "-" : s.getItem().toString().replace("minecraft:", "")).append(' ');
        }
        System.out.println("[ITEM-MISMATCH] seq=" + packet.getSequence() + " target=" + client.target() + " client=" + client.item()
                + " (slot " + client.slot() + ", tick " + client.tick() + ") server=" + server + " (slot "
                + this.player.getInventory().getSelectedSlot() + ") serverHotbar=" + hotbar);
    }
}
