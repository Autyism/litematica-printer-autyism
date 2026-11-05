package com.autyism.printer.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//? if >=1.21 {
import com.autyism.printer.config.Configs;
import com.autyism.printer.printer.CrafterSlots;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
//?}

/**
 * Crafter slots: a crafter in the schematic with slots 0, 4 and 8 switched off. The printer places it, opens it
 * without showing the screen, switches those slots off and closes it; afterwards nothing is open.
 */
@SuppressWarnings("UnstableApiUsage")
public final class CrafterGameTest implements FabricClientGameTest {
    //? if >=1.21 {
    private static final BlockPos POS = new BlockPos(950, 64, 0);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("crafter")) return;
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.randomTicksOff(sp);
            GT.clearArena(sp, 940, -10, 960, 10, 75);
            sp.getServer().runCommand("tp @a 952.5 64 2.5");
            context.waitTicks(20);
            sp.getServer().runCommand("setblock 950 64 0 minecraft:crafter[orientation=up_north]{disabled_slots:[I;0,4,8]}");
            context.waitFor(client -> client.level.getBlockState(POS).is(Blocks.CRAFTER), 200);
            GT.captureAndPlace(context, sp, POS, POS, POS, "ale_test_crafter");
            sp.getServer().runOnServer(server -> {
                server.overworld().setBlockAndUpdate(POS, Blocks.AIR.defaultBlockState());
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                player.getInventory().setItem(0, new ItemStack(Items.CRAFTER, 4));
                player.inventoryMenu.sendAllDataToRemote();
            });
            GT.waitSchematicBlock(context, POS, Blocks.CRAFTER);
            int before = context.computeOnClient(c -> CrafterSlots.adjusted);
            context.runOnClient(c -> {
                if (!Configs.Print.CRAFTER_SLOTS.getBooleanValue()) throw new AssertionError("[crafter] Crafter Slots should be on by default");
                GT.enablePrint();
            });
            boolean[] screenSeen = {false};
            int ticks = GT.waitServer(context, () -> {
                if (context.computeOnClient(c -> screen(c) != null)) screenSeen[0] = true;
                return sp.getServer().computeOnServer(server -> server.overworld().getBlockEntity(POS) instanceof CrafterBlockEntity c
                        && c.isSlotDisabled(0) && c.isSlotDisabled(4) && c.isSlotDisabled(8) && !c.isSlotDisabled(1));
            }, 400, "[crafter] the crafter's slots 0, 4, 8 were not switched off");
            context.waitTicks(10);
            GT.log("[crafter] slots set after " + ticks + " ticks; adjusted " + (context.computeOnClient(c -> CrafterSlots.adjusted) - before)
                    + ", a screen was shown: " + screenSeen[0]);
            if (screenSeen[0]) throw new AssertionError("[crafter] the crafter's screen was shown");
            boolean closed = context.computeOnClient(c -> c.player.containerMenu == c.player.inventoryMenu && screen(c) == null);
            if (!closed) throw new AssertionError("[crafter] the crafter was left open");
            String others = sp.getServer().computeOnServer(server -> {
                CrafterBlockEntity c = (CrafterBlockEntity) server.overworld().getBlockEntity(POS);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 9; i++) sb.append(c.isSlotDisabled(i) ? 'x' : '.');
                return sb.toString();
            });
            if (!others.equals("x...x...x")) throw new AssertionError("[crafter] slots should be x...x...x, are " + others);
            GT.log("[crafter] PASS slots " + others);
            verifierButton(context);
        } finally {
            context.runOnClient(c -> GT.disableAll());
            GT.removeAllPlacements(context);
        }
    }
    private static net.minecraft.client.gui.screens.Screen screen(net.minecraft.client.Minecraft c) {
        //? if >=26.2 {
        /*return c.gui.screen();
        *///?} else
        return c.screen;
    }

    /** The Schematic Verifier of this placement has "Print Report"; it reports the placement as all right now. */
    private static void verifierButton(ClientGameTestContext context) {
        context.runOnClient(c -> {
            var placement = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
            fi.dy.masa.litematica.gui.GuiSchematicVerifier gui = new fi.dy.masa.litematica.gui.GuiSchematicVerifier(placement);
            fi.dy.masa.malilib.gui.GuiBase.openGui(gui);
        });
        context.waitTicks(10);
        fi.dy.masa.malilib.gui.button.ButtonBase button = context.computeOnClient(c -> {
            var b = com.autyism.printer.gui.VerifierReportButton.last;
            return b != null && com.autyism.printer.gui.MainMenuButtonOverlap.buttons((fi.dy.masa.malilib.gui.GuiBase) screen(c)).contains(b) ? b : null;
        });
        if (button == null) throw new AssertionError("[crafter] no Print Report button in the verifier");
        GT.screenshot(context, "crafter_verifier");
        // what the button does (malilib's click signature differs between versions)
        context.runOnClient(c -> com.autyism.printer.printer.PrintReport.INSTANCE.requestFor(
                fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement(), false));
        context.waitFor(c -> !com.autyism.printer.printer.PrintReport.INSTANCE.isScanning() && com.autyism.printer.printer.PrintReport.INSTANCE.getResult() != null, 200);
        int unfinished = context.computeOnClient(c -> com.autyism.printer.printer.PrintReport.INSTANCE.getResult().unfinished());
        GT.log("[crafter] verifier report: " + unfinished + " unfinished");
        if (unfinished != 0) throw new AssertionError("[crafter] the verifier report should find nothing left, found " + unfinished);
        //? if >=26.2 {
        /*context.runOnClient(c -> c.gui.setScreen(null));
        *///?} else
        context.runOnClient(c -> c.setScreen(null));
    }
    //?} else {
    /*@Override
    public void runTest(ClientGameTestContext context) {
    }
    *///?}
}
