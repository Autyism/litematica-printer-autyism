package com.autyism.ale.enums;

import com.autyism.ale.I18n;
import com.autyism.ale.config.ConfigOptionListEntry;

public enum MiningFilterType implements ConfigOptionListEntry<MiningFilterType> {
    TWEAKEROO("excavateListMode.tweakeroo"),
    CUSTOM("excavateListMode.custom");

    private final I18n i18n;

    MiningFilterType(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
