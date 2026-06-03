package puregero.multipaper.server.hotspot;

import puregero.multipaper.mastermessagingprotocol.messages.serverbound.TransferRegionOwnershipMessage;
import puregero.multipaper.server.ServerConnection;
import puregero.multipaper.server.hotspot.RegionDensityTracker.HotRegion;

import java.util.HashMap;
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

    private static volatile ScheduledExecutorService scheduler;

    /** Wire the loop up. Called once from the master bootstrap. */
    public static synchronized void start() {
        if (scheduler != null) return;
        if (CROWD_POOL.length == 0 && !HotspotConfig.DRY_RUN) {
            System.out.println("[hotspot] coordinator: no crowd servers configured, staying in dry-run logging only");
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

            ServerConnection crowd = pickCrowdServer();
            if (crowd == null) {
                System.out.println("[hotspot] no eligible crowd server for region " + key + " (total=" + region.total() + ")");
                return;
            }

            if (HotspotConfig.DRY_RUN) {
                System.out.println("[hotspot] DRY-RUN would transfer region " + key +
                        " (total=" + region.total() + ") to crowd server " + crowd.getBungeeCordName());
            } else {
                crowd.send(new TransferRegionOwnershipMessage(region.world(), region.rx(), region.rz(),
                        HotspotConfig.REGION_SIZE_CHUNKS));
                System.out.println("[hotspot] transferred region " + key +
                        " (total=" + region.total() + ") to crowd server " + crowd.getBungeeCordName());
            }
            lastTransferAt.put(key, now);
            return; // one transfer per tick — observe its effect before issuing more
        }
    }

    private static ServerConnection pickCrowdServer() {
        for (String name : CROWD_POOL) {
            ServerConnection candidate = ServerConnection.getConnection(name);
            if (candidate != null && candidate.isOnline()) {
                return candidate;
            }
        }
        return null;
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
