package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReportRegionDensityMessage;
import puregero.multipaper.server.ServerConnection;
import puregero.multipaper.server.hotspot.HotspotConfig;
import puregero.multipaper.server.hotspot.RegionDensityTracker;
import puregero.multipaper.server.hotspot.RegionDensityTracker.HotRegion;

import java.util.List;

public class ReportRegionDensityHandler {

    public static void handle(ServerConnection connection, ReportRegionDensityMessage message) {
        RegionDensityTracker.report(message.world, message.rx, message.rz, connection, message.playerCount);

        // Threshold check lives here for the moment — it's a constant-time
        // scan over hot cells (which is empty in normal operation). When the
        // transfer protocol lands we'll hand the candidates to an offload
        // coordinator instead of just logging.
        List<HotRegion> hot = RegionDensityTracker.hottestAbove(HotspotConfig.THRESHOLD_PLAYERS);
        if (!hot.isEmpty()) {
            HotRegion top = hot.get(0);
            if (HotspotConfig.DRY_RUN) {
                System.out.println("[hotspot] region " + top.world() + " (" + top.rx() + ", " + top.rz() + ") " +
                        "has " + top.total() + " players across " + hot.size() + " hot region(s) " +
                        "(dry-run; transfer disabled)");
            }
        }
    }
}
