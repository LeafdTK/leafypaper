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
    /**
     * Optional explicit chunk coordinates to claim. Packed as
     * {@code (cx << 32) | (cz & 0xFFFFFFFFL)}. When non-empty the receiver
     * claims only these chunks instead of every chunk in the region.
     *
     * Backward-compatible: older senders don't write the trailing length and
     * older readers see EOF and default this to an empty array — which the
     * receiver treats as "claim the whole region", preserving the old
     * behavior.
     */
    public final long[] chunks;

    public TransferRegionOwnershipMessage(String world, int rx, int rz, int regionSizeChunks) {
        this(world, rx, rz, regionSizeChunks, EMPTY_CHUNKS);
    }

    public TransferRegionOwnershipMessage(String world, int rx, int rz, int regionSizeChunks, long[] chunks) {
        this.world = world;
        this.rx = rx;
        this.rz = rz;
        this.regionSizeChunks = regionSizeChunks;
        this.chunks = chunks;
    }

    public TransferRegionOwnershipMessage(ExtendedByteBuf byteBuf) {
        this.world = byteBuf.readString();
        this.rx = byteBuf.readInt();
        this.rz = byteBuf.readInt();
        this.regionSizeChunks = byteBuf.readVarInt();
        if (byteBuf.isReadable()) {
            int n = byteBuf.readVarInt();
            this.chunks = new long[n];
            for (int i = 0; i < n; i++) this.chunks[i] = byteBuf.readLong();
        } else {
            this.chunks = EMPTY_CHUNKS;
        }
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(rx);
        byteBuf.writeInt(rz);
        byteBuf.writeVarInt(regionSizeChunks);
        if (chunks.length > 0) {
            byteBuf.writeVarInt(chunks.length);
            for (long c : chunks) byteBuf.writeLong(c);
        }
        // else: skip the trailing field entirely so the wire stays the same
        // length as the pre-extension format for receivers that don't read
        // beyond the regionSizeChunks varint.
    }

    private static final long[] EMPTY_CHUNKS = new long[0];

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
