package puregero.multipaper.mastermessagingprotocol.messages.serverbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

/**
 * Sent by the master to a designated crowd server to instruct it to take
 * ownership of every chunk in a region (16x16 chunks). The crowd server is
 * expected to:
 *   1. Subscribe to every chunk in {@code (rx*16 .. rx*16+15, rz*16 .. rz*16+15)}
 *      so it receives current state from the previous owners.
 *   2. Lock each chunk via {@code LockChunkMessage} with the force flag, which
 *      causes the master to broadcast the new owner to existing subscribers.
 *
 * Old owner servers do nothing special on receipt of the new
 * {@code SetChunkOwnerMessage} for each chunk — they already release ticking
 * responsibility on owner change.
 *
 * When the master is running in dry-run mode this message is never emitted;
 * candidates are only logged.
 */
public class TransferRegionOwnershipMessage extends ServerBoundMessage {

    public final String world;
    public final int rx;
    public final int rz;
    /** Region edge length in chunks, for forward compatibility with non-16 regions. */
    public final int regionSizeChunks;

    public TransferRegionOwnershipMessage(String world, int rx, int rz, int regionSizeChunks) {
        this.world = world;
        this.rx = rx;
        this.rz = rz;
        this.regionSizeChunks = regionSizeChunks;
    }

    public TransferRegionOwnershipMessage(ExtendedByteBuf byteBuf) {
        this.world = byteBuf.readString();
        this.rx = byteBuf.readInt();
        this.rz = byteBuf.readInt();
        this.regionSizeChunks = byteBuf.readVarInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(rx);
        byteBuf.writeInt(rz);
        byteBuf.writeVarInt(regionSizeChunks);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
