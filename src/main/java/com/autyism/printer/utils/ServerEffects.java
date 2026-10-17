package com.autyism.printer.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

/**
 * 服务器给这个玩家的状态效果（以服务器发来的包为准）。
 * 客户端模组可以自己给玩家加效果，比如 Meteor 的 SpeedMine「急迫」模式会显示急迫 II，但服务器并不知道；
 * 按这种效果算出“能秒破”去挖，服务器会把方块放回来。所以挖掘速度只算服务器给的效果。
 */
public final class ServerEffects {
    private static final Map<MobEffect, Integer> AMPLIFIERS = new ConcurrentHashMap<>();

    private ServerEffects() {
    }

    public static void set(MobEffect effect, int amplifier) {
        AMPLIFIERS.put(effect, amplifier);
    }

    public static void remove(MobEffect effect) {
        AMPLIFIERS.remove(effect);
    }

    /** 新的玩家（进服、重生、换维度）：服务器会重新发一遍 */
    public static void clear() {
        AMPLIFIERS.clear();
    }

    /** 服务器给的等级（0 = I 级），没有就是 -1 */
    public static int amplifier(MobEffect effect) {
        Integer amplifier = AMPLIFIERS.get(effect);
        return amplifier == null ? -1 : amplifier;
    }

    /** 同 MobEffectUtil.getDigSpeedAmplification（急迫或潮涌能量里较高的），按服务器给的效果；都没有是 -1 */
    public static int digSpeed() {
        return Math.max(amplifier(haste()), amplifier(conduitPower()));
    }

    /** 服务器给的挖掘疲劳等级，没有是 -1 */
    public static int miningFatigue() {
        return amplifier(fatigue());
    }

    private static MobEffect haste() {
        //? if <1.21.5 {
        /*return MobEffects.DIG_SPEED;
        *///?} else
        return MobEffects.HASTE.value();
    }

    private static MobEffect fatigue() {
        //? if <1.21.5 {
        /*return MobEffects.DIG_SLOWDOWN;
        *///?} else
        return MobEffects.MINING_FATIGUE.value();
    }

    private static MobEffect conduitPower() {
        //? if <1.21.5 {
        /*return MobEffects.CONDUIT_POWER;
        *///?} else
        return MobEffects.CONDUIT_POWER.value();
    }
}
