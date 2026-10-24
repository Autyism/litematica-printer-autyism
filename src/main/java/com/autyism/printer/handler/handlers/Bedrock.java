package com.autyism.printer.handler.handlers;

import com.autyism.printer.I18n;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.HighlightType;
import com.autyism.printer.handler.Module;
import com.autyism.printer.compat.BedrockCompat;
import com.autyism.printer.utils.MessageUtils;
import com.autyism.printer.utils.ToolSwitchUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 破基岩模式：把范围里的基岩（和名单里的方块）交给破基岩模组去破。
 * <p>
 * 同时交几格（设置“同时破几格”），但同时在破的格子彼此相距至少 {@link #SPACING} 格：破基岩模组在每一格旁边放的活塞、
 * 活塞头、火把和垫块都在目标两格以内，相距 5 格时一格的火把碰不到、也隔着方块激活不了另一格的活塞。挨得近的格子同时交，
 * 活塞和火把会互相干扰，哪一格都破不掉。一格要等服务端确认破掉、破基岩模组连收尾的活塞和火把都处理完，才算做完。
 */
public class Bedrock extends Module {
    public final static String NAME = "bedrock";

    /** 同时在破的两格之间至少隔这么远（x、y、z 里最大的那个差） */
    private static final int SPACING = 5;
    /** 一格最多等这么久（破基岩模组卡住了）：等别的格子做完，清掉它手上的活，先做别的格子 */
    private static final int GIVE_UP_TICKS = 300;
    /** 交出去以后这么久破基岩模组还没开始干这一格（任务列表里没有它），就当它放弃了 */
    private static final int START_GRACE_TICKS = 40;
    /** 放弃一格后多久再试：第一次 40 tick，之后每次翻倍，最多这么久 */
    private static final int GIVE_UP_COOLDOWN = 600;
    private static final int FIRST_RETRY_COOLDOWN = 40;
    /** 人走远了先放下的格子，回来以后多久再交 */
    private static final int WALKED_AWAY_COOLDOWN = 40;
    /** 破基岩模组的活塞、火把放在目标多远以内 */
    private static final int LEFTOVER_RADIUS = 3;
    /** 拆一块残留最多试几次（服务器不让拆就算了） */
    private static final int LEFTOVER_MAX_ATTEMPTS = 5;
    /** 放下一格之后多久之内，破基岩模组在它旁边放的东西还记（人走开时它还会接着放一两下） */
    private static final int RECENT_TICKS = 200;
    private static final boolean DEBUG = Boolean.getBoolean("ale.debugbedrock");

    /** 交给破基岩模组、还没做完的一格 */
    private static final class Target {
        final BlockPos pos;
        final long since;
        int sequenceWhenGone = Integer.MIN_VALUE;
        /** 等太久了：等别的格子都做完，清掉破基岩模组手上的活（它不能只停一格） */
        boolean stuck;

        Target(BlockPos pos, long since) {
            this.pos = pos;
            this.since = since;
        }
    }

    private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<Target> active = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
    /** 每一格放弃过几次（破掉以后清零） */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap giveUps = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    /**
     * 破基岩模组在目标旁边放下的活塞、火把、垫块（打印机看着它放的）。它自己没拆掉的（人走开时它够不着就不拆，
     * 或者它放弃了这一格），打印机拆掉：留着会挡住旁边几格，到处都是活塞和火把。
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.block.Block> minerPlaced = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap leftoverAttempts = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    /** 刚放下的格子（完成、放弃、人走开）和放下的时间 */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap recentTargets = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    public Bedrock() {
        super(NAME, Configs.Bedrock.ENABLED, null, true);
        com.autyism.printer.printer.BlockChangeHub.registerWithState(this::onClientBlockChanged);
    }

    /** 这一刻最多同时交几格：设置和破基岩模组自己能同时做的，取小的 */
    private static int maxActive() {
        return Math.max(1, Math.min(Configs.Bedrock.MAX_ACTIVE.getIntegerValue(), BedrockCompat.concurrencyLimit()));
    }

    /**
     * 客户端世界里一格变了：正在破的格子旁边出现了破基岩模组用的方块，记下来。
     * 刚放下的格子旁边也算：人走开以后它还会接着放一两下，服务端的确认还要再晚一两 tick 才到（那时它可能已经停手了）。
     */
    private void onClientBlockChanged(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (level == null || !BedrockCompat.isMinerBlock(state)) return;
        if (nearActive(pos)) {
            minerPlaced.put(pos.asLong(), state.getBlock());
            return;
        }
        if (recentTargets.isEmpty()) return;
        long now = level.getGameTime();
        recentTargets.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > RECENT_TICKS || now < e.getLongValue());
        for (long target : recentTargets.keySet()) {
            if (near(pos, BlockPos.of(target))) {
                minerPlaced.put(pos.asLong(), state.getBlock());
                return;
            }
        }
    }

    private static boolean near(BlockPos pos, BlockPos target) {
        return Math.abs(pos.getX() - target.getX()) <= LEFTOVER_RADIUS && Math.abs(pos.getY() - target.getY()) <= LEFTOVER_RADIUS
                && Math.abs(pos.getZ() - target.getZ()) <= LEFTOVER_RADIUS;
    }

    private boolean nearActive(BlockPos pos) {
        for (Target t : active.values()) {
            if (near(pos, t.pos)) return true;
        }
        return false;
    }

    /** 和正在破的某一格挨得太近（它们的活塞、火把会互相干扰） */
    private boolean tooClose(BlockPos pos) {
        for (Target t : active.values()) {
            int d = Math.max(Math.abs(pos.getX() - t.pos.getX()), Math.max(Math.abs(pos.getY() - t.pos.getY()), Math.abs(pos.getZ() - t.pos.getZ())));
            if (d < SPACING) return true;
        }
        return false;
    }

    /** 放下这一格（完成、放弃、人走开都走这里） */
    private void release(Target t) {
        if (level != null) recentTargets.put(t.pos.asLong(), level.getGameTime());
        active.remove(t.pos.asLong());
    }

    private void giveUp(Target t, String why) {
        if (DEBUG) debug("give up " + t.pos.toShortString() + " after " + (level.getGameTime() - t.since) + " ticks (" + why + "): " + BedrockCompat.debugTasks() + " around: " + debugAround(t.pos));
        int tries = giveUps.addTo(t.pos.asLong(), 1);
        setCooldown(t.pos, Math.min(GIVE_UP_COOLDOWN, FIRST_RETRY_COOLDOWN << Math.min(tries, 4)));
        release(t);
    }

    /**
     * 拆掉破基岩模组留下、现在够得着的方块（一次一块，等服务端回应再拆下一块）。投影里本来就有的同种方块不拆；
     * 正在破的格子旁边的不碰（那是它正在用的）。
     *
     * @return true 表示这一 tick 在拆（先不交新的格子）
     */
    private boolean cleanLeftovers() {
        if (minerPlaced.isEmpty()) return false;
        var iterator = minerPlaced.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long key = entry.getLongKey();
            net.minecraft.world.level.block.Block block = entry.getValue();
            BlockPos pos = BlockPos.of(key);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (!state.is(block) || leftoverAttempts.get(key) >= LEFTOVER_MAX_ATTEMPTS
                    || com.autyism.printer.utils.LitematicaUtils.getBlockState(pos).is(block)) {
                // 已经没了（破基岩模组自己拆了）、拆不掉、或者投影里就是它：不再管
                iterator.remove();
                leftoverAttempts.remove(key);
                continue;
            }
            if (nearActive(pos)) continue;
            if (!com.autyism.printer.utils.PlayerUtils.isWithinBlockInteractionRange(player, pos, 0)) continue;
            if (isOnCooldown(pos)) return true;
            if (DEBUG) debug("clean up " + block + " at " + pos.toShortString());
            leftoverAttempts.addTo(key, 1);
            com.autyism.printer.utils.BreakUtils.INSTANCE.continueDestroyBlock(pos);
            setCooldown(pos, 10);
            return true;
        }
        return false;
    }

    @Override
    protected int getTickInterval() {
        return Configs.Break.BREAK_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxExecutions() {
        return maxActive();
    }

    @Override
    protected boolean canExecute() {
        if (player.isCreative()) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_CREATIVE_MODE.getName());
            return false;
        }
        if (!BedrockCompat.isAvailable()) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_MOD_MISSING.getName());
            return false;
        }
        // bunnyi116 的破基岩模组在 Meteor 的 NoGhostBlocks（防幽灵方块）开着时什么都破不掉（测试过放置、挖掘两部分都会这样），
        // lxyan2333 的版本没问题。不拦着（也许以后的版本修好了），只提示
        if (BedrockCompat.isBunnyiBackend() && com.autyism.printer.compat.MeteorCompat.isNoGhostBlocksActive()) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_NOGHOST_CONFLICT.getName());
        }
        BedrockCompat.ensureWorking();
        BedrockCompat.syncAllowList();
        updateTargets();
        // 有一格卡住了：先不交新的，等别的做完再一起清掉
        for (Target t : active.values()) {
            if (t.stuck) return false;
        }
        if (cleanLeftovers()) return false;
        if (active.size() >= maxActive()) return false;
        // 耐久保护已经停下了（修好或换好镐，重开打印机才继续）：照常提示
        return !ToolSwitchUtils.isHalted() || ToolSwitchUtils.checkBedrockTool(player);
    }

    /** 看一遍正在破的格子：做完的、放弃的、人走开够不着的都放下 */
    private void updateTargets() {
        long now = level.getGameTime();
        // 破基岩模组手上还有够得着、打印机没在看的活（人回来以后它接着做先前挂起的格子，或者玩家自己点的）：也算正在破的格子
        for (BlockPos task : BedrockCompat.reachableTasks(player)) {
            if (!active.containsKey(task.asLong())) {
                if (DEBUG) debug("miner is working on " + task.toShortString() + ", tracking it");
                active.put(task.asLong(), new Target(task, now));
            }
        }
        for (Target t : new ArrayList<>(active.values())) {
            boolean busy = BedrockCompat.isBusyWith(t.pos);
            // 人走远了，破基岩模组够不着这一格（bunnyi116 的会把它挂起，人回来再接着做）：先去做够得着的格子，回来再交
            if (!com.autyism.printer.utils.PlayerUtils.isWithinBlockInteractionRange(player, t.pos, 0)) {
                if (DEBUG) debug("walked away from " + t.pos.toShortString() + ", miner busy with it=" + busy);
                setCooldown(t.pos, WALKED_AWAY_COOLDOWN);
                release(t);
                continue;
            }
            if (BedrockCompat.isTargetBlock(level.getBlockState(t.pos))) {
                t.sequenceWhenGone = Integer.MIN_VALUE;
                if (t.stuck) continue;
                boolean minerGaveUp = BedrockCompat.tracksTasks() && !busy && now - t.since > START_GRACE_TICKS;
                if (DEBUG && (now - t.since) % 100 == 0) debug("waiting " + t.pos.toShortString() + " for " + (now - t.since) + " ticks: " + BedrockCompat.debugTasks());
                // 破不掉（放不下活塞、被挡住……）：过一阵再试，先做别的格子
                if (minerGaveUp) giveUp(t, "miner gave up");
                else if (now - t.since > GIVE_UP_TICKS || now < t.since) t.stuck = true;
                continue;
            }
            // 方块不见了：等服务端把这一刻之前的动作都确认完（那时客户端看到的就是服务端的结果），再等这一格收尾做完
            if (t.sequenceWhenGone == Integer.MIN_VALUE && level instanceof com.autyism.printer.utils.PacketUtils.SequenceExtension seq) {
                t.sequenceWhenGone = seq.litematica_printer3$currentSequence();
            }
            boolean neverFinished = now - t.since > 2L * GIVE_UP_TICKS || now < t.since;
            if (!neverFinished && (com.autyism.printer.printer.ActionConfirm.lastAcked(level) < t.sequenceWhenGone || busy)) continue;
            if (DEBUG) debug("done " + t.pos.toShortString() + " in " + (now - t.since) + " ticks" + (neverFinished ? " (miner never finished)" : ""));
            giveUps.remove(t.pos.asLong());
            release(t);
        }
        // 卡住的格子：别的格子都做完（或者也卡住）了，清掉破基岩模组手上的活（它没法只停一格），过一阵再试
        if (!active.isEmpty()) {
            boolean anyStuck = false;
            boolean othersWorking = false;
            for (Target t : active.values()) {
                if (t.stuck) anyStuck = true;
                else othersWorking = true;
            }
            if (anyStuck && !othersWorking) {
                BedrockCompat.clearTasks();
                for (Target t : new ArrayList<>(active.values())) giveUp(t, "stuck");
            }
        }
    }

    @Override
    protected boolean respectsRenderLayer() {
        return !Configs.Bedrock.IGNORE_RENDER_LAYER.getBooleanValue();
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        return BedrockCompat.isTargetBlock(level.getBlockState(pos));
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        return !BedrockCompat.isTargetBlock(level.getBlockState(pos));
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (active.size() >= maxActive()) {
            skipIteration.set(true);
            return;
        }
        if (active.containsKey(blockPos.asLong())) return;
        // 破基岩模组够不着的格子不交（交了它也只是挂着），走近了再说
        if (!com.autyism.printer.utils.PlayerUtils.isWithinBlockInteractionRange(player, blockPos, 0)) return;
        // 和正在破的格子挨得太近：这次先跳过（不记冷却，那几格做完了就轮到它）
        if (tooClose(blockPos)) return;
        // 破基岩模组挖活塞用的镐快坏了：不交这一格，停下（它自己选镐，不看耐久）
        if (!ToolSwitchUtils.checkBedrockTool(player)) {
            skipIteration.set(true);
            return;
        }
        // 先记成正在破的格子再交：lxyan2333 的破基岩模组交的时候当场就放下活塞和火把，放下的东西要记得住
        Target t = new Target(blockPos.immutable(), level.getGameTime());
        active.put(t.pos.asLong(), t);
        boolean accepted = BedrockCompat.addToBreakList(blockPos, client.level);
        if (DEBUG) debug("hand " + blockPos.toShortString() + " accepted=" + accepted + " active=" + active.size());
        if (accepted) {
            addHighlight(blockPos, HighlightType.BREAK);
        } else {
            active.remove(t.pos.asLong());
        }
        setCooldown(blockPos, 100);
    }

    /** 调试：目标旁边除了空气、石头、基岩以外的方块 */
    private String debugAround(BlockPos target) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-LEFTOVER_RADIUS, -1, -LEFTOVER_RADIUS), target.offset(LEFTOVER_RADIUS, LEFTOVER_RADIUS, LEFTOVER_RADIUS))) {
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.is(Blocks.STONE) || state.is(Blocks.BEDROCK)) continue;
            sb.append(pos.toShortString()).append('=').append(state.getBlock().getDescriptionId().replace("block.minecraft.", "")).append(' ');
        }
        return sb.toString();
    }

    private void debug(String msg) {
        System.out.println("[ALE-BEDROCK] t" + level.getGameTime() + " " + msg);
    }

    @Override
    public void resetScanState() {
        super.resetScanState();
        active.clear();
        giveUps.clear();
        leftoverAttempts.clear();
        recentTargets.clear();
    }
}
