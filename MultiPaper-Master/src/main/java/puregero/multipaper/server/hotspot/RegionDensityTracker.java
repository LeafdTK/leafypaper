package puregero.multipaper.server.hotspot;

import puregero.multipaper.server.ServerConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-region rolling population tally.
 *
 * Each (world, regionX, regionZ) cell accumulates the latest player counts
 * reported by every game server that has subscribers in that region. The
 * cell's total is the sum across reporting servers — i.e. how many players
 * total are inside that 256x256 block area cluster-wide. When the total
 * crosses {@link HotspotConfig#thresholdPlayers} the region is flagged as a
 * hotspot candidate; the offload coordinator (separate component) inspects
 * flagged regions and decides whether to trigger ownership transfer.
 */
public final class RegionDensityTracker {

    private static final Map<RegionKey, Cell> cells = new ConcurrentHashMap<>();

    /** Update {@code (world, rx, rz)}'s tally with {@code newCount} from {@code server}. */
    public static void report(String world, int rx, int rz, ServerConnection server, int newCount) {
        RegionKey key = new RegionKey(world, rx, rz);
        Cell cell = cells.computeIfAbsent(key, k -> new Cell());
        cell.contributions.compute(server, (k, prev) -> {
            int delta = newCount - (prev == null ? 0 : prev);
            cell.total.addAndGet(delta);
            return newCount == 0 ? null : newCount;
        });
        // GC empty cells so we don't accumulate forever for transient regions.
        if (cell.contributions.isEmpty()) {
            cells.remove(key, cell);
        }
    }

    /** Drop every contribution from {@code server} — call on disconnect. */
    public static void forgetServer(ServerConnection server) {
        cells.values().forEach(cell -> {
            Integer removed = cell.contributions.remove(server);
            if (removed != null) {
                cell.total.addAndGet(-removed);
            }
        });
        cells.entrySet().removeIf(entry -> entry.getValue().contributions.isEmpty());
    }

    /** Current combined population of a single region. Zero if untracked. */
    public static int regionTotal(String world, int rx, int rz) {
        Cell cell = cells.get(new RegionKey(world, rx, rz));
        return cell == null ? 0 : cell.total.get();
    }

    /** Snapshot all regions whose combined population is at or above {@code threshold}. */
    public static List<HotRegion> hottestAbove(int threshold) {
        List<HotRegion> hot = new ArrayList<>();
        cells.forEach((key, cell) -> {
            int total = cell.total.get();
            if (total >= threshold) {
                hot.add(new HotRegion(key.world, key.rx, key.rz, total));
            }
        });
        hot.sort((a, b) -> Integer.compare(b.total, a.total));
        return hot;
    }

    public record RegionKey(String world, int rx, int rz) {}

    public record HotRegion(String world, int rx, int rz, int total) {}

    private static final class Cell {
        final AtomicInteger total = new AtomicInteger(0);
        final Map<ServerConnection, Integer> contributions = new ConcurrentHashMap<>();
    }
}
