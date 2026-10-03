package com.autyism.printer.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.*;
import fi.dy.masa.malilib.config.options.*;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import fi.dy.masa.malilib.config.ConfigManager;
import com.autyism.printer.Reference;
import com.autyism.printer.enums.*;
import com.autyism.printer.gui.ConfigUi;
import com.autyism.printer.utils.ModUtils;
import net.minecraft.world.level.block.Blocks;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

import fi.dy.masa.malilib.util.data.json.JsonUtils;
public class Configs extends ConfigBuilders implements IConfigHandler {
    private static final Configs INSTANCE = new Configs();

    private static final String FILE_PATH = "./config/" + Reference.MOD_ID + ".json";
    private static final File CONFIG_DIR = new File("./config");

    private static final BooleanSupplier isBreakCustom = () -> Break.BREAK_LIMITER.getOptionListValue().equals(MiningFilterType.CUSTOM);
    private static final BooleanSupplier isBreakWhitelist = () -> isBreakCustom.getAsBoolean() && Break.BREAK_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.WHITELIST);
    private static final BooleanSupplier isBreakBlacklist = () -> isBreakCustom.getAsBoolean() && Break.BREAK_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.BLACKLIST);

    private static final BooleanSupplier isExcavateCustom = () -> Mine.EXCAVATE_LIMITER.getOptionListValue().equals(MiningFilterType.CUSTOM);
    private static final BooleanSupplier isExcavateWhitelist = () -> isExcavateCustom.getAsBoolean() && Mine.EXCAVATE_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.WHITELIST);
    private static final BooleanSupplier isExcavateBlacklist = () -> isExcavateCustom.getAsBoolean() && Mine.EXCAVATE_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.BLACKLIST);
    private static final BooleanSupplier isBlocklist = () -> Fill.FILL_BLOCK_MODE.getOptionListValue().equals(FillBlockModeType.BLOCKLIST);
    private static final BooleanSupplier isHandheld = () -> Fill.FILL_BLOCK_MODE.getOptionListValue().equals(FillBlockModeType.HANDHELD);
    private static final BooleanSupplier isRemoteInventoryLoaded = ModUtils::isRemoteInventoryNextLoaded;

    public static final ImmutableList<IConfigBase> OPTIONS;
    public static final ImmutableList<IHotkey> HOTKEYS;

    static {
        LinkedHashSet<IConfigBase> optionSet = new LinkedHashSet<>();
        optionSet.addAll(Core.OPTIONS);
        optionSet.addAll(Placement.OPTIONS);
        optionSet.addAll(Break.OPTIONS);
        optionSet.addAll(Hotkeys.OPTIONS);
        optionSet.addAll(Print.OPTIONS);
        optionSet.addAll(Mine.OPTIONS);
        optionSet.addAll(Fill.OPTIONS);
        optionSet.addAll(Fluid.OPTIONS);
        optionSet.addAll(Bedrock.OPTIONS);
        optionSet.addAll(Highlight.OPTIONS);
        OPTIONS = ImmutableList.copyOf(optionSet);

        List<IHotkey> hotkeys = new ArrayList<>();
        for (IConfigBase option : optionSet) {
            if (option instanceof IHotkey hokey) {
                hotkeys.add(hokey);
            }
        }
        HOTKEYS = ImmutableList.copyOf(hotkeys);
    }

    public static ImmutableList<IConfigBase> All = ImmutableList.<IConfigBase>builder()
            .addAll(Core.OPTIONS)
            .addAll(Placement.OPTIONS)
            .addAll(Break.OPTIONS)
            .addAll(Hotkeys.OPTIONS)
            .addAll(Print.OPTIONS)
            .addAll(Mine.OPTIONS)
            .addAll(Fill.OPTIONS)
            .addAll(Fluid.OPTIONS)
            .addAll(Bedrock.OPTIONS)
            .addAll(Highlight.OPTIONS)
            .build();

    public static class Core {
        // 全局开关
        public static final ConfigBooleanHotkeyed WORK_SWITCH = booleanHotkey("workingSwitch")
                .defaultValue(false)
                .defaultHotkey("CAPS_LOCK")
                .keybindSettings(KeybindSettings.PRESS_ALLOWEXTRA_EMPTY)
                .build();

        // 工作半径（0 = 自动使用最大可用交互距离）
        public static final ConfigDouble WORK_RANGE = floatValue("workRange")
                .defaultValue(0)
                .range(0, 4096)
                .build();

        // 单人世界：工作半径大于原版交互距离时，自动用指令调高交互距离属性（需要允许作弊，最大 64）
        public static final ConfigBoolean AUTO_RAISE_REACH = booleanValue("autoRaiseReachSingleplayer")
                .defaultValue(true)
                .build();

        // 单人世界：工作半径不超过服务端允许的交互距离
        public static final ConfigBoolean LIMIT_RANGE_SINGLEPLAYER = booleanValue("limitRangeSingleplayer")
                .defaultValue(true)
                .build();

        // 需求 1：打开容器时暂停打印机
        public static final ConfigBoolean PAUSE_ON_CONTAINER = booleanValue("pauseOnContainer")
                .defaultValue(true)
                .build();

        // 迭代占用时长（毫秒）
        public static final ConfigInteger ITERATION_TIME_LIMIT = integerValue("iterationTimeLimit")
                .defaultValue(8)
                .range(0, 32)
                .build();

        // 按方块类型分类（每轮扫描仅处理一种方块）
        public static final ConfigBoolean CLASSIFY_BY_BLOCK = booleanValue("classifyByBlock")
                .defaultValue(false)
                .build();

        // 延迟检测
        public static final ConfigBoolean LAG_CHECK = booleanValue("printerLagCheck")
                .defaultValue(true)
                .build();

        public static final ConfigInteger LAG_CHECK_MAX = integerValue("printerLagCheckMax")
                .defaultValue(20)
                .setVisible(LAG_CHECK::getBooleanValue)
                .range(20, 1200)
                .build();

        // 迭代区域形状
        public static final ConfigOptionList ITERATOR_SHAPE = optionList("printerIteratorShape")
                .defaultValue(RadiusShapeType.SPHERE)
                .build();

        // 遍历顺序
        public static final ConfigOptionList ITERATION_ORDER = optionList("printerIteratorMode")
                .defaultValue(IterationOrderType.XZY)
                .build();

        // 迭代X轴反向
        public static final ConfigBoolean X_REVERSE = booleanValue("printerXAxisReverse")
                .defaultValue(false)
                .build();

        // 迭代Y轴反向
        public static final ConfigBoolean Y_REVERSE = booleanValue("printerYAxisReverse")
                .defaultValue(false)
                .build();

        // 迭代Z轴反向
        public static final ConfigBoolean Z_REVERSE = booleanValue("printerZAxisReverse")
                .defaultValue(false)
                .build();

        // 显示打印机HUD
        public static final ConfigBoolean RENDER_HUD = booleanValue("renderHud")
                .defaultValue(false)
                .build();

        // 显示缺失材料HUD
        public static final ConfigBoolean MISSING_MATERIAL_HUD = booleanValue("missingMaterialHud")
                .defaultValue(true)
                .build();

        // 自动禁用打印机
        public static final ConfigBoolean AUTO_DISABLE_PRINTER = booleanValue("printerAutoDisable")
                .defaultValue(true)
                .build();

        // 检查更新
        public static final ConfigBoolean UPDATE_CHECK = booleanValue("updateCheck")
                .defaultValue(true)
                .build();

        // 调试输出
        public static final ConfigBoolean DEBUG_OUTPUT = booleanValue("debugOutput")
                .defaultValue(false)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                WORK_SWITCH,
                WORK_RANGE,
                AUTO_RAISE_REACH,
                LIMIT_RANGE_SINGLEPLAYER,
                PAUSE_ON_CONTAINER,
                ITERATION_TIME_LIMIT,
                CLASSIFY_BY_BLOCK,
                RENDER_HUD,
                MISSING_MATERIAL_HUD,
                LAG_CHECK,
                LAG_CHECK_MAX,
                ITERATOR_SHAPE,
                ITERATION_ORDER,
                X_REVERSE,
                Y_REVERSE,
                Z_REVERSE,
                AUTO_DISABLE_PRINTER,
                DEBUG_OUTPUT
        );
    }

    public static class Placement {

        // 使用数据包打印
        public static final ConfigBoolean PRINT_USE_PACKET = booleanValue("placeUsePacket")
                .defaultValue(false)
                .build();

        // 核心 - 工作间隔
        public static final ConfigInteger PLACE_INTERVAL = integerValue("placeInterval")
                .defaultValue(1)
                .range(0, 20)
                .build();

        // 每刻放置方块数
        public static final ConfigInteger PLACE_BLOCKS_PER_TICK = integerValue("placeBlocksPerTick")
                .defaultValue(1)
                .range(0, 256)
                .build();

        // 放置冷却
        public static final ConfigInteger PLACE_COOLDOWN = integerValue("placeCooldown")
                .defaultValue(3)
                .range(0, 64)
                .build();

        // 下落方块检查
        public static final ConfigBoolean FALLING_CHECK = booleanValue("printFallingBlockCheck")
            .defaultValue(true)
            .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                PRINT_USE_PACKET,
                PLACE_INTERVAL,
                PLACE_BLOCKS_PER_TICK,
                PLACE_COOLDOWN,
                FALLING_CHECK
        );
    }

    public static class Break {
        public static final ConfigBoolean BREAK_USE_PACKET = booleanValue("breakUsePacket")
                .defaultValue(false)
                .build();

        public static final ConfigInteger BREAK_PROGRESS_THRESHOLD = integerValue("breakProgressThreshold")
                .defaultValue(100)
                .range(70, 100)
                .build();

        public static final ConfigInteger BREAK_INTERVAL = integerValue("breakInterval")
                .defaultValue(1)
                .range(0, 20)
                .build();

        public static final ConfigInteger BREAK_BLOCKS_PER_TICK = integerValue("breakBlocksPerTick")
                .defaultValue(1)
                .range(0, 256)
                .build();

        public static final ConfigInteger BREAK_COOLDOWN = integerValue("breakCooldown")
                .defaultValue(3)
                .range(0, 64)
                .build();

        public static final ConfigBoolean BREAK_CHECK_HARDNESS = booleanValue("breakCheckHardness")
                .defaultValue(true)
                .build();

        // 即时挖掘
        public static final ConfigBoolean BREAK_INSTANT_MINE = booleanValue("breakInstantOnSameTick")
                .defaultValue(false)
                .build();

        // 模式限制器
        public static final ConfigOptionList BREAK_LIMITER = optionList("breakLimiter")
                .defaultValue(MiningFilterType.CUSTOM)
                .build();

        // 模式限制
        public static final ConfigOptionList BREAK_LIMIT = optionList("breakLimit")
                .defaultValue(UsageRestriction.ListType.NONE)
                .setVisible(isBreakCustom)
                .build();

        // 白名单
        public static final ConfigStringList BREAK_WHITELIST = stringListValue("breakWhitelist")
                .setVisible(isBreakWhitelist)
                .build();

        // 黑名单
        public static final ConfigStringList BREAK_BLACKLIST = stringListValue("breakBlacklist")
                .setVisible(isBreakBlacklist)
                .build();

        // 自动切换工具（独立实现，不依赖 Tweakeroo）
        public static final ConfigBoolean AUTO_TOOL_SWITCH = booleanValue("breakAutoToolSwitch")
                .defaultValue(true)
                .build();

        // 工具耐久保护
        public static final ConfigBoolean TOOL_DURABILITY_PROTECT = booleanValue("breakToolDurabilityProtect")
                .defaultValue(true)
                .build();

        public static final ConfigInteger TOOL_DURABILITY_THRESHOLD = integerValue("breakToolDurabilityThreshold")
                .defaultValue(10)
                .range(1, 500)
                .setVisible(TOOL_DURABILITY_PROTECT::getBooleanValue)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                AUTO_TOOL_SWITCH,
                TOOL_DURABILITY_PROTECT,
                TOOL_DURABILITY_THRESHOLD,
                BREAK_CHECK_HARDNESS,
                BREAK_INSTANT_MINE,
                BREAK_USE_PACKET,
                BREAK_INTERVAL,
                BREAK_BLOCKS_PER_TICK,
                BREAK_COOLDOWN,
                BREAK_PROGRESS_THRESHOLD,
                // 限制器
                BREAK_LIMITER,
                BREAK_LIMIT,
                BREAK_WHITELIST,
                BREAK_BLACKLIST
        );
    }

    public static class Print {
        // 需求 13：大范围分层打印（从下往上一层层打）
        public static final ConfigBooleanHotkeyed LAYERED_MODE = booleanHotkey("printLayeredMode")
                .defaultValue(true)
                .build();

        // 启用打印
        public static final ConfigBooleanHotkeyed ENABLED = booleanHotkey("printEnabled")
                .defaultValue(false)
                .build();

        // 选区类型
        public static final ConfigOptionList PRINT_SELECTION_TYPE = optionList("printSelectionType")
                .defaultValue(SelectionType.LITEMATICA_RENDER_LAYER)
                .build();

        // 投影轻松放置协议
        // 轻松放置协议：服务端认得（单人游戏 / 装了 Servux）时直接按投影的状态放，不用转头；不认得的服务器自动用普通方式
        public static final ConfigBoolean EASY_PLACE_PROTOCOL = booleanValue("easyPlaceProtocol")
                .defaultValue(true)
                .build();

        // 凭空放置
        public static final ConfigBoolean PLACE_IN_AIR = booleanValue("placeInAir")
                .defaultValue(true)
                .build();

        // 跳过含水方块
        public static final ConfigBoolean SKIP_WATERLOGGED_BLOCK = booleanValue("printSkipWaterlogged")
                .defaultValue(false)
                .build();

        // 跳过放置
        public static final ConfigBoolean PRINT_SKIP = booleanValue("printSkip")
                .defaultValue(false)
                .build();

        // 跳过放置名单
        public static final ConfigStringList PRINT_SKIP_LIST = stringListValue("printSkipList")
                .build();

        // 始终潜行
        public static final ConfigBoolean PRINT_FORCED_SNEAK = booleanValue("printForcedSneak")
                .defaultValue(false)
                .build();

        // 覆盖打印
        public static final ConfigBoolean PRINT_REPLACE = booleanValue("printReplace")
                .defaultValue(true)
                .build();

        // 覆盖方块列表
        public static final ConfigStringList REPLACEABLE_LIST = stringListValue("printReplaceableList")
                .defaultValue(Blocks.SNOW, Blocks.LAVA, Blocks.WATER, Blocks.BUBBLE_COLUMN, Blocks.SHORT_GRASS)
                .build();

        // 替换珊瑚
        public static final ConfigBoolean REPLACE_CORAL = booleanValue("printReplaceCoral")
                .defaultValue(false)
                .build();

        // 用桶打印流体（水源 / 岩浆源 / 装满的炼药锅）
        public static final ConfigBoolean PRINT_FLUIDS_WITH_BUCKET = booleanValue("printFluidsWithBucket")
                .defaultValue(false)
                .build();

        // 破冰放水
        public static final ConfigBooleanHotkeyed PRINT_ICE_FOR_WATER = booleanHotkey("printIceForWater")
                .defaultValue(false)
                .build();

        // 自动去皮
        public static final ConfigBoolean STRIP_LOGS = booleanValue("printAutoStripLogs")
                .defaultValue(false)
                .build();

        // 音符盒自动调音
        public static final ConfigBoolean NOTE_BLOCK_TUNING = booleanValue("printAutoTuning")
                .defaultValue(true)
                .build();

        // 侦测器安全放置
        public static final ConfigBoolean SAFELY_OBSERVER = booleanValue("printSafelyObserver")
                .defaultValue(true)
                .build();

        // 铁轨安全放置：只在不会放错方向、不会把旁边铁轨拉歪时才放
        public static final ConfigBoolean SAFE_RAILS = booleanValue("printSafeRails")
                .defaultValue(true)
                .build();

        // 堆肥桶自动填充
        public static final ConfigBoolean FILL_COMPOSTER = booleanValue("printAutoFillComposter")
                .defaultValue(false)
                .build();

        // 堆肥桶白名单
        public static final ConfigStringList FILL_COMPOSTER_WHITELIST = stringListValue("printAutoFillComposterWhitelist")
                .setVisible(FILL_COMPOSTER::getBooleanValue)
                .build();

        // 农作物催熟
        public static final ConfigBoolean BONEMEAL_CROPS = booleanValue("printBonemealCrops")
                .defaultValue(false)
                .build();

        // 破坏错误方块
        public static final ConfigBoolean BREAK_WRONG_BLOCK = booleanValue("printBreakWrongBlock")
                .defaultValue(false)
                .build();

        // 破坏多余方块
        public static final ConfigBoolean BREAK_EXTRA_BLOCK = booleanValue("printBreakExtraBlock")
                .defaultValue(false)
                .build();

        // 破坏错误状态方块（实验性）
        public static final ConfigBoolean BREAK_WRONG_STATE_BLOCK = booleanValue("printBreakWrongStateBlock")
                .defaultValue(false)
                .build();

        // 使用远程容器材料
        public static final ConfigBooleanHotkeyed USE_REMOTE_CONTAINER = booleanHotkey("useRemoteContainer")
                .defaultValue(false)
                .setVisible(isRemoteInventoryLoaded)
                .build();

        // 远程容器方块列表
        public static final ConfigStringList REMOTE_CONTAINER_BLOCKS = stringListValue("remoteContainerBlocks")
                .defaultValue("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel")
                .setVisible(isRemoteInventoryLoaded)
                .build();

        // 使用快捷潜影盒
        public static final ConfigBoolean USE_QUICK_SHULKER = booleanValue("useQuickShulker")
                .defaultValue(true)
                .build();

        // 潜影盒来源
        public static final ConfigOptionList SHULKER_SOURCE = optionList("shulkerSource")
                .defaultValue(ShulkerSource.MOD)
                .build();

        // 潜影盒冷却
        public static final ConfigInteger SHULKER_COOLDOWN = integerValue("shulkerCooldown")
                .defaultValue(5)
                .range(0, 100)
                .build();

        // 背包满时有序放回潜影盒
        public static final ConfigBoolean RETURN_TO_SHULKER_WHEN_FULL = booleanValue("returnToShulkerWhenFull")
                .defaultValue(true)
                .build();

        // 背包满时有序放回远程容器
        public static final ConfigBoolean RETURN_TO_CONTAINER_WHEN_FULL = booleanValue("returnToContainerWhenFull")
                .defaultValue(true)
                .setVisible(isRemoteInventoryLoaded)
                .build();

        // 远程容器回塞节流（tick）
        public static final ConfigInteger CONTAINER_RETURN_INTERVAL = integerValue("containerReturnInterval")
                .defaultValue(60)
                .range(1, 200)
                .setVisible(isRemoteInventoryLoaded)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ENABLED,
                LAYERED_MODE,
                PRINT_SELECTION_TYPE,
                EASY_PLACE_PROTOCOL,
                PLACE_IN_AIR,
                PRINT_FORCED_SNEAK,
                BREAK_WRONG_BLOCK,
                BREAK_EXTRA_BLOCK,
                BREAK_WRONG_STATE_BLOCK,
                PRINT_SKIP,
                PRINT_SKIP_LIST,
                PRINT_REPLACE,
                REPLACEABLE_LIST,
                SKIP_WATERLOGGED_BLOCK,
                PRINT_FLUIDS_WITH_BUCKET,
                PRINT_ICE_FOR_WATER,
                SAFELY_OBSERVER,
                SAFE_RAILS,
                STRIP_LOGS,
                NOTE_BLOCK_TUNING,
                REPLACE_CORAL,
                FILL_COMPOSTER,
                FILL_COMPOSTER_WHITELIST,
                BONEMEAL_CROPS,
                USE_REMOTE_CONTAINER,
                REMOTE_CONTAINER_BLOCKS,
                USE_QUICK_SHULKER,
                SHULKER_SOURCE,
                SHULKER_COOLDOWN,
                RETURN_TO_SHULKER_WHEN_FULL,
                RETURN_TO_CONTAINER_WHEN_FULL,
                CONTAINER_RETURN_INTERVAL
        );
    }

    public static class Mine {
        // 启用挖掘
        public static final ConfigBooleanHotkeyed ENABLED = booleanHotkey("mineEnabled")
                .defaultValue(false)
                .build();

        // 选区类型
        public static final ConfigOptionList MINE_SELECTION_TYPE = optionList("mineSelectionType")
                .defaultValue(SelectionType.LITEMATICA_SELECTION)
                .build();

        // 秒破优先：先挖范围内能秒破的方块，都挖完了再挖不能秒破的。
        // 开启时仅在检测到“效率 V 工具 + 急迫 II”时启用这套逻辑；关闭则始终启用
        public static final ConfigBoolean MINE_INSTANT_FIRST_DETECT = booleanValue("mineInstantFirstDetect")
                .defaultValue(true)
                .build();

        // 挖掘模式限制器
        public static final ConfigOptionList EXCAVATE_LIMITER = optionList("excavateLimiter")
                .defaultValue(MiningFilterType.CUSTOM)
                .build();

        // 挖掘模式限制
        public static final ConfigOptionList EXCAVATE_LIMIT = optionList("excavateLimit")
                .defaultValue(UsageRestriction.ListType.NONE)
                .setVisible(isExcavateCustom)
                .build();

        // 挖掘白名单
        public static final ConfigStringList EXCAVATE_WHITELIST = stringListValue("excavateWhitelist")
                .setVisible(isExcavateWhitelist)
                .build();

        // 挖掘黑名单
        public static final ConfigStringList EXCAVATE_BLACKLIST = stringListValue("excavateBlacklist")
                .setVisible(isExcavateBlacklist)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ENABLED,
                MINE_SELECTION_TYPE,
                MINE_INSTANT_FIRST_DETECT,
                EXCAVATE_LIMITER,
                EXCAVATE_LIMIT,
                EXCAVATE_WHITELIST,
                EXCAVATE_BLACKLIST
        );
    }

    public static class Fill {
        // 启用填充
        public static final ConfigBooleanHotkeyed ENABLED = booleanHotkey("fillEnabled")
                .defaultValue(false)
                .build();

        // 选区类型
        public static final ConfigOptionList FILL_SELECTION_TYPE = optionList("fillSelectionType")
                .defaultValue(SelectionType.LITEMATICA_SELECTION)
                .build();

        // 填充方块模式
        public static final ConfigOptionList FILL_BLOCK_MODE = optionList("fillBlockMode")
                .defaultValue(FillBlockModeType.BLOCKLIST)
                .build();

        // 填充方块名单
        public static final ConfigStringList FILL_BLOCK_LIST = stringListValue("fillBlockList")
                .defaultValue(Blocks.COBBLESTONE)
                .setVisible(isBlocklist)
                .build();

        // 手持物品黑名单
        public static final ConfigStringList FILL_HANDHELD_BLACKLIST = stringListValue("fillHandheldBlacklist")
                .setVisible(isHandheld)
                .build();

        // 模式朝向
        public static final ConfigOptionList FILL_BLOCK_FACING = optionList("fillModeFacing")
                .defaultValue(FillModeFacingType.NONE)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ENABLED,
                FILL_SELECTION_TYPE,
                FILL_BLOCK_MODE,
                FILL_BLOCK_LIST,
                FILL_HANDHELD_BLACKLIST,
                FILL_BLOCK_FACING
        );
    }

    public static class Fluid {
        // 启用排流体
        public static final ConfigBooleanHotkeyed ENABLED = booleanHotkey("fluidEnabled")
                .defaultValue(false)
                .build();

        // 选区类型
        public static final ConfigOptionList FLUID_SELECTION_TYPE = optionList("fluidSelectionType")
                .defaultValue(SelectionType.LITEMATICA_SELECTION)
                .build();

        // 填充流动液体
        public static final ConfigBoolean FILL_FLOWING_FLUID = booleanValue("fluidModeFillFlowing")
                .defaultValue(true)
                .build();

        // 方块名单
        public static final ConfigStringList FLUID_REPLACE_BLOCK_LIST = stringListValue("fluidReplaceBlockList")
                .defaultValue(Blocks.SAND)
                .build();

        // 液体名单
        public static final ConfigStringList FLUID_LIST = stringListValue("fluidList")
                .defaultValue(Blocks.WATER, Blocks.LAVA)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ENABLED,
                FLUID_SELECTION_TYPE,
                FILL_FLOWING_FLUID,
                FLUID_REPLACE_BLOCK_LIST,
                FLUID_LIST
        );
    }

    public static class Bedrock {
        // 启用破基岩
        public static final ConfigBooleanHotkeyed ENABLED = booleanHotkey("bedrockEnabled")
                .defaultValue(false)
                .build();

        // 使用哪个破基岩模组（同时装了多个时可指定）
        public static final ConfigOptionList BACKEND = optionList("bedrockBackend")
                .defaultValue(BedrockBackend.AUTO)
                .build();

        // 框选范围内的基岩全部处理，不受 Litematica 渲染层限制
        public static final ConfigBoolean IGNORE_RENDER_LAYER = booleanValue("bedrockIgnoreRenderLayer")
                .defaultValue(true)
                .build();

        // 破基岩模式要处理的方块（例如末地传送门框架、强化深板岩），会自动同步到破基岩模组的允许列表
        public static final ConfigStringList BLOCK_LIST = stringListValue("bedrockBlockList")
                .defaultValue(com.google.common.collect.ImmutableList.of("minecraft:bedrock"))
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ENABLED,
                BACKEND,
                IGNORE_RENDER_LAYER,
                BLOCK_LIST
        );
    }

    public static class Highlight {
        // 启用方块高亮
        public static final ConfigBoolean HIGHLIGHT_ENABLED = booleanValue("highlightEnabled")
                .defaultValue(false)
                .build();

        // 成功放置颜色
        public static final ConfigColor HIGHLIGHT_COLOR_PLACE = color("highlightColorPlace")
                .defaultValue("#80FFFFFF")
                .build();

        // 调整颜色（填充/流体）
        public static final ConfigColor HIGHLIGHT_COLOR_ADJUST = color("highlightColorAdjust")
                .defaultValue("#8000FF00")
                .build();

        // 破坏方块颜色
        public static final ConfigColor HIGHLIGHT_COLOR_BREAK = color("highlightColorBreak")
                .defaultValue("#80FF0000")
                .build();

        // 放置失败颜色
        public static final ConfigColor HIGHLIGHT_COLOR_FAILED = color("highlightColorFailed")
                .defaultValue("#80808080")
                .build();

        // 高亮样式
        public static final ConfigOptionList HIGHLIGHT_STYLE = optionList("highlightStyle")
                .defaultValue(HighlightStyleType.OUTLINE)
                .build();

        // 完成后渐隐时长（单位0.1秒，10=1秒）
        public static final ConfigInteger HIGHLIGHT_FADE_DURATION = integerValue("highlightFadeDuration")
                .defaultValue(5)
                .range(1, 100)
                .useSlider(true)
                .build();

        // 透视模式（透过方块查看）
        public static final ConfigBoolean HIGHLIGHT_THROUGH_WALLS = booleanValue("highlightThroughWalls")
                .defaultValue(false)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                HIGHLIGHT_ENABLED,
                HIGHLIGHT_COLOR_PLACE,
                HIGHLIGHT_COLOR_ADJUST,
                HIGHLIGHT_COLOR_BREAK,
                HIGHLIGHT_COLOR_FAILED,
                HIGHLIGHT_STYLE,
                HIGHLIGHT_FADE_DURATION,
                HIGHLIGHT_THROUGH_WALLS
        );
    }

    public static class Hotkeys {
        // 打开设置菜单
        public static final ConfigHotkey OPEN_SCREEN = hotkeyValue("openScreen")
                .defaultStorageString("Z,Y")
                .build();

        // 关闭全部模式
        public static final ConfigHotkey CLOSE_ALL_MODE = hotkeyValue("closeAllMode")
                .defaultStorageString("LEFT_CONTROL,G")
                .build();

        // 轮换模式
        public static final ConfigHotkey CYCLE_MODE = hotkeyValue("cycleMode")
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                OPEN_SCREEN,
                Core.WORK_SWITCH,
                CLOSE_ALL_MODE,
                CYCLE_MODE
        );
    }

    /** 旧版打印机（litematica-printer）的配置文件：首次运行时迁移过来，保留用户原有设置 */
    private static final String LEGACY_FILE_PATH = "./config/litematica-printer.json";
    private static final String LEGACY_CATEGORY = "litematica-printer";

    @Override
    public void load() {
        File settingFile = new File(FILE_PATH);
        if (!settingFile.exists()) {
            File legacy = new File(LEGACY_FILE_PATH);
            if (legacy.isFile()) {
                JsonElement legacyJson = JsonUtils.parseJsonFile(legacy.toPath());
                if (legacyJson != null && legacyJson.isJsonObject()) {
                    ConfigUtils.readConfigBase(legacyJson.getAsJsonObject(), LEGACY_CATEGORY, OPTIONS);
                    Reference.LOGGER.info("Migrated settings from {}", LEGACY_FILE_PATH);
                    save();
                }
                return;
            }
        }
        if (settingFile.isFile() && settingFile.exists()) {
            JsonElement jsonElement = JsonUtils.parseJsonFile(settingFile.toPath());
            if (jsonElement != null && jsonElement.isJsonObject()) {
                JsonObject obj = jsonElement.getAsJsonObject();
                ConfigUtils.readConfigBase(obj, Reference.MOD_ID, OPTIONS);
            }
        }
    }

    @Override
    public void save() {
        if ((CONFIG_DIR.exists() && CONFIG_DIR.isDirectory()) || CONFIG_DIR.mkdirs()) {
            JsonObject configRoot = new JsonObject();
            ConfigUtils.writeConfigBase(configRoot, Reference.MOD_ID, OPTIONS);
            JsonUtils.writeJsonToFile(configRoot, new File(FILE_PATH).toPath());
        }
    }

    public static void init() {
        Configs.INSTANCE.load();
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, Configs.INSTANCE);
        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
        InputEventHandler.getInputManager().registerKeyboardInputHandler(InputHandler.getInstance());
        fi.dy.masa.malilib.registry.Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new fi.dy.masa.malilib.util.data.ModInfo(Reference.MOD_ID, Reference.MOD_NAME, ConfigUi::new)
        );
    }
}