package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.IterationOrderType;
import com.autyism.printer.enums.MiningFilterType;
import com.autyism.printer.enums.RadiusShapeType;
import com.autyism.printer.enums.SelectionType;
import com.autyism.printer.handler.ModuleManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 超大的岩浆湖（比工作范围大得多），设置和真实实例一样（每 tick 不限个数、10 毫秒、球形、xzy、流动的也填、超大选区）。
 * 生存，材料在潜影盒里。both：挖掘和填流体一起开；fluidonly：只开填流体。范围里面一圈的岩浆要填完、不再回来。
 */
@SuppressWarnings("UnstableApiUsage")
public final class BigFluidGameTest implements FabricClientGameTest {
    private static final List<String> ORES = List.of("minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gold_ore", "minecraft:diamond_ore",
            "minecraft:nether_gold_ore", "minecraft:nether_quartz_ore", "minecraft:glowstone", "minecraft:ancient_debris");

    @Override
    public void runTest(ClientGameTestContext context) {
        // 都是生存：材料装在潜影盒里（只有两组散的），和真实玩的时候一样；范围 30 = 真实服务器（地毯 antiCheatDisabled）
        if (GTFilter.enabled("bigfluid")) {
            run(context, "both", new BlockPos(3000, 64, 3000), 100, 4, 30, true, 18);
        }
        if (GTFilter.enabled("bigfluidonly")) {
            run(context, "fluidonly", new BlockPos(5000, 64, 5000), 100, 8, 30, false, 18);
        }
    }

    /** 湖：side x side、深 depth 的岩浆源，四周和下面是石头；玩家飞在湖中心、贴着岩浆面上方 */
    private static void run(ClientGameTestContext context, String tag, BlockPos corner, int side, int depth, int range, boolean withMine, double inner) {
        int top = corner.getY() + depth + 1; // 石头底 1 层，岩浆 depth 层，上面一层石头边
        BlockPos stoneMin = corner.offset(-2, 0, -2), stoneMax = corner.offset(side + 1, depth, side + 1);
        BlockPos lakeMin = corner.offset(0, 1, 0), lakeMax = corner.offset(side - 1, depth, side - 1);
        Vec3 center = new Vec3(corner.getX() + side / 2.0, top, corner.getZ() + side / 2.0);
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            GT.removeAllPlacements(context);
            sp.getServer().runCommand("gamemode creative @a"); // 搭湖的时候先飞着，后面换生存
            sp.getServer().runCommand("tp @a " + center.x + " " + center.y + " " + center.z);
            context.waitFor(c -> c.player != null && Math.abs(c.player.getX() - center.x) < 0.01, 400);
            context.runOnClient(c -> {
                c.player.getAbilities().flying = true;
                c.player.onUpdateAbilities();
            });
            context.waitTicks(60);
            sp.getServer().runOnServer(s -> {
                ServerLevel level = s.overworld();
                for (BlockPos p : BlockPos.betweenClosed(stoneMin.offset(-4, 0, -4), stoneMax.offset(4, 30, 4))) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
                for (BlockPos p : BlockPos.betweenClosed(stoneMin, stoneMax)) level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
                for (BlockPos p : BlockPos.betweenClosed(lakeMin, lakeMax)) level.setBlock(p, Blocks.LAVA.defaultBlockState(), 2);
                var player = s.getPlayerList().getPlayers().getFirst();
                player.getInventory().clearContent();
                int slot = 0;
                if (withMine) {
                    ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
                    var efficiency = s.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                            .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
                    pick.enchant(efficiency, 5);
                    player.getInventory().setItem(slot++, pick);
                }
                player.getInventory().setItem(slot++, new ItemStack(Items.NETHERRACK, 64));
                player.getInventory().setItem(slot++, new ItemStack(Items.NETHERRACK, 64));
                while (slot < 36) player.getInventory().setItem(slot++, netherrackBox());
                player.inventoryMenu.sendAllDataToRemote();
            });
            sp.getServer().runCommand("gamemode survival @a");
            sp.getServer().runCommand("effect give @a minecraft:fire_resistance infinite 0 true");
            sp.getServer().runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            });
            //? if >=1.20.5
            sp.getServer().runCommand("attribute @p minecraft:block_interaction_range base set " + range);
            context.waitTicks(60);
            int lavaStart = count(sp, center, 1e9);
            context.runOnClient(c -> {
                GT.disableAll();
                var selectionManager = DataManager.getSelectionManager();
                if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) selectionManager.switchSelectionMode();
                AreaSelection selection = DataManager.getSimpleArea();
                Box box = selection.getSubRegionBox(selection.getName());
                if (box == null) box = selection.getSelectedSubRegionBox();
                // 和真实实例一样的超大选区（272 x 272）
                box.setPos1(new BlockPos((int) center.x - 136, corner.getY(), (int) center.z - 136));
                box.setPos2(new BlockPos((int) center.x + 135, corner.getY() + 63, (int) center.z + 135));
                Configs.Core.WORK_RANGE.setDoubleValue(range);
                Configs.Core.AUTO_RAISE_REACH.setBooleanValue(true);
                Configs.Core.ITERATION_TIME_LIMIT.setIntegerValue(10);
                Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
                Configs.Core.ITERATION_ORDER.setOptionListValue(IterationOrderType.XZY);
                Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(0);
                Configs.Break.BREAK_INTERVAL.setIntegerValue(0);
                Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(0);
                Configs.Fluid.FLUID_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
                Configs.Fluid.SOURCES_ONLY.setBooleanValue(false);
                Configs.Fluid.FLUID_REPLACE_BLOCK_LIST.setStrings(List.of("minecraft:netherrack"));
                Configs.Fluid.FLUID_LIST.setStrings(List.of("minecraft:water", "minecraft:lava"));
                Configs.Fluid.ENABLED.setBooleanValue(true);
                ModuleManager.FLUID_REMOVAL.resetScanState();
                if (withMine) {
                    Configs.Mine.MINE_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
                    Configs.Mine.EXCAVATE_LIMITER.setOptionListValue(MiningFilterType.CUSTOM);
                    Configs.Mine.EXCAVATE_LIMIT.setOptionListValue(UsageRestriction.ListType.BLACKLIST);
                    Configs.Mine.EXCAVATE_BLACKLIST.setStrings(ORES);
                    Configs.Mine.ENABLED.setBooleanValue(true);
                    ModuleManager.MINE.resetScanState();
                }
                Configs.Print.USE_QUICK_SHULKER.setBooleanValue(true);
                Configs.Print.SHULKER_SOURCE.setOptionListValue(com.autyism.printer.enums.ShulkerSource.MOD);
                Configs.Core.WORK_SWITCH.setBooleanValue(true);
            });
            // 离玩家眼睛 inner 格以内（工作范围里面一圈）的岩浆：最后 400 tick 里每 20 tick 看一次，都要是 0
            int total = Integer.getInteger("ale.ticks", withMine ? 3000 : 3600);
            int worstEnd = 0, firstClear = -1;
            for (int t = 20; t <= total; t += 20) {
                context.waitTicks(20);
                int near = count(sp, center, inner);
                if (near == 0 && firstClear < 0) firstClear = t;
                if (t > total - 400) worstEnd = Math.max(worstEnd, near);
                if (t % 200 == 0) {
                    GT.log("[bigfluid-" + tag + "] t=" + t + " lava within " + (int) inner + "=" + near + " all=" + count(sp, center, 1e9)
                            + " netherrack=" + countNetherrack(sp, stoneMin, stoneMax) + " minedStone=" + minedStone(sp, stoneMin, stoneMax, lakeMin, lakeMax));
                }
            }
            context.runOnClient(c -> GT.disableAll());
            int boxesLeft = sp.getServer().computeOnServer(s -> {
                var inv = s.getPlayerList().getPlayers().getFirst().getInventory();
                int n = 0;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    var c = inv.getItem(i).get(net.minecraft.core.component.DataComponents.CONTAINER);
                    if (c != null) for (ItemStack st : c.nonEmptyItemsCopy()) n += st.getCount();
                    else if (inv.getItem(i).is(Items.NETHERRACK)) n += inv.getItem(i).getCount();
                }
                return n;
            });
            GT.log("[bigfluid-" + tag + "] netherrack left in inventory+boxes=" + boxesLeft);
            GT.log("[bigfluid-" + tag + "] RESULT start=" + lavaStart + " firstClear=" + firstClear + " worstNearInLast400=" + worstEnd
                    + " minedStone=" + minedStone(sp, stoneMin, stoneMax, lakeMin, lakeMax));
            // 不抛错：几种情况一次跑完，各自记结果
            boolean ok = worstEnd == 0 && (!withMine || minedStone(sp, stoneMin, stoneMax, lakeMin, lakeMax) > 0);
            GT.log("[bigfluid-" + tag + "] " + (ok ? "PASS" : "FAIL: lava inside the range (or no mining) up to " + worstEnd + " in the last 400 ticks"));
        } finally {
            context.runOnClient(c -> GT.disableAll());
        }
    }

    private static ItemStack netherrackBox() {
        ItemStack box = new ItemStack(Items.WHITE_SHULKER_BOX);
        net.minecraft.core.NonNullList<ItemStack> list = net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
        for (int i = 0; i < 27; i++) list.set(i, new ItemStack(Items.NETHERRACK, 64));
        box.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(list));
        return box;
    }

    /** 离 center 不超过 r 格的岩浆（源头和流动的） */
    private static int count(TestSingleplayerContext sp, Vec3 center, double r) {
        return sp.getServer().computeOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            Vec3 eye = player.getEyePosition();
            ServerLevel level = s.overworld();
            int n = 0;
            int reach = (int) Math.min(r, 80);
            BlockPos c = BlockPos.containing(center);
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-reach, -reach, -reach), c.offset(reach, 0, reach))) {
                if (level.getFluidState(p).isEmpty()) continue;
                if (r < 1e8 && eye.distanceToSqr(Vec3.atCenterOf(p)) > r * r) continue;
                n++;
            }
            return n;
        });
    }

    private static int countNetherrack(TestSingleplayerContext sp, BlockPos min, BlockPos max) {
        return sp.getServer().computeOnServer(s -> {
            int n = 0;
            for (BlockPos p : BlockPos.betweenClosed(min, max)) if (s.overworld().getBlockState(p).is(Blocks.NETHERRACK)) n++;
            return n;
        });
    }

    /** 石头部分（湖以外）被挖成空气的格数 */
    private static int minedStone(TestSingleplayerContext sp, BlockPos min, BlockPos max, BlockPos lakeMin, BlockPos lakeMax) {
        return sp.getServer().computeOnServer(s -> {
            int n = 0;
            for (BlockPos p : BlockPos.betweenClosed(min, max)) {
                boolean lake = p.getX() >= lakeMin.getX() && p.getX() <= lakeMax.getX() && p.getY() >= lakeMin.getY() && p.getY() <= lakeMax.getY()
                        && p.getZ() >= lakeMin.getZ() && p.getZ() <= lakeMax.getZ();
                if (!lake && s.overworld().getBlockState(p).isAir()) n++;
            }
            return n;
        });
    }
}
