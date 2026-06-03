package puregero.multipaper.server.proxy;

import puregero.multipaper.server.ChunkSubscriptionManager;
import puregero.multipaper.server.ServerConnection;
import puregero.multipaper.server.hotspot.HotspotConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Coordinate / affinity aware proxy routing helper. Replaces the plain
 * "lowest tick time" scan inside {@link puregero.multipaper.server.velocity.MultiPaperVelocity}
 * and {@link puregero.multipaper.server.bungee.MultiPaperBungee}. Priority,
 * in order:
 *
 *   1. Sticky-session: if the player UUID is already registered as owned by
 *      a still-alive server in the master's player index, keep them there.
 *      Prevents flap when a player briefly disconnects mid-fight.
 *
 *   2. Pinned hot regions: if any region from
 *      {@code -Dmultipaper.hotspot.pinnedRegions} is currently being
 *      targeted, route into the crowd-server pool
 *      ({@code -Dmultipaper.hotspot.crowdServers}). This is the
 *      "final fight zone" affinity case — pre-assigned hot region, fixed
 *      pool of beefy servers absorbs it.
 *
 *   3. Chunk-owner affinity: if a last-known coordinate is supplied and the
 *      owning server for that chunk is alive, prefer it. Cuts chunk
 *      subscription churn at spawn-in.
 *
 *   4. Fallback: lowest tick time across all alive candidates.
 */
public final class ProxyRouter {

    private ProxyRouter() {}

    private static final PinnedRegion[] PINNED = parsePinned(HotspotConfig.PINNED_REGIONS_RAW);
    private static final Set<String> CROWD_POOL = parsePool(System.getProperty("multipaper.hotspot.crowdServers", ""));

    /**
     * @param player        player UUID being routed; may be {@code null} on first-time joiners
     * @param lastWorld     last-known world; may be {@code null}
     * @param lastChunkX    last-known chunk X; ignored if world is null
     * @param lastChunkZ    last-known chunk Z
     * @param candidateNames names of all servers the proxy considers reachable
     * @return the name of the chosen server, or {@code null} if nothing is alive
     */
    public static String pick(UUID player, String lastWorld, int lastChunkX, int lastChunkZ, List<String> candidateNames) {
        if (candidateNames.isEmpty()) return null;

        // 1. Sticky session
        if (player != null) {
            ServerConnection owner = ServerConnection.getPlayerOwner(player);
            if (owner != null && owner.isOnline() && candidateNames.contains(owner.getBungeeCordName())) {
                return owner.getBungeeCordName();
            }
        }

        // 2. Pinned region routing -> crowd pool
        if (PINNED.length > 0 && !CROWD_POOL.isEmpty() && lastWorld != null) {
            int rx = lastChunkX >> HotspotConfig.regionShift();
            int rz = lastChunkZ >> HotspotConfig.regionShift();
            if (matchesPinned(lastWorld, rx, rz)) {
                String pick = pickLowestTickFromPool(candidateNames, CROWD_POOL);
                if (pick != null) return pick;
            }
        }

        // 3. Chunk-owner affinity
        if (lastWorld != null) {
            ServerConnection owner = ChunkSubscriptionManager.getOwner(lastWorld, lastChunkX, lastChunkZ);
            if (owner != null && owner.isOnline() && candidateNames.contains(owner.getBungeeCordName())) {
                return owner.getBungeeCordName();
            }
        }

        // 4. Lowest tick time fallback
        return pickLowestTickFromPool(candidateNames, null);
    }

    private static String pickLowestTickFromPool(List<String> candidateNames, Set<String> restrictTo) {
        String best = null;
        long bestTick = Long.MAX_VALUE;
        for (String name : candidateNames) {
            if (restrictTo != null && !restrictTo.contains(name)) continue;
            ServerConnection conn = ServerConnection.getConnection(name);
            if (conn == null || !ServerConnection.isAlive(name)) continue;
            long tick = conn.getTimer().averageInMillis();
            if (tick < bestTick) {
                bestTick = tick;
                best = name;
            }
        }
        return best;
    }

    private static boolean matchesPinned(String world, int rx, int rz) {
        for (PinnedRegion p : PINNED) {
            if (p.world.equals(world) && p.rx == rx && p.rz == rz) return true;
        }
        return false;
    }

    private record PinnedRegion(String world, int rx, int rz) {}

    private static PinnedRegion[] parsePinned(String raw) {
        if (raw == null || raw.isBlank()) return new PinnedRegion[0];
        String[] parts = raw.split("\\|");
        List<PinnedRegion> list = new ArrayList<>(parts.length);
        for (String part : parts) {
            int colon = part.indexOf(':');
            int comma = part.indexOf(',', colon + 1);
            if (colon < 0 || comma < 0) continue;
            try {
                String world = part.substring(0, colon).trim();
                int rx = Integer.parseInt(part.substring(colon + 1, comma).trim());
                int rz = Integer.parseInt(part.substring(comma + 1).trim());
                list.add(new PinnedRegion(world, rx, rz));
            } catch (NumberFormatException ignored) {}
        }
        return list.toArray(new PinnedRegion[0]);
    }

    private static Set<String> parsePool(String raw) {
        if (raw == null || raw.isBlank()) return java.util.Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
