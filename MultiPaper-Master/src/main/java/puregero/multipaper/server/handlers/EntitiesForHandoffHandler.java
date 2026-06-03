package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.EntitiesForHandoffMessage;
import puregero.multipaper.server.ServerConnection;
import puregero.multipaper.server.hotspot.EntityHandoffCoordinator;

public class EntitiesForHandoffHandler {
    public static void handle(ServerConnection connection, EntitiesForHandoffMessage message) {
        EntityHandoffCoordinator.onEntityBlob(message);
    }
}
