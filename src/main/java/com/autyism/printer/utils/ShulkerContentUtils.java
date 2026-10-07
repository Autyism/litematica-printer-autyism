package com.autyism.printer.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
//? if >=1.20.5
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
//? if >=1.20.5
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求 8：潜影盒内容物比对工具。
 */
public final class ShulkerContentUtils {
    private ShulkerContentUtils() {
    }

    public static boolean isShulkerItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    /** 投影中该位置潜影盒应有的内容物（非空物品的副本）；没有方块实体时为空列表 */
    public static List<ItemStack> requiredContents(BlockGetter schematicWorld, BlockPos pos) {
        List<ItemStack> result = new ArrayList<>();
        BlockEntity be = schematicWorld.getBlockEntity(pos);
        if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (!s.isEmpty()) result.add(s.copy());
            }
        }
        return result;
    }

    /** 潜影盒物品里装的东西（非空物品的副本） */
    public static List<ItemStack> itemContents(ItemStack shulker) {
        List<ItemStack> result = new ArrayList<>();
        //? if <1.20.5 {
        /*// 1.20.5 以前潜影盒里的物品存在 NBT 的 BlockEntityTag.Items 里
        net.minecraft.nbt.CompoundTag tag = BlockItem.getBlockEntityData(shulker);
        if (tag != null && tag.contains("Items", 9)) {
            NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
            net.minecraft.world.ContainerHelper.loadAllItems(tag, items);
            for (ItemStack s : items) if (!s.isEmpty()) result.add(s.copy());
        }
        *///?} elif >=26.1 {
        /*ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
        if (contents != null) {
            contents.nonEmptyItemCopyStream().forEach(result::add);
        }
        *///?} else {
        ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
        if (contents != null) {
            for (ItemStack s : contents.nonEmptyItemsCopy()) result.add(s);
        }
        //?}
        return result;
    }

    /** 两组物品是否相同（不计顺序，比较物品、组件与数量） */
    public static boolean sameContents(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        boolean[] used = new boolean[b.size()];
        outer:
        for (ItemStack x : a) {
            for (int i = 0; i < b.size(); i++) {
                if (!used[i] && ItemStack.matches(x, b.get(i))) {
                    used[i] = true;
                    continue outer;
                }
            }
            return false;
        }
        return true;
    }

    /** 创造模式下用的“带内容物的潜影盒”物品 */
    public static ItemStack withContents(ItemStack shulker, List<ItemStack> contents) {
        ItemStack copy = shulker.copyWithCount(1);
        NonNullList<ItemStack> list = NonNullList.withSize(27, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(27, contents.size()); i++) list.set(i, contents.get(i).copy());
        //? if <1.20.5 {
        /*BlockItem.setBlockEntityData(copy, net.minecraft.world.level.block.entity.BlockEntityType.SHULKER_BOX,
                net.minecraft.world.ContainerHelper.saveAllItems(new net.minecraft.nbt.CompoundTag(), list));
        *///?} else
        copy.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(list));
        return copy;
    }
}
