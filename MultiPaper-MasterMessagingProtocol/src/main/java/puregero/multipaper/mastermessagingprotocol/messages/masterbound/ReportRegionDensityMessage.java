package puregero.multipaper.mastermessagingprotocol.messages.masterbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

/**
 * Sent by a game server to the master to report how many players are currently
 * within a given region (16x16 chunks = 256x256 blocks). The master aggregates
 * these reports across servers to detect hotspots — regions whose combined
 * player count exceeds a configured threshold — and triggers ownership
 * transfer to a dedicated crowd server when needed.
 *
 * Sent periodically (every {@code hotspotOffload.reportIntervalTicks} ticks)
 * and only for regions whose count has changed since the last report.
 */
public class ReportRegionDensityMessage extends MasterBoundMessage {

    public final String world;
    /** Region X coordinate (chunk_x &gt;&gt; 4). */
    public final int rx;
    /** Region Z coordinate (chunk_z &gt;&gt; 4). */
    public final int rz;
    /** Number of real players this server has inside the region. */
    public final int playerCount;

    public ReportRegionDensityMessage(String world, int rx, int rz, int playerCount) {
        this.world = world;
        this.rx = rx;
        this.rz = rz;
        this.playerCount = playerCount;
    }

    public ReportRegionDensityMessage(ExtendedByteBuf byteBuf) {
        this.world = byteBuf.readString();
        this.rx = byteBuf.readInt();
        this.rz = byteBuf.readInt();
        this.playerCount = byteBuf.readVarInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(rx);
        byteBuf.writeInt(rz);
        byteBuf.writeVarInt(playerCount);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
