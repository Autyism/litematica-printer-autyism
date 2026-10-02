package com.autyism.ale.compat;

import com.autyism.ale.utils.ModUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import org.jetbrains.annotations.Nullable;


import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Compatibility layer for TakeItOut (mod ID: takeitout).
 * <p>
 * All interactions use reflection so this class compiles and runs safely
 * when TakeItOut is not installed.
 */
public class TakeItOutCompat {
    private static final String CLIENT = "net.maxbel.takeitout.client";
    private static final String MAIN   = "net.maxbel.takeitout";

    @Nullable private static Method getShulkerWithStackMethod;
    @Nullable private static Method getSlotWithStackMethod;
    @Nullable private static Method getInventoryFromShulkerMethod;
    @Nullable private static Field  awaitingStackField;
    @Nullable private static Constructor<?> payloadConstructor;

    private static boolean initAttempted = false;

    private static void init() {
        if (initAttempted) return;
        initAttempted = true;
        try {
            Class<?> util = Class.forName(CLIENT + ".Util");
            getShulkerWithStackMethod = util.getMethod("getShulkerWithStack", Inventory.class, ItemStack.class);
            getSlotWithStackMethod     = util.getMethod("getSlotWithStack", Container.class, ItemStack.class);

            Class<?> inv = Class.forName(CLIENT + ".ItemStackInventory");
            getInventoryFromShulkerMethod = inv.getMethod("getInventoryFromShulker", ItemStack.class);

            Class<?> client = Class.forName(CLIENT + ".TakeitoutClient");
            awaitingStackField = client.getField("awaitingStack");

            Class<?> payload = Class.forName(MAIN + ".Takeitout$GetShulkerStackPayload");
            payloadConstructor = payload.getConstructor(int.class, int.class);
        } catch (Exception ignored) {
            clear();
        }
    }

    private static void clear() {
        getShulkerWithStackMethod = null;
        getSlotWithStackMethod = null;
        getInventoryFromShulkerMethod = null;
        awaitingStackField = null;
        payloadConstructor = null;
    }

    public static boolean isAwaitingItem() {
        if (!ModUtils.isTakeItOutLoaded()) return false;
        init();
        if (awaitingStackField == null) return false;
        try {
            ItemStack s = (ItemStack) awaitingStackField.get(null);
            return s != null && !s.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean tryExtract(LocalPlayer player, Item... items) {
        return false;
    }
}