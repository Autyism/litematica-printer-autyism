package com.autyism.printer.utils;

import com.autyism.printer.mixin.printer.mc.ServerboundMovePlayerPacketAccessor;
import com.autyism.printer.mixin.extension.MultiPlayerGameModeExtension;
import com.autyism.printer.printer.ActionManager;
import com.autyism.printer.printer.PlayerLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

public class PacketUtils {

    private static final Minecraft client = Minecraft.getInstance();

    public static void sendPacket(Packet<?> packet) {
        ClientPacketListener connection = client.getConnection();
        if (connection != null) {
            connection.send(packet);
        }
    }

    public static void sendPacket(MultiPlayerGameModeExtension.PredictiveAction packetCreator) {
        if (client.level instanceof SequenceExtension sequenceExtension) {
            int currentSequence = sequenceExtension.litematica_printer3$getSequence();
            Packet<ServerGamePacketListener> packet = packetCreator.predict(currentSequence);
            PacketUtils.sendPacket(packet);
        }
    }

    public static void sendLookPacket(LocalPlayer playerEntity, float lookYaw, float lookPitch) {
        playerEntity.connection.send(new ServerboundMovePlayerPacket.Rot(
                lookYaw,
                lookPitch,
                playerEntity.onGround()
                , playerEntity.horizontalCollision
        ));
    }

    public static void sendLookPacket(LocalPlayer playerEntity, PlayerLook playerLook) {
        sendLookPacket(playerEntity, playerLook.yaw(), playerLook.pitch());
    }


    public static boolean isRotPacket(Packet<?> packet) {
        return packet instanceof ServerboundMovePlayerPacket.Rot;
    }

    public static boolean isMovePlayerPacket(Packet<?> packet) {
        return packet instanceof ServerboundMovePlayerPacket;
    }

    public static Packet<?> getFixedPacket(Packet<?> packet) {
        // 服务端最后收到的视角在 MixinConnectionRotationNote 里记（所有真正发出去的包，包括使用物品包）
        return getFixedPacket0(packet);
    }

    private static Packet<?> getFixedPacket0(Packet<?> packet) {
        PlayerLook playerLook = ActionManager.INSTANCE.look;
        if (!isMovePlayerPacket(packet) || playerLook == null) {
            return packet;
        }
        boolean onGround = ((ServerboundMovePlayerPacketAccessor) packet).getOnGround();
        if (isRotPacket(packet)) {
            return new ServerboundMovePlayerPacket.Rot(playerLook.yaw(), playerLook.pitch(), onGround
                    , ((ServerboundMovePlayerPacketAccessor) packet).getHorizontalCollision()
            );
        }
        double x = ((ServerboundMovePlayerPacketAccessor) packet).getX();
        double y = ((ServerboundMovePlayerPacketAccessor) packet).getY();
        double z = ((ServerboundMovePlayerPacketAccessor) packet).getZ();
        return new ServerboundMovePlayerPacket.PosRot(x, y, z, playerLook.yaw(), playerLook.pitch(), onGround
                , ((ServerboundMovePlayerPacketAccessor) packet).getHorizontalCollision()
        );
    }

    public interface SequenceExtension {
        /** 申请一个新的动作序号（发包模式用） */
        default int litematica_printer3$getSequence() {
            return 0;
        }

        /** 最近一次动作用的序号（不申请新的） */
        default int litematica_printer3$currentSequence() {
            return 0;
        }
    }
}