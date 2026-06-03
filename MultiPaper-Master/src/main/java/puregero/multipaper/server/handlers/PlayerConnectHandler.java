package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PlayerConnectMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.server.ServerConnection;

public class PlayerConnectHandler {
    public static void handle(ServerConnection connection, PlayerConnectMessage message) {
        // O(1) atomic claim via the global UUID -> owner index. Replaces the old
        // synchronized-list scan that serialised every player join across the
        // whole cluster.
        boolean claimed = connection.claimPlayer(message.uuid);
        connection.sendReply(new BooleanMessageReply(claimed), message);
    }
}
