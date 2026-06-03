package puregero.multipaper.mastermessagingprotocol.messages.masterbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

/**
 * Previous chunk owner -> master: serialised entity NBT for the chunk
 * referenced by {@code transactionId}. Sent in reply to a
 * {@link puregero.multipaper.mastermessagingprotocol.messages.serverbound.RequestEntitiesForHandoffMessage}.
 *
 * The blob is opaque to the master — it's just forwarded to the new owner
 * in a {@link puregero.multipaper.mastermessagingprotocol.messages.serverbound.TransferEntitiesMessage}.
 * Empty {@code entityNbt} means "no live entities here, nothing to transfer".
 */
public class EntitiesForHandoffMessage extends MasterBoundMessage {

    public final String world;
    public final int cx;
    public final int cz;
    public final byte[] entityNbt;

    public EntitiesForHandoffMessage(String world, int cx, int cz, byte[] entityNbt) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.entityNbt = entityNbt;
    }

    public EntitiesForHandoffMessage(ExtendedByteBuf byteBuf) {
        this.world = byteBuf.readString();
        this.cx = byteBuf.readInt();
        this.cz = byteBuf.readInt();
        int n = byteBuf.readVarInt();
        this.entityNbt = new byte[n];
        byteBuf.readBytes(this.entityNbt);
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(cx);
        byteBuf.writeInt(cz);
        byteBuf.writeVarInt(entityNbt.length);
        byteBuf.writeBytes(entityNbt);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
