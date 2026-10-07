package com.autyism.printer.gametest;

import com.autyism.printer.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * 需求 13 实测：用户真实的“三头巨龙”投影（697x380x236，约 28.8 万方块，99% 是空气）。
 * 创造模式，玩家站着不动，工作半径 64，分层模式：必须从最底层开始逐层往上打，且不能停滞。
 */
@SuppressWarnings("UnstableApiUsage")
public final class DragonGameTest implements FabricClientGameTest {
    private static final Path SOURCE = Path.of("D:/Games/PCL2/.minecraft/schematics/建筑/3head Big Dragon.litematic");
    private static final BlockPos ORIGIN = new BlockPos(300, -60, 300);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GTFilter.enabled("dragon")) return;
        if (!Files.exists(SOURCE)) {
            GT.log("[dragon] user schematic not found, skipped: " + SOURCE);
            return;
        }
        boolean layered = Boolean.parseBoolean(System.getProperty("ale.layered", "true"));
        double range = Double.parseDouble(System.getProperty("ale.range", "64"));
        int ticksToRun = Integer.parseInt(System.getProperty("ale.ticks", "").isBlank() ? "1200" : System.getProperty("ale.ticks").strip());
        try (TestSingleplayerContext sp = GT.newWorld(context)) {
            sp.getServer().runCommand("gamemode creative @a");
            context.runOnClient(client -> client.options.renderDistance().set(8));
            // 站在龙身体下方附近（先找到底层方块最多的位置）
            SchematicPlacement placement = context.computeOnClient(client -> {
                try {
                    //? if <1.21 {
                    /*java.io.File dir = DataManager.getSchematicsBaseDirectory();
                    Files.copy(SOURCE, dir.toPath().resolve("ale_dragon.litematic"), StandardCopyOption.REPLACE_EXISTING);
                    GT.legacySchematicVersion(dir.toPath().resolve("ale_dragon.litematic"));
                    *///?} else {
                    Path dir = DataManager.getSchematicsBaseDirectory();
                    Files.copy(SOURCE, dir.resolve("ale_dragon.litematic"), StandardCopyOption.REPLACE_EXISTING);
                    //?}
                    LitematicaSchematic schematic = LitematicaSchematic.createFromFile(dir, "ale_dragon.litematic");
                    SchematicPlacement p = SchematicPlacement.createFor(schematic, ORIGIN, "dragon", true, true);
                    DataManager.getSchematicPlacementManager().addSchematicPlacement(p, false);
                    return p;
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            BlockPos stand = findDenseBottom(placement);
            GT.log("[dragon] standing at " + stand.toShortString());
            sp.getServer().runCommand("tp @a " + (stand.getX() + 0.5) + " " + (stand.getY()) + " " + (stand.getZ() + 0.5));
            context.waitFor(client -> client.player != null && Math.abs(client.player.getX() - stand.getX() - 0.5) < 0.01, 200);
            context.waitTicks(100); // 让投影世界的区块载入

            int bottomY = ORIGIN.getY();
            int layers = 24;
            int[] expected = expectedPerLayer(context, stand, bottomY, layers, range);
            GT.log("[dragon] schematic blocks within range for the bottom " + layers + " layers: " + Arrays.toString(expected));

            context.runOnClient(client -> {
                Configs.Core.WORK_RANGE.setDoubleValue(range);
                Configs.Print.LAYERED_MODE.setBooleanValue(layered);
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(Integer.parseInt(System.getProperty("ale.bpt", "8")));
                GT.enablePrint();
            });
            int lastPlaced = 0, lastProgress = 0, maxStall = 0;
            boolean orderViolated = false;
            for (int t = 1; t <= ticksToRun; t++) {
                context.waitTick();
                if (t % 20 != 0) continue;
                int[] placed = placedPerLayer(sp, stand, bottomY, layers, range);
                int sum = Arrays.stream(placed).sum();
                for (int i = 1; i < layers; i++) {
                    if (placed[i] > 0 && placed[i - 1] < expected[i - 1]) orderViolated = true;
                }
                if (sum != lastPlaced) {
                    maxStall = Math.max(maxStall, t - lastProgress);
                    lastProgress = t;
                    lastPlaced = sum;
                }
                if (t % 200 == 0) {
                    String hud = context.computeOnClient(c -> "fps=" + c.getFps() + " layer=" + com.autyism.printer.handler.ModuleManager.PRINT.getCurrentLayer());
                    GT.log("[dragon] t" + t + " placed=" + sum + " per-layer=" + Arrays.toString(placed) + " " + hud);
                }
            }
            int expectedSum = Arrays.stream(expected).sum();
            java.util.List<String> missing = context.computeOnClient(client -> {
                var w = SchematicWorldHandler.getSchematicWorld();
                java.util.List<String> out = new java.util.ArrayList<>();
                int r = (int) Math.ceil(range);
                for (int x = -r; x <= r && out.size() < 10; x++) for (int z = -r; z <= r && out.size() < 10; z++) {
                    BlockPos p = new BlockPos(stand.getX() + x, bottomY, stand.getZ() + z);
                    if (inRange(p, stand, range) && !w.getBlockState(p).isAir() && client.level.getBlockState(p).isAir()) {
                        double d = Math.sqrt(client.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p)));
                        out.add(p.toShortString() + " want=" + w.getBlockState(p) + " dist=" + String.format("%.2f", d)
                                + " canInteract=" + com.autyism.printer.utils.PlayerUtils.canInteracted(p)
                                + " eff=" + com.autyism.printer.utils.ConfigUtils.getEffectiveRange());
                    }
                }
                return out;
            });
            GT.log("[dragon] unplaced in bottom layer: " + missing);
            context.runOnClient(client -> GT.disableAll());
            GT.log("[dragon] RESULT layered=" + layered + " range=" + range + ": placed " + lastPlaced + " blocks in " + ticksToRun
                    + " ticks (bottom " + layers + " layers in range need " + expectedSum + "), longest stall " + maxStall
                    + " ticks, orderViolated=" + orderViolated);
            if (lastPlaced == 0) throw new AssertionError("[dragon] printer placed nothing");
            if (layered && orderViolated) throw new AssertionError("[dragon] layer order violated");
            if (ticksToRun - lastProgress > 200 && lastPlaced < expectedSum) {
                throw new AssertionError("[dragon] printer stalled (no progress in the last " + (ticksToRun - lastProgress) + " ticks)");
            }
        } finally {
            GT.removeAllPlacements(context);
            context.runOnClient(client -> {
                GT.disableAll();
                Configs.Print.LAYERED_MODE.setBooleanValue(false);
            });
        }
    }

    /** 在龙的底层找一处方块最密集的位置站着 */
    private static BlockPos findDenseBottom(SchematicPlacement placement) {
        var schematic = placement.getSchematic();
        String region = schematic.getAreas().keySet().iterator().next();
        var container = schematic.getSubRegionContainer(region);
        var size = container.getSize();
        // 子区域尺寸为负方向（-697,-380,236），用投影坐标 → 世界坐标需要考虑偏移，这里直接用放置的方块框
        var box = placement.getSubRegionBoxes(fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.ANY).get(region);
        int minX = Math.min(box.getPos1().getX(), box.getPos2().getX());
        int minY = Math.min(box.getPos1().getY(), box.getPos2().getY());
        int minZ = Math.min(box.getPos1().getZ(), box.getPos2().getZ());
        int bestX = minX, bestZ = minZ, best = -1;
        int sx = Math.abs(size.getX()), sz = Math.abs(size.getZ());
        int[][] counts = new int[sx / 16 + 1][sz / 16 + 1];
        for (int y = 0; y < Math.min(24, Math.abs(size.getY())); y++) {
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (!container.get(x, y, z).isAir()) counts[x / 16][z / 16]++;
                }
            }
        }
        for (int cx = 0; cx < counts.length; cx++) {
            for (int cz = 0; cz < counts[cx].length; cz++) {
                if (counts[cx][cz] > best) {
                    best = counts[cx][cz];
                    bestX = minX + cx * 16 + 8;
                    bestZ = minZ + cz * 16 + 8;
                }
            }
        }
        // 世界坐标与容器坐标在无旋转/镜像时一一对应（容器 y=0 对应最低层）
        return new BlockPos(bestX, minY, bestZ);
    }

    private static boolean inRange(BlockPos p, BlockPos stand, double range) {
        double ex = stand.getX() + 0.5, ey = stand.getY() + 1.62, ez = stand.getZ() + 0.5;
        double dx = Math.max(Math.max(p.getX() - ex, ex - (p.getX() + 1)), 0);
        double dy = Math.max(Math.max(p.getY() - ey, ey - (p.getY() + 1)), 0);
        double dz = Math.max(Math.max(p.getZ() - ez, ez - (p.getZ() + 1)), 0);
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    private static int[] expectedPerLayer(ClientGameTestContext context, BlockPos stand, int bottomY, int layers, double range) {
        return context.computeOnClient(client -> {
            var w = SchematicWorldHandler.getSchematicWorld();
            int r = (int) Math.ceil(range);
            int[] c = new int[layers];
            for (int y = 0; y < layers; y++) {
                for (int x = -r; x <= r; x++) {
                    for (int z = -r; z <= r; z++) {
                        BlockPos p = new BlockPos(stand.getX() + x, bottomY + y, stand.getZ() + z);
                        // 玩家自己占据的两格无法放置，不计入期望
                        boolean occupied = x == 0 && z == 0 && (y == 0 || y == 1);
                        if (!occupied && inRange(p, stand, range) && !w.getBlockState(p).isAir()) c[y]++;
                    }
                }
            }
            return c;
        });
    }

    private static int[] placedPerLayer(TestSingleplayerContext sp, BlockPos stand, int bottomY, int layers, double range) {
        return sp.getServer().computeOnServer(server -> {
            int r = (int) Math.ceil(range);
            int[] c = new int[layers];
            var level = server.overworld();
            for (int y = 0; y < layers; y++) {
                for (int x = -r; x <= r; x++) {
                    for (int z = -r; z <= r; z++) {
                        BlockPos p = new BlockPos(stand.getX() + x, bottomY + y, stand.getZ() + z);
                        if (inRange(p, stand, range) && !level.getBlockState(p).isAir()) c[y]++;
                    }
                }
            }
            return c;
        });
    }
}
