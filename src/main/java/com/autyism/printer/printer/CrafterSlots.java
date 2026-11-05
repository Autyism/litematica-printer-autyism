package com.autyism.printer.printer;

//? if >=1.21 {
import com.autyism.printer.config.Configs;
import com.autyism.printer.utils.ConfigUtils;
import com.autyism.printer.utils.LitematicaUtils;
import com.autyism.printer.utils.ModUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
//?}

/**
 * Crafter slots. Which of a crafter's nine slots are switched off is not part of the block state, it lives in the
 * crafter and can only be changed in its screen. When a printed crafter (or one already standing there) should have
 * switched-off slots, the printer opens it (the screen is not shown), switches the slots the schematic wants, waits
 * for the server to confirm and closes it again. A slot with an item in it cannot be switched off; such a crafter is
 * left as it is (the print report lists it as a wrong state).
 */
public final class CrafterSlots {
    private CrafterSlots() {
    }

    //? if >=1.21 {
    private static final int SCAN_EVERY = 20;
    private static final int OPEN_TIMEOUT = 20;
    private static final int CONFIRM_TIMEOUT = 20;

    /** Crafters done (or given up on): position -> wanted mask. Forgotten when the block there changes. */
    private static final Long2IntOpenHashMap done = new Long2IntOpenHashMap();
    private static int tick;

    @Nullable
    private static BlockPos working;
    private static int wanted;
    private static int waitTicks;
    private static boolean sent;
    /** For the tests: how many crafters were set. */
    public static int adjusted;

    /** A block of the client world changed (the print module forwards it). */
    public static void blockChanged(BlockPos pos) {
        done.remove(pos.asLong());
    }

    /** Disabled slots the schematic wants at pos (bit i = slot i off), or -1 when there is no crafter there. */
    static int wantedMask(WorldSchematic schematic, BlockPos pos) {
        if (!(schematic.getBlockState(pos).getBlock() instanceof CrafterBlock)) return -1;
        BlockEntity be = schematic.getBlockEntity(pos);
        if (!(be instanceof CrafterBlockEntity crafter)) return -1;
        int mask = 0;
        for (int i = 0; i < 9; i++) if (crafter.isSlotDisabled(i)) mask |= 1 << i;
        return mask;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            working = null;
            done.clear();
            return;
        }
        if (working != null) {
            work(mc, player);
            return;
        }
        if (!Configs.Print.CRAFTER_SLOTS.getBooleanValue() || !ConfigUtils.isPrinterEnable() || !Configs.Print.ENABLED.getBooleanValue()) return;
        if (++tick % SCAN_EVERY != 0 || player.containerMenu != player.inventoryMenu || player.isSecondaryUseActive()) return;
        //? if >=26.2 {
        /*if (mc.gui.screen() != null) return;
        *///?} else
        if (mc.screen != null) return;
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return;
        double reach = player.blockInteractionRange() - 0.5;
        int r = (int) Math.ceil(reach);
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        BlockPos best = null;
        int bestMask = 0;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(eye.offset(-r, -r, -r), eye.offset(r, r, r))) {
            if (!LitematicaUtils.isPositionWithinRange(pos)) continue;
            if (!(level.getBlockState(pos).getBlock() instanceof CrafterBlock)) continue;
            int mask = wantedMask(schematic, pos);
            if (mask <= 0) continue; // nothing to switch off (a crafter is placed with all slots on)
            long key = pos.asLong();
            if (done.containsKey(key) && done.get(key) == mask) continue;
            double d = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
            if (d > reach * reach || d >= bestDist) continue;
            best = pos.immutable();
            bestMask = mask;
            bestDist = d;
        }
        if (best == null) return;
        // open it; the screen is not shown (see MixinMinecraft)
        working = best;
        wanted = bestMask;
        waitTicks = 0;
        sent = false;
        ModUtils.closeScreen++;
        ContainerGuard.beginPrinterInteraction();
        try {
            Vec3 hit = Vec3.atCenterOf(best);
            Vec3 to = player.getEyePosition().subtract(hit);
            double ax = Math.abs(to.x), ay = Math.abs(to.y), az = Math.abs(to.z);
            Direction face = ay >= ax && ay >= az ? (to.y > 0 ? Direction.UP : Direction.DOWN)
                    : ax >= az ? (to.x > 0 ? Direction.EAST : Direction.WEST) : (to.z > 0 ? Direction.SOUTH : Direction.NORTH);
            Vec3 at = hit.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(at, face, best, false));
        } finally {
            ContainerGuard.endPrinterInteraction();
        }
    }

    private static void work(Minecraft mc, LocalPlayer player) {
        waitTicks++;
        if (!(player.containerMenu instanceof CrafterMenu menu)) {
            if (waitTicks > OPEN_TIMEOUT) {
                // did not open (blocked, too far): leave it until the block changes
                done.put(working.asLong(), wanted);
                if (ModUtils.closeScreen > 0) ModUtils.closeScreen--;
                working = null;
            }
            return;
        }
        int now = 0;
        for (int i = 0; i < 9; i++) if (menu.isSlotDisabled(i)) now |= 1 << i;
        if (now == wanted) {
            if (sent) adjusted++;
            finish(player);
            return;
        }
        if (!sent && waitTicks >= 2) {
            // the slot states arrive with the screen's data; then switch every slot that differs (only empty slots can be switched off)
            for (int i = 0; i < 9; i++) {
                boolean want = (wanted >> i & 1) != 0;
                if (want == menu.isSlotDisabled(i)) continue;
                if (want && !menu.getSlot(i).getItem().isEmpty()) continue;
                menu.setSlotState(i, !want);
                player.connection.send(new ServerboundContainerSlotStateChangedPacket(i, menu.containerId, !want));
            }
            sent = true;
            waitTicks = 0;
            return;
        }
        if (sent && waitTicks > CONFIRM_TIMEOUT) finish(player);
    }

    private static void finish(LocalPlayer player) {
        done.put(working.asLong(), wanted);
        working = null;
        player.closeContainer();
    }
    //?} else {
    /*public static void tick() {
    }

    public static void blockChanged(net.minecraft.core.BlockPos pos) {
    }
    *///?}
}
