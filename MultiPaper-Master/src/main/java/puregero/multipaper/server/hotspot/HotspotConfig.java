package puregero.multipaper.server.hotspot;

/**
 * Static hotspot-offload configuration. Sourced from JVM system properties
 * so it can be tuned without touching the master's main config file. All
 * properties default to values that keep the feature inert (threshold
 * effectively unreachable) so existing deployments are unaffected until an
 * operator opts in.
 */
public final class HotspotConfig {

    private HotspotConfig() {}

    /** Number of chunks per side in a region. Default 16 = 256 blocks. */
    public static final int REGION_SIZE_CHUNKS = Integer.getInteger(
            "multipaper.hotspot.regionSizeChunks", 16);

    /** Minimum combined player count in a region to flag it as a hotspot. */
    public static final int THRESHOLD_PLAYERS = Integer.getInteger(
            "multipaper.hotspot.thresholdPlayers", 80);

    /** Minimum seconds between consecutive offload triggers for the same region. */
    public static final int COOLDOWN_SECONDS = Integer.getInteger(
            "multipaper.hotspot.cooldownSeconds", 60);

    /**
     * Hysteresis lower bound: a transferred region is released back to its
     * previous owners when its combined population falls at or below this for
     * {@link #RELEASE_HOLD_SECONDS} consecutive seconds. Sit comfortably
     * below {@link #THRESHOLD_PLAYERS} so we don't flap.
     */
    public static final int RELEASE_THRESHOLD_PLAYERS = Integer.getInteger(
            "multipaper.hotspot.releaseThresholdPlayers", Math.max(1, THRESHOLD_PLAYERS / 2));

    /** How long the release threshold must be met before the region is actually released. */
    public static final int RELEASE_HOLD_SECONDS = Integer.getInteger(
            "multipaper.hotspot.releaseHoldSeconds", 30);

    /** Logging-only mode: scoring runs but no ownership transfer is issued. Default on while transfer protocol matures. */
    public static final boolean DRY_RUN = Boolean.parseBoolean(
            System.getProperty("multipaper.hotspot.dryRun", "true"));

    /**
     * Density-threshold for advertising a region as "hot" to game servers for
     * view-distance shrinking. Separate from {@link #THRESHOLD_PLAYERS} so the
     * shrink fires earlier than the ownership-transfer trigger — the cheaper
     * mitigation should kick in first.
     */
    public static final int VIEW_SHRINK_THRESHOLD_PLAYERS = Integer.getInteger(
            "multipaper.hotspot.viewShrinkThresholdPlayers", Math.max(20, THRESHOLD_PLAYERS / 2));

    public static final int DEFAULT_VIEW_DISTANCE = Integer.getInteger(
            "multipaper.hotspot.defaultViewDistance", 10);
    public static final int HOT_VIEW_DISTANCE = Integer.getInteger(
            "multipaper.hotspot.hotViewDistance", 4);
    public static final int DEFAULT_SIMULATION_DISTANCE = Integer.getInteger(
            "multipaper.hotspot.defaultSimulationDistance", 10);
    public static final int HOT_SIMULATION_DISTANCE = Integer.getInteger(
            "multipaper.hotspot.hotSimulationDistance", 4);

    /** chunk_x &gt;&gt; regionShift converts chunk coords to region coords. */
    public static int regionShift() {
        // log2(REGION_SIZE_CHUNKS), assuming power-of-two region size.
        int s = REGION_SIZE_CHUNKS;
        int shift = 0;
        while (s > 1) {
            s >>= 1;
            shift++;
        }
        return shift;
    }
}
