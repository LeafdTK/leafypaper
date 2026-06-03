package puregero.multipaper.mastermessagingprotocol.messages.serverbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

/**
 * Broadcast by the master at the hotspot-loop cadence. Carries the current
 * list of hot regions and the view/simulation distances servers should apply
 * to players inside them. Empty list = no hot regions, restore defaults.
 *
 * Wire format: regionSize (varint), default view (varint), hot view (varint),
 * default sim (varint), hot sim (varint), then [world, rx, rz] tuples.
 */
public class HotRegionsMessage extends ServerBoundMessage {

    public final int regionSizeChunks;
    public final int defaultViewDistance;
    public final int hotViewDistance;
    public final int defaultSimulationDistance;
    public final int hotSimulationDistance;
    public final String[] worlds;
    public final int[] rx;
    public final int[] rz;

    public HotRegionsMessage(int regionSizeChunks,
                             int defaultViewDistance, int hotViewDistance,
                             int defaultSimulationDistance, int hotSimulationDistance,
                             String[] worlds, int[] rx, int[] rz) {
        this.regionSizeChunks = regionSizeChunks;
        this.defaultViewDistance = defaultViewDistance;
        this.hotViewDistance = hotViewDistance;
        this.defaultSimulationDistance = defaultSimulationDistance;
        this.hotSimulationDistance = hotSimulationDistance;
        this.worlds = worlds;
        this.rx = rx;
        this.rz = rz;
    }

    public HotRegionsMessage(ExtendedByteBuf byteBuf) {
        this.regionSizeChunks = byteBuf.readVarInt();
        this.defaultViewDistance = byteBuf.readVarInt();
        this.hotViewDistance = byteBuf.readVarInt();
        this.defaultSimulationDistance = byteBuf.readVarInt();
        this.hotSimulationDistance = byteBuf.readVarInt();
        int n = byteBuf.readVarInt();
        this.worlds = new String[n];
        this.rx = new int[n];
        this.rz = new int[n];
        for (int i = 0; i < n; i++) {
            this.worlds[i] = byteBuf.readString();
            this.rx[i] = byteBuf.readInt();
            this.rz[i] = byteBuf.readInt();
        }
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeVarInt(regionSizeChunks);
        byteBuf.writeVarInt(defaultViewDistance);
        byteBuf.writeVarInt(hotViewDistance);
        byteBuf.writeVarInt(defaultSimulationDistance);
        byteBuf.writeVarInt(hotSimulationDistance);
        byteBuf.writeVarInt(worlds.length);
        for (int i = 0; i < worlds.length; i++) {
            byteBuf.writeString(worlds[i]);
            byteBuf.writeInt(rx[i]);
            byteBuf.writeInt(rz[i]);
        }
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
