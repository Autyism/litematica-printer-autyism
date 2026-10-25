package com.autyism.printer.bedrock;

import com.autyism.printer.I18n;
import com.autyism.printer.compat.BedrockCompat;
import com.autyism.printer.config.Configs;
import com.autyism.printer.mixin.extension.MultiPlayerGameModeExtension;
import com.autyism.printer.printer.ActionConfirm;
import com.autyism.printer.utils.InventoryUtils;
import com.autyism.printer.utils.MessageUtils;
import com.autyism.printer.utils.PacketUtils;
import com.autyism.printer.utils.PlayerUtils;
import com.autyism.printer.utils.ToolSwitchUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 打印机自己破基岩（不用破基岩模组）。原版的活塞办法，每一格：
 * <ol>
 *     <li>在基岩上面（或下面）放一个背对基岩的活塞，旁边放红石火把，活塞伸出去；</li>
 *     <li>服务端确认活塞伸出以后，同一 tick 里接连发：拆火把、拆活塞（镐要能秒挖活塞）、在原地放一个朝着基岩的活塞。
 *     拆火把时活塞已经排上了“收回”，拆掉再放的是同一种方块，到了 tick 末尾这个收回落在新活塞上，新活塞朝着基岩，
 *     收回时就把基岩当成活塞头删掉了；</li>
 *     <li>服务端确认以后看基岩还在不在，再把活塞、火把拆干净（等活塞停稳再拆）。</li>
 * </ol>
 * 每一步都等服务端确认了上一步（动作序号的确认，确认回来时客户端看到的就是服务端的结果）再看世界、走下一步，
 * 不靠固定的等待时间，卡的时候也不会乱。
 * <p>
 * 很多格同时破：每一格占着自己的基岩、活塞、活塞头、火把和火把下面的方块，彼此不能重叠；一格的火把也不能让
 * 另一格的活塞通上电（火把旁边、火把上面那格的旁边，以及活塞上面那格挨着这些地方——活塞的“半连接”），
 * 否则那一格放下去的新活塞会被这个火把顶住、收不回来。按这两条判断，格子之间不用隔得很远。
 */
public final class PistonBreaker {
    public static final PistonBreaker INSTANCE = new PistonBreaker();

    /** 一格最多试几次（每次都会把活塞、火把拆干净再来） */
    private static final int MAX_ATTEMPTS = 3;
    /** 发出动作后这么久还没确认（丢包、掉线）：当作没回来，照当前看到的接着走 */
    private static final int ACK_TIMEOUT_TICKS = 100;
    /** 活塞一直在动（推拉中）最多等这么久 */
    private static final int SETTLE_TIMEOUT_TICKS = 40;
    /**
     * 确认回来以后，活塞伸出、基岩被删这些“方块事件”的结果还要再晚服务端一 tick 才发过来（服务端先发这一 tick
     * 改过的方块，再处理方块事件，下一 tick 才发事件改的方块），最多等这么久，等不到就当这一次没成
     */
    private static final int EVENT_WAIT_TICKS = 30;
    /** 拆残留最多拆几轮 */
    private static final int MAX_CLEANUP_ROUNDS = 6;
    /** 补放火把最多几次 */
    private static final int MAX_TORCH_TRIES = 2;
    /** 一格最多磨损镐几次：拆两个活塞，留一点余量 */
    private static final int WEAR_PER_TARGET = 3;
    /** 离够得着的边缘留这么多余量：人一边走一边破，服务端处理时人可能又走远了一点 */
    private static final double REACH_MARGIN = -0.6;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final boolean DEBUG = Boolean.getBoolean("ale.debugbedrock");

    private enum Step { PRIME, WAIT_PRIME, EXECUTE, WAIT_EXECUTE, CLEANUP, WAIT_CLEANUP }

    /**
     * 火把放在哪：贴着 support 的 face 面放（UP = 立在上面，横向 = 挂在侧面）。placeSupport：旁边没有能立火把的方块，
     * 先在 support 放一块黏液块垫着（一下就能拆掉，不磨镐），拆的时候一起拆。
     */
    private record Torch(BlockPos pos, BlockPos support, Direction face, boolean placeSupport) {
        Torch(BlockPos pos, BlockPos support, Direction face) {
            this(pos, support, face, false);
        }
    }

    private static final class Machine {
        final BlockPos target;
        /** 活塞在基岩的哪一边（UP：上面，活塞朝上伸出） */
        final Direction side;
        final BlockPos piston;
        final BlockPos head;
        final Torch torch;
        Step step = Step.PRIME;
        int seq = Integer.MIN_VALUE;
        int sentTick;
        int attempts;
        int torchTries;
        int cleanupRounds;
        int settleSince = -1;
        boolean broken;
        /** 破掉以后点过一下这一格：服务端点方块时总会把这一格和旁边一格的真实样子发回来，确认回来时客户端看到的就是真的 */
        boolean verified;

        Machine(BlockPos target, Direction side, Torch torch) {
            this.target = target;
            this.side = side;
            this.piston = target.relative(side);
            this.head = piston.relative(side);
            this.torch = torch;
        }

        /** 这一格占着的格子（彼此不能重叠） */
        List<BlockPos> footprint() {
            return List.of(target, piston, head, torch.pos, torch.support);
        }
    }

    /** 这一 tick 能用的快捷栏格子（-1 = 快捷栏里没有） */
    private record Tools(int pick, int piston, int torch, int slime) {
    }

    private final Long2ObjectLinkedOpenHashMap<Machine> machines = new Long2ObjectLinkedOpenHashMap<>();
    /** 拆了几轮都没拆掉的活塞、火把（服务端一直不认）：够得着、镐准备好时再拆，拆掉或者变成别的就不管了 */
    private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet leftovers = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap leftoverTries = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private int leftoverSeq = Integer.MIN_VALUE;
    private static final int LEFTOVER_MAX_TRIES = 10;
    /** 做不了的格子（试满次数）告诉调用方，过一阵再试 */
    private final it.unimi.dsi.fastutil.longs.LongArrayList failed = new it.unimi.dsi.fastutil.longs.LongArrayList();
    @Nullable
    private ClientLevel level;
    private int succeeded;
    private int gaveUp;
    /** 这一 tick 发过视角包（tick 末尾把视角改回来） */
    private boolean looked;
    @Nullable
    private Tools tools;

    /**
     * 镐上的效率附魔要等服务端处理这个玩家的下一个 tick 才算上（换到手上的那一刻服务端按空手的速度算，活塞挖不动）。
     * 所以破基岩期间手上一直拿着镐（放活塞、火把时临时换一下，同一 tick 里换回来，服务端那边没感觉），
     * 换上镐以后发一个标记包，它被确认了（服务端已经处理过这个玩家的 tick），才开始挖活塞。
     */
    private int pickSlot = -1;
    private int pickSettleSeq = Integer.MAX_VALUE;
    /** 开始破之前手上拿的那一格：做完、停下以后换回去 */
    private int handBefore = -1;
    private int idleTicks;
    /** 做完以后手上的镐留这么久再换回去（中间又有格子要破就不用重新等镐） */
    private static final int RESTORE_HAND_AFTER = 20;

    private PistonBreaker() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public boolean isIdle() {
        return machines.isEmpty() && !leftoversNear();
    }

    /** 人走远了（这么远以外）：手上的格子先放下，留下的活塞火把记着，回来再拆 */
    private static final double FAR = 24.0;

    private boolean leftoversNear() {
        LocalPlayer player = mc().player;
        if (player == null) return false;
        for (long key : leftovers) {
            if (BlockPos.of(key).distToCenterSqr(player.position()) < FAR * FAR) return true;
        }
        return false;
    }

    public int activeCount() {
        return machines.size();
    }

    /** 还没破完的格子（破完在收拾活塞火把的不算：“同时破几格”只算这些） */
    private int breaking() {
        int n = 0;
        for (Machine m : machines.values()) {
            if (m.step != Step.CLEANUP && m.step != Step.WAIT_CLEANUP) n++;
        }
        return n;
    }

    public int succeeded() {
        return succeeded;
    }

    public int gaveUp() {
        return gaveUp;
    }

    /** 试满次数还没破掉的格子（取出后清空） */
    public long[] drainFailed() {
        long[] out = failed.toLongArray();
        failed.clear();
        return out;
    }

    public boolean isWorkingOn(BlockPos pos) {
        return machines.containsKey(pos.asLong());
    }

    public void reset() {
        machines.clear();
        leftovers.clear();
        leftoverTries.clear();
        failed.clear();
        tools = null;
        pickSlot = -1;
        handBefore = -1;
    }

    /** 换世界：手上的格子全部作废（那边的方块已经不是这个世界的了） */
    private void syncLevel(ClientLevel now) {
        if (now != level) {
            reset();
            level = now;
        }
    }

    // ------------------------------------------------------------------ 收下一格

    /** 还能不能再收一格：没到上限、手边有活塞、火把和能秒挖活塞的镐，镐也不会被用坏 */
    public boolean canAdmit(LocalPlayer player, int maxActive) {
        if (breaking() >= maxActive) return false;
        Tools t = tools;
        if (t == null || t.pick < 0) return false;
        int pistons = count(player, Items.PISTON) - inFlight(Items.PISTON);
        int torches = count(player, Items.REDSTONE_TORCH) - inFlight(Items.REDSTONE_TORCH);
        // 每一格要用两个活塞、一个火把（拆下来的掉在地上，捡回来才又有）
        if (pistons < 2 * (machines.size() + 1) || torches < machines.size() + 1) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_NEED_ITEMS.getName());
            return false;
        }
        ItemStack pick = player.getInventory().getItem(t.pick);
        if (Configs.Break.TOOL_DURABILITY_PROTECT.getBooleanValue() && pick.isDamageableItem()) {
            int remaining = pick.getMaxDamage() - pick.getDamageValue() - ActionConfirm.breaksInFlight(level)
                    - WEAR_PER_TARGET * (machines.size() + 1);
            if (remaining <= Configs.Break.TOOL_DURABILITY_THRESHOLD.getIntegerValue()) {
                MessageUtils.setOverlayMessage(I18n.BEDROCK_PICK_WORN.getName());
                return false;
            }
        }
        return true;
    }

    /**
     * 试着收下这一格：找一种摆法（活塞放上面或下面、火把放哪），和正在破的格子都不冲突就收下，这一 tick 就开始。
     *
     * @return true 表示收下了
     */
    public boolean admit(LocalPlayer player, ClientLevel level, BlockPos target) {
        syncLevel(level);
        if (machines.containsKey(target.asLong())) return false;
        if (!BedrockCompat.isTargetBlock(level.getBlockState(target))) return false;
        // 这一格是别的格子的火把底座之类：那一格做完再说
        for (Machine m : machines.values()) {
            if (m.footprint().contains(target)) return false;
        }
        Machine plan = plan(player, level, target, 0);
        if (plan == null) return false;
        machines.put(target.asLong(), plan);
        if (DEBUG) debug("admit " + target.toShortString() + " side=" + plan.side + " torch=" + plan.torch);
        return true;
    }

    @Nullable
    private Machine plan(LocalPlayer player, ClientLevel level, BlockPos target, int attempts) {
        if (!reachable(player, target)) return null;
        for (Direction side : new Direction[]{Direction.UP, Direction.DOWN}) {
            BlockPos piston = target.relative(side);
            BlockPos head = piston.relative(side);
            if (!freeForPiston(level, piston) || !freeForPiston(level, head)) continue;
            // 别的东西（玩家自己的红石）已经给这里通着电：放下的新活塞会被顶住，换一边
            if (level.hasNeighborSignal(piston) || level.hasNeighborSignal(piston.above())) continue;
            if (reserved(piston, null) || reserved(head, null)) continue;
            for (Torch torch : torchOptions(level, piston, side, hasSlime(player))) {
                if (!reachable(player, torch.pos) || !reachable(player, torch.support)) continue;
                Machine m = new Machine(target.immutable(), side, torch);
                if (conflicts(m)) continue;
                m.attempts = attempts;
                return m;
            }
        }
        return null;
    }

    /** 活塞和活塞头的位置：空着（可以被替换、没有流体），也没有挡着放方块的生物 */
    private static boolean freeForPiston(ClientLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) return false;
        return level.isUnobstructed(Blocks.PISTON.defaultBlockState(), pos, CollisionContext.empty());
    }

    /**
     * 能给 piston 供电的火把位置，先试的在前：立在旁边一格的方块上（贴着活塞）；挂在旁边一格（贴着活塞）的另一面；
     * 立在活塞上面那格的旁边（活塞的半连接）。不放在基岩那一边。
     */
    private static List<Torch> torchOptions(ClientLevel level, BlockPos piston, Direction side, boolean slime) {
        List<Torch> out = new ArrayList<>();
        BlockPos above = piston.above();
        for (Direction h : HORIZONTAL) {
            BlockPos pos = piston.relative(h);
            BlockPos support = pos.below();
            if (torchSpot(level, pos) && Block.canSupportCenter(level, support, Direction.UP) && plainSupport(level, support)) out.add(new Torch(pos, support, Direction.UP));
        }
        for (Direction h : HORIZONTAL) {
            BlockPos pos = piston.relative(h);
            if (!torchSpot(level, pos)) continue;
            for (Direction face : HORIZONTAL) {
                // 挂在活塞上不行（拆活塞时火把会掉）；面朝外贴在别的方块上
                BlockPos support = pos.relative(face.getOpposite());
                if (support.equals(piston)) continue;
                if (level.getBlockState(support).isFaceSturdy(level, support, face) && plainSupport(level, support)) out.add(new Torch(pos, support, face));
            }
        }
        // 半连接：火把挨着活塞上面那一格。活塞朝上时那一格就是活塞头，头旁边的火把
        if (side == Direction.UP || side == Direction.DOWN) {
            for (Direction h : HORIZONTAL) {
                BlockPos pos = above.relative(h);
                BlockPos support = pos.below();
                if (support.equals(piston)) continue;
                if (torchSpot(level, pos) && Block.canSupportCenter(level, support, Direction.UP) && plainSupport(level, support)) out.add(new Torch(pos, support, Direction.UP));
            }
        }
        // 都不行：旁边一格下面放一块黏液块，火把立在上面（贴着活塞）
        if (slime) {
            for (Direction h : HORIZONTAL) {
                BlockPos pos = piston.relative(h);
                BlockPos support = pos.below();
                if (torchSpot(level, pos) && freeForPiston(level, support)) out.add(new Torch(pos, support, Direction.UP, true));
            }
        }
        return out;
    }

    /**
     * 点了会打开、拨动的方块（箱子、工作台、门、拉杆……）不当火把底座：点上去会打开它，不会放火把。
     * 已经被充能的方块也不当（火把放上去就是熄的）
     */
    private static boolean plainSupport(ClientLevel level, BlockPos pos) {
        if (level.getDirectSignalTo(pos) > 0) return false;
        BlockState state = level.getBlockState(pos);
        Block b = state.getBlock();
        return !state.hasBlockEntity() && !(b instanceof net.minecraft.world.level.block.CraftingTableBlock)
                && !(b instanceof net.minecraft.world.level.block.DoorBlock) && !(b instanceof net.minecraft.world.level.block.TrapDoorBlock)
                && !(b instanceof net.minecraft.world.level.block.FenceGateBlock) && !(b instanceof net.minecraft.world.level.block.ButtonBlock)
                && !(b instanceof net.minecraft.world.level.block.LeverBlock) && !(b instanceof net.minecraft.world.level.block.AnvilBlock)
                && !(b instanceof net.minecraft.world.level.block.NoteBlock) && !(b instanceof net.minecraft.world.level.block.DiodeBlock)
                && !(b instanceof net.minecraft.world.level.block.CakeBlock) && !(b instanceof net.minecraft.world.level.block.AbstractCauldronBlock)
                && !(b instanceof net.minecraft.world.level.block.RespawnAnchorBlock) && !(b instanceof net.minecraft.world.level.block.StonecutterBlock)
                && !(b instanceof net.minecraft.world.level.block.GrindstoneBlock) && !(b instanceof net.minecraft.world.level.block.LoomBlock)
                && !(b instanceof net.minecraft.world.level.block.CartographyTableBlock) && !(b instanceof net.minecraft.world.level.block.SmithingTableBlock)
                && !(b instanceof net.minecraft.world.level.block.ComposterBlock) && !(b instanceof net.minecraft.world.level.block.RedStoneWireBlock);
    }

    private static boolean torchSpot(ClientLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getBlockState(pos).isAir();
    }

    /** 这一格被正在破的哪一格占着（except 不算） */
    private boolean reserved(BlockPos pos, @Nullable Machine except) {
        for (Machine m : machines.values()) {
            if (m != except && m.footprint().contains(pos)) return true;
        }
        return false;
    }

    /** 和正在破的格子冲突：占的格子重叠（本来就有的火把底座可以共用），或者一边的火把会给另一边的活塞通电 */
    private boolean conflicts(Machine c) {
        for (Machine m : machines.values()) {
            for (BlockPos p : c.footprint()) {
                if (p.equals(c.torch.support) && p.equals(m.torch.support) && !c.torch.placeSupport && !m.torch.placeSupport) continue;
                if (m.footprint().contains(p)) return true;
            }
            if (powers(c.torch.pos, m.piston) || powers(m.torch.pos, c.piston)) return true;
            // 火把给它上面那格强充能：那一格是另一边火把的底座的话，那支火把会熄灭（红石火把是反相的），活塞就收回去了
            if (c.torch.support.equals(m.torch.pos.above()) || m.torch.support.equals(c.torch.pos.above())) return true;
        }
        return false;
    }

    /**
     * 火把 torch 会不会让 piston 处的活塞通上电（往多了算）：火把给旁边各格供电，还给上面那格强充能，
     * 强充能的方块再给它旁边各格供电；活塞自己或者它上面那格挨着这些地方就算（半连接）。
     */
    static boolean powers(BlockPos torch, BlockPos piston) {
        return reaches(torch, piston) || reaches(torch, piston.above());
    }

    private static boolean reaches(BlockPos torch, BlockPos pos) {
        int d = pos.distManhattan(torch);
        if (d <= 1) return true;
        return pos.distManhattan(torch.above()) <= 1;
    }

    private static boolean reachable(LocalPlayer player, BlockPos pos) {
        return PlayerUtils.isWithinBlockInteractionRange(player, pos, REACH_MARGIN);
    }

    // ------------------------------------------------------------------ 每 tick

    /**
     * 每 tick 调一次（破基岩模式没开时也调：手上没做完的格子要收拾干净）。
     *
     * @param stopping true 表示不再开始新的步骤：活塞已经伸出的也不再破，直接拆干净
     */
    public void tick(LocalPlayer player, ClientLevel level, boolean stopping) {
        syncLevel(level);
        if (player.getAbilities().instabuild || player.isSpectator()) {
            tools = null;
            return;
        }
        // 开着别的界面（箱子之类）时不动背包，也不放方块
        if (player.containerMenu != player.inventoryMenu || com.autyism.printer.utils.QuickShulkerUtils.isBusy()) return;
        tools = resolveTools(player, level, !machines.isEmpty() || !stopping);
        Inventory inventory = player.getInventory();
        for (Machine m : new ArrayList<>(machines.values())) {
            if (m.target.distToCenterSqr(player.position()) < FAR * FAR) continue;
            machines.remove(m.target.asLong());
            for (BlockPos pos : m.footprint()) {
                if (!pos.equals(m.target) && (!pos.equals(m.torch.support) || m.torch.placeSupport)) leftovers.add(pos.asLong());
            }
            if (DEBUG) debug("walked far from " + m.target.toShortString() + " at " + m.step);
        }
        if (leftovers.size() > 4096) leftovers.clear();
        if (machines.isEmpty() && !leftoversNear()) {
            if (handBefore >= 0 && (stopping || ++idleTicks > RESTORE_HAND_AFTER)) {
                select(player, handBefore);
                handBefore = -1;
                pickSlot = -1;
            }
            return;
        }
        idleTicks = 0;
        if (handBefore < 0) handBefore = InventoryUtils.getSelectedSlot(inventory);
        settlePick(player, level);
        looked = false;
        // 打印机排着的放置视角会改写发出去的视角包（见 PacketUtils.getFixedPacket）：这一段先拿掉，发完放回去
        com.autyism.printer.printer.PlayerLook queuedLook = com.autyism.printer.printer.ActionManager.INSTANCE.look;
        com.autyism.printer.printer.ActionManager.INSTANCE.look = null;
        try {
        int acked = ActionConfirm.lastAcked(level);
        int now = player.tickCount;
            for (Machine m : new ArrayList<>(machines.values())) {
                if (m.seq != Integer.MIN_VALUE && m.seq > acked && now - m.sentTick < ACK_TIMEOUT_TICKS && now >= m.sentTick) continue;
                advance(player, level, m, stopping);
            }
            if (!leftovers.isEmpty()) cleanLeftovers(player, level);
            // tick 结束时手上是镐（服务端处理这个玩家的 tick 时看到的是镐，效率一直算着）
            Tools t = tools;
            if (t != null && t.pick >= 0) select(player, t.pick);
            if (looked) {
                PacketUtils.sendLookPacket(player, player.getYRot(), player.getXRot());
                looked = false;
            }
        } finally {
            com.autyism.printer.printer.ActionManager.INSTANCE.look = queuedLook;
        }
    }

    /** 手上换成镐；刚换上的（或者别的模式把手上换走过）发标记包，等它被确认 */
    private void settlePick(LocalPlayer player, ClientLevel level) {
        Tools t = tools;
        if (t == null || t.pick < 0) {
            pickSlot = -1;
            return;
        }
        if (pickSlot == t.pick && InventoryUtils.getSelectedSlot(player.getInventory()) == t.pick) return;
        select(player, t.pick);
        pickSlot = t.pick;
        BlockPos far = player.blockPosition().offset(512, 0, 0);
        PacketUtils.sendPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, far, Direction.DOWN, sequence));
        pickSettleSeq = level instanceof PacketUtils.SequenceExtension seq ? seq.litematica_printer3$currentSequence() : Integer.MIN_VALUE;
        if (DEBUG) debug("pick to slot " + t.pick + ", waiting for seq " + pickSettleSeq);
    }

    /** 服务端已经按镐的速度算了（见 pickSlot） */
    private boolean pickReady(ClientLevel level, Tools t) {
        return t != null && t.pick >= 0 && pickSlot == t.pick && ActionConfirm.lastAcked(level) >= pickSettleSeq;
    }

    private void advance(LocalPlayer player, ClientLevel level, Machine m, boolean stopping) {
        Tools t = tools;
        int now = player.tickCount;
        switch (m.step) {
            case PRIME -> {
                if (stopping || !BedrockCompat.isTargetBlock(level.getBlockState(m.target))) {
                    finish(m, false);
                    return;
                }
                if (slotWith(player, Items.PISTON) < 0 || slotWith(player, Items.REDSTONE_TORCH) < 0
                        || (m.torch.placeSupport && slotWith(player, Items.SLIME_BLOCK) < 0)) return;
                // 收下以后世界变了（人走开、有东西挡住）：重新找摆法，找不到就先放下
                if (!freeForPiston(level, m.piston) || !freeForPiston(level, m.head) || !torchSpot(level, m.torch.pos)
                        || (m.torch.placeSupport && !freeForPiston(level, m.torch.support))
                        || !reachable(player, m.target) || !reachable(player, m.torch.support) || !reachable(player, m.torch.pos)) {
                    machines.remove(m.target.asLong());
                    Machine again = plan(player, level, m.target, m.attempts);
                    if (again == null) {
                        if (DEBUG) debug("drop " + m.target.toShortString() + " (world changed before priming)");
                        return;
                    }
                    machines.put(m.target.asLong(), again);
                    m = again;
                }
                m.attempts++;
                m.torchTries = 0;
                m.cleanupRounds = 0;
                m.broken = false;
                placePiston(player, level, m, m.side);
                placeTorch(player, level, m);
                sent(m, now);
                m.step = Step.WAIT_PRIME;
            }
            case WAIT_PRIME -> {
                BlockState p = level.getBlockState(m.piston);
                if (!BedrockCompat.isTargetBlock(level.getBlockState(m.target))) {
                    // 别的原因没了（玩家自己挖的、别的模组）：收拾干净
                    toCleanup(player, level, m, stopping);
                    return;
                }
                if (p.is(Blocks.PISTON) && p.getValue(PistonBaseBlock.EXTENDED) && p.getValue(PistonBaseBlock.FACING) == m.side) {
                    if (stopping) {
                        toCleanup(player, level, m, true);
                        return;
                    }
                    m.step = Step.EXECUTE;
                    advance(player, level, m, false);
                    return;
                }
                if (p.is(Blocks.PISTON) && !p.getValue(PistonBaseBlock.EXTENDED) && p.getValue(PistonBaseBlock.FACING) == m.side) {
                    if (isTorch(level.getBlockState(m.torch.pos))) {
                        // 火把在：伸出要等下一 tick 的方块事件发过来
                        if (waiting(m, now, EVENT_WAIT_TICKS)) return;
                    } else if (m.torchTries < MAX_TORCH_TRIES && !stopping && slotWith(player, Items.REDSTONE_TORCH) >= 0 && torchSpot(level, m.torch.pos)
                            && reachable(player, m.torch.support)
                            && (!m.torch.placeSupport || slotWith(player, Items.SLIME_BLOCK) >= 0)) {
                        // 活塞放好了，火把没放上（被拒、掉了）：补一个
                        m.torchTries++;
                        placeTorch(player, level, m);
                        sent(m, now);
                        return;
                    }
                }
                if (p.is(Blocks.MOVING_PISTON) && waiting(m, now, SETTLE_TIMEOUT_TICKS)) return;
                // 活塞没放上、朝向不对、有火把也没伸出：这一次不行，拆干净再来
                if (DEBUG) debug("prime failed at " + m.target.toShortString() + ": piston=" + p + " torch=" + level.getBlockState(m.torch.pos));
                toCleanup(player, level, m, stopping);
            }
            case EXECUTE -> {
                if (t == null || t.pick < 0 || slotWith(player, Items.PISTON) < 0 || !pickReady(level, t)) return;
                BlockState p = level.getBlockState(m.piston);
                if (!p.is(Blocks.PISTON) || !p.getValue(PistonBaseBlock.EXTENDED)) {
                    toCleanup(player, level, m, stopping);
                    return;
                }
                // 人走开了：等回来（活塞伸着不碍事）
                if (!reachable(player, m.target) || !reachable(player, m.piston) || !reachable(player, m.torch.pos)) return;
                ItemStack pick = player.getInventory().getItem(t.pick);
                // 拆活塞必须一下拆掉（服务端那边一个包就拆掉），否则后面放的活塞放不下去：站在地上、镐够快才动手
                if (PlayerUtils.getDestroyProgressWithStack(player, p, pick) < 1.0F) {
                    MessageUtils.setOverlayMessage(I18n.BEDROCK_NEED_INSTANT_PISTON.getName());
                    return;
                }
                // ---- 这几个包一个接一个发出去，中间不看任何结果 ----
                select(player, t.pick);
                BlockState torchState = level.getBlockState(m.torch.pos);
                if (isTorch(torchState)) breakPacket(m.torch.pos, Direction.UP);
                breakPacket(m.piston, m.side.getOpposite());
                placePiston(player, level, m, m.side.getOpposite());
                sent(m, now);
                m.step = Step.WAIT_EXECUTE;
                if (DEBUG) debug("execute " + m.target.toShortString());
            }
            case WAIT_EXECUTE -> {
                m.broken = !BedrockCompat.isTargetBlock(level.getBlockState(m.target));
                if (!m.broken) {
                    // 新活塞放上了，收回（删基岩）是方块事件，结果下一 tick 才发过来：等
                    BlockState p = level.getBlockState(m.piston);
                    boolean placed = p.is(Blocks.PISTON) && !p.getValue(PistonBaseBlock.EXTENDED) && p.getValue(PistonBaseBlock.FACING) == m.side.getOpposite();
                    if ((placed || p.is(Blocks.MOVING_PISTON)) && waiting(m, now, EVENT_WAIT_TICKS)) return;
                }
                if (DEBUG) debug((m.broken ? "broken " : "still there ") + m.target.toShortString() + " attempt " + m.attempts
                        + " piston=" + level.getBlockState(m.piston));
                toCleanup(player, level, m, stopping);
            }
            case CLEANUP -> cleanup(player, level, m, stopping);
            case WAIT_CLEANUP -> {
                m.step = Step.CLEANUP;
                cleanup(player, level, m, stopping);
            }
        }
    }

    private void toCleanup(LocalPlayer player, ClientLevel level, Machine m, boolean stopping) {
        m.step = Step.CLEANUP;
        m.settleSince = -1;
        cleanup(player, level, m, stopping);
    }

    /** 等世界变（活塞停稳、方块事件的结果发过来）：从第一次等开始算，还没等满 limit 就返回 true */
    private static boolean waiting(Machine m, int now, int limit) {
        if (m.settleSince < 0 || now < m.settleSince) m.settleSince = now;
        return now - m.settleSince < limit;
    }

    /** 拆掉这一格留下的活塞、活塞头、火把；都没了就算做完（基岩还在、还能再试就从头再来） */
    private void cleanup(LocalPlayer player, ClientLevel level, Machine m, boolean stopping) {
        int now = player.tickCount;
        BlockState p = level.getBlockState(m.piston);
        BlockState h = level.getBlockState(m.head);
        if ((p.is(Blocks.MOVING_PISTON) || h.is(Blocks.MOVING_PISTON)) && waiting(m, now, SETTLE_TIMEOUT_TICKS)) return;
        List<BlockPos> left = new ArrayList<>(3);
        if (p.is(Blocks.PISTON) || p.is(Blocks.STICKY_PISTON)) left.add(m.piston);
        // 活塞头只在底座已经没了时单独拆（拆底座时头跟着没了）
        else if (h.is(Blocks.PISTON_HEAD)) left.add(m.head);
        if (isTorch(level.getBlockState(m.torch.pos))) left.add(m.torch.pos);
        if (m.torch.placeSupport && level.getBlockState(m.torch.support).is(Blocks.SLIME_BLOCK)) left.add(m.torch.support);
        boolean verify = m.broken && !m.verified;
        if ((left.isEmpty() && !verify) || m.cleanupRounds >= MAX_CLEANUP_ROUNDS) {
            if (!left.isEmpty()) {
                if (DEBUG) debug("leftovers at " + m.target.toShortString() + ": " + left);
                for (BlockPos pos : left) leftovers.add(pos.asLong());
            }
            finish(m, !stopping);
            return;
        }
        // 人走开了够不着：等回来再拆（够不着时发出去服务端也不认）
        for (BlockPos pos : left) {
            if (!reachable(player, pos)) return;
        }
        if (verify && !reachable(player, m.target)) return;
        Tools t = tools;
        if (!left.isEmpty() && (t == null || t.pick < 0 || !pickReady(level, t))) return;
        if (t != null && t.pick >= 0) {
            ItemStack pick = player.getInventory().getItem(t.pick);
            for (BlockPos pos : left) {
                BlockState state = level.getBlockState(pos);
                // 拆不掉一下的（镐不够快、人在空中）先等：拆一半留着更麻烦
                if (PlayerUtils.getDestroyProgressWithStack(player, state, pick) < 1.0F) return;
            }
            select(player, t.pick);
        } else if (verify && !player.getMainHandItem().isEmpty()) {
            // 核对是空点一下：手上要是镐或者空手，不能拿着方块（会放下去）
            return;
        }
        for (BlockPos pos : left) breakPacket(pos, Direction.UP);
        if (verify) {
            // 空点之前再明确告诉服务端手上是镐：万一服务端那边手上还是活塞，这一下就会把活塞放进刚破开的格子
            if (t != null && t.pick >= 0) player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(t.pick));
            verifyClick(m);
            m.verified = true;
        }
        if (!left.isEmpty()) m.cleanupRounds++;
        sent(m, now);
        m.step = Step.WAIT_CLEANUP;
    }

    /** 空点一下目标格（手上是镐，什么都不会发生）：服务端回这一格和旁边一格的真实方块 */
    private static void verifyClick(Machine m) {
        Vec3 hit = Vec3.atCenterOf(m.target).add(Vec3.atLowerCornerOf(com.autyism.printer.utils.BlockUtils.getVector(m.side)).scale(0.5));
        BlockHitResult result = new BlockHitResult(hit, m.side, m.target, false);
        PacketUtils.sendPacket(sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, result, sequence));
    }

    /** 拆残留：一次拆一轮，等确认了再看 */
    private void cleanLeftovers(LocalPlayer player, ClientLevel level) {
        if (leftoverSeq != Integer.MIN_VALUE && ActionConfirm.lastAcked(level) < leftoverSeq) return;
        Tools t = tools;
        if (t == null || t.pick < 0 || !pickReady(level, t)) return;
        ItemStack pick = player.getInventory().getItem(t.pick);
        boolean sentAny = false;
        var it = leftovers.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            BlockPos pos = BlockPos.of(key);
            BlockState state = level.getBlockState(pos);
            boolean ours = state.is(Blocks.PISTON) || state.is(Blocks.PISTON_HEAD) || isTorch(state) || state.is(Blocks.SLIME_BLOCK);
            if (!ours || leftoverTries.get(key) >= LEFTOVER_MAX_TRIES || reserved(pos, null)) {
                it.remove();
                leftoverTries.remove(key);
                continue;
            }
            if (state.is(Blocks.MOVING_PISTON) || !reachable(player, pos) || PlayerUtils.getDestroyProgressWithStack(player, state, pick) < 1.0F) continue;
            if (!sentAny) select(player, t.pick);
            breakPacket(pos, Direction.UP);
            leftoverTries.addTo(key, 1);
            sentAny = true;
        }
        if (sentAny) leftoverSeq = level instanceof PacketUtils.SequenceExtension seq ? seq.litematica_printer3$currentSequence() : Integer.MIN_VALUE;
    }

    /** 这一格做完：破掉了就算一格；没破掉、还能试就从头再来，试满了交还给调用方 */
    private void finish(Machine m, boolean mayRetry) {
        machines.remove(m.target.asLong());
        ClientLevel lvl = level;
        boolean still = lvl != null && BedrockCompat.isTargetBlock(lvl.getBlockState(m.target));
        if (!still) {
            if (m.attempts > 0) succeeded++;
            return;
        }
        LocalPlayer player = mc().player;
        if (mayRetry && m.attempts < MAX_ATTEMPTS && player != null && lvl != null) {
            Machine again = plan(player, lvl, m.target, m.attempts);
            if (again != null) {
                machines.put(m.target.asLong(), again);
                return;
            }
        }
        if (m.attempts >= MAX_ATTEMPTS) {
            gaveUp++;
            failed.add(m.target.asLong());
        }
    }

    private static boolean isTorch(BlockState state) {
        return state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH);
    }

    private void sent(Machine m, int now) {
        ClientLevel lvl = level;
        m.seq = lvl instanceof PacketUtils.SequenceExtension seq ? seq.litematica_printer3$currentSequence() : Integer.MIN_VALUE;
        m.sentTick = now;
        m.settleSince = -1;
    }

    // ------------------------------------------------------------------ 发包

    private void select(LocalPlayer player, int slot) {
        Inventory inventory = player.getInventory();
        if (InventoryUtils.getSelectedSlot(inventory) != slot) InventoryUtils.setSelectedSlot(inventory, slot);
        if (mc().gameMode instanceof MultiPlayerGameModeExtension gm) gm.litematica_printer$syncCarriedItem();
    }

    /** 秒挖：只发开始挖（服务端算出一下就够，当场拆掉） */
    private static void breakPacket(BlockPos pos, Direction face) {
        PacketUtils.sendPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, sequence));
    }

    /**
     * 快捷栏里还有 item 可放的一格，没有时 -1；手上那格优先。放置不靠客户端预测，客户端的数量要等服务端同步才扣：
     * 减掉发出去还没扣的（ActionConfirm.unreflectedUses，这一 tick 发的也算），一组用完就换下一组，不会拿着空手去放
     */
    private int slotWith(LocalPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int selected = InventoryUtils.getSelectedSlot(inv);
        if (left(player, inv, selected, item) > 0) return selected;
        for (int i = 0; i < 9; i++) {
            if (left(player, inv, i, item) > 0) return i;
        }
        return -1;
    }

    private int left(LocalPlayer player, Inventory inv, int slot, Item item) {
        ItemStack stack = inv.getItem(slot);
        if (!stack.is(item)) return 0;
        ClientLevel lvl = level;
        int unconfirmed = lvl == null ? 0 : ActionConfirm.unreflectedUses(lvl, player.tickCount, item, slot);
        return stack.getCount() - unconfirmed;
    }

    /** 快捷栏里一共还能放几个 */
    private int hotbarLeft(LocalPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < 9; i++) n += Math.max(0, left(player, inv, i, item));
        return n;
    }

    /** 手上换成快捷栏 slot 那一格（放东西用） */
    private void use(LocalPlayer player, int slot) {
        select(player, slot);
    }

    /**
     * 在 m.piston 放一个朝 facing 的活塞：点基岩朝着活塞的那一面（基岩一直在，点得到）。
     * 活塞朝着玩家视线的反方向，竖着的朝向只看俯仰角，同一个包之前转过去服务端就认。
     */
    private void placePiston(LocalPlayer player, ClientLevel level, Machine m, Direction facing) {
        int slot = slotWith(player, Items.PISTON);
        if (slot < 0) return;
        use(player, slot);
        // 朝上：往下看；朝下：往上看
        float pitch = facing == Direction.UP ? 90.0F : -90.0F;
        PacketUtils.sendLookPacket(player, player.getYRot(), pitch);
        looked = true;
        Vec3 hit = Vec3.atCenterOf(m.target).add(Vec3.atLowerCornerOf(com.autyism.printer.utils.BlockUtils.getVector(m.side)).scale(0.5));
        BlockHitResult result = new BlockHitResult(hit, m.side, m.target, false);
        PacketUtils.sendPacket(sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, result, sequence));
        noteSent(level, player, m.target, m.side, Items.PISTON);
    }

    private void placeTorch(LocalPlayer player, ClientLevel level, Machine m) {
        Torch torch = m.torch;
        int slime = slotWith(player, Items.SLIME_BLOCK);
        if (torch.placeSupport && slime >= 0 && level.getBlockState(torch.support).canBeReplaced()) {
            // 黏液块放在空中：点那一格本身（原版服务端认，客户端界面里做不到而已）
            use(player, slime);
            BlockHitResult support = new BlockHitResult(Vec3.atCenterOf(torch.support), Direction.UP, torch.support, false);
            PacketUtils.sendPacket(sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, support, sequence));
            noteSent(level, player, torch.support, Direction.UP, Items.SLIME_BLOCK);
        }
        int torchSlot = slotWith(player, Items.REDSTONE_TORCH);
        if (torchSlot < 0) return;
        use(player, torchSlot);
        Vec3 hit = Vec3.atCenterOf(torch.support).add(Vec3.atLowerCornerOf(com.autyism.printer.utils.BlockUtils.getVector(torch.face)).scale(0.5));
        BlockHitResult result = new BlockHitResult(hit, torch.face, torch.support, false);
        PacketUtils.sendPacket(sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, result, sequence));
        noteSent(level, player, torch.support, torch.face, Items.REDSTONE_TORCH);
    }

    private static void noteSent(ClientLevel level, LocalPlayer player, BlockPos clicked, Direction face, Item item) {
        if (level instanceof PacketUtils.SequenceExtension seq) {
            ActionConfirm.sent(level, clicked, face, seq.litematica_printer3$currentSequence(), player.tickCount, item, false);
        }
    }

    // ------------------------------------------------------------------ 物品

    /** 发出去、服务端还没回数量的放置（客户端没自己扣） */
    private int inFlight(Item item) {
        ClientLevel lvl = level;
        LocalPlayer player = mc().player;
        return lvl == null || player == null ? 0 : ActionConfirm.unreflectedUses(lvl, player.tickCount, item);
    }

    private static int count(LocalPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    /**
     * 找快捷栏里的镐（能秒挖活塞的，手上的优先）、活塞、红石火把。快捷栏里没有、背包里有的，这一 tick 换一样进快捷栏
     * （等服务端确认了上一次换物品才换下一次）。
     */
    @Nullable
    private Tools resolveTools(LocalPlayer player, ClientLevel level, boolean fetch) {
        Inventory inv = player.getInventory();
        BlockState piston = Blocks.PISTON.defaultBlockState();
        int pick = -1;
        int selected = InventoryUtils.getSelectedSlot(inv);
        if (instantPick(player, inv.getItem(selected), piston)) pick = selected;
        for (int i = 0; i < 9 && pick < 0; i++) {
            if (instantPick(player, inv.getItem(i), piston)) pick = i;
        }
        int pistonSlot = hotbarSlot(inv, Items.PISTON);
        int torchSlot = hotbarSlot(inv, Items.REDSTONE_TORCH);
        int slimeSlot = hotbarSlot(inv, Items.SLIME_BLOCK);
        int busy = Math.max(1, machines.size());
        // 快捷栏里剩的不够这几格用了，背包里还有：先换一组进来（一组用完时就接着用下一组，不用停下来等）
        boolean morePistons = hotbarLeft(player, Items.PISTON) < 2 * busy + 2 && mainSlot(inv, Items.PISTON) >= 0;
        boolean moreTorches = hotbarLeft(player, Items.REDSTONE_TORCH) < busy + 1 && mainSlot(inv, Items.REDSTONE_TORCH) >= 0;
        boolean moreSlime = needsSlime() && hotbarLeft(player, Items.SLIME_BLOCK) < busy && mainSlot(inv, Items.SLIME_BLOCK) >= 0;
        if (fetch && (pick < 0 || morePistons || moreTorches || moreSlime || pistonSlot < 0 || torchSlot < 0)) {
            int from = -1;
            Item fetching = null;
            if (pick < 0) {
                for (int i = 9; i < 36 && from < 0; i++) {
                    if (instantPick(player, inv.getItem(i), piston)) from = i;
                }
            }
            if (from < 0 && morePistons) from = mainSlot(inv, fetching = Items.PISTON);
            if (from < 0 && moreTorches) from = mainSlot(inv, fetching = Items.REDSTONE_TORCH);
            if (from < 0 && moreSlime) from = mainSlot(inv, fetching = Items.SLIME_BLOCK);
            // 换进来的那一格：快捷栏里同种快用完的那一组（换出去的剩下几个回到背包里），没有就挑一格空的或者最久没用的
            int into = -1;
            if (fetching != null) {
                for (int i = 0; i < 9 && into < 0; i++) {
                    if (i != pick && inv.getItem(i).is(fetching) && left(player, inv, i, fetching) <= 2) into = i;
                }
            }
            if (from >= 0) bringToHotbar(player, level, from, into, pick, pistonSlot, torchSlot, slimeSlot);
            else if (pick < 0) MessageUtils.setOverlayMessage(I18n.BEDROCK_NEED_INSTANT_PISTON.getName());
            else MessageUtils.setOverlayMessage(I18n.BEDROCK_NEED_ITEMS.getName());
        }
        return new Tools(pick, pistonSlot, torchSlot, slimeSlot);
    }

    /** 有格子要垫黏液块 */
    private boolean needsSlime() {
        for (Machine m : machines.values()) {
            if (m.torch.placeSupport) return true;
        }
        return false;
    }

    /** 还有黏液块可垫（快捷栏或背包里） */
    private boolean hasSlime(LocalPlayer player) {
        int used = 0;
        for (Machine m : machines.values()) {
            if (m.torch.placeSupport) used++;
        }
        return count(player, Items.SLIME_BLOCK) - inFlight(Items.SLIME_BLOCK) > used;
    }

    /** 能一下挖掉活塞、而且不会被这一格用坏的工具 */
    private static boolean instantPick(LocalPlayer player, ItemStack stack, BlockState piston) {
        if (stack.isEmpty()) return false;
        // 不看人在不在地上：在空中只是这一刻先不拆，镐还是这把
        boolean ground = player.onGround();
        float progress = PlayerUtils.getDestroyProgressWithStack(player, piston, stack) * (ground ? 1.0F : 5.0F);
        if (progress < 1.0F) return false;
        return !Configs.Break.TOOL_DURABILITY_PROTECT.getBooleanValue() || !ToolSwitchUtils.isNearlyBroken(stack);
    }

    private static int hotbarSlot(Inventory inv, Item item) {
        int selected = InventoryUtils.getSelectedSlot(inv);
        if (inv.getItem(selected).is(item)) return selected;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) return i;
        }
        return -1;
    }

    /** 背包里（不算快捷栏）这种物品最多的一组 */
    private static int mainSlot(Inventory inv, Item item) {
        int best = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item) && (best < 0 || stack.getCount() > inv.getItem(best).getCount())) best = i;
        }
        return best;
    }

    private void bringToHotbar(LocalPlayer player, ClientLevel level, int from, int into, int pick, int pistonSlot, int torchSlot, int slimeSlot) {
        int sequenceNow = level instanceof PacketUtils.SequenceExtension seq ? seq.litematica_printer3$currentSequence() : 0;
        if (!ActionConfirm.readyForInventoryClick(level, sequenceNow, player.tickCount)) return;
        int target = into;
        for (int slot : InventoryUtils.pickTargetSlots(player, false)) {
            if (target >= 0) break;
            if (slot == pick || slot == pistonSlot || slot == torchSlot || slot == slimeSlot) continue;
            target = slot;
        }
        if (target < 0) return;
        if (DEBUG) debug("bring " + player.getInventory().getItem(from) + " from " + from + " to hotbar " + target);
        // 背包菜单里主背包的格子编号和背包里的一样（9-35）
        if (InventoryUtils.swapIntoHotbar(player, from, target)) {
            InventoryUtils.noteHotbarSlotUsed(target);
            ActionConfirm.noteInventoryClick(level, sequenceNow, player.tickCount);
        }
    }

    // ------------------------------------------------------------------ 调试

    public String debugState() {
        StringBuilder sb = new StringBuilder("active=" + machines.size() + " ok=" + succeeded + " gaveUp=" + gaveUp);
        for (Machine m : machines.values()) {
            sb.append(' ').append(m.target.toShortString()).append(':').append(m.step).append('#').append(m.attempts);
        }
        return sb.toString();
    }

    private void debug(String msg) {
        ClientLevel lvl = level;
        System.out.println("[ALE-BEDROCK] t" + (lvl == null ? 0 : lvl.getGameTime()) + " " + msg);
    }

    /** 调试、测试用：清掉计数 */
    public void resetCounters() {
        succeeded = 0;
        gaveUp = 0;
    }
}
