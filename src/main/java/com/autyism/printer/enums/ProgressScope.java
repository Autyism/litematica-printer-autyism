package com.autyism.printer.enums;

import com.autyism.printer.I18n;
import com.autyism.printer.config.ConfigOptionListEntry;

/** 打印时进度条统计什么 */
public enum ProgressScope implements ConfigOptionListEntry<ProgressScope> {
    /** 整个投影：所有启用的投影放置里、已加载区块中的方块 */
    LOADED_AREA("progressScope.loaded"),
    /** 只算工作范围内的方块（以前的做法） */
    WORK_RANGE("progressScope.range");

    private final I18n i18n;

    ProgressScope(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
