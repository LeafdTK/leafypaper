package puregero.multipaper.mastermessagingprotocol.messages.masterbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

public class LockChunkMessage extends MasterBoundMessage {

    public final String world;
    public final int cx;
    public final int cz;
    /**
     * When true, the master should reassign ownership to the sender even if
     * another server currently holds the lock — used by hotspot offload to
     * pull a chunk away from its previous owner. Wire-compatible trailing
     * field: older senders don't write it, older readers default to false.
     */
    public final boolean force;

    public LockChunkMessage(String world, int cx, int cz) {
        this(world, cx, cz, false);
    }

    public LockChunkMessage(String world, int cx, int cz, boolean force) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.force = force;
    }

    public LockChunkMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        cx = byteBuf.readInt();
        cz = byteBuf.readInt();
        force = byteBuf.isReadable() && byteBuf.readBoolean();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(cx);
        byteBuf.writeInt(cz);
        // Only emit the trailing byte when set so steady-state (force=false)
        // lock messages stay byte-identical to the pre-extension format.
        if (force) {
            byteBuf.writeBoolean(true);
        }
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
