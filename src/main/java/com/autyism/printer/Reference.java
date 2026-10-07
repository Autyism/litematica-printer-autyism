package com.autyism.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.ComposterBlock;
//? if >=26.3 {
/*import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
*///?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * 模组核心常量引用类
 * 集中管理模组的全局固定值，避免硬编码和拼写错误
 */
public class Reference {
    public static final Minecraft MINECRAFT = Minecraft.getInstance();
    public static final String MOD_ID = "litematica-printer-autyism";
    public static final String MOD_NAME = "Litematica Printer Autyism Edition";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    //? if >=26.3 {
    /*// 26.3 起能否堆肥是物品组件（数据包决定），组件要连上世界后才有：用到时再取，每个连接算一次
    private static Object compostableFor;
    private static Item[] compostableItems = new Item[0];

    public static Item[] compostableItems() {
        Object connection = MINECRAFT.getConnection();
        if (connection != compostableFor) {
            compostableItems = BuiltInRegistries.ITEM.stream()
                    .filter(item -> item.components().has(DataComponents.COMPOSTABLE))
                    .toArray(Item[]::new);
            compostableFor = connection;
        }
        return compostableItems;
    }
    *///?} else
    public static final Item[] COMPOSTABLE_ITEMS = Arrays.stream(ComposterBlock.COMPOSTABLES.keySet().toArray(ItemLike[]::new)).map(ItemLike::asItem).toArray(Item[]::new);
    public static final Item[] HOE_ITEMS = {Items.DIAMOND_HOE, Items.IRON_HOE, Items.GOLDEN_HOE, Items.NETHERITE_HOE, Items.STONE_HOE, Items.WOODEN_HOE};
    public static final Item[] SHOVEL_ITEMS = {Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL, Items.GOLDEN_SHOVEL, Items.NETHERITE_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL};
    public static final Item[] AXE_ITEMS = {Items.DIAMOND_AXE, Items.IRON_AXE, Items.GOLDEN_AXE, Items.NETHERITE_AXE, Items.STONE_AXE, Items.WOODEN_AXE};
}
