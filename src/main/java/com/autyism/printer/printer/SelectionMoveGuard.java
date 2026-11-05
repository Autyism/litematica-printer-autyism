package com.autyism.printer.printer;

import com.autyism.printer.I18n;
import com.autyism.printer.config.Configs;
import com.autyism.printer.utils.LitematicaUtils;
import com.autyism.printer.utils.MessageUtils;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 安全设置“投影或框选范围移动时关闭打印机”（默认开）的框选部分：Litematica 的框选范围一变（移动、改大小、换成另一个选区、
 * 普通和简单模式之间切换），就关掉打印机，免得按新的范围接着放、接着挖。Litematica 不通知框选的变化，所以每 tick 在打印机
 * 动手之前比一下框选里的几个框。换世界、换维度时载入的是那边的框选，不算变了。
 */
public final class SelectionMoveGuard {
    private SelectionMoveGuard() {
    }

    @Nullable
    private static List<PrinterBox> last;
    @Nullable
    private static Object lastLevel;
    /** 上一次看的时候打印机开着：框选变了而打印机是之后才打开的（同一 tick 里先改框选再开），不算 */
    private static boolean wasOn;

    /**
     * The printer was just switched on: the selection now is the one it works with. Before, a change made right before
     * switching on could count as a change while on when this was checked late (1.20.1: from the player's tick).
     */
    public static void switchedOn() {
        Object level = Minecraft.getInstance().level;
        last = level == null ? List.of() : LitematicaUtils.getSelectionWorkBoxes();
        lastLevel = level;
        wasOn = true;
    }

    public static void tick() {
        Object level = Minecraft.getInstance().level;
        List<PrinterBox> now = level == null ? List.of() : LitematicaUtils.getSelectionWorkBoxes();
        boolean changed = last != null && level == lastLevel && !now.equals(last);
        boolean on = Configs.Core.WORK_SWITCH.getBooleanValue();
        boolean onBefore = wasOn;
        last = now;
        lastLevel = level;
        wasOn = on;
        if (!changed || !onBefore || !on || !Configs.Core.STOP_ON_PLACEMENT_MOVE.getBooleanValue()) return;
        wasOn = false;
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        MessageUtils.setOverlayMessage(I18n.SELECTION_MOVED_NOTICE.getName());
    }
}
