package com.autyism.printer.handler.handlers;

import com.autyism.printer.I18n;
import com.autyism.printer.utils.ModUtils;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.HighlightType;
import com.autyism.printer.handler.Module;
import com.autyism.printer.compat.BedrockCompat;
import com.autyism.printer.utils.MessageUtils;
import com.autyism.printer.utils.ToolSwitchUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 破基岩模式：把范围里的基岩（和名单里的方块）交给破基岩模组去破。
 * <p>
 * 一次只交一格：同时交一大片时，它会在每一格旁边同时放活塞和火把，互相干扰，哪一格都破不掉。
 * 上一格要等服务端确认破掉、破基岩模组连收尾的活塞和火把都处理完，才交下一格。
 */
public class Bedrock extends Module {
    public final static String NAME = "bedrock";

    /** 一格最多等这么久（破基岩模组卡住了）：清掉它手上的活，先做别的格子 */
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
    private BlockPos current;
    private long currentSince;
    private int sequenceWhenGone = Integer.MIN_VALUE;
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

    /**
     * 客户端世界里一格变了：破基岩模组正在破的那一格旁边出现了它用的方块，记下来。
     * 刚放下的格子旁边也算：人走开以后它还会接着放一两下，服务端的确认还要再晚一两 tick 才到（那时它可能已经停手了）。
     */
    private void onClientBlockChanged(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (level == null || !BedrockCompat.isMinerBlock(state)) return;
        if (current != null && near(pos, current)) {
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

    /** 放下当前这一格（完成、放弃、人走开都走这里） */
    private void releaseCurrent() {
        if (current != null && level != null) recentTargets.put(current.asLong(), level.getGameTime());
        current = null;
        sequenceWhenGone = Integer.MIN_VALUE;
    }

    /**
     * 拆掉破基岩模组留下、现在够得着的方块（一次一块，等服务端回应再拆下一块）。投影里本来就有的同种方块不拆。
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
        return 1;
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
        if (!currentDone()) return false;
        if (cleanLeftovers()) return false;
        // 耐久保护已经停下了（修好或换好镐，重开打印机才继续）：照常提示
        return !ToolSwitchUtils.isHalted() || ToolSwitchUtils.checkBedrockTool(player);
    }

    /** 当前交出去的那一格已经处理完（或者没有在处理的格子） */
    private boolean currentDone() {
        long now = level.getGameTime();
        if (current == null) {
            // 破基岩模组手上还有够得着的活（人回来以后它接着做先前挂起的那一格，或者玩家自己点的）：当作当前这一格，等它做完再交新的
            BlockPos task = BedrockCompat.reachableTask(player);
            if (task == null) return true;
            if (DEBUG) debug("miner is working on " + task.toShortString() + ", waiting for it");
            current = task;
            currentSince = now;
            sequenceWhenGone = Integer.MIN_VALUE;
        }
        boolean busy = BedrockCompat.isBusyWith(current);
        // 人走远了，破基岩模组够不着这一格（bunnyi116 的会把它挂起，人回来再接着做）：先去做够得着的格子，回来再交
        if (!com.autyism.printer.utils.PlayerUtils.isWithinBlockInteractionRange(player, current, 0)) {
            if (DEBUG) debug("walked away from " + current.toShortString() + ", miner busy with it=" + busy);
            setCooldown(current, WALKED_AWAY_COOLDOWN);
            releaseCurrent();
            return true;
        }
        if (BedrockCompat.isTargetBlock(level.getBlockState(current))) {
            sequenceWhenGone = Integer.MIN_VALUE;
            boolean minerGaveUp = BedrockCompat.tracksTasks() && !busy && now - currentSince > START_GRACE_TICKS;
            if (DEBUG && (now - currentSince) % 100 == 0) debug("waiting " + current.toShortString() + " for " + (now - currentSince) + " ticks: " + BedrockCompat.debugTasks());
            if (minerGaveUp || now - currentSince > GIVE_UP_TICKS || now < currentSince) {
                // 破不掉（放不下活塞、被挡住……）：过一阵再试，先做别的格子
                if (DEBUG) debug("give up " + current.toShortString() + " after " + (now - currentSince) + " ticks, miner gave up=" + minerGaveUp + ": " + BedrockCompat.debugTasks() + " around: " + debugAround(current));
                if (!minerGaveUp) BedrockCompat.clearTasks();
                int tries = giveUps.addTo(current.asLong(), 1);
                setCooldown(current, Math.min(GIVE_UP_COOLDOWN, FIRST_RETRY_COOLDOWN << Math.min(tries, 4)));
                releaseCurrent();
                return true;
            }
            return false;
        }
        // 方块不见了：等服务端把这一刻之前的动作都确认完（那时客户端看到的就是服务端的结果），再等这一格收尾做完
        if (sequenceWhenGone == Integer.MIN_VALUE && level instanceof com.autyism.printer.utils.PacketUtils.SequenceExtension seq) {
            sequenceWhenGone = seq.litematica_printer3$currentSequence();
        }
        boolean stuck = now - currentSince > 2L * GIVE_UP_TICKS || now < currentSince;
        if (!stuck && (com.autyism.printer.printer.ActionConfirm.lastAcked(level) < sequenceWhenGone || busy)) return false;
        if (DEBUG) debug("done " + current.toShortString() + " in " + (now - currentSince) + " ticks" + (stuck ? " (miner never finished)" : ""));
        giveUps.remove(current.asLong());
        releaseCurrent();
        return true;
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
        if (current != null) return;
        // 破基岩模组够不着的格子不交（交了它也只是挂着），走近了再说
        if (!com.autyism.printer.utils.PlayerUtils.isWithinBlockInteractionRange(player, blockPos, 0)) return;
        // 破基岩模组挖活塞用的镐快坏了：不交这一格，停下（它自己选镐，不看耐久）
        if (!ToolSwitchUtils.checkBedrockTool(player)) {
            skipIteration.set(true);
            return;
        }
        // 先记成当前这一格再交：lxyan2333 的破基岩模组交的时候当场就放下活塞和火把，放下的东西要记得住
        current = blockPos.immutable();
        currentSince = level.getGameTime();
        sequenceWhenGone = Integer.MIN_VALUE;
        boolean accepted = BedrockCompat.addToBreakList(blockPos, client.level);
        if (DEBUG) debug("hand " + blockPos.toShortString() + " accepted=" + accepted);
        if (accepted) {
            addHighlight(blockPos, HighlightType.BREAK);
        } else {
            current = null;
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
        current = null;
        sequenceWhenGone = Integer.MIN_VALUE;
        giveUps.clear();
        leftoverAttempts.clear();
        recentTargets.clear();
    }
}