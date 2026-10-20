package com.autyism.printer.compat;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.BedrockBackend;
import com.autyism.printer.utils.ModUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * 破基岩模组联动（全部反射，未安装时安全）：
 * <ul>
 *     <li>bunnyi116 的 bedrockminer（mod id: bedrockminer）：TaskManager 任务队列</li>
 *     <li>lxyan2333 的 bedrock-miner（mod id: bedrock-miner）：BreakingFlowController.tryEnqueueBlock</li>
 *     <li>z7087 的 blockminer（mod id: blockminer）</li>
 * </ul>
 * 原版打印机只支持前者，装的是 lxyan2333 版时破基岩模式直接失效，只能手动一个个点。
 */
public class BedrockCompat {
    private enum Impl { NONE, BUNNYI, LXYAN, BLOCKMINER }

    @Nullable private static Impl resolvedImpl;
    @Nullable private static BedrockBackend resolvedFor;

    @Nullable private static Object minerInstance;
    @Nullable private static Method addBlockTaskMethod;
    @Nullable private static Method clearTaskMethod;
    @Nullable private static Method isRunningMethod;
    @Nullable private static Method setRunningMethod;
    @Nullable private static Method isFeatureEnableMethod;
    @Nullable private static Method setFeatureEnableMethod;
    @Nullable private static Method enableMethod;
    @Nullable private static Method disableMethod;
    /** 破基岩模组手上还有没有活：lxyan2333 的进行中任务；bunnyi116 的待办和进行中任务 */
    @Nullable private static Method activeTasksMethod;
    @Nullable private static Method pendingTasksMethod;
    /** bunnyi116 不在聊天栏提示的清空任务（removeAll(false)） */
    @Nullable private static Method quietClearMethod;

    /** 由打印机开启（而非玩家自己开启）的破基岩模组，打印机关闭破基岩模式时再关掉 */
    private static boolean enabledByPrinter;
    private static boolean featureDisabledByPrinter;

    private static boolean bunnyiLoaded() {
        return ModUtils.isBedrockMinerLoaded();
    }

    private static boolean lxyanLoaded() {
        return ModUtils.isLoadMod("bedrock-miner");
    }

    private static boolean blockMinerLoaded() {
        return ModUtils.isBlockMinerLoaded();
    }

    /** 是否装了任意一个受支持的破基岩模组 */
    public static boolean isAnyMinerLoaded() {
        return bunnyiLoaded() || lxyanLoaded() || blockMinerLoaded();
    }

    private static Impl impl() {
        BedrockBackend wanted = Configs.Bedrock.BACKEND.getOptionListValue() instanceof BedrockBackend b ? b : BedrockBackend.AUTO;
        if (resolvedImpl != null && wanted == resolvedFor) return resolvedImpl;
        clear();
        resolvedFor = wanted;
        Impl choice = switch (wanted) {
            case BUNNYI -> bunnyiLoaded() ? Impl.BUNNYI : Impl.NONE;
            case LXYAN -> lxyanLoaded() ? Impl.LXYAN : Impl.NONE;
            case BLOCKMINER -> blockMinerLoaded() ? Impl.BLOCKMINER : Impl.NONE;
            // 自动：优先 lxyan2333（在用户真实实例里测试最快最稳），其次 bunnyi116、blockminer
            case AUTO -> lxyanLoaded() ? Impl.LXYAN : bunnyiLoaded() ? Impl.BUNNYI : blockMinerLoaded() ? Impl.BLOCKMINER : Impl.NONE;
        };
        boolean ok = switch (choice) {
            case BUNNYI -> resolveBunnyi();
            case LXYAN -> resolveLxyan();
            case BLOCKMINER -> resolveBlockMiner();
            case NONE -> false;
        };
        resolvedImpl = ok ? choice : Impl.NONE;
        return resolvedImpl;
    }

    private static boolean resolveBlockMiner() {
        try {
            Class<?> modClass = Class.forName("me.z7087.blockminer.BlockMinerMod");
            Method getInstance = modClass.getDeclaredMethod("getInstance");
            Object modContainer = getInstance.invoke(null);
            Method getTaskManager = modContainer.getClass().getDeclaredMethod("getTaskManager");
            minerInstance = getTaskManager.invoke(modContainer);
            Class<?> tmClass = Class.forName("me.z7087.blockminer.task.TaskManager");
            addBlockTaskMethod = tmClass.getDeclaredMethod("handleAttackBlock", BlockPos.class);
            clearTaskMethod = tmClass.getDeclaredMethod("clearTasks");
            clearTaskMethod.setAccessible(true);
            isRunningMethod = tmClass.getDeclaredMethod("isEnabled");
            enableMethod = tmClass.getDeclaredMethod("onEnable");
            disableMethod = tmClass.getDeclaredMethod("onDisable");
            enableMethod.setAccessible(true);
            disableMethod.setAccessible(true);
            return true;
        } catch (Throwable ignored) {
            clear();
            return false;
        }
    }

    private static boolean resolveBunnyi() {
        try {
            Class<?> tmClass = Class.forName("com.github.bunnyi116.bedrockminer.task.TaskManager");
            minerInstance = tmClass.getDeclaredMethod("getInstance").invoke(null);
            addBlockTaskMethod = tmClass.getDeclaredMethod("addBlockTask", ClientLevel.class, BlockPos.class, Block.class);
            clearTaskMethod = tmClass.getDeclaredMethod("clearTask");
            isRunningMethod = tmClass.getDeclaredMethod("isRunning");
            setRunningMethod = tmClass.getDeclaredMethod("setRunning", boolean.class, boolean.class);
            isFeatureEnableMethod = tmClass.getDeclaredMethod("isBedrockMinerFeatureEnable");
            setFeatureEnableMethod = tmClass.getDeclaredMethod("setBedrockMinerFeatureEnable", boolean.class);
            activeTasksMethod = findOptional(tmClass, "getActiveBlockTasks");
            pendingTasksMethod = findOptional(tmClass, "getPendingBlockTasks");
            quietClearMethod = findOptional(tmClass, "removeAll", boolean.class);
            return true;
        } catch (Throwable ignored) {
            clear();
            return false;
        }
    }

    private static boolean resolveLxyan() {
        try {
            Class<?> c = Class.forName("com.github.lxyan2333.bedrockminer.client.breaking.BreakingFlowController");
            minerInstance = c.getField("INSTANCE").get(null);
            addBlockTaskMethod = c.getMethod("tryEnqueueBlock", BlockPos.class);
            clearTaskMethod = c.getMethod("cancelAllFlows");
            isRunningMethod = c.getMethod("getEnabled");
            enableMethod = c.getMethod("enable");
            disableMethod = c.getMethod("disable");
            activeTasksMethod = findOptional(c, "getActiveFlows");
            return true;
        } catch (Throwable ignored) {
            clear();
            return false;
        }
    }

    @Nullable
    private static Method findOptional(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void clear() {
        activeTasksMethod = null;
        pendingTasksMethod = null;
        quietClearMethod = null;
        minerInstance = null;
        addBlockTaskMethod = null;
        clearTaskMethod = null;
        isRunningMethod = null;
        setRunningMethod = null;
        isFeatureEnableMethod = null;
        setFeatureEnableMethod = null;
        enableMethod = null;
        disableMethod = null;
    }

    // ================================================================
    //  方块列表（破基岩模式要处理哪些方块）
    // ================================================================

    private static java.util.List<String> cachedListConfig = java.util.List.of();
    private static java.util.Set<net.minecraft.world.level.block.Block> targetBlocks = java.util.Set.of(net.minecraft.world.level.block.Blocks.BEDROCK);
    private static java.util.List<String> syncedIds = java.util.List.of();

    private static void refreshTargets() {
        java.util.List<String> current = Configs.Bedrock.BLOCK_LIST.getStrings();
        if (current.equals(cachedListConfig)) return;
        cachedListConfig = new java.util.ArrayList<>(current);
        java.util.Set<net.minecraft.world.level.block.Block> set = new java.util.HashSet<>();
        for (net.minecraft.world.level.block.Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            for (String entry : current) {
                if (com.autyism.printer.utils.PinYinSearchUtils.matchBlockName(entry, block.defaultBlockState())) {
                    set.add(block);
                    break;
                }
            }
        }
        targetBlocks = set;
    }

    public static boolean isTargetBlock(net.minecraft.world.level.block.state.BlockState state) {
        refreshTargets();
        return targetBlocks.contains(state.getBlock());
    }

    /** 把方块列表同步进破基岩模组自己的允许列表（只添加，不删除用户原有的设置） */
    public static void syncAllowList() {
        refreshTargets();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (net.minecraft.world.level.block.Block b : targetBlocks) {
            ids.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString());
        }
        java.util.Collections.sort(ids);
        if (ids.equals(syncedIds)) return;
        syncedIds = ids;
        if (bunnyiLoaded()) {
            try {
                Class<?> cfgClass = Class.forName("com.github.bunnyi116.bedrockminer.config.Config");
                Object cfg = cfgClass.getMethod("getInstance").invoke(null);
                @SuppressWarnings("unchecked")
                java.util.List<String> list = (java.util.List<String>) cfgClass.getField("blockWhitelist").get(cfg);
                for (String id : ids) if (!list.contains(id)) list.add(id);
            } catch (Throwable ignored) {
            }
        }
        if (lxyanLoaded()) {
            try {
                Class<?> client = Class.forName("com.github.lxyan2333.bedrockminer.client.config.Configs$Client");
                Object inst = client.getField("INSTANCE").get(null);
                fi.dy.masa.malilib.config.options.ConfigStringList allow =
                        (fi.dy.masa.malilib.config.options.ConfigStringList) client.getMethod("getALLOW_LIST").invoke(inst);
                java.util.List<String> merged = new java.util.ArrayList<>(allow.getStrings());
                boolean changed = false;
                for (String id : ids) {
                    if (!merged.contains(id)) {
                        merged.add(id);
                        changed = true;
                    }
                }
                if (changed) allow.setStrings(merged);
            } catch (Throwable ignored) {
            }
        }
    }

    // ================================================================
    //  Public API
    // ================================================================

    public static boolean isAvailable() {
        return impl() != Impl.NONE;
    }

    /** 当前用的是 bunnyi116 的破基岩模组（它和 Meteor 的 NoGhostBlocks 不兼容） */
    public static boolean isBunnyiBackend() {
        return impl() == Impl.BUNNYI;
    }

    /** 当前使用的后端名称（提示用） */
    public static String getBackendName() {
        return switch (impl()) {
            case BUNNYI -> "bedrockminer (bunnyi116)";
            case LXYAN -> "bedrock-miner (lxyan2333)";
            case BLOCKMINER -> "blockminer";
            case NONE -> "none";
        };
    }

    /** 交给破基岩模组一个方块，返回它是否接下了（lxyan2333 的会拒绝不允许或正被占用的位置） */
    public static boolean addToBreakList(BlockPos pos, ClientLevel world) {
        Impl impl = impl();
        if (impl == Impl.NONE || addBlockTaskMethod == null) return false;
        try {
            Object result = switch (impl) {
                case BUNNYI -> addBlockTaskMethod.invoke(minerInstance, world, pos, world.getBlockState(pos).getBlock());
                default -> addBlockTaskMethod.invoke(minerInstance, pos);
            };
            return !(result instanceof net.minecraft.world.InteractionResult r) || r != net.minecraft.world.InteractionResult.PASS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 调试：破基岩模组手上的任务（位置、状态） */
    public static String debugTasks() {
        StringBuilder sb = new StringBuilder();
        try {
            for (Method m : new Method[]{activeTasksMethod, pendingTasksMethod}) {
                if (m == null) continue;
                sb.append(m.getName()).append('=');
                if (m.invoke(minerInstance) instanceof java.util.Collection<?> c) {
                    for (Object t : c) {
                        String state = "";
                        Object pos = null;
                        try {
                            state = String.valueOf(t.getClass().getMethod("getCurrentState").invoke(t));
                        } catch (Throwable ignored) {
                        }
                        try {
                            pos = t.getClass().getField("pos").get(t);
                        } catch (Throwable ignored) {
                        }
                        sb.append('[').append(pos != null ? pos : t).append(' ').append(state).append(']');
                    }
                }
                sb.append(' ');
            }
        } catch (Throwable e) {
            sb.append(e);
        }
        return sb.toString();
    }

    /**
     * 破基岩模组手上还有这一格的活（还没做完，或者还在拆活塞、火把）。别的格子的活不算（例如人走远时 bunnyi116 挂起的那一格）；
     * 读不出任务的位置时，手上有活就算。
     */
    public static boolean isBusyWith(BlockPos pos) {
        if (impl() == Impl.NONE) return false;
        try {
            for (Method m : new Method[]{activeTasksMethod, pendingTasksMethod}) {
                if (m == null || !(m.invoke(minerInstance) instanceof java.util.Collection<?> tasks)) continue;
                for (Object task : tasks) {
                    BlockPos taskPos = taskPos(task);
                    if (taskPos == null || taskPos.equals(pos)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 破基岩模组破一格时会放下的方块：活塞（推出时的活塞头、移动中的活塞）、红石火把、粘液块，
     * 以及 lxyan2333 设置里的垫块（它放在火把下面）。
     */
    public static boolean isMinerBlock(BlockState state) {
        if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON) || state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON)
                || state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH) || state.is(Blocks.SLIME_BLOCK)) return true;
        Block support = lxyanSupportBlock();
        return support != null && support != Blocks.AIR && state.is(support);
    }

    @Nullable
    private static Block lxyanSupportBlock() {
        if (impl() != Impl.LXYAN) return null;
        try {
            Class<?> generic = Class.forName("com.github.lxyan2333.bedrockminer.client.config.Configs$Generic");
            return (Block) generic.getMethod("getSupportBlock").invoke(generic.getField("INSTANCE").get(null));
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 破基岩模组手上一个够得着的任务的位置（例如人走开时挂起、回来以后它会接着做的那一格，或者玩家自己点的）；没有就是 null。
     * 读不出位置的任务不算。
     */
    @Nullable
    public static BlockPos reachableTask(net.minecraft.client.player.LocalPlayer player) {
        if (impl() == Impl.NONE) return null;
        try {
            for (Method m : new Method[]{activeTasksMethod, pendingTasksMethod}) {
                if (m == null || !(m.invoke(minerInstance) instanceof java.util.Collection<?> tasks)) continue;
                for (Object task : tasks) {
                    BlockPos taskPos = taskPos(task);
                    if (taskPos != null && com.autyism.printer.utils.PlayerUtils.isWithinBlockInteractionRange(player, taskPos, 0)) {
                        return taskPos.immutable();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 任务的目标位置：lxyan2333 的 BreakingFlow.getTargetPos()，bunnyi116 的 Task.pos */
    @Nullable
    private static BlockPos taskPos(Object task) {
        try {
            if (impl() == Impl.LXYAN) return (BlockPos) task.getClass().getMethod("getTargetPos").invoke(task);
            return (BlockPos) task.getClass().getField("pos").get(task);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 读得到破基岩模组的任务列表（lxyan2333、bunnyi116 的能；z7087 的 blockminer 不能） */
    public static boolean tracksTasks() {
        return impl() != Impl.NONE && activeTasksMethod != null;
    }

    /**
     * 破基岩模组手上没有活了（上一个方块连同收尾的活塞、火把都处理完）。读不到它的任务列表时（z7087 的 blockminer）
     * 当作空闲，靠方块消失和服务端确认来判断。
     */
    public static boolean isIdle() {
        if (impl() == Impl.NONE) return true;
        try {
            if (activeTasksMethod != null && activeTasksMethod.invoke(minerInstance) instanceof java.util.Collection<?> c && !c.isEmpty()) return false;
            if (pendingTasksMethod != null && pendingTasksMethod.invoke(minerInstance) instanceof java.util.Collection<?> c && !c.isEmpty()) return false;
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static void clearTasks() {
        if (impl() == Impl.NONE || clearTaskMethod == null) return;
        try {
            // bunnyi116 的 clearTask 每次都在聊天栏说一句“已清除任务”：有不说话的版本就用它
            if (quietClearMethod != null) quietClearMethod.invoke(minerInstance, false);
            else clearTaskMethod.invoke(minerInstance);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isWorking() {
        if (impl() == Impl.NONE || isRunningMethod == null) return false;
        try {
            return (boolean) isRunningMethod.invoke(minerInstance);
        } catch (Throwable e) {
            return false;
        }
    }

    public static void setWorking(boolean running) {
        setWorking(running, false);
    }

    public static void setWorking(boolean running, boolean showMessage) {
        Impl impl = impl();
        if (impl == Impl.NONE) return;
        try {
            if (impl == Impl.BUNNYI) {
                setRunningMethod.invoke(minerInstance, running, showMessage);
                if (!running) clearTasks();
            } else {
                Method m = running ? enableMethod : disableMethod;
                if (m != null) m.invoke(minerInstance);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 破基岩模式开启时由模块调用：确保破基岩模组在运行，并记住是打印机打开的 */
    public static void ensureWorking() {
        if (!isWorking()) {
            setWorking(true);
            enabledByPrinter = true;
        }
        if (isFeatureEnable()) {
            setFeatureEnable(false);
            featureDisabledByPrinter = true;
        }
    }

    /** 每 tick 调用：破基岩模式已关闭时，把打印机打开的破基岩模组关掉，恢复玩家原本的状态 */
    public static void syncWithModule(boolean bedrockModeActive) {
        if (bedrockModeActive) return;
        if (enabledByPrinter) {
            enabledByPrinter = false;
            setWorking(false);
        }
        if (featureDisabledByPrinter) {
            featureDisabledByPrinter = false;
            setFeatureEnable(true);
        }
    }

    public static boolean isFeatureEnable() {
        if (impl() != Impl.BUNNYI || isFeatureEnableMethod == null) return false;
        try {
            return (boolean) isFeatureEnableMethod.invoke(minerInstance);
        } catch (Throwable e) {
            return false;
        }
    }

    public static void setFeatureEnable(boolean enabled) {
        if (impl() != Impl.BUNNYI || setFeatureEnableMethod == null) return;
        try {
            setFeatureEnableMethod.invoke(minerInstance, enabled);
        } catch (Throwable ignored) {
        }
    }
}
