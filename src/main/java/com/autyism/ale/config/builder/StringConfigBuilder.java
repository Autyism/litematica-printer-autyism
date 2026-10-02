package com.autyism.ale.config.builder;

import fi.dy.masa.malilib.config.options.ConfigString;
import com.autyism.ale.I18n;

public class StringConfigBuilder extends BaseConfigBuilder<ConfigString, StringConfigBuilder> {
    private String defaultValue = "";

    public StringConfigBuilder(I18n i18n) {
        super(i18n);
    }

    public StringConfigBuilder(String translateKey) {
        this(I18n.of(translateKey));
    }

    public StringConfigBuilder defaultValue(String value) {
        this.defaultValue = value;
        return this;
    }

    @Override
    public ConfigString build() {
        ConfigString config = new ConfigString(i18n.getNameKey(), defaultValue, descKey);
        return buildExtension(config);
    }
}