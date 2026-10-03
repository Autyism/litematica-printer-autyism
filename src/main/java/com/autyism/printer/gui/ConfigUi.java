package com.autyism.printer.gui;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import com.autyism.printer.I18n;
import com.autyism.printer.Reference;
import com.autyism.printer.mixin.extension.ConfigExtension;
import com.autyism.printer.config.Configs;
import com.autyism.printer.utils.ModUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public class ConfigUi extends GuiConfigsBase {
    private static Tab tab = Tab.CORE;

    public ConfigUi(@Nullable Screen parent) {
        super(10, 50, Reference.MOD_ID, parent, Reference.MOD_NAME + " " + ModUtils.LOCAL_VERSION + "   " + I18n.FREE_NOTICE.getName().getString());
    }

    public ConfigUi() {
        this(Minecraft.getInstance().screen);
    }

    public static void refresh() {
        if (Reference.MINECRAFT.screen instanceof ConfigUi gui) {
            gui.initGui();
        }
    }

    /** 分页按钮占几行（窗口窄时一行放不下会换行，配置列表跟着往下移） */
    private int tabRows = 1;

    @Override
    public void initGui() {
        int rows = this.layoutTabs(false);
        if (rows != this.tabRows) {
            this.tabRows = rows;
            this.setListPosition(10, 50 + (rows - 1) * 22);
            this.reCreateListWidget();
        }
        // 标题太长会盖住右上角的“切换模组”下拉框：放不下时去掉后半段说明
        String full = Reference.MOD_NAME + " " + ModUtils.LOCAL_VERSION + "   " + I18n.FREE_NOTICE.getName().getString();
        this.title = 20 + this.getStringWidth(full) <= this.getScreenWidth() - 230 ? full : Reference.MOD_NAME + " " + ModUtils.LOCAL_VERSION;
        super.initGui();
        this.clearOptions();
        this.layoutTabs(true);
    }

    @Override
    protected int getBrowserHeight() {
        return super.getBrowserHeight() - (this.tabRows - 1) * 22;
    }

    /** 排列分页按钮，返回占用的行数；create = false 时只计算不添加 */
    private int layoutTabs(boolean create) {
        int x = 10;
        int y = 26;
        int rows = 1;
        for (Tab tab : Tab.values()) {
            ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, tab.getName(), tab.getComment());
            if (x > 10 && x + button.getWidth() > this.getScreenWidth() - 10) {
                x = 10;
                y += 22;
                rows++;
                button.setPosition(x, y);
            }
            if (create) {
                button.setEnabled(ConfigUi.tab != tab);
                this.addButton(button, new ButtonListener(tab, this));
            }
            x += button.getWidth() + 2;
        }
        return rows;
    }

    public void reset() {
        reCreateListWidget();
        Objects.requireNonNull(getListWidget()).resetScrollbarPosition();
        initGui();
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        ImmutableList.Builder<ConfigOptionWrapper> builder = ImmutableList.builder();
        for (IConfigBase config : ConfigUi.tab.getConfigs()) {
            if (config instanceof ConfigExtension extension) {
                @Nullable BooleanSupplier visible = extension.litematica_printer$getVisible();
                if (visible != null && visible.getAsBoolean()) {
                    builder.add(new ConfigOptionWrapper(config));
                }
            }
        }
        return builder.build();
    }

    public enum Tab {
        ALL(I18n.of("category.all")),
        CORE(I18n.of("category.core")),
        PLACEMENT(I18n.of("category.placement")),
        BREAK(I18n.of("category.break")),
        HOTKEYS(I18n.of("category.hotkeys")),
        PRINT(I18n.of("category.print")),
        EXCAVATE(I18n.of("category.mine")),
        FILL(I18n.of("category.fill")),
        FLUID(I18n.of("category.fluid")),
        BEDROCK(I18n.of("category.bedrock")),
        HIGHLIGHT(I18n.of("category.highlight"));

        private final I18n i18n;

        Tab(I18n i18n) {
            this.i18n = i18n;
        }

        public String getName() {
            return i18n.getConfigName().getString();
        }

        public String getComment() {
            return i18n.getConfigDesc().getString();
        }

        public ImmutableList<IConfigBase> getConfigs() {
            return switch (this) {
                case ALL        -> Configs.All;
                case CORE       -> Configs.Core.OPTIONS;
                case PLACEMENT  -> Configs.Placement.OPTIONS;
                case BREAK      -> Configs.Break.OPTIONS;
                case PRINT      -> Configs.Print.OPTIONS;
                case EXCAVATE   -> Configs.Mine.OPTIONS;
                case FILL       -> Configs.Fill.OPTIONS;
                case FLUID      -> Configs.Fluid.OPTIONS;
                case BEDROCK    -> Configs.Bedrock.OPTIONS;
                case HIGHLIGHT  -> Configs.Highlight.OPTIONS;
                case HOTKEYS    -> Configs.Hotkeys.OPTIONS;
            };
        }
    }

    public record ButtonListener(Tab tab, ConfigUi parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            ConfigUi.tab = this.tab;
            this.parent.reset();
        }
    }
}