package com.autyism.printer.printer;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 遍历投影放置的方块框里、已经加载的区块中投影不是空气的格子（客户端世界和投影世界都要有这个区块：
 * 没加载的区块看不到，不算）。投影里整段都是空气的区块段直接跳过。
 * 可以分几个 tick 做完：每次给一个截止时间，做完一个区块段检查一次。
 * <p>
 * 用在：进度条统计整个投影（GUI）、分层打印判断整层有没有打完（Print）。
 */
public final class LoadedAreaScan {
    @FunctionalInterface
    public interface Visitor {
        /** pos 是同一个可变对象，要保存就 immutable() */
        void visit(BlockPos pos, BlockState required, BlockState current);
    }

    private final ClientLevel level;
    private final WorldSchematic schematic;
    private final List<PrinterBox> boxes;
    /** 只看这一层（null = 所有高度） */
    @Nullable
    private final Integer layer;

    private int boxIndex;
    private boolean boxStarted;
    private int y0, y1, cx0, cx1, cz0, cz1, sy0, sy1;
    private int cx, cz, sy;
    @Nullable
    private LevelChunk worldChunk;
    @Nullable
    private LevelChunk schematicChunk;
    private boolean finished;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public LoadedAreaScan(ClientLevel level, WorldSchematic schematic, List<PrinterBox> boxes, @Nullable Integer layer) {
        this.level = level;
        this.schematic = schematic;
        this.boxes = List.copyOf(boxes);
        this.layer = layer;
    }

    @Nullable
    public Integer layer() {
        return layer;
    }

    public boolean isFinished() {
        return finished;
    }

    /** 一直做到扫完或者到了截止时间（System.nanoTime()），返回是否扫完 */
    public boolean run(long deadlineNanos, Visitor visitor) {
        while (!finished) {
            if (!nextSection()) {
                finished = true;
                break;
            }
            visitSection(visitor);
            if (System.nanoTime() >= deadlineNanos) break;
        }
        return finished;
    }

    /** 前进到下一个要看的区块段（已加载、投影里不全是空气），没有了返回 false */
    private boolean nextSection() {
        while (boxIndex < boxes.size()) {
            if (!boxStarted) {
                PrinterBox b = boxes.get(boxIndex);
                y0 = layer != null ? Math.max(b.minY, layer) : b.minY;
                y1 = layer != null ? Math.min(b.maxY, layer) : b.maxY;
                if (y0 > y1) {
                    boxIndex++;
                    continue;
                }
                cx0 = b.minX >> 4;
                cx1 = b.maxX >> 4;
                cz0 = b.minZ >> 4;
                cz1 = b.maxZ >> 4;
                sy0 = y0 >> 4;
                sy1 = y1 >> 4;
                cx = cx0;
                cz = cz0;
                sy = sy0 - 1;
                boxStarted = true;
                loadColumn();
            }
            sy++;
            if (sy > sy1) {
                sy = sy0;
                cz++;
                if (cz > cz1) {
                    cz = cz0;
                    cx++;
                    if (cx > cx1) {
                        boxIndex++;
                        boxStarted = false;
                        continue;
                    }
                }
                loadColumn();
            }
            if (worldChunk == null || schematicChunk == null) {
                // 这一列没加载：整列跳过
                sy = sy1;
                continue;
            }
            int index = schematicChunk.getSectionIndexFromSectionY(sy);
            if (index < 0 || index >= schematicChunk.getSectionsCount() || schematicChunk.getSection(index).hasOnlyAir()) continue;
            return true;
        }
        return false;
    }

    private void loadColumn() {
        worldChunk = level.getChunkSource().getChunkNow(cx, cz);
        //? if <1.21.11 {
        /*schematicChunk = schematic.getChunkProvider().getChunkIfExists(cx, cz);
        *///?} else
        schematicChunk = schematic.getChunkSource().getChunkIfExists(cx, cz);
    }

    private void visitSection(Visitor visitor) {
        PrinterBox b = boxes.get(boxIndex);
        LevelChunkSection schematicSection = schematicChunk.getSection(schematicChunk.getSectionIndexFromSectionY(sy));
        int worldIndex = worldChunk.getSectionIndexFromSectionY(sy);
        LevelChunkSection worldSection = worldIndex >= 0 && worldIndex < worldChunk.getSectionsCount() ? worldChunk.getSection(worldIndex) : null;
        int xa = Math.max(b.minX, cx << 4), xb = Math.min(b.maxX, (cx << 4) + 15);
        int za = Math.max(b.minZ, cz << 4), zb = Math.min(b.maxZ, (cz << 4) + 15);
        int ya = Math.max(y0, sy << 4), yb = Math.min(y1, (sy << 4) + 15);
        for (int y = ya; y <= yb; y++) {
            for (int z = za; z <= zb; z++) {
                for (int x = xa; x <= xb; x++) {
                    BlockState required = schematicSection.getBlockState(x & 15, y & 15, z & 15);
                    if (required.isAir() || coveredByEarlierBox(x, y, z)) continue;
                    BlockState current = worldSection == null ? net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState()
                            : worldSection.getBlockState(x & 15, y & 15, z & 15);
                    visitor.visit(cursor.set(x, y, z), required, current);
                }
            }
        }
    }

    /** 几个方块框重叠时，重叠的格子只算一次 */
    private boolean coveredByEarlierBox(int x, int y, int z) {
        for (int i = 0; i < boxIndex; i++) {
            if (boxes.get(i).contains(x, y, z)) return true;
        }
        return false;
    }
}
