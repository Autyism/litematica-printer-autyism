package com.autyism.printer.utils;

import com.autyism.printer.config.Configs;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 秒破判定：
 * <ul>
 *     <li>一 tick 的挖掘进度 >= 秒破阈值 就算能秒破。</li>
 *     <li>秒破阈值 = 100%；开启“同 tick 即时破坏”时为“挖掘进度阈值”配置（70%~100%，原版服务端在 70% 就认可破坏，
 *     与 SpeedMine 一类“挖到一定进度就直接破坏”的思路一致）。</li>
 *     <li>进度按背包里最合适的工具计算（会自动换上），包含效率附魔、急迫、挖掘疲劳、是否在地面/水下。</li>
 * </ul>
 */
public final class MiningUtils {
    private MiningUtils() {
    }

    /** 能被判定为“秒破”的单 tick 进度下限 */
    public static float instantThreshold() {
        if (!Configs.Break.BREAK_INSTANT_MINE.getBooleanValue()) return 1.0F;
        int value = Math.max(70, Math.min(100, Configs.Break.BREAK_PROGRESS_THRESHOLD.getIntegerValue()));
        return value / 100F;
    }

    /** 用背包里最快的工具挖这个方块，一 tick 的进度 */
    public static float bestProgress(LocalPlayer player, BlockState state) {
        Inventory inv = player.getInventory();
        float best = PlayerUtils.getDestroyProgressWithStack(player, state, player.getMainHandItem());
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            if (Configs.Break.TOOL_DURABILITY_PROTECT.getBooleanValue() && ToolSwitchUtils.isNearlyBroken(stack)) continue;
            best = Math.max(best, PlayerUtils.getDestroyProgressWithStack(player, state, stack));
        }
        return best;
    }

    public static boolean canInstantBreak(LocalPlayer player, BlockState state) {
        return bestProgress(player, state) >= instantThreshold();
    }

    /** 是否具备秒破配置：背包里有效率 V（及以上）的工具，并且有急迫 II（及以上） */
    public static boolean hasInstantMiningSetup(LocalPlayer player) {
        //? if <1.21.5 {
        /*MobEffectInstance haste = player.getEffect(MobEffects.DIG_SPEED);
        *///?} else
        MobEffectInstance haste = player.getEffect(MobEffects.HASTE);
        if (haste == null || haste.getAmplifier() < 1) return false;
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            //? if <1.21 {
            /*if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, stack) >= 5) {
                return true;
            }
            *///?} else {
            for (Holder<Enchantment> ench : stack.getEnchantments().keySet()) {
                if (ench.is(Enchantments.EFFICIENCY) && EnchantmentHelper.getItemEnchantmentLevel(ench, stack) >= 5) {
                    return true;
                }
            }
            //?}
        }
        return false;
    }
}
