package com.autyism.printer.printer;

import com.autyism.printer.enums.BlockMatchingType;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 分层打印的整层判定，增量维护：只看当前这一层，按区块列（16x16）记每列的统计和“还没打好”的格子
 * （缺的方块、能在原地调整的状态）。只有这一列附近的方块变了、区块新加载 / 重新加载、或者轮到它定期复查时才重算这一列，
 * 每 tick 有时间预算。判断“整层打完了没有”时只复查这些记下来的格子（找到一个还要打的就停）。
 */
public final class LayerIndex implements LoadedColumns.Listener {
    /** 分类和范围内的分层统计一样 */
    public static final byte CORRECT = 0, WRONG_STATE = 1, WRONG_BLOCK = 2, MISSING = 3, SKIPPED = 4;

    public interface Rules {
        /** 打印机会处理的格子（渲染层、选区类型） */
        boolean counts(BlockPos pos);

        BlockMatchingType matching(BlockPos pos, BlockState required, BlockState current);

        /** 状态错 / 方块错的格子能不能在原地点对（不能的话它永远不会让这一层“没完成”） */
        boolean adjustable(BlockState required, BlockState current);

        /** 分层里已经放弃 / 跳过的格子（尝试太多次、被实体挡住、等上一层） */
        boolean skipped(BlockPos pos);

        /** 这一格（没打好、没被放弃）现在会不会让这一层还不算完成 */
        boolean blocking(BlockPos pos, BlockState required, BlockState current, BlockMatchingType type);
    }

    private static final class Column {
        final int[] counts = new int[4];
        final LongArrayList open = new LongArrayList();
        final ByteArrayList openKind = new ByteArrayList();
        boolean computed;
    }

    private final Rules rules;
    private int layer = Integer.MIN_VALUE;
    @Nullable
    private LoadedColumns columns;
    private Object signature;
    private final Long2ObjectOpenHashMap<Column> data = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    private int unknown;
    private final LongArrayList refresh = new LongArrayList();
    private int refreshIndex;
    /** 上一次判断时挡住这一层的格子（下次先看它） */
    private long lastBlocking = Long.MIN_VALUE;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public LayerIndex(Rules rules) {
        this.rules = rules;
    }

    public int layer() {
        return layer;
    }

    /** 调试：上一次判断时挡住这一层的格子；没有时为 null */
    @Nullable
    public BlockPos lastBlocking() {
        return lastBlocking == Long.MIN_VALUE ? null : BlockPos.of(lastBlocking);
    }

    public void clear() {
        layer = Integer.MIN_VALUE;
        columns = null;
        signature = null;
        data.clear();
        dirty.clear();
        unknown = 0;
        refresh.clear();
        refreshIndex = 0;
        lastBlocking = Long.MIN_VALUE;
    }

    /** 客户端世界里 pos 的方块变了：这一层和上下一层的变化会影响这一层（例如下面的支撑方块） */
    public void blockChanged(BlockPos pos) {
        if (columns == null || Math.abs(pos.getY() - layer) > 1) return;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        markDirty(cx, cz);
        // 区块列边上的格子也会影响旁边那一列的格子（例如贴在它上面的火把）
        int lx = pos.getX() & 15, lz = pos.getZ() & 15;
        if (lx == 0) markDirty(cx - 1, cz);
        if (lx == 15) markDirty(cx + 1, cz);
        if (lz == 0) markDirty(cx, cz - 1);
        if (lz == 15) markDirty(cx, cz + 1);
    }

    private void markDirty(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        if (data.containsKey(key)) dirty.add(key);
    }

    /** 每 tick 调用一次（投影的方块框、渲染层……变了就从头算；换了一层时已加载的区块列不用重新找） */
    public void tick(ClientLevel level, WorldSchematic schematic, int layer, List<PrinterBox> boxes, Object signature,
                     int centerX, int centerZ, int range, long budgetNanos) {
        Object sig = java.util.Arrays.asList(boxes, signature);
        if (columns == null || !Objects.equals(sig, this.signature)) {
            clear();
            this.layer = layer;
            this.signature = sig;
            columns = new LoadedColumns(boxes);
        } else if (layer != this.layer) {
            switchLayer(layer);
        }
        columns.check(level, schematic, centerX, centerZ, range, 512, this);
        if (refreshIndex >= refresh.size()) {
            refresh.clear();
            refresh.addAll(data.keySet());
            refreshIndex = 0;
        }
        // 定期复查：每 tick 复查两列（被直接改过的投影、实体挡住后又走开了……迟早会被纠正）
        for (int i = 0; i < 2 && refreshIndex < refresh.size(); i++) {
            long key = refresh.getLong(refreshIndex++);
            if (data.containsKey(key)) dirty.add(key);
        }
        process(budgetNanos);
    }

    /** 换到另一层：已加载的区块列还是那些，只是每列要按新的一层重算 */
    private void switchLayer(int layer) {
        this.layer = layer;
        data.clear();
        dirty.clear();
        unknown = 0;
        refresh.clear();
        refreshIndex = 0;
        lastBlocking = Long.MIN_VALUE;
        for (Long2ObjectMap.Entry<LevelChunk[]> e : columns.entries()) {
            long key = e.getLongKey();
            columnLoaded(ChunkPos.getX(key), ChunkPos.getZ(key), e.getValue()[0], e.getValue()[1]);
        }
    }

    /** 当场把等着重算的列算掉一部分（判断整层时用） */
    private void process(long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        while (!dirty.isEmpty() && System.nanoTime() < deadline) {
            compute(dirty.removeFirstLong());
        }
    }

    /** 已加载的部分都算过了，而且没有等着重算的列 */
    public boolean ready() {
        return columns != null && columns.discovered() && unknown == 0 && dirty.isEmpty();
    }

    /**
     * 这一层（已加载的部分）是不是都完成了：每个记下来的没打好的格子，现在都不会再挡住这一层。
     * 还没算完时先当场算一会儿（小的层当场就算完）；算不完就当作还没完成。
     */
    public boolean done(WorldSchematic schematic, ClientLevel level, long budgetNanos) {
        if (columns == null) return false;
        process(budgetNanos);
        if (!ready()) return false;
        if (lastBlocking != Long.MIN_VALUE && stillBlocking(schematic, level, lastBlocking)) return false;
        for (Long2ObjectMap.Entry<Column> e : data.long2ObjectEntrySet()) {
            LongArrayList open = e.getValue().open;
            for (int i = 0; i < open.size(); i++) {
                long pos = open.getLong(i);
                if (stillBlocking(schematic, level, pos)) {
                    lastBlocking = pos;
                    return false;
                }
            }
        }
        lastBlocking = Long.MIN_VALUE;
        return true;
    }

    private boolean stillBlocking(WorldSchematic schematic, ClientLevel level, long packed) {
        BlockPos pos = cursor.set(packed);
        BlockState required = schematic.getBlockState(pos);
        if (required.isAir()) return false;
        BlockState current = level.getBlockState(pos);
        BlockMatchingType type = rules.matching(pos, required, current);
        if (type == BlockMatchingType.CORRECT || rules.skipped(pos)) return false;
        return rules.blocking(pos, required, current, type);
    }

    /** 整层的统计 {总数, 对, 状态错, 方块错, 缺失, 跳过}（缺失和跳过按现在的情况分）；还没算完时返回 null */
    @Nullable
    public int[] stats() {
        if (!ready()) return null;
        int[] out = new int[6];
        for (Column c : data.values()) {
            out[1] += c.counts[CORRECT];
            out[2] += c.counts[WRONG_STATE];
            out[3] += c.counts[WRONG_BLOCK];
            for (int i = 0; i < c.open.size(); i++) {
                if (c.openKind.getByte(i) != MISSING) continue;
                if (rules.skipped(cursor.set(c.open.getLong(i)))) out[5]++;
                else out[4]++;
            }
        }
        out[0] = out[1] + out[2] + out[3] + out[4] + out[5];
        return out;
    }

    @Override
    public void columnLoaded(int cx, int cz, LevelChunk world, LevelChunk schematicChunk) {
        long key = ChunkPos.asLong(cx, cz);
        int index = schematicChunk.getSectionIndexFromSectionY(layer >> 4);
        boolean empty = index < 0 || index >= schematicChunk.getSectionsCount() || schematicChunk.getSection(index).hasOnlyAir()
                || !touchesLayer(cx, cz);
        if (empty) {
            remove(key);
            return;
        }
        if (!data.containsKey(key)) {
            data.put(key, new Column());
            unknown++;
        }
        dirty.add(key);
    }

    @Override
    public void columnUnloaded(int cx, int cz) {
        remove(ChunkPos.asLong(cx, cz));
    }

    private boolean touchesLayer(int cx, int cz) {
        for (PrinterBox b : columns.boxes()) {
            if (layer >= b.minY && layer <= b.maxY && cx >= b.minX >> 4 && cx <= b.maxX >> 4 && cz >= b.minZ >> 4 && cz <= b.maxZ >> 4) return true;
        }
        return false;
    }

    private void remove(long key) {
        Column old = data.remove(key);
        if (old != null && !old.computed) unknown--;
        dirty.remove(key);
    }

    /** 重算一列：这一层在这一列里的每一格 */
    private void compute(long key) {
        Column column = data.get(key);
        if (column == null) return;
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        LevelChunk[] chunks = columns.get(cx, cz);
        if (chunks == null) {
            remove(key);
            return;
        }
        int si = chunks[1].getSectionIndexFromSectionY(layer >> 4);
        int wi = chunks[0].getSectionIndexFromSectionY(layer >> 4);
        if (si < 0 || si >= chunks[1].getSectionsCount() || wi < 0 || wi >= chunks[0].getSectionsCount()) {
            remove(key);
            return;
        }
        LevelChunkSection schematicSection = chunks[1].getSection(si);
        LevelChunkSection worldSection = chunks[0].getSection(wi);
        java.util.Arrays.fill(column.counts, 0);
        column.open.clear();
        column.openKind.clear();
        List<PrinterBox> boxes = columns.boxes();
        int y = layer;
        for (int bi = 0; bi < boxes.size(); bi++) {
            PrinterBox b = boxes.get(bi);
            if (y < b.minY || y > b.maxY) continue;
            int x0 = Math.max(b.minX, cx << 4), x1 = Math.min(b.maxX, (cx << 4) + 15);
            int z0 = Math.max(b.minZ, cz << 4), z1 = Math.min(b.maxZ, (cz << 4) + 15);
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    BlockState required = schematicSection.getBlockState(x & 15, y & 15, z & 15);
                    if (required.isAir() || ProgressIndex.inEarlierBox(boxes, bi, x, y, z)) continue;
                    cursor.set(x, y, z);
                    if (!rules.counts(cursor)) continue;
                    BlockState current = worldSection.getBlockState(x & 15, y & 15, z & 15);
                    switch (rules.matching(cursor, required, current)) {
                        case CORRECT -> column.counts[CORRECT]++;
                        case ERROR_BLOCK_STATE -> {
                            if (rules.adjustable(required, current)) open(column, WRONG_STATE);
                            else column.counts[WRONG_STATE]++;
                        }
                        case ERROR_BLOCK -> {
                            if (rules.adjustable(required, current)) open(column, WRONG_BLOCK);
                            else column.counts[WRONG_BLOCK]++;
                        }
                        default -> open(column, MISSING);
                    }
                }
            }
        }
        // 记下来的格子里：状态错 / 方块错的已经算进统计；缺失的按现在是否被放弃分到“缺失 / 跳过”（stats() 里算）
        for (int i = 0; i < column.openKind.size(); i++) {
            byte kind = column.openKind.getByte(i);
            if (kind != MISSING) column.counts[kind]++;
        }
        if (!column.computed) {
            column.computed = true;
            unknown--;
        }
    }

    private void open(Column column, byte kind) {
        column.open.add(cursor.asLong());
        column.openKind.add(kind);
    }
}
