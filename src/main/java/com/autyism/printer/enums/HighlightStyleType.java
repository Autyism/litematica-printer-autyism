package com.autyism.printer.enums;

import com.autyism.printer.I18n;
import com.autyism.printer.config.ConfigOptionListEntry;

public enum HighlightStyleType implements ConfigOptionListEntry<HighlightStyleType> {
    OUTLINE("highlightStyleType.outline"),
    FILLED("highlightStyleType.filled"),
    BOTH("highlightStyleType.both");

    private final I18n i18n;

    HighlightStyleType(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
