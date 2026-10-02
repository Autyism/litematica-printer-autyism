package com.autyism.printer.compat;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.BedrockBackend;
import com.autyism.printer.utils.ModUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
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
            return true;
        } catch (Throwable ignored) {
            clear();
            return false;
        }
    }

    private static void clear() {
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

    /** 当前使用的后端名称（提示用） */
    public static String getBackendName() {
        return switch (impl()) {
            case BUNNYI -> "bedrockminer (bunnyi116)";
            case LXYAN -> "bedrock-miner (lxyan2333)";
            case BLOCKMINER -> "blockminer";
            case NONE -> "none";
        };
    }

    public static void addToBreakList(BlockPos pos, ClientLevel world) {
        Impl impl = impl();
        if (impl == Impl.NONE || addBlockTaskMethod == null) return;
        try {
            switch (impl) {
                case BUNNYI -> addBlockTaskMethod.invoke(minerInstance, world, pos, world.getBlockState(pos).getBlock());
                default -> addBlockTaskMethod.invoke(minerInstance, pos);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clearTasks() {
        if (impl() == Impl.NONE || clearTaskMethod == null) return;
        try {
            clearTaskMethod.invoke(minerInstance);
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
