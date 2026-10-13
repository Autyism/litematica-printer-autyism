package com.autyism.printer.printer;

import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;

/**
 * 投影方块框覆盖的、已经加载的区块列（客户端世界和投影世界都有这个区块）。
 * 每 tick 只看玩家周围已加载范围里的一部分区块列（轮流看），发现新加载、重新加载（区块对象换了）或卸载就通知，
 * 所以开销只和已加载的范围有关，和投影多大无关。增量统计（进度条、整层判定）用它知道哪些区块要算。
 */
public final class LoadedColumns {
    public interface Listener {
        /** 这一列新加载了，或者重新加载了（区块对象换了，内容可能全变了） */
        void columnLoaded(int cx, int cz, LevelChunk world, LevelChunk schematic);

        void columnUnloaded(int cx, int cz);
    }

    private final List<PrinterBox> boxes;
    private final int minCx, maxCx, minCz, maxCz;
    /** 已加载的区块列 → {客户端世界的区块, 投影世界的区块} */
    private final Long2ObjectOpenHashMap<LevelChunk[]> loaded = new Long2ObjectOpenHashMap<>();
    private int cursor;
    private int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE, radius = -1;
    /** 当前中心附近的范围从头到尾看完了几遍（看完一遍之前，已加载的区块列还没找全） */
    private int sweeps;

    public LoadedColumns(List<PrinterBox> boxes) {
        this.boxes = List.copyOf(boxes);
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (PrinterBox b : this.boxes) {
            x0 = Math.min(x0, b.minX >> 4);
            x1 = Math.max(x1, b.maxX >> 4);
            z0 = Math.min(z0, b.minZ >> 4);
            z1 = Math.max(z1, b.maxZ >> 4);
        }
        this.minCx = x0;
        this.maxCx = x1;
        this.minCz = z0;
        this.maxCz = z1;
    }

    public List<PrinterBox> boxes() {
        return boxes;
    }

    /** 已加载的区块列都找到了（以玩家现在的位置为中心至少完整看过一遍） */
    public boolean discovered() {
        return sweeps > 0;
    }

    @Nullable
    public LevelChunk[] get(int cx, int cz) {
        return loaded.get(ChunkPos.asLong(cx, cz));
    }

    public Iterable<Long2ObjectMap.Entry<LevelChunk[]>> entries() {
        return loaded.long2ObjectEntrySet();
    }

    /**
     * 看最多 maxChecks 个区块列。centerX / centerZ 是玩家所在的区块，range 是客户端加载的半径。
     */
    public void check(ClientLevel level, WorldSchematic schematic, int centerX, int centerZ, int range, int maxChecks, Listener listener) {
        if (centerX != this.centerX || centerZ != this.centerZ || range != this.radius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = range;
            sweeps = 0;
            cursor = 0;
            // 移出范围的区块客户端也会卸载：先当作卸载
            for (Iterator<Long2ObjectMap.Entry<LevelChunk[]>> it = loaded.long2ObjectEntrySet().iterator(); it.hasNext(); ) {
                long key = it.next().getLongKey();
                int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
                if (Math.abs(cx - centerX) > range || Math.abs(cz - centerZ) > range) {
                    it.remove();
                    listener.columnUnloaded(cx, cz);
                }
            }
        }
        // 只看“已加载的范围 ∩ 投影的区块范围”
        int x0 = Math.max(centerX - range, minCx), x1 = Math.min(centerX + range, maxCx);
        int z0 = Math.max(centerZ - range, minCz), z1 = Math.min(centerZ + range, maxCz);
        if (x0 > x1 || z0 > z1) {
            sweeps++;
            return;
        }
        int width = x1 - x0 + 1;
        int area = width * (z1 - z0 + 1);
        if (cursor >= area) cursor = 0;
        for (int i = 0; i < Math.min(maxChecks, area); i++) {
            int k = cursor;
            if (++cursor >= area) {
                cursor = 0;
                sweeps++;
            }
            int cx = x0 + k % width, cz = z0 + k / width;
            if (!touchesBox(cx, cz)) continue;
            long key = ChunkPos.asLong(cx, cz);
            LevelChunk world = level.getChunkSource().getChunkNow(cx, cz);
            //? if <1.21.11 {
            /*LevelChunk schematicChunk = schematic.getChunkProvider().getChunkIfExists(cx, cz);
            *///?} else
            LevelChunk schematicChunk = schematic.getChunkSource().getChunkIfExists(cx, cz);
            LevelChunk[] have = loaded.get(key);
            if (world == null || schematicChunk == null) {
                if (have != null) {
                    loaded.remove(key);
                    listener.columnUnloaded(cx, cz);
                }
                continue;
            }
            if (have == null || have[0] != world || have[1] != schematicChunk) {
                loaded.put(key, new LevelChunk[]{world, schematicChunk});
                listener.columnLoaded(cx, cz, world, schematicChunk);
            }
        }
    }

    private boolean touchesBox(int cx, int cz) {
        for (PrinterBox b : boxes) {
            if (cx >= b.minX >> 4 && cx <= b.maxX >> 4 && cz >= b.minZ >> 4 && cz <= b.maxZ >> 4) return true;
        }
        return false;
    }
}
