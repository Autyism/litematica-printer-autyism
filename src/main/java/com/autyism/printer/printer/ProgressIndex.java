package com.autyism.printer.printer;

import com.autyism.printer.enums.BlockMatchingType;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 整个投影（已加载的区块）的打印进度，增量维护：按区块段（16x16x16）记每段的统计，
 * 只有这一段的方块变了（客户端世界的方块变化）、区块新加载 / 重新加载、或者轮到它定期复查时才重算这一段，
 * 每 tick 有时间预算。投影放置、渲染层、选区类型变了就从头算（算完之前显示上一次的结果）。
 * <p>
 * 每一段还记着段里每一层的总数和对的数：分层打印用它判断“这一层（已加载的部分）每一格都对了”，
 * 不用一层一层地逐格重算（进度条和分层判定共用这一份，见 Print.loadedProgressIndex）。
 * 打印机开关、换层都不清空；换了世界、投影的方块框或者“哪些格子算 / 怎么算”的设置变了才从头算。
 */
public final class ProgressIndex implements LoadedColumns.Listener {
    /** 怎么数一格：是不是打印机会处理的格子（渲染层、选区类型），对不对 */
    public interface Rules {
        boolean counts(BlockPos pos);

        BlockMatchingType matching(BlockPos pos, BlockState required, BlockState current);

        /** 这一整段的格子都算（例如渲染层是“全部”）：不用逐格问 counts */
        default boolean countsWholeSection(int sectionX, int sectionY, int sectionZ) {
            return false;
        }
    }

    /** {总数, 对的, 状态错, 方块错} */
    private static final int TOTAL = 0, CORRECT = 1, WRONG_STATE = 2, WRONG_BLOCK = 3;

    /** 一个区块段的统计：整段的 {总数, 对, 状态错, 方块错}，以及段里每一层的总数和对的数 */
    private static final class Section {
        final long[] counts = new long[4];
        final int[] layerTotal = new int[16];
        final int[] layerCorrect = new int[16];
    }

    private final Rules rules;
    @Nullable
    private ClientLevel level;
    private LoadedColumns columns;
    private Object signature;
    /** 区块段 → 统计；null = 还没算过 */
    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    /** 区块段的 Y → 这一排的区块段 */
    private final Int2ObjectOpenHashMap<LongOpenHashSet> rows = new Int2ObjectOpenHashMap<>();
    /** 每一排还没算过的段数、等着重算的段数 */
    private final Int2IntOpenHashMap rowUnknown = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap rowDirty = new Int2IntOpenHashMap();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    private final long[] sums = new long[4];
    private int unknown;
    private final LongArrayList refresh = new LongArrayList();
    private int refreshIndex;
    /** 这一 tick 已经找过区块、复查过了（进度条和打印机每 tick 都会调用 tick） */
    private int lastTick = Integer.MIN_VALUE;
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
        level = null;
        reset();
        java.util.Arrays.fill(shown, 0);
        hasShown = false;
    }

    /** 从头算（保留上一次显示的结果，算完之前还显示它） */
    private void reset() {
        columns = null;
        signature = null;
        sections.clear();
        rows.clear();
        rowUnknown.clear();
        rowDirty.clear();
        dirty.clear();
        java.util.Arrays.fill(sums, 0);
        unknown = 0;
        refresh.clear();
        refreshIndex = 0;
        lastTick = Integer.MIN_VALUE;
    }

    /** 客户端世界里 pos 的方块变了 */
    public void blockChanged(BlockPos pos) {
        if (columns == null) return;
        long key = SectionPos.asLong(pos);
        if (sections.containsKey(key)) markDirty(key);
    }

    private void markDirty(long key) {
        if (dirty.add(key)) rowDirty.addTo(SectionPos.y(key), 1);
    }

    private void unmarkDirty(long key) {
        if (dirty.remove(key)) rowDirty.addTo(SectionPos.y(key), -1);
    }

    /**
     * 每 tick 调用（进度条、打印机都会调；同一 tick 里只有第一次找区块和定期复查，之后的调用只多算一会儿）。
     * signature 包含所有会改变“哪些格子算、怎么算”的东西（投影的方块框、渲染层、选区类型……），变了就从头算。
     */
    public void tick(ClientLevel level, WorldSchematic schematic, List<PrinterBox> boxes, Object signature,
                     int centerX, int centerZ, int range, long budgetNanos, int tick) {
        if (level != this.level) {
            // 换了世界（服务器、维度）：上一个世界的统计没有意义，显示的结果也清掉
            clear();
            this.level = level;
        }
        Object sig = java.util.Arrays.asList(boxes, signature);
        if (columns == null || !Objects.equals(sig, this.signature)) {
            reset();
            columns = new LoadedColumns(boxes);
            this.signature = sig;
        }
        if (tick != lastTick) {
            lastTick = tick;
            columns.check(level, schematic, centerX, centerZ, range, 512, this);
            // 定期复查：每 tick 复查一段（投影被直接改过、漏掉的变化……迟早会被纠正）
            if (refreshIndex >= refresh.size()) {
                refresh.clear();
                refresh.addAll(sections.keySet());
                refreshIndex = 0;
            }
            if (refreshIndex < refresh.size()) {
                long key = refresh.getLong(refreshIndex++);
                if (sections.containsKey(key)) markDirty(key);
            }
        }
        long deadline = System.nanoTime() + budgetNanos;
        while (!dirty.isEmpty() && System.nanoTime() < deadline) {
            long key = dirty.firstLong();
            unmarkDirty(key);
            compute(key);
        }
        if (columns.discovered() && unknown == 0) {
            System.arraycopy(sums, 0, shown, 0, sums.length);
            hasShown = true;
        }
    }

    /** 先把这一排（段 Y = sectionY）没算过 / 等着重算的段算掉（分层判定要的那一排优先），最多用 budgetNanos */
    public void processRow(int sectionY, long budgetNanos) {
        LongOpenHashSet row = rows.get(sectionY);
        if (row == null || rowDirty.get(sectionY) == 0) return;
        LongArrayList todo = new LongArrayList();
        for (LongIterator it = row.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (dirty.contains(key)) todo.add(key);
        }
        long deadline = System.nanoTime() + budgetNanos;
        for (int i = 0; i < todo.size() && System.nanoTime() < deadline; i++) {
            long key = todo.getLong(i);
            if (!dirty.contains(key)) continue;
            unmarkDirty(key);
            compute(key);
        }
    }

    /** 这一排（段 Y）在已加载的部分都算好了、没有等着重算的段 */
    public boolean rowReady(int sectionY) {
        return columns != null && columns.discovered() && rowUnknown.get(sectionY) == 0 && rowDirty.get(sectionY) == 0;
    }

    /**
     * 第 y 层（已加载的部分）的 {总数, 对的数}；这一排还没算好时返回 null。
     * 总数 == 对的数 就是这一层每一格都对（和打印机判断“打好了”的规则一样）。
     */
    @Nullable
    public long[] layerCounts(int y) {
        int sy = y >> 4;
        if (!rowReady(sy)) return null;
        long total = 0, correct = 0;
        LongOpenHashSet row = rows.get(sy);
        if (row != null) {
            int ly = y & 15;
            for (LongIterator it = row.iterator(); it.hasNext(); ) {
                Section s = sections.get(it.nextLong());
                if (s == null) return null;
                total += s.layerTotal[ly];
                correct += s.layerCorrect[ly];
            }
        }
        return new long[]{total, correct};
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
                rowUnknown.addTo(sy, 1);
                rows.computeIfAbsent(sy, k -> new LongOpenHashSet()).add(key);
            }
            markDirty(key);
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
        Section old = sections.remove(key);
        int sy = SectionPos.y(key);
        if (old == null) {
            unknown--;
            rowUnknown.addTo(sy, -1);
        } else {
            for (int i = 0; i < sums.length; i++) sums[i] -= old.counts[i];
        }
        unmarkDirty(key);
        LongOpenHashSet row = rows.get(sy);
        if (row != null && row.remove(key) && row.isEmpty()) rows.remove(sy);
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
        Section result = new Section();
        long[] counts = result.counts;
        boolean all = rules.countsWholeSection(cx, sy, cz);
        List<PrinterBox> boxes = columns.boxes();
        for (int bi = 0; bi < boxes.size(); bi++) {
            PrinterBox b = boxes.get(bi);
            int x0 = Math.max(b.minX, cx << 4), x1 = Math.min(b.maxX, (cx << 4) + 15);
            int y0 = Math.max(b.minY, sy << 4), y1 = Math.min(b.maxY, (sy << 4) + 15);
            int z0 = Math.max(b.minZ, cz << 4), z1 = Math.min(b.maxZ, (cz << 4) + 15);
            for (int y = y0; y <= y1; y++) {
                int ly = y & 15;
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        BlockState required = schematicSection.getBlockState(x & 15, ly, z & 15);
                        if (required.isAir() || inEarlierBox(boxes, bi, x, y, z)) continue;
                        cursor.set(x, y, z);
                        if (!all && !rules.counts(cursor)) continue;
                        BlockState current = worldSection.getBlockState(x & 15, ly, z & 15);
                        counts[TOTAL]++;
                        result.layerTotal[ly]++;
                        switch (rules.matching(cursor, required, current)) {
                            case CORRECT -> {
                                counts[CORRECT]++;
                                result.layerCorrect[ly]++;
                            }
                            case ERROR_BLOCK_STATE -> counts[WRONG_STATE]++;
                            case ERROR_BLOCK -> counts[WRONG_BLOCK]++;
                            default -> {
                            }
                        }
                    }
                }
            }
        }
        Section old = sections.put(key, result);
        if (old == null) {
            unknown--;
            rowUnknown.addTo(sy, -1);
        } else {
            for (int i = 0; i < sums.length; i++) sums[i] -= old.counts[i];
        }
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
