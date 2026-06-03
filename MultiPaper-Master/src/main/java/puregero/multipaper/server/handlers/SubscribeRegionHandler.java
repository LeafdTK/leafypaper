package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.SubscribeRegionMessage;
import puregero.multipaper.server.ChunkSubscriptionManager;
import puregero.multipaper.server.ServerConnection;

public class SubscribeRegionHandler {
    public static void handle(ServerConnection connection, SubscribeRegionMessage message) {
        ChunkSubscriptionManager.subscribeRegion(connection, message.world,
                message.cxLow, message.cxHigh, message.czLow, message.czHigh);
    }
}
