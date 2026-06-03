package puregero.multipaper.server.hotspot;

import puregero.multipaper.mastermessagingprotocol.messages.serverbound.HotRegionsMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.TransferRegionOwnershipMessage;
import puregero.multipaper.server.ChunkSubscriptionManager;
import puregero.multipaper.server.ServerConnection;
import puregero.multipaper.server.hotspot.RegionDensityTracker.HotRegion;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Master-side scheduler that picks hot regions above the configured threshold
 * and instructs a crowd server to take ownership of them.
 *
 * Behavior is gated by {@link HotspotConfig#DRY_RUN}: when true (default),
 * candidates are only logged and no transfer message is emitted. Flipping
 * dry-run off requires that the transfer-receiver code on the game server
 * (LockChunkMessage broadcasting via ChunkSubscriptionManager) has been
 * verified under load.
 */
public final class HotspotCoordinator {

    private HotspotCoordinator() {}

    /** Crowd-server pool: comma-separated server names from -Dmultipaper.hotspot.crowdServers. */
    private static final String[] CROWD_POOL = parsePool(System.getProperty("multipaper.hotspot.crowdServers", ""));

    /** How often the scoring loop runs, seconds. */
    private static final int LOOP_INTERVAL_SECONDS = Integer.getInteger("multipaper.hotspot.loopSeconds", 5);

    /** When a region was last transferred — used to enforce {@link HotspotConfig#COOLDOWN_SECONDS}. */
    private static final Map<RegionDensityTracker.RegionKey, Long> lastTransferAt = new HashMap<>();

    /**
     * Regions currently held by a crowd server. Used by the release sweep:
     * when density falls below {@link HotspotConfig#RELEASE_THRESHOLD_PLAYERS}
     * for {@link HotspotConfig#RELEASE_HOLD_SECONDS} consecutive seconds we
     * force-unlock the chunks so the next-in-line server (the previous owner)
     * is promoted back to chunk owner.
     */
    private static final Map<RegionDensityTracker.RegionKey, ActiveTransfer> active = new HashMap<>();

    private record ActiveTransfer(ServerConnection crowdServer, long firstLowAt) {}

    private static volatile ScheduledExecutorService scheduler;

    /** Wire the loop up. Called once from the master bootstrap. */
    public static synchronized void start() {
        if (scheduler != null) return;
        if (CROWD_POOL.length == 0) {
            System.out.println("[hotspot] coordinator: no explicit crowd pool — running in homogeneous mode (load-balance across all connected servers)");
        } else {
            System.out.println("[hotspot] coordinator: explicit crowd pool of " + CROWD_POOL.length + " server(s)");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hotspot-coordinator");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(HotspotCoordinator::tick, LOOP_INTERVAL_SECONDS, LOOP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("[hotspot] coordinator started (interval=" + LOOP_INTERVAL_SECONDS + "s, threshold=" + HotspotConfig.THRESHOLD_PLAYERS + ", dryRun=" + HotspotConfig.DRY_RUN + ")");
    }

    private static void tick() {
        try {
            tickInner();
        } catch (Throwable t) {
            // Never let a single iteration crash the scheduler.
            t.printStackTrace();
        }
    }

    private static void tickInner() {
        // Release sweep runs first so a cooled region frees its crowd server
        // before we look at the heat map for new candidates this cycle.
        sweepReleases();

        broadcastHotRegions();

        List<HotRegion> hot = RegionDensityTracker.hottestAbove(HotspotConfig.THRESHOLD_PLAYERS);
        if (hot.isEmpty()) return;

        long now = System.currentTimeMillis();
        long cooldownMs = HotspotConfig.COOLDOWN_SECONDS * 1000L;

        for (HotRegion region : hot) {
            RegionDensityTracker.RegionKey key = new RegionDensityTracker.RegionKey(region.world(), region.rx(), region.rz());
            Long last = lastTransferAt.get(key);
            if (last != null && now - last < cooldownMs) {
                continue; // still in cooldown window
            }

            HotspotScheduler.Decision decision = pickCrowdServer(key);
            if (decision == null) {
                System.out.println("[hotspot] no eligible candidate for region " + key + " (total=" + region.total() + ")");
                return;
            }
            ServerConnection crowd = decision.chosen();

            // Only transfer chunks that currently have an owner. Empty/unowned
            // chunks in the region have no state to move and would just churn
            // the master with empty subscribe+lock pairs.
            int rs = HotspotConfig.REGION_SIZE_CHUNKS;
            int cxLow = region.rx() * rs;
            int czLow = region.rz() * rs;
            long[] chunks = ChunkSubscriptionManager.lockedChunksInRange(
                    region.world(), cxLow, cxLow + rs - 1, czLow, czLow + rs - 1);

            if (HotspotConfig.DRY_RUN) {
                System.out.println("[hotspot] DRY-RUN would transfer region " + key +
                        " (total=" + region.total() + ", " + chunks.length + " live chunks) to " + crowd.getBungeeCordName() +
                        " score=" + decision.score() + " (" + decision.breakdown() + ")");
            } else {
                crowd.send(new TransferRegionOwnershipMessage(region.world(), region.rx(), region.rz(),
                        HotspotConfig.REGION_SIZE_CHUNKS, chunks));
                System.out.println("[hotspot] transferred region " + key +
                        " (total=" + region.total() + ", " + chunks.length + " live chunks) to " + crowd.getBungeeCordName() +
                        " score=" + decision.score() + " (" + decision.breakdown() + ")");
                active.put(key, new ActiveTransfer(crowd, 0L));
            }
            lastTransferAt.put(key, now);
            return; // one transfer per tick — observe its effect before issuing more
        }
    }

    private static void sweepReleases() {
        long now = System.currentTimeMillis();
        long releaseHoldMs = HotspotConfig.RELEASE_HOLD_SECONDS * 1000L;

        Iterator<Map.Entry<RegionDensityTracker.RegionKey, ActiveTransfer>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<RegionDensityTracker.RegionKey, ActiveTransfer> entry = it.next();
            RegionDensityTracker.RegionKey key = entry.getKey();
            ActiveTransfer at = entry.getValue();
            int total = RegionDensityTracker.regionTotal(key.world(), key.rx(), key.rz());

            if (total > HotspotConfig.RELEASE_THRESHOLD_PLAYERS) {
                if (at.firstLowAt() != 0L) {
                    entry.setValue(new ActiveTransfer(at.crowdServer(), 0L));
                }
                continue;
            }

            long firstLow = at.firstLowAt();
            if (firstLow == 0L) {
                entry.setValue(new ActiveTransfer(at.crowdServer(), now));
                continue;
            }
            if (now - firstLow < releaseHoldMs) {
                continue;
            }

            // Held below release threshold long enough — give the region back.
            releaseRegion(key, at.crowdServer());
            it.remove();
        }
    }

    /**
     * Force-unlock every chunk in {@code key} from {@code crowdServer}.
     * {@link ChunkSubscriptionManager#unlock} promotes the next-in-line server
     * (typically the previous owner from before the transfer) and broadcasts
     * {@code SetChunkOwnerMessage} to subscribers. The crowd server itself
     * receives the broadcast and stops ticking. Subscription stays in place —
     * the crowd server is still mirroring; it just isn't authoritative.
     */
    private static void releaseRegion(RegionDensityTracker.RegionKey key, ServerConnection crowdServer) {
        int regionSize = HotspotConfig.REGION_SIZE_CHUNKS;
        int cxLow = key.rx() * regionSize;
        int czLow = key.rz() * regionSize;
        // Only unlock chunks the crowd server actually holds — empty chunks
        // weren't locked in the first place, so iterating the full 16x16
        // grid just wastes lock-manager work.
        long[] live = ChunkSubscriptionManager.lockedChunksInRange(
                key.world(), cxLow, cxLow + regionSize - 1, czLow, czLow + regionSize - 1);
        for (long packed : live) {
            int cx = (int) (packed >> 32);
            int cz = (int) packed;
            ChunkSubscriptionManager.unlock(crowdServer, key.world(), cx, cz);
        }
        System.out.println("[hotspot] released region " + key +
                " (" + live.length + " live chunks) from crowd server " + crowdServer.getBungeeCordName());
    }

    /**
     * Pick the best server to take over a hot region. Two-phase scheduler:
     *
     *   <li>If {@code multipaper.hotspot.crowdServers} is set, restrict the
     *   candidate set to that explicit pool — useful when you have reserved
     *   spare-capacity pods. Then run the scoring scheduler over them.
     *
     *   <li>Otherwise (the homogeneous-Kubernetes default), the candidate set
     *   is every connected server. The scheduler scores each by load, TPS
     *   headroom, player count, and locality, then picks the highest score.
     *
     * Returns {@code null} when no candidate survives the filter — the
     * coordinator logs and skips the cycle in that case.
     */
    private static HotspotScheduler.Decision pickCrowdServer(RegionDensityTracker.RegionKey region) {
        ServerConnection currentOwner = ChunkSubscriptionManager.getOwner(
                region.world(), region.rx() * HotspotConfig.REGION_SIZE_CHUNKS, region.rz() * HotspotConfig.REGION_SIZE_CHUNKS);

        List<ServerConnection> candidates;
        if (CROWD_POOL.length > 0) {
            candidates = new java.util.ArrayList<>(CROWD_POOL.length);
            for (String name : CROWD_POOL) {
                ServerConnection sc = ServerConnection.getConnection(name);
                if (sc != null) candidates.add(sc);
            }
        } else {
            candidates = ServerConnection.getConnections();
        }

        return HotspotScheduler.pick(region, candidates, currentOwner, HotspotCoordinator::countActiveTransfers);
    }

    private static int countActiveTransfers(ServerConnection candidate) {
        int count = 0;
        for (ActiveTransfer at : active.values()) {
            if (at.crowdServer() == candidate) count++;
        }
        return count;
    }

    /**
     * Snapshot current hot regions (above the view-shrink threshold) and push
     * them to every connected server so they can reduce per-player view and
     * simulation distance for clients in those regions. Empty list = restore
     * defaults. This is the cheap mitigation; runs alongside ownership
     * transfer and is not gated by dry-run since it only changes per-player
     * client streaming, not authoritative chunk state.
     */
    private static void broadcastHotRegions() {
        List<HotRegion> hot = RegionDensityTracker.hottestAbove(HotspotConfig.VIEW_SHRINK_THRESHOLD_PLAYERS);
        int n = hot.size();
        String[] worlds = new String[n];
        int[] rxs = new int[n];
        int[] rzs = new int[n];
        for (int i = 0; i < n; i++) {
            HotRegion r = hot.get(i);
            worlds[i] = r.world();
            rxs[i] = r.rx();
            rzs[i] = r.rz();
        }
        HotRegionsMessage msg = new HotRegionsMessage(
                HotspotConfig.REGION_SIZE_CHUNKS,
                HotspotConfig.DEFAULT_VIEW_DISTANCE, HotspotConfig.HOT_VIEW_DISTANCE,
                HotspotConfig.DEFAULT_SIMULATION_DISTANCE, HotspotConfig.HOT_SIMULATION_DISTANCE,
                worlds, rxs, rzs);
        ServerConnection.broadcastAll(msg);
    }

    /** Drop any active-transfer entries owned by a disconnecting server. */
    public static void forgetServer(ServerConnection server) {
        active.entrySet().removeIf(e -> e.getValue().crowdServer() == server);
    }

    private static String[] parsePool(String raw) {
        if (raw == null || raw.isBlank()) return new String[0];
        String[] split = raw.split(",");
        for (int i = 0; i < split.length; i++) {
            split[i] = split[i].trim();
        }
        return split;
    }
}
