package com.autyism.ale.enums;

import com.autyism.ale.I18n;
import com.autyism.ale.config.ConfigOptionListEntry;

public enum ShulkerSource implements ConfigOptionListEntry<ShulkerSource> {
    MOD("shulkerSource.mod"),
    PLUGIN("shulkerSource.plugin"),
    ;

    private final I18n i18n;

    ShulkerSource(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}