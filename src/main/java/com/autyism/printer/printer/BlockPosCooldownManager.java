package com.autyism.printer.printer;

import com.autyism.printer.utils.ConfigUtils;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * 格子冷却：打印机对一格动过手以后，这几 tick 里不再碰它。
 * 按 {维度, 类型, 来源} 分开记，每一组里坐标 → 冷却到第几次 tick() 为止。以前每查一次都要新建一个键对象、
 * 算一遍它的哈希（维度名、类型名都要参与），一 tick 放上百块时这一项占了扫描时间的几个百分点
 */
public class BlockPosCooldownManager {
    public static final BlockPosCooldownManager INSTANCE = new BlockPosCooldownManager();

    /**
     * 冷却来源标记。
     * SELF — 打印机自身操作产生的冷却（用于 BlockUpdate 回显过滤）；
     * EXTERNAL — 外部来源（预留）。
     */
    public enum CooldownSource {
        SELF,
        EXTERNAL
    }

    private record Key(Identifier dimension, String type, CooldownSource source) {
    }

    private final Map<Key, Long2IntOpenHashMap> cooldowns = new HashMap<>();
    /** tick() 调用了多少次 */
    private int now;

    /** 每 tick 调用一次：到期的冷却去掉；打印机关着时全部清空 */
    public void tick() {
        if (!ConfigUtils.isPrinterEnable()) {
            if (!cooldowns.isEmpty()) {
                cooldowns.clear();
            }
            return;
        }
        now++;
        if (cooldowns.isEmpty()) {
            return;
        }
        cooldowns.values().removeIf(map -> {
            map.long2IntEntrySet().removeIf(e -> e.getIntValue() <= now);
            return map.isEmpty();
        });
    }

    private static Identifier dimension(ClientLevel level) {
        //? if <1.21.11 {
        /*return level.dimension().location();
        *///?} else
        return level.dimension().identifier();
    }

    /**
     * 设置冷却，来源默认为 SELF
     */
    public void setCooldown(ClientLevel level, String type, BlockPos pos, int cooldownTicks) {
        setCooldown(level, type, pos, cooldownTicks, CooldownSource.SELF);
    }

    /**
     * 设置冷却，显式指定来源
     */
    public void setCooldown(ClientLevel level, String type, BlockPos pos, int cooldownTicks, CooldownSource source) {
        if (cooldownTicks <= 0) return;
        cooldowns.computeIfAbsent(new Key(dimension(level), type, source), k -> new Long2IntOpenHashMap())
                .put(pos.asLong(), now + cooldownTicks);
    }

    /**
     * 判断指定方块是否处于冷却中（按 type 精确匹配）
     */
    public boolean isOnCooldown(ClientLevel level, String type, BlockPos pos) {
        if (cooldowns.isEmpty()) return false;
        Long2IntOpenHashMap map = cooldowns.get(new Key(dimension(level), type, CooldownSource.SELF));
        return map != null && map.get(pos.asLong()) > now;
    }

    /**
     * 判断指定方块是否处于冷却中（按 source 模糊匹配，忽略 type）
     * 用于 BlockUpdate 回显过滤：查询该位置是否存在任何 SELF 来源的冷却
     */
    public boolean isOnCooldown(ClientLevel level, CooldownSource source, BlockPos pos) {
        if (cooldowns.isEmpty()) return false;
        Identifier dimension = dimension(level);
        long key = pos.asLong();
        for (Map.Entry<Key, Long2IntOpenHashMap> e : cooldowns.entrySet()) {
            if (e.getKey().source() == source && e.getKey().dimension().equals(dimension) && e.getValue().get(key) > now) {
                return true;
            }
        }
        return false;
    }

    /**
     * 手动移除指定方块的冷却（强制取消冷却）
     */
    public void removeCooldown(ClientLevel level, String type, BlockPos pos) {
        removeCooldown(level, type, pos, CooldownSource.SELF);
    }

    public void removeCooldown(ClientLevel level, String type, BlockPos pos, CooldownSource source) {
        Long2IntOpenHashMap map = cooldowns.get(new Key(dimension(level), type, source));
        if (map != null) map.remove(pos.asLong());
    }

    /**
     * 获取指定方块的剩余冷却刻数
     *
     * @return 剩余冷却刻数，未冷却则返回0
     */
    public int getRemainingCooldown(ClientLevel level, String type, BlockPos pos) {
        return getRemainingCooldown(level, type, pos, CooldownSource.SELF);
    }

    public int getRemainingCooldown(ClientLevel level, String type, BlockPos pos, CooldownSource source) {
        Long2IntOpenHashMap map = cooldowns.get(new Key(dimension(level), type, source));
        return map == null ? 0 : Math.max(0, map.get(pos.asLong()) - now);
    }

    /**
     * 清空指定维度的所有冷却数据
     */
    public void clearDimensionCooldowns(ClientLevel level) {
        Identifier dimension = dimension(level);
        cooldowns.keySet().removeIf(key -> key.dimension().equals(dimension));
    }

    /**
     * 清空指定维度+指定类型的所有冷却数据（如清空某维度所有打印冷却）
     */
    public void clearTypeCooldowns(ClientLevel level, String type) {
        Identifier dimension = dimension(level);
        cooldowns.keySet().removeIf(key -> key.dimension().equals(dimension) && key.type().equals(type));
    }

    /**
     * 清空所有冷却数据（模组重载/退出游戏/全局重置时调用）
     */
    public void clearAllCooldowns() {
        cooldowns.clear();
    }

    /** 还有格子在冷却 */
    public boolean hasAny() {
        for (Long2IntOpenHashMap map : cooldowns.values()) {
            for (Long2IntMap.Entry e : map.long2IntEntrySet()) {
                if (e.getIntValue() > now) return true;
            }
        }
        return false;
    }
}
