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
        //? if >=26.3 {
        /*System.out.println("[ORIENT-MISMATCH] seq=" + packet.sequence() + " pos=" + client.workPos().toShortString() + " " + diff
        *///?} else
        System.out.println("[ORIENT-MISMATCH] seq=" + packet.getSequence() + " pos=" + client.workPos().toShortString() + " " + diff
                + "click=" + client.target() + " serverRot=" + this.player.getYRot() + "/" + this.player.getXRot() + " head=" + this.player.getYHeadRot()
                + " tick=" + this.player.tickCount + " recentRot=[" + UseRecord.recentServerRotations() + "]");
    }

    /** 服务端收到的视角包，以及处理之后服务端的身体 / 头部朝向 */
    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void gt$noteRotation(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread() || !packet.hasRotation()) return;
        UseRecord.noteServerRotation("t" + this.player.tickCount + " " + packet.getClass().getSimpleName() + " " + packet.getYRot(0f) + "/" + packet.getXRot(0f)
                + " -> " + this.player.getYRot() + "/head " + this.player.getYHeadRot());
    }

    /** 所有收到的移动包（包括服务端中途 return 的情况）：处理前的值 */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void gt$noteMoveIn(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread()) return;
        if (!packet.hasRotation() && !packet.hasPosition()) return;
        UseRecord.noteServerRotation("t" + this.player.tickCount + " in:" + packet.getClass().getSimpleName()
                + (packet.hasRotation() ? " rot " + packet.getYRot(0f) + "/" + packet.getXRot(0f) : "")
                + (packet.hasPosition() ? String.format(java.util.Locale.ROOT, " pos %.2f,%.2f,%.2f", packet.getX(0), packet.getY(0), packet.getZ(0)) : "")
                + " now " + this.player.getYRot() + "/" + this.player.getXRot());
    }

    /** 服务端主动传送玩家（客户端会用真实视角回一个 PosRot）：谁调用的 */
    //? if <1.21.2 {
    /*@Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
    private void gt$noteServerTeleport(double x, double y, double z, float yRot, float xRot, java.util.Set<net.minecraft.world.entity.RelativeMovement> relatives, CallbackInfo ci) {
        String pos = x + "," + y + "," + z + " " + yRot + "/" + xRot;
    *///?} else {
    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("HEAD"))
    private void gt$noteServerTeleport(net.minecraft.world.entity.PositionMoveRotation pos, java.util.Set<net.minecraft.world.entity.Relative> relatives, CallbackInfo ci) {
    //?}
        StringBuilder by = new StringBuilder();
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (int i = 2, n = 0; i < st.length && n < 5; i++) {
            String cls = st[i].getClassName();
            if (cls.startsWith("java.") || cls.contains("ServerItemMismatchMixin")) continue;
            by.append(cls.substring(cls.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName()).append(' ');
            n++;
        }
        UseRecord.noteServerRotation("t" + this.player.tickCount + " SERVER-TELEPORT " + pos + " rel=" + relatives + " by=" + by);
    }

    /** 传送确认（服务端在等它的时候会无视所有移动 / 视角包） */
    @Inject(method = "handleAcceptTeleportPacket", at = @At("TAIL"))
    private void gt$noteTeleportAccept(net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread()) return;
        UseRecord.noteServerRotation("t" + this.player.tickCount + " teleport-accept " + this.player.getYRot() + "/head " + this.player.getYHeadRot());
    }

    /** 每个世界开头的“切换格子”服务端都打印出来（和客户端的 [client-carried-send] 对照） */
    @Inject(method = "handleSetCarriedItem", at = @At("TAIL"))
    private void gt$logEarlyCarried(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread() || this.player.tickCount >= 400) return;
        //? if <1.21.5 {
        /*System.out.println("[server-carried-early] slot=" + packet.getSlot() + " now=" + this.player.getInventory().selected
        *///?} else
        System.out.println("[server-carried-early] slot=" + packet.getSlot() + " now=" + this.player.getInventory().getSelectedSlot()
                + " tick=" + this.player.tickCount);
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void gt$checkItem(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!this.player.level().getServer().isSameThread()) return; // 网络线程那一次只是转交给主线程
        //? if >=26.3 {
        /*UseRecord.Entry client = UseRecord.take(packet.sequence());
        *///?} else
        UseRecord.Entry client = UseRecord.take(packet.getSequence());
        gt$current = client;
        if (client == null || client.item().equals("minecraft:air")) return; // 生存模式最后一个用掉后客户端手里是空的：不算
        //? if >=26.3 {
        /*String server = this.player.getItemInHand(packet.hand()).getItem().toString();
        *///?} else
        String server = this.player.getItemInHand(packet.getHand()).getItem().toString();
        if (server.equals(client.item())) return;
        StringBuilder hotbar = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            var s = this.player.getInventory().getItem(i);
            hotbar.append(i).append(':').append(s.isEmpty() ? "-" : s.getItem().toString().replace("minecraft:", "")).append(' ');
        }
        //? if >=26.3 {
        /*System.out.println("[ITEM-MISMATCH] seq=" + packet.sequence() + " target=" + client.target() + " client=" + client.item()
        *///?} else
        System.out.println("[ITEM-MISMATCH] seq=" + packet.getSequence() + " target=" + client.target() + " client=" + client.item()
                + " (slot " + client.slot() + ", tick " + client.tick() + ") server=" + server + " (slot "
                //? if <1.21.5 {
                /*+ this.player.getInventory().selected + ") serverHotbar=" + hotbar);
                *///?} else
                + this.player.getInventory().getSelectedSlot() + ") serverHotbar=" + hotbar);
    }
}
