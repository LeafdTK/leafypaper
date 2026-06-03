package puregero.multipaper.mastermessagingprotocol.messages.serverbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

/**
 * Master -> new chunk owner: inject these entities into chunk (world, cx, cz)
 * before resuming tick. Carries the NBT blob the master previously collected
 * from the old owner via
 * {@link puregero.multipaper.mastermessagingprotocol.messages.masterbound.EntitiesForHandoffMessage}.
 *
 * The receiver must:
 *   1. Pause ticking of the chunk (it's just been force-locked to this server).
 *   2. Deserialise entities from {@code entityNbt} and add them to the chunk
 *      with their AI/combat state intact.
 *   3. Resume ticking.
 *
 * Empty {@code entityNbt} -> no-op (old owner had no live entities here).
 */
public class TransferEntitiesMessage extends ServerBoundMessage {

    public final String world;
    public final int cx;
    public final int cz;
    public final byte[] entityNbt;

    public TransferEntitiesMessage(String world, int cx, int cz, byte[] entityNbt) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.entityNbt = entityNbt;
    }

    public TransferEntitiesMessage(ExtendedByteBuf byteBuf) {
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
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
