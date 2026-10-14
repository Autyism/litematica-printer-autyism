package com.autyism.printer.printer;

import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.PlacementHandler;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayDeque;
import java.util.List;

/**
 * 服务端真的认不认轻松放置协议（V3）。
 * <p>
 * 用哪个协议由 Litematica 决定：单人游戏、或者认出了服务器上的 Servux 时用 V3。Litematica 只有开着“实体数据同步”
 * （entityDataSync）时才会和 Servux 打招呼；关着时它认不出 Servux，打印机放有朝向的方块只能先转头、等服务端确认再放。
 * <p>
 * Servux 也可能不让这个玩家用协议（权限不够、关掉了）：这时用协议的放置会被服务端拒绝（原版不接受点在方块外面的点击位置）
 * 或者放成别的状态。前几次放置里一次都没成功、却已经失败了几次，就对这个服务器关掉协议，改回转头放置，并提示一次。
 */
public final class ServerProtocol {
    /** 一次都没成功之前，失败几次就关掉协议 */
    private static final int MAX_FAILURES = 3;
    /** 发出去这么久还没确认的放置不再等（掉线、丢包） */
    private static final int TIMEOUT_TICKS = 200;
    /** 协议能直接定下的朝向类属性：放出来和投影不一样，说明服务端没按协议放 */
    private static final List<Property<?>> ORIENTATION = List.of(BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.FACING_HOPPER, BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS);

    /** @param conclusive 放对了只能是服务端按协议放的（见 ActionManager 里的 protocolProbe） */
    private record Sent(BlockPos pos, int sequence, int tick, BlockState wanted, boolean conclusive) {
    }

    /** 服务端按“头部朝向”定朝向的方块（要等服务端的头转过去才能放对的那些） */
    public static boolean headDependent(net.minecraft.world.level.block.Block block) {
        return block instanceof net.minecraft.world.level.block.ObserverBlock
                || block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                || block instanceof net.minecraft.world.level.block.DispenserBlock
                || block instanceof net.minecraft.world.level.block.BarrelBlock
                || block instanceof net.minecraft.world.level.block.CrafterBlock;
    }

    private static final ArrayDeque<Sent> SENT = new ArrayDeque<>();
    /** 状态属于哪个连接（换服务器、重进都会换） */
    private static Object connection;
    private static boolean refused;
    private static int accepted;
    private static int failures;

    private ServerProtocol() {
    }

    /** 打印机这次放置该用的协议版本：Litematica 的结论，减去被服务端拒绝过的 */
    public static EasyPlaceProtocol effective() {
        EasyPlaceProtocol protocol = PlacementHandler.getEffectiveProtocolVersion();
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer()) return protocol;
        sync(mc);
        if (refused && protocol == EasyPlaceProtocol.V3) return EasyPlaceProtocol.NONE;
        return protocol;
    }

    /**
     * 现在能不能用协议放这一块。服务端不认协议时不一定拒绝放置，也可能照常按视角放、朝向就错了；
     * 打印机一 tick 能发几十个放置，等第一个结果回来时已经放错一大片。所以在服务器上确认协议真的能用之前，
     * 同一时间只让一个方块用协议（试放），其余的照常转头放。一块“不按协议就放不对”的方块放对了，就算确认，不再限制；
     * 这种方块放错了就马上停用协议（不认协议的服务器上最多放错这一块）。
     */
    public static boolean mayUseNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer()) return true;
        sync(mc);
        return accepted > 0 || (!refused && SENT.isEmpty());
    }

    /** 记下一次在服务器上用协议放的方块，确认回来后看服务端有没有照着放 */
    public static void sent(BlockPos pos, int sequence, int tick, BlockState wanted, boolean conclusive) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer()) return;
        sync(mc);
        if (refused || accepted > 0) return; // 确认过一次就不用再验了
        SENT.addLast(new Sent(pos.immutable(), sequence, tick, wanted, conclusive));
        while (SENT.size() > 64) SENT.removeFirst();
    }

    /** 服务端确认处理到了 sequence：这时客户端看到的就是服务端的结果（由 ActionConfirm.onAck 调用） */
    public static void onAck(ClientLevel level, int sequence) {
        if (SENT.isEmpty()) return;
        var player = Minecraft.getInstance().player;
        int now = player == null ? 0 : player.tickCount;
        while (!SENT.isEmpty()) {
            Sent s = SENT.peekFirst();
            if (s.sequence() > sequence) {
                if (now - s.tick() > TIMEOUT_TICKS || now < s.tick()) {
                    SENT.removeFirst();
                    continue;
                }
                break;
            }
            SENT.removeFirst();
            BlockState placed = level.getBlockState(s.pos());
            if (placed.getBlock() == s.wanted().getBlock() && sameOrientation(placed, s.wanted())) {
                // 放对了。只有不按协议就放不对的那种才算确认（别的照视角也能放对，说明不了什么）
                if (s.conclusive()) {
                    accepted++;
                    SENT.clear();
                    return;
                }
                continue;
            }
            // 没放上，或者放成了别的状态。不按协议就一定放错的那种放错了：服务端不认，马上停用
            if (s.conclusive() || ++failures >= MAX_FAILURES) {
                refused = true;
                SENT.clear();
                InfoUtils.showGuiAndInGameMessage(Message.MessageType.WARNING, 8000, "litematica-printer-autyism.message.protocol_refused");
                return;
            }
        }
    }

    private static boolean sameOrientation(BlockState a, BlockState b) {
        for (Property<?> p : ORIENTATION) {
            if (a.hasProperty(p) && b.hasProperty(p) && !a.getValue(p).equals(b.getValue(p))) return false;
        }
        return true;
    }

    private static void sync(Minecraft mc) {
        Object now = mc.getConnection();
        if (now != connection) {
            connection = now;
            refused = false;
            accepted = 0;
            failures = 0;
            SENT.clear();
        }
    }

    /** 调试 / 测试用 */
    public static String describe() {
        return "refused=" + refused + " accepted=" + accepted + " failures=" + failures;
    }
}
