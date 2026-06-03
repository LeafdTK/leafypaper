package puregero.multipaper.mastermessagingprotocol.messages.masterbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

/**
 * Batched subscribe: subscribe the sending server to every chunk in the
 * inclusive rectangle {@code [cxLow..cxHigh] x [czLow..czHigh]}. Replaces
 * up to {@code (cxHigh-cxLow+1) * (czHigh-czLow+1)} individual
 * {@link SubscribeChunkMessage}s with a single packet — the dominant
 * inbound traffic shape at 300-in-a-region scale.
 *
 * Master-side fan-out to existing subscribers of each chunk still happens
 * per-chunk for now; only the upstream (server-to-master) direction is
 * collapsed by this message.
 */
public class SubscribeRegionMessage extends MasterBoundMessage {

    public final String world;
    public final int cxLow;
    public final int cxHigh;
    public final int czLow;
    public final int czHigh;

    public SubscribeRegionMessage(String world, int cxLow, int cxHigh, int czLow, int czHigh) {
        this.world = world;
        this.cxLow = cxLow;
        this.cxHigh = cxHigh;
        this.czLow = czLow;
        this.czHigh = czHigh;
    }

    public SubscribeRegionMessage(ExtendedByteBuf byteBuf) {
        this.world = byteBuf.readString();
        this.cxLow = byteBuf.readInt();
        this.cxHigh = byteBuf.readInt();
        this.czLow = byteBuf.readInt();
        this.czHigh = byteBuf.readInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(cxLow);
        byteBuf.writeInt(cxHigh);
        byteBuf.writeInt(czLow);
        byteBuf.writeInt(czHigh);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
