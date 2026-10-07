plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.11"

stonecutter parameters {
    replacements {
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }
        // 26.1 renames (whole class/method names only)
        regex(current.parsed >= "26.1") {
            replace("\\bGuiGraphics\\b", "GuiGraphicsExtractor", "\\bGuiGraphicsExtractor\\b", "GuiGraphics")
        }
        regex(current.parsed >= "26.1") {
            replace("\\bClickType\\b", "ContainerInput", "\\bContainerInput\\b", "ClickType")
        }
        regex(current.parsed >= "26.1") {
            replace("\\bhandleInventoryMouseClick\\b", "handleContainerInput", "\\bhandleContainerInput\\b", "handleInventoryMouseClick")
        }
        regex(current.parsed >= "26.1") {
            replace("\\bFarmBlock\\b", "FarmlandBlock", "\\bFarmlandBlock\\b", "FarmBlock")
        }
        regex(current.parsed >= "26.1") {
            replace("\\bWaterlilyBlock\\b", "LilyPadBlock", "\\bLilyPadBlock\\b", "WaterlilyBlock")
        }
        // 26.2: dyed blocks and items are grouped by colour
        regex(current.parsed >= "26.2") {
            replace("\\bItems\\.WHITE_SHULKER_BOX\\b", "Items.DYED_SHULKER_BOX.white()", "\\bItems\\.DYED_SHULKER_BOX\\.white\\(\\)", "Items.WHITE_SHULKER_BOX")
        }
        regex(current.parsed >= "26.2") {
            replace("\\bBlocks\\.WHITE_SHULKER_BOX\\b", "Blocks.DYED_SHULKER_BOX.white()", "\\bBlocks\\.DYED_SHULKER_BOX\\.white\\(\\)", "Blocks.WHITE_SHULKER_BOX")
        }
    }
}
