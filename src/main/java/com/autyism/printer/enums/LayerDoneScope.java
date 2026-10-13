package com.autyism.printer.enums;

import com.autyism.printer.I18n;
import com.autyism.printer.config.ConfigOptionListEntry;

/** 分层打印：一层什么时候算完成 */
public enum LayerDoneScope implements ConfigOptionListEntry<LayerDoneScope> {
    /** 已加载区块里的整层都打完（没加载的区块看不到，不算） */
    WHOLE_LAYER("layerDoneScope.whole"),
    /** 工作范围内的部分打完就算（以前的做法） */
    WORK_RANGE("layerDoneScope.range");

    private final I18n i18n;

    LayerDoneScope(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
