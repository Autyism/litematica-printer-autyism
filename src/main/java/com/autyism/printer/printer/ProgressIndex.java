package com.autyism.printer.printer;

import com.autyism.printer.enums.BlockMatchingType;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.List;
import java.util.Objects;

/**
 * 整个投影（已加载的区块）的打印进度，增量维护：按区块段（16x16x16）记每段的统计，
 * 只有这一段的方块变了（客户端世界的方块变化）、区块新加载 / 重新加载、或者轮到它定期复查时才重算这一段，
 * 每 tick 有时间预算。投影放置、渲染层、选区类型变了就从头算（算完之前显示上一次的结果）。
 */
public final class ProgressIndex implements LoadedColumns.Listener {
    /** 怎么数一格：是不是打印机会处理的格子（渲染层、选区类型），对不对 */
    public interface Rules {
        boolean counts(BlockPos pos);

        BlockMatchingType matching(BlockPos pos, BlockState required, BlockState current);
    }

    /** {总数, 对的, 状态错, 方块错} */
    private static final int TOTAL = 0, CORRECT = 1, WRONG_STATE = 2, WRONG_BLOCK = 3;

    private final Rules rules;
    private LoadedColumns columns;
    private Object signature;
    /** 区块段 → 统计；null = 还没算过 */
    private final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    private final long[] sums = new long[4];
    private int unknown;
    private final LongArrayList refresh = new LongArrayList();
    private int refreshIndex;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /** 最近一次完整的结果（显示用） */
    private final long[] shown = new long[4];
    private boolean hasShown;

    public ProgressIndex(Rules rules) {
        this.rules = rules;
    }

    public long shownTotal() {
        return shown[TOTAL];
    }

    public long shownCorrect() {
        return shown[CORRECT];
    }

    public long shownWrongState() {
        return shown[WRONG_STATE];
    }

    public long shownWrongBlock() {
        return shown[WRONG_BLOCK];
    }

    public boolean hasShown() {
        return hasShown;
    }

    public void clear() {
        columns = null;
        signature = null;
        sections.clear();
        dirty.clear();
        java.util.Arrays.fill(sums, 0);
        unknown = 0;
        refresh.clear();
        refreshIndex = 0;
        java.util.Arrays.fill(shown, 0);
        hasShown = false;
    }

    /** 客户端世界里 pos 的方块变了 */
    public void blockChanged(BlockPos pos) {
        if (columns == null) return;
        long key = SectionPos.asLong(pos);
        if (sections.containsKey(key)) dirty.add(key);
    }

    /**
     * 每 tick 调用一次。signature 包含所有会改变“哪些格子算、怎么算”的东西（投影的方块框、渲染层、选区类型……），变了就从头算。
     */
    public void tick(ClientLevel level, WorldSchematic schematic, List<PrinterBox> boxes, Object signature,
                     int centerX, int centerZ, int range, long budgetNanos) {
        Object sig = java.util.Arrays.asList(boxes, signature);
        if (columns == null || !Objects.equals(sig, this.signature)) {
            sections.clear();
            dirty.clear();
            java.util.Arrays.fill(sums, 0);
            unknown = 0;
            refresh.clear();
            refreshIndex = 0;
            columns = new LoadedColumns(boxes);
            this.signature = sig;
        }
        columns.check(level, schematic, centerX, centerZ, range, 512, this);
        // 定期复查：每 tick 复查一段（投影被直接改过、漏掉的变化……迟早会被纠正）
        if (refreshIndex >= refresh.size()) {
            refresh.clear();
            refresh.addAll(sections.keySet());
            refreshIndex = 0;
        }
        if (refreshIndex < refresh.size()) {
            long key = refresh.getLong(refreshIndex++);
            if (sections.containsKey(key)) dirty.add(key);
        }
        long deadline = System.nanoTime() + budgetNanos;
        while (!dirty.isEmpty() && System.nanoTime() < deadline) {
            compute(dirty.removeFirstLong());
        }
        if (columns.discovered() && unknown == 0) {
            System.arraycopy(sums, 0, shown, 0, sums.length);
            hasShown = true;
        }
    }

    @Override
    public void columnLoaded(int cx, int cz, LevelChunk world, LevelChunk schematicChunk) {
        int sy0 = Integer.MAX_VALUE, sy1 = Integer.MIN_VALUE;
        for (PrinterBox b : columns.boxes()) {
            if (cx < b.minX >> 4 || cx > b.maxX >> 4 || cz < b.minZ >> 4 || cz > b.maxZ >> 4) continue;
            sy0 = Math.min(sy0, b.minY >> 4);
            sy1 = Math.max(sy1, b.maxY >> 4);
        }
        for (int sy = sy0; sy <= sy1; sy++) {
            long key = SectionPos.asLong(cx, sy, cz);
            int index = schematicChunk.getSectionIndexFromSectionY(sy);
            boolean empty = index < 0 || index >= schematicChunk.getSectionsCount() || schematicChunk.getSection(index).hasOnlyAir();
            if (empty) {
                remove(key);
                continue;
            }
            if (!sections.containsKey(key)) {
                sections.put(key, null);
                unknown++;
            }
            dirty.add(key);
        }
    }

    @Override
    public void columnUnloaded(int cx, int cz) {
        int sy0 = Integer.MAX_VALUE, sy1 = Integer.MIN_VALUE;
        for (PrinterBox b : columns.boxes()) {
            sy0 = Math.min(sy0, b.minY >> 4);
            sy1 = Math.max(sy1, b.maxY >> 4);
        }
        for (int sy = sy0; sy <= sy1; sy++) remove(SectionPos.asLong(cx, sy, cz));
    }

    private void remove(long key) {
        if (!sections.containsKey(key)) return;
        long[] old = sections.remove(key);
        if (old == null) unknown--;
        else for (int i = 0; i < sums.length; i++) sums[i] -= old[i];
        dirty.remove(key);
    }

    /** 重算一个区块段 */
    private void compute(long key) {
        if (!sections.containsKey(key)) return;
        int cx = SectionPos.x(key), sy = SectionPos.y(key), cz = SectionPos.z(key);
        LevelChunk[] chunks = columns.get(cx, cz);
        if (chunks == null) {
            remove(key);
            return;
        }
        LevelChunk worldChunk = chunks[0], schematicChunk = chunks[1];
        int si = schematicChunk.getSectionIndexFromSectionY(sy);
        int wi = worldChunk.getSectionIndexFromSectionY(sy);
        if (si < 0 || si >= schematicChunk.getSectionsCount() || wi < 0 || wi >= worldChunk.getSectionsCount()) {
            remove(key);
            return;
        }
        LevelChunkSection schematicSection = schematicChunk.getSection(si);
        LevelChunkSection worldSection = worldChunk.getSection(wi);
        long[] counts = new long[4];
        List<PrinterBox> boxes = columns.boxes();
        for (int bi = 0; bi < boxes.size(); bi++) {
            PrinterBox b = boxes.get(bi);
            int x0 = Math.max(b.minX, cx << 4), x1 = Math.min(b.maxX, (cx << 4) + 15);
            int y0 = Math.max(b.minY, sy << 4), y1 = Math.min(b.maxY, (sy << 4) + 15);
            int z0 = Math.max(b.minZ, cz << 4), z1 = Math.min(b.maxZ, (cz << 4) + 15);
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        BlockState required = schematicSection.getBlockState(x & 15, y & 15, z & 15);
                        if (required.isAir() || inEarlierBox(boxes, bi, x, y, z)) continue;
                        cursor.set(x, y, z);
                        if (!rules.counts(cursor)) continue;
                        BlockState current = worldSection.getBlockState(x & 15, y & 15, z & 15);
                        counts[TOTAL]++;
                        switch (rules.matching(cursor, required, current)) {
                            case CORRECT -> counts[CORRECT]++;
                            case ERROR_BLOCK_STATE -> counts[WRONG_STATE]++;
                            case ERROR_BLOCK -> counts[WRONG_BLOCK]++;
                            default -> {
                            }
                        }
                    }
                }
            }
        }
        long[] old = sections.put(key, counts);
        if (old == null) unknown--;
        else for (int i = 0; i < sums.length; i++) sums[i] -= old[i];
        for (int i = 0; i < sums.length; i++) sums[i] += counts[i];
    }

    /** 几个方块框重叠时，重叠的格子只算一次 */
    static boolean inEarlierBox(List<PrinterBox> boxes, int index, int x, int y, int z) {
        for (int i = 0; i < index; i++) {
            if (boxes.get(i).contains(x, y, z)) return true;
        }
        return false;
    }
}
