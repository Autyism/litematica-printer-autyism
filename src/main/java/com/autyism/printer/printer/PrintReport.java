package com.autyism.printer.printer;

import com.autyism.printer.I18n;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.BlockMatchingType;
import com.autyism.printer.handler.ModuleManager;
import com.autyism.printer.utils.BlockUtils;
import com.autyism.printer.utils.LitematicaUtils;
import com.autyism.printer.utils.MessageUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Print report: which blocks of the schematic are still not right (in the loaded part of the print area: render layers,
 * selection type) and why, grouped by reason, posted in chat. A hotkey then leads to them one by one, nearest first:
 * a box through walls and a line under the crosshair with the reason, the distance and the direction.
 * <p>
 * Reasons the printer only knows while it works (safe observer rules, no item, a missing material...) are noted by
 * the print module as it skips a block; the rest is decided when the report is made.
 */
public final class PrintReport {
    public static final PrintReport INSTANCE = new PrintReport();

    public enum Reason {
        MATERIAL("material"),
        NO_SUPPORT("no_support"),
        SAFE_OBSERVER("safe_observer"),
        OBSERVER_PULSE("observer_pulse"),
        RAIL("rail"),
        PLAYER_IN_WAY("player_in_way"),
        WAITING("waiting"),
        NO_ITEM("no_item"),
        SKIP_LIST("skip_list"),
        WATERLOGGED_SKIP("waterlogged_skip"),
        WRONG_BLOCK("wrong_block"),
        WRONG_STATE("wrong_state"),
        EXTRA_BLOCK("extra_block"),
        NOT_REACHED("not_reached");

        public final I18n name;

        Reason(String key) {
            this.name = I18n.of("report.reason." + key);
        }
    }

    /** Positions kept per reason for locating (all are counted). */
    private static final int KEEP_PER_REASON = 4096;
    private static final int MAX_NOTED = 500_000;
    private static final long BUDGET_NANOS = 3_000_000L;

    private final Long2ObjectOpenHashMap<Reason> noted = new Long2ObjectOpenHashMap<>();

    /** Which positions of the boxes count: the printer's own (render layers + selection type), all, or the render layers. */
    private enum Scope { PRINTER, ALL, RENDER_LAYERS }

    // scan in progress
    private Scope scope = Scope.PRINTER;
    private boolean scanning;
    private boolean announce;
    private boolean locateWhenDone;
    @Nullable
    private List<PrinterBox> boxes;
    private int boxIndex;
    private int cx, cy, cz;
    private final EnumMap<Reason, List<BlockPos>> found = new EnumMap<>(Reason.class);
    private final EnumMap<Reason, Integer> counts = new EnumMap<>(Reason.class);
    private final Map<Item, Integer> materials = new LinkedHashMap<>();
    private long scanned;

    // last finished report
    @Nullable
    private Result result;

    // locating
    private final List<BlockPos> order = new ArrayList<>();
    private final List<Reason> orderReasons = new ArrayList<>();
    private int locateIndex = -1;
    @Nullable
    private ClientLevel locateLevel;
    /** The world the report and the notes belong to: another world (or leaving) clears them. */
    @Nullable
    private ClientLevel reportLevel;

    public record Result(EnumMap<Reason, List<BlockPos>> positions, EnumMap<Reason, Integer> counts, Map<Item, Integer> materials,
                         long scanned, int unfinished) {
    }

    // "done": printing on, blocks of the schematic changed, then none for QUIET_TICKS
    private static final int QUIET_TICKS = 100;
    private volatile int lastChangeTick;
    private volatile boolean changedSinceReport;

    private PrintReport() {
    }

    // ---------------------------------------------------------------- noted while printing

    /** The print module skipped pos for this reason (kept until the block is right or the printer starts again). */
    public void note(BlockPos pos, Reason reason) {
        if (noted.size() >= MAX_NOTED) noted.clear();
        noted.put(pos.asLong(), reason);
    }

    /** Another world: nothing of the last one is kept. */
    public void reset() {
        scanning = false;
        result = null;
        noted.clear();
        changedSinceReport = false;
        clearLocate();
    }

    /** The printer was switched on again: what it noted before may no longer be true. */
    public void clearNoted() {
        noted.clear();
    }

    // ---------------------------------------------------------------- making a report

    /**
     * Starts a report. announce: post it in chat when done (always for the hotkey; the automatic one after printing
     * only posts when nothing is left that walking closer could fix).
     */
    public void request(boolean announce, boolean locate) {
        if (scanning) {
            this.announce |= announce;
            this.locateWhenDone |= locate;
            return;
        }
        boxes = LitematicaUtils.getSchematicWorkBoxes();
        scope = Scope.PRINTER;
        startScan(announce, locate);
    }

    /**
     * From the Schematic Verifier: one placement, all of it or only the render layers (the verifier's own range
     * setting). Posted in chat.
     */
    public void requestFor(fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement, boolean renderLayersOnly) {
        clearLocate();
        boxes = LitematicaUtils.getPlacementBoxes(placement);
        scope = renderLayersOnly ? Scope.RENDER_LAYERS : Scope.ALL;
        scanning = false;
        startScan(true, false);
    }

    private void startScan(boolean announce, boolean locate) {
        boxIndex = 0;
        startBox();
        found.clear();
        counts.clear();
        materials.clear();
        scanned = 0;
        this.announce = announce;
        this.locateWhenDone = locate;
        scanning = true;
    }

    public boolean isScanning() {
        return scanning;
    }

    @Nullable
    public Result getResult() {
        return result;
    }

    private void startBox() {
        if (boxes != null && boxIndex < boxes.size()) {
            PrinterBox b = boxes.get(boxIndex);
            cx = b.minX;
            cy = b.minY;
            cz = b.minZ;
        }
    }

    /** Client tick: goes on with the scan (at most 3 ms a tick) and drops the locate target once it is right. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != reportLevel) {
            reportLevel = level;
            reset();
        }
        if (level == null || player == null) return;
        watchForDone(player);
        if (scanning) scanStep(level, player);
        BlockPos target = target();
        if (target != null && classify(level, player, target) == null) {
            // fixed meanwhile: take the next one
            advance(level, player, false);
        }
    }

    /**
     * Printing is on, the printer sent actions and then nothing for 5 s: it has done what it can (or waits for the
     * player to walk on). A report is made; it is posted only when walking closer would not help (see autoPost).
     */
    private void watchForDone(LocalPlayer player) {
        if (!com.autyism.printer.utils.ConfigUtils.isPrinterEnable() || !Configs.Print.ENABLED.getBooleanValue()) {
            changedSinceReport = false;
            return;
        }
        int now = player.tickCount;
        if (changedSinceReport && (now - lastChangeTick >= QUIET_TICKS || now < lastChangeTick)) {
            changedSinceReport = false;
            if (Configs.Print.REPORT_WHEN_DONE.getBooleanValue()) request(false, false);
        }
    }

    /** A block of the client world changed (BlockChangeHub, via the print module). */
    public void blockChanged(BlockPos pos) {
        if (!com.autyism.printer.utils.ConfigUtils.isPrinterEnable() || !Configs.Print.ENABLED.getBooleanValue()) return;
        if (LitematicaUtils.getBlockState(pos).isAir()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        lastChangeTick = player == null ? 0 : player.tickCount;
        changedSinceReport = true;
    }

    private void scanStep(ClientLevel level, LocalPlayer player) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null || boxes == null) {
            scanning = false;
            return;
        }
        long deadline = System.nanoTime() + BUDGET_NANOS;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int n = 0;
        while (boxIndex < boxes.size()) {
            PrinterBox b = boxes.get(boxIndex);
            pos.set(cx, cy, cz);
            if (level.hasChunk(cx >> 4, cz >> 4) && inScope(pos)) {
                Reason r = classify(level, player, pos);
                scanned++;
                if (r != null) add(r, pos, schematic);
            }
            if (++cx > b.maxX) {
                cx = b.minX;
                if (++cz > b.maxZ) {
                    cz = b.minZ;
                    if (++cy > b.maxY) {
                        boxIndex++;
                        startBox();
                    }
                }
            }
            if ((++n & 255) == 0 && System.nanoTime() > deadline) return;
        }
        scanning = false;
        int unfinished = 0;
        for (int c : counts.values()) unfinished += c;
        EnumMap<Reason, List<BlockPos>> positions = new EnumMap<>(Reason.class);
        for (var e : found.entrySet()) positions.put(e.getKey(), new ArrayList<>(e.getValue()));
        result = new Result(positions, new EnumMap<>(counts), new LinkedHashMap<>(materials), scanned, unfinished);
        if (announce) post(result);
        else autoPost(result);
        if (locateWhenDone) {
            buildOrder(player);
            advance(level, player, true);
        }
    }

    private boolean inScope(BlockPos pos) {
        return switch (scope) {
            case PRINTER -> ModuleManager.PRINT.inPrintSelection(pos);
            case RENDER_LAYERS -> LitematicaUtils.isPositionWithinRange(pos);
            case ALL -> true;
        };
    }

    private void add(Reason r, BlockPos pos, WorldSchematic schematic) {
        counts.merge(r, 1, Integer::sum);
        List<BlockPos> list = found.computeIfAbsent(r, k -> new ArrayList<>());
        if (list.size() < KEEP_PER_REASON) list.add(pos.immutable());
        if (r == Reason.MATERIAL) {
            Item item = schematic.getBlockState(pos).getBlock().asItem();
            if (item != Items.AIR) materials.merge(item, 1, Integer::sum);
        }
    }

    /** Why pos is not right yet; null when it is right (or the printer has nothing to do there). */
    @Nullable
    public Reason classify(ClientLevel level, LocalPlayer player, BlockPos pos) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return null;
        BlockState required = LitematicaUtils.getBlockState(pos);
        BlockState current = level.getBlockState(pos);
        if (required.isAir()) {
            if (current.isAir() || current.canBeReplaced()) return null;
            return Reason.EXTRA_BLOCK;
        }
        BlockMatchingType match = BlockMatchingType.get(required, current);
        if (match == BlockMatchingType.CORRECT) return null;
        if (match == BlockMatchingType.ERROR_BLOCK_STATE && WaterlogPlacer.acceptsDry(level, schematic, player, pos, required, current)) return null;
        // blocks that come with their other half (upper door / plant half, bed head) follow the lower one
        if (match == BlockMatchingType.MISSING_BLOCK && !ModuleManager.PRINT.placedOnItsOwn(required)) return null;
        if (ModuleManager.PRINT.skipListed(required)) return Reason.SKIP_LIST;
        if (Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue() && (BlockUtils.needsWater(required) || BlockUtils.isLiveCoral(required))) {
            return Reason.WATERLOGGED_SKIP;
        }
        if (match == BlockMatchingType.ERROR_BLOCK) return Reason.WRONG_BLOCK;
        Reason seen = noted.get(pos.asLong());
        if (match == BlockMatchingType.ERROR_BLOCK_STATE) return seen == Reason.WAITING || seen == null ? Reason.WRONG_STATE : seen;
        if (com.autyism.printer.handler.handlers.Print.playerInTheWay(level, player, pos, required)) return Reason.PLAYER_IN_WAY;
        if (seen == Reason.PLAYER_IN_WAY) seen = null; // walked away meanwhile
        if (seen != null) return seen;
        if (!required.canSurvive(level, pos)) return Reason.NO_SUPPORT;
        Item item = required.getBlock().asItem();
        if (item != Items.AIR && MissingMaterialTracker.getInstance().has(item)) return Reason.MATERIAL;
        return Reason.NOT_REACHED;
    }

    // ---------------------------------------------------------------- chat

    /** After printing (nothing left to do in range): a report only when walking closer would not help. */
    private void autoPost(Result r) {
        Integer far = r.counts().get(Reason.NOT_REACHED);
        if (far != null && far > 0) return;
        post(r);
    }

    private void post(Result r) {
        if (r.unfinished() == 0) {
            MessageUtils.addMessage(I18n.REPORT_ALL_DONE.getName(r.scanned()));
            return;
        }
        MessageUtils.addMessage(I18n.REPORT_TITLE.getName(r.unfinished()));
        for (Reason reason : Reason.values()) {
            Integer c = r.counts().get(reason);
            if (c == null || c == 0) continue;
            MutableComponent line = I18n.REPORT_LINE.getName(reason.name.getName(), c);
            if (reason == Reason.MATERIAL && !r.materials().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (var e : r.materials().entrySet()) {
                    if (shown++ == 6) {
                        sb.append(", ...");
                        break;
                    }
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(e.getKey().getDefaultInstance().getHoverName().getString()).append(" ").append(e.getValue());
                }
                line.append(MessageUtils.literal(" §7(" + sb + ")"));
            }
            MessageUtils.addMessage(line);
        }
        String keys = Configs.Hotkeys.REPORT_LOCATE.getKeybind().getKeysDisplayString();
        MessageUtils.addMessage(keys == null || keys.isBlank() ? I18n.REPORT_HINT_UNBOUND.getName() : I18n.REPORT_HINT.getName(keys));
    }

    // ---------------------------------------------------------------- locating

    /** Hotkey: the next unfinished block, nearest first (makes a report first when there is none or it is old). */
    public void locateNext() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (result == null || order.isEmpty() && locateIndex < 0) {
            request(true, true);
            return;
        }
        advance(mc.level, mc.player, false);
    }

    /** Hotkey: a new report, posted in chat. */
    public void report() {
        clearLocate();
        request(true, false);
    }

    private void buildOrder(LocalPlayer player) {
        order.clear();
        orderReasons.clear();
        locateIndex = -1;
        locateLevel = Minecraft.getInstance().level;
        if (result == null) return;
        record P(BlockPos pos, Reason reason, double d) {
        }
        List<P> all = new ArrayList<>();
        for (var e : result.positions().entrySet()) {
            for (BlockPos p : e.getValue()) all.add(new P(p, e.getKey(), player.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));
        }
        all.sort((a, b) -> Double.compare(a.d, b.d));
        for (P p : all) {
            order.add(p.pos);
            orderReasons.add(p.reason);
        }
    }

    private void advance(ClientLevel level, LocalPlayer player, boolean fromStart) {
        if (order.isEmpty()) {
            if (fromStart) MessageUtils.setOverlayMessage(I18n.REPORT_NOTHING_TO_LOCATE.getName());
            clearLocate();
            return;
        }
        int i = fromStart ? 0 : locateIndex + 1;
        while (i < order.size() && classify(level, player, order.get(i)) == null) i++;
        if (i >= order.size()) {
            MessageUtils.setOverlayMessage(I18n.REPORT_LOCATE_END.getName());
            clearLocate();
            result = null;
            return;
        }
        locateIndex = i;
    }

    public void clearLocate() {
        order.clear();
        orderReasons.clear();
        locateIndex = -1;
        locateLevel = null;
    }

    @Nullable
    public BlockPos target() {
        return locateIndex >= 0 && locateIndex < order.size() ? order.get(locateIndex) : null;
    }

    @Nullable
    public Reason targetReason() {
        return locateIndex >= 0 && locateIndex < orderReasons.size() ? orderReasons.get(locateIndex) : null;
    }

    public int targetNumber() {
        return locateIndex + 1;
    }

    public int targetCount() {
        return order.size();
    }

    /** The line under the crosshair while locating. */
    @Nullable
    public String hudLine() {
        BlockPos t = target();
        Minecraft mc = Minecraft.getInstance();
        if (t == null || mc.player == null) return null;
        LocalPlayer p = mc.player;
        double dx = t.getX() + 0.5 - p.getX();
        double dz = t.getZ() + 0.5 - p.getZ();
        int dy = t.getY() - p.getBlockY();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz + dy * dy));
        String arrow;
        if (dx * dx + dz * dz < 0.5) {
            arrow = dy >= 0 ? "▲" : "▼";
        } else {
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = ((yaw - p.getYRot()) % 360 + 540) % 360 - 180; // -180..180, positive = to the right
            String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
            arrow = arrows[(int) Math.floorMod(Math.round(rel / 45.0), 8)];
        }
        String height = dy == 0 ? "" : (dy > 0 ? " ▲" + dy : " ▼" + (-dy));
        Reason reason = targetReason();
        String block = LitematicaUtils.getBlockState(t).getBlock().getName().getString();
        return I18n.REPORT_HUD.getName(targetNumber(), targetCount(), reason == null ? "" : reason.name.getName().getString(), block,
                dist, arrow + height, t.getX(), t.getY(), t.getZ()).getString();
    }
}
