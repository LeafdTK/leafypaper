package puregero.multipaper.server.hotspot;

import puregero.multipaper.mastermessagingprotocol.ChunkKey;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.EntitiesForHandoffMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.RequestEntitiesForHandoffMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.TransferEntitiesMessage;
import puregero.multipaper.server.ServerConnection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates per-chunk entity handoff during hotspot region transfer.
 *
 * Flow:
 *   1. {@link #beginHandoff} (called from the hotspot transfer path before
 *      the new owner force-locks the chunk) sends
 *      {@link RequestEntitiesForHandoffMessage} to the OLD owner.
 *   2. Old owner serialises live entities (AI/combat state included) and
 *      replies with {@link EntitiesForHandoffMessage}; the master forwards
 *      it as a {@link TransferEntitiesMessage} to the new owner.
 *   3. New owner injects entities into the chunk before resuming tick.
 *
 * If the old owner doesn't reply within {@link #TIMEOUT_MS}, the pending
 * entry is dropped (the new owner falls back to re-loading entities from
 * disk NBT — vanilla behaviour, with combat state loss).
 *
 * The actual entity NBT serialisation and injection live in server-side
 * patches; today the master happily orchestrates against handlers that
 * default to no-op, which means the protocol is exercise-able end-to-end
 * with logging-only behaviour on the game servers.
 */
public final class EntityHandoffCoordinator {

    private EntityHandoffCoordinator() {}

    private static final long TIMEOUT_MS = Long.getLong("multipaper.hotspot.entityHandoffTimeoutMs", 2000L);

    private static final Map<ChunkKey, Pending> pending = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "entity-handoff-timeout");
                t.setDaemon(true);
                return t;
            });

    private record Pending(ServerConnection newOwner, long deadlineMs) {}

    /**
     * Start a per-chunk handoff. Sends the request to {@code oldOwner};
     * records {@code newOwner} as the target for the eventual entity blob.
     * Idempotent — second call for the same chunk just refreshes the target.
     */
    public static void beginHandoff(ServerConnection oldOwner, ServerConnection newOwner,
                                    String world, int cx, int cz) {
        if (oldOwner == null || newOwner == null || oldOwner == newOwner) return;

        ChunkKey key = new ChunkKey(world, cx, cz);
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        pending.put(key, new Pending(newOwner, deadline));

        oldOwner.send(new RequestEntitiesForHandoffMessage(world, cx, cz));

        timeoutScheduler.schedule(() -> {
            Pending p = pending.get(key);
            if (p != null && p.deadlineMs() <= System.currentTimeMillis()) {
                pending.remove(key, p);
            }
        }, TIMEOUT_MS + 50, TimeUnit.MILLISECONDS);
    }

    /**
     * Called from {@code WriteEntitiesForHandoffHandler} when the old owner's
     * reply lands. Forwards the blob to the recorded new owner.
     */
    public static void onEntityBlob(EntitiesForHandoffMessage message) {
        ChunkKey key = new ChunkKey(message.world, message.cx, message.cz);
        Pending p = pending.remove(key);
        if (p == null) {
            return; // timed out or duplicate reply
        }
        if (!p.newOwner().isOnline()) {
            return;
        }
        p.newOwner().send(new TransferEntitiesMessage(message.world, message.cx, message.cz, message.entityNbt));
    }

    /** Drop pending entries targeted at a disconnecting server. */
    public static void forgetServer(ServerConnection server) {
        pending.entrySet().removeIf(e -> e.getValue().newOwner() == server);
    }
}
