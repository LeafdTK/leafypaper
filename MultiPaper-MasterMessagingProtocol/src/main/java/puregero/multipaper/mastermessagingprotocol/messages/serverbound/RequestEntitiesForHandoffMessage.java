package puregero.multipaper.mastermessagingprotocol.messages.serverbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

/**
 * Master -> previous chunk owner: "serialise the live entities currently
 * resident in chunk (world, cx, cz) and reply with
 * {@link puregero.multipaper.mastermessagingprotocol.messages.masterbound.EntitiesForHandoffMessage}".
 *
 * Issued at the start of a hotspot ownership transfer so mid-combat AI state
 * (target, cooldowns, equipment, momentum) survives the handoff instead of
 * being lost when the new owner re-loads the chunk from disk NBT.
 *
 * The {@code transactionId} echoes back in the reply so the master can route
 * the entity blob to the correct pending new-owner injection.
 */
public class RequestEntitiesForHandoffMessage extends ServerBoundMessage {

    public final String world;
    public final int cx;
    public final int cz;

    public RequestEntitiesForHandoffMessage(String world, int cx, int cz) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
    }

    public RequestEntitiesForHandoffMessage(ExtendedByteBuf byteBuf) {
        this.world = byteBuf.readString();
        this.cx = byteBuf.readInt();
        this.cz = byteBuf.readInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(cx);
        byteBuf.writeInt(cz);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
