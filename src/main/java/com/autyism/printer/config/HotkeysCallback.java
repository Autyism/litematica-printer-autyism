package com.autyism.printer.config;

import com.autyism.printer.I18n;
import com.autyism.printer.gui.ConfigUi;
import com.autyism.printer.handler.ModuleManager;
import com.autyism.printer.printer.ActionManager;
import com.autyism.printer.utils.MessageUtils;
import com.autyism.printer.compat.BedrockCompat;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

// 按键与回调注册
public class HotkeysCallback {
    private static final Minecraft client = Minecraft.getInstance();

    /** 模式名称，顺序和 {@link #modes()} 一致 */
    private static final I18n[] MODE_NAMES = {
            I18n.of("print"), I18n.of("mine"), I18n.of("fill"), I18n.of("fluid"), I18n.of("bedrock")
    };

    /** 各模式的“启用”开关，也是轮换顺序：打印 → 挖掘 → 填充 → 排流体 → 破基岩 */
    private static ConfigBooleanHotkeyed[] modes() {
        return new ConfigBooleanHotkeyed[]{
                Configs.Print.ENABLED,
                Configs.Mine.ENABLED,
                Configs.Fill.ENABLED,
                Configs.Fluid.ENABLED,
                Configs.Bedrock.ENABLED
        };
    }

    public static void initCallbacks() {
        // 打开设置界面
        Configs.Hotkeys.OPEN_SCREEN.getKeybind().setCallback((action, keybind) -> {
            if (client.player != null && client.level != null) {
                client.setScreen(new ConfigUi());
            }
            return true;
        });

        // 打印机开关
        Configs.Core.WORK_SWITCH.getKeybind().setCallback((action, keybind) -> {
            togglePrinter();
            return true;
        });

        // 各模式自己的快捷键
        for (ConfigBooleanHotkeyed mode : modes()) {
            mode.getKeybind().setCallback((action, keybind) -> {
                toggleMode(mode);
                return true;
            });
        }

        Configs.Hotkeys.CYCLE_MODE.getKeybind().setCallback((action, keybind) -> {
            cycleMode();
            return true;
        });

        Configs.Hotkeys.CLOSE_ALL_MODE.getKeybind().setCallback((action, keybind) -> {
            if (keybind.isKeybindHeld()) {
                for (ConfigBooleanHotkeyed mode : modes()) {
                    mode.setBooleanValue(false);
                }
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                MessageUtils.setOverlayMessage(I18n.MESSAGE_ALL_MODES_CLOSED.getName());
            }
            return true;
        });

        // 打印机开关：关掉时清掉还没做完的操作；打开时一个模式都没开，就开“打印”（否则打开了也什么都不做）
        Configs.Core.WORK_SWITCH.setValueChangeCallback(b -> {
            if (!b.getBooleanValue()) {
                clearPendingActions();
                if (BedrockCompat.isAvailable()) {
                    if (BedrockCompat.isWorking()) {
                        BedrockCompat.setWorking(false);
                        BedrockCompat.setFeatureEnable(true);
                    }
                }
            } else if (!anyModeEnabled()) {
                Configs.Print.ENABLED.setBooleanValue(true);
            }
        });

        // 切换基岩功能时，关闭破基岩
        Configs.Bedrock.ENABLED.setValueChangeCallback(b -> {
            if (!b.getBooleanValue()) {
                if (BedrockCompat.isAvailable()) {
                    if (BedrockCompat.isWorking()) {
                        BedrockCompat.setWorking(false);
                        BedrockCompat.setFeatureEnable(true);
                    }
                }
            }
        });

        // 特殊设置时，自动刷新界面
        Configs.Print.FILL_COMPOSTER.setValueChangeCallback(b -> ConfigUi.refresh());
        Configs.Break.BREAK_LIMITER.setValueChangeCallback(b -> ConfigUi.refresh());
        Configs.Break.BREAK_LIMIT.setValueChangeCallback(b -> ConfigUi.refresh());
        Configs.Mine.EXCAVATE_LIMITER.setValueChangeCallback(b -> ConfigUi.refresh());
        Configs.Mine.EXCAVATE_LIMIT.setValueChangeCallback(b -> ConfigUi.refresh());
        Configs.Fill.FILL_BLOCK_MODE.setValueChangeCallback(b -> ConfigUi.refresh());
        Configs.Core.LAG_CHECK.setValueChangeCallback(b -> ConfigUi.refresh());
    }

    /** 打印机开关的快捷键：开 / 关打印机，按开着的模式工作 */
    public static void togglePrinter() {
        Configs.Core.WORK_SWITCH.setBooleanValue(!Configs.Core.WORK_SWITCH.getBooleanValue());
        showState();
    }

    /**
     * 某个模式自己的快捷键。
     * 打印机关着：只开这个模式，并打开打印机（按一下就开始干活，不会因为总开关关着而没反应）。
     * 打印机开着：开 / 关这个模式；最后一个在工作的模式关掉时，打印机也一起关掉。
     */
    public static void toggleMode(ConfigBooleanHotkeyed mode) {
        if (!Configs.Core.WORK_SWITCH.getBooleanValue()) {
            for (ConfigBooleanHotkeyed m : modes()) {
                m.setBooleanValue(m == mode);
            }
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
        } else if (mode.getBooleanValue()) {
            mode.setBooleanValue(false);
            if (anyModeEnabled()) {
                clearPendingActions();
            } else {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
            }
        } else {
            mode.setBooleanValue(true);
        }
        showState();
    }

    /**
     * 轮换模式：换成下一个模式，只开这一个。没装破基岩模组时跳过破基岩。
     * “轮换模式时关闭打印机”开着（默认）时顺便关掉打印机，新模式不会自己开始干活；
     * 关着时打印机保持原样，开着就直接用新模式继续。
     */
    public static void cycleMode() {
        ConfigBooleanHotkeyed[] all = modes();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == Configs.Bedrock.ENABLED && !BedrockCompat.isAvailable()) continue;
            order.add(i);
        }
        int current = -1;
        for (int k = 0; k < order.size(); k++) {
            if (all[order.get(k)].getBooleanValue()) {
                current = k;
                break;
            }
        }
        int next = order.get((current + 1) % order.size());
        for (int i = 0; i < all.length; i++) {
            all[i].setBooleanValue(i == next);
        }
        if (Configs.Hotkeys.CYCLE_TURNS_OFF.getBooleanValue()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
        } else if (Configs.Core.WORK_SWITCH.getBooleanValue()) {
            clearPendingActions();
        }
        I18n message = Configs.Core.WORK_SWITCH.getBooleanValue() ? I18n.MESSAGE_CYCLE_ON : I18n.MESSAGE_CYCLE_OFF;
        MessageUtils.setOverlayMessage(message.getName(MODE_NAMES[next].getConfigName()));
    }

    public static boolean anyModeEnabled() {
        for (ConfigBooleanHotkeyed mode : modes()) {
            if (mode.getBooleanValue()) return true;
        }
        return false;
    }

    /** 快捷栏上方提示打印机现在的状态：开着时列出在工作的模式 */
    private static void showState() {
        if (!Configs.Core.WORK_SWITCH.getBooleanValue()) {
            MessageUtils.setOverlayMessage(I18n.MESSAGE_PRINTER_OFF.getName());
            return;
        }
        MutableComponent names = MessageUtils.literal("");
        ConfigBooleanHotkeyed[] all = modes();
        boolean first = true;
        for (int i = 0; i < all.length; i++) {
            if (!all[i].getBooleanValue()) continue;
            if (!first) names.append(", ");
            names.append(MODE_NAMES[i].getConfigName());
            first = false;
        }
        MessageUtils.setOverlayMessage(I18n.MESSAGE_PRINTER_ON.getName(names));
    }

    /** 换模式或关打印机时，丢掉排队中、还没发出去的操作 */
    private static void clearPendingActions() {
        ModuleManager.PRINT.setWatingForWaterPos(null);
        ActionManager.INSTANCE.clearQueue();
    }
}
