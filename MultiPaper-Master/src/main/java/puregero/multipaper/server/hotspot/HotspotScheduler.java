package puregero.multipaper.server.hotspot;

import puregero.multipaper.server.ChunkSubscriptionManager;
import puregero.multipaper.server.ServerConnection;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Kubernetes-style two-phase scheduler for picking a transfer target.
 *
 * <ol>
 *   <li><b>Filter</b> — remove servers that <em>cannot</em> host the region:
 *       offline, already the region's owner, TPS below the floor.
 *   <li><b>Score</b> — for each survivor, sum a set of weighted signals:
 *       lower active-transfer load → higher score, higher TPS → higher score,
 *       lower current player count → higher score, locality bonus if the
 *       candidate already mirrors chunks in the region (less data to ship).
 * </ol>
 *
 * Weights are tunable via {@code -Dmultipaper.hotspot.score.&lt;name&gt;=W}
 * system properties so an operator can swing the scheduler toward whichever
 * signal matters most without recompiling.
 */
public final class HotspotScheduler {

    private HotspotScheduler() {}

    /** TPS at or below this filters a candidate out — it's already struggling. */
    private static final double MIN_TPS = doubleProp("multipaper.hotspot.minTps", 17.0);

    private static final int W_LOAD     = Integer.getInteger("multipaper.hotspot.score.load", 100);
    private static final int W_TPS      = Integer.getInteger("multipaper.hotspot.score.tps", 50);
    private static final int W_PLAYERS  = Integer.getInteger("multipaper.hotspot.score.players", 20);
    private static final int W_LOCALITY = Integer.getInteger("multipaper.hotspot.score.locality", 30);

    /** Result of one scheduling attempt, with the score breakdown for logs. */
    public record Decision(ServerConnection chosen, int score, String breakdown) {}

    /**
     * Pick the best candidate for taking ownership of {@code region}.
     * {@code activeLoad} maps a server to how many hot regions it currently
     * already absorbs (lower = preferred).
     *
     * Returns {@code null} when no candidate survives the filter phase.
     */
    public static Decision pick(RegionDensityTracker.RegionKey region,
                                List<ServerConnection> candidates,
                                ServerConnection currentOwner,
                                ToIntFunction<ServerConnection> activeLoadFn) {
        Decision best = null;
        for (ServerConnection candidate : candidates) {
            if (!survivesFilter(candidate, currentOwner)) continue;

            int loadScore     = W_LOAD     * (10 - Math.min(activeLoadFn.applyAsInt(candidate), 10));
            int tpsScore      = W_TPS      * tpsBucket(candidate.getTps());
            int playerScore   = W_PLAYERS  * (10 - Math.min(candidate.getPlayers().size() / 10, 10));
            int localityScore = W_LOCALITY * (hasLocalityFor(candidate, region) ? 10 : 0);

            int total = loadScore + tpsScore + playerScore + localityScore;
            if (best == null || total > best.score()) {
                String breakdown = String.format("load=%d tps=%d players=%d locality=%d",
                        loadScore, tpsScore, playerScore, localityScore);
                best = new Decision(candidate, total, breakdown);
            }
        }
        return best;
    }

    private static boolean survivesFilter(ServerConnection candidate, ServerConnection currentOwner) {
        if (candidate == currentOwner) return false;
        if (!candidate.isOnline()) return false;
        if (candidate.getTps() < MIN_TPS) return false;
        return true;
    }

    /** Bucket 20 TPS → 10, 15 TPS → 5, etc. Capped at 10. */
    private static int tpsBucket(double tps) {
        return Math.max(0, Math.min(10, (int) Math.round(tps / 2.0)));
    }

    /**
     * Locality bonus: if the candidate is already a subscriber on at least one
     * chunk in the region they're a better choice (they already have some
     * chunk state, so the data-transfer cost of taking ownership is lower).
     */
    private static boolean hasLocalityFor(ServerConnection candidate, RegionDensityTracker.RegionKey region) {
        int regionSize = HotspotConfig.REGION_SIZE_CHUNKS;
        int cxStart = region.rx() * regionSize;
        int czStart = region.rz() * regionSize;
        // Sample a few chunks in the region rather than all 256 — locality is
        // a soft signal and the full scan would dominate scheduler tick time.
        int[] samples = {0, regionSize / 2, regionSize - 1};
        for (int dx : samples) {
            for (int dz : samples) {
                List<ServerConnection> subs = ChunkSubscriptionManager.getSubscribers(
                        region.world(), cxStart + dx, czStart + dz);
                if (subs.contains(candidate)) return true;
            }
        }
        return false;
    }

    private static double doubleProp(String key, double dflt) {
        String v = System.getProperty(key);
        if (v == null) return dflt;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return dflt; }
    }
}
