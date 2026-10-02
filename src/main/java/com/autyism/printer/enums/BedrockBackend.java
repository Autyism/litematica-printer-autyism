package com.autyism.printer.enums;

import com.autyism.printer.I18n;
import com.autyism.printer.config.ConfigOptionListEntry;

/** 破基岩模式使用的破基岩模组。 */
public enum BedrockBackend implements ConfigOptionListEntry<BedrockBackend> {
    AUTO("bedrockBackend.auto"),
    BUNNYI("bedrockBackend.bunnyi"),
    LXYAN("bedrockBackend.lxyan"),
    BLOCKMINER("bedrockBackend.blockminer"),
    ;

    private final I18n i18n;

    BedrockBackend(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
