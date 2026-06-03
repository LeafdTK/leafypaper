package puregero.multipaper.server;

import puregero.multipaper.mastermessagingprotocol.ChunkKey;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.AddChunkSubscriberMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ChunkSubscribersSyncMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.RemoveChunkSubscriberMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.SetChunkOwnerMessage;
import puregero.multipaper.server.util.ChunkLock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkSubscriptionManager {

    // Index 0 in the list is the current chunk owner.
    private static final Map<ChunkKey, List<ServerConnection>> chunkLocks = new ConcurrentHashMap<>();
    private static final Map<ChunkKey, List<ServerConnection>> chunkSubscribers = new ConcurrentHashMap<>();

    // Per-server set of chunks they hold a lock on or a subscription to. Must be
    // ConcurrentHashMap (previously plain HashMap) because they are read from
    // both inside the stripe locks and from unsubscribeAndUnlockAll which runs
    // outside any stripe lock.
    private static final Map<ServerConnection, Set<ChunkKey>> lockedChunks = new ConcurrentHashMap<>();
    private static final Map<ServerConnection, Set<ChunkKey>> subscribedChunks = new ConcurrentHashMap<>();

    public static ServerConnection getOwner(String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkLocks.get(key);
            if (serverConnections != null && !serverConnections.isEmpty()) {
                return serverConnections.get(0);
            }
        }
        return null;
    }

    public static ServerConnection getOwnerOrSubscriber(String world, int cx, int cz) {
        ServerConnection owner = getOwner(world, cx, cz);
        if (owner != null) {
            return owner;
        }

        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkSubscribers.get(key);
            if (serverConnections != null && !serverConnections.isEmpty()) {
                return serverConnections.get(0);
            }
        }

        return null;
    }

    public static ServerConnection lock(ServerConnection serverConnection, String world, int cx, int cz) {
        return lock(serverConnection, world, cx, cz, false);
    }

    public static ServerConnection lock(ServerConnection serverConnection, String world, int cx, int cz, boolean force) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        ServerConnection currentOwner;
        // Captured under the lock, flushed afterward.
        List<ServerConnection> ownerNotifyTargets = null;
        String ownerNotifyName = null;

        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkLocks.computeIfAbsent(key, k -> new ArrayList<>());

            if (force && serverConnections.indexOf(serverConnection) != 0) {
                serverConnections.remove(serverConnection);
            }

            if (!serverConnections.contains(serverConnection)) {
                if (force) {
                    serverConnections.add(0, serverConnection);
                } else {
                    serverConnections.add(serverConnection);
                }

                lockedChunks.computeIfAbsent(serverConnection, k -> ConcurrentHashMap.newKeySet()).add(key);

                if (serverConnections.size() == 1 || force) {
                    List<ServerConnection> subscribers = chunkSubscribers.get(key);
                    if (subscribers != null && !subscribers.isEmpty()) {
                        ownerNotifyTargets = new ArrayList<>(subscribers);
                        ownerNotifyName = serverConnections.get(0).getBungeeCordName();
                    }
                }
            }

            currentOwner = serverConnections.get(0);
        }

        if (ownerNotifyTargets != null) {
            broadcastOwner(ownerNotifyTargets, ownerNotifyName, world, cx, cz);
        }
        return currentOwner;
    }

    public static void unlock(ServerConnection serverConnection, String world, int cx, int cz) {
        unlock(serverConnection, new ChunkKey(world, cx, cz));
    }

    public static void unlock(ServerConnection serverConnection, ChunkKey key) {
        // Captured under the lock, flushed afterward.
        List<ServerConnection> ownerNotifyTargets = null;
        String ownerNotifyName = null;
        boolean ownerChanged = false;

        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkLocks.get(key);
            if (serverConnections != null) {
                for (int i = 0; i < serverConnections.size(); i++) {
                    if (serverConnections.get(i) == serverConnection) {
                        serverConnections.remove(i--);

                        if (i == -1) {
                            List<ServerConnection> subscribers = chunkSubscribers.get(key);
                            if (subscribers != null && !subscribers.isEmpty()) {
                                ownerNotifyTargets = new ArrayList<>(subscribers);
                                ownerNotifyName = serverConnections.isEmpty()
                                        ? ""
                                        : serverConnections.get(0).getBungeeCordName();
                                ownerChanged = true;
                            }
                        }
                    }
                }
                if (serverConnections.isEmpty()) {
                    chunkLocks.remove(key);
                }
            }

            Set<ChunkKey> chunks = lockedChunks.get(serverConnection);
            if (chunks != null) {
                chunks.remove(key);
            }
        }

        if (ownerChanged) {
            broadcastOwner(ownerNotifyTargets, ownerNotifyName, key.world, key.x, key.z);
        }
    }

    /// Send a SetChunkOwnerMessage to every connection in `targets`. Caller
    /// must invoke this outside the stripe lock; the targets list must be a
    /// snapshot taken under the lock.
    private static void broadcastOwner(List<ServerConnection> targets, String ownerName, String world, int cx, int cz) {
        SetChunkOwnerMessage message = new SetChunkOwnerMessage(world, cx, cz, ownerName);
        for (ServerConnection connection : targets) {
            connection.send(message);
        }
    }

    public static void subscribe(ServerConnection serverConnection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        // Captured under the lock, flushed afterward.
        List<ServerConnection> notifyExistingOfNewSubscriber = null;
        List<ServerConnection> notifyNewOfExistingSubscribers = null;
        ServerConnection ownerToTellNewSubscriber = null;

        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkSubscribers.computeIfAbsent(key, k -> new ArrayList<>());

            // First-time subscriber for this chunk: tell every existing
            // subscriber about us, and tell us about every existing one.
            if (!serverConnections.contains(serverConnection)) {
                if (!serverConnections.isEmpty()) {
                    notifyExistingOfNewSubscriber = new ArrayList<>(serverConnections);
                    notifyNewOfExistingSubscribers = new ArrayList<>(serverConnections);
                }
                serverConnections.add(serverConnection);
                subscribedChunks.computeIfAbsent(serverConnection, k -> ConcurrentHashMap.newKeySet()).add(key);
            }

            List<ServerConnection> owners = chunkLocks.get(key);
            if (owners != null && !owners.isEmpty()) {
                ownerToTellNewSubscriber = owners.get(0);
            }
        }

        // All sends happen outside the lock. Order within a single subscribe
        // call is preserved; ordering between concurrent subscribes is not
        // (eventually-consistent receivers reconstruct from add/remove ops).
        if (notifyExistingOfNewSubscriber != null) {
            String newSubscriberName = serverConnection.getBungeeCordName();
            AddChunkSubscriberMessage addMsg = new AddChunkSubscriberMessage(world, cx, cz, newSubscriberName);
            for (ServerConnection existing : notifyExistingOfNewSubscriber) {
                if (existing != serverConnection) {
                    existing.send(addMsg);
                }
            }
        }
        if (notifyNewOfExistingSubscribers != null) {
            for (ServerConnection existing : notifyNewOfExistingSubscribers) {
                if (existing != serverConnection) {
                    serverConnection.send(new AddChunkSubscriberMessage(world, cx, cz, existing.getBungeeCordName()));
                }
            }
        }
        if (ownerToTellNewSubscriber != null) {
            serverConnection.send(new SetChunkOwnerMessage(world, cx, cz, ownerToTellNewSubscriber.getBungeeCordName()));
        }
    }

    public static void unsubscribe(ServerConnection serverConnection, String world, int cx, int cz) {
        unsubscribe(serverConnection, new ChunkKey(world, cx, cz));
    }

    public static void unsubscribe(ServerConnection serverConnection, ChunkKey key) {
        List<ServerConnection> notifyRemove = null;

        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkSubscribers.get(key);
            if (serverConnections != null) {
                if (serverConnections.remove(serverConnection) && !serverConnections.isEmpty()) {
                    notifyRemove = new ArrayList<>(serverConnections);
                }
                if (serverConnections.isEmpty()) {
                    chunkSubscribers.remove(key);
                }
            }

            Set<ChunkKey> chunks = subscribedChunks.get(serverConnection);
            if (chunks != null) {
                chunks.remove(key);
            }
        }

        if (notifyRemove != null) {
            String unsubscriberName = serverConnection.getBungeeCordName();
            RemoveChunkSubscriberMessage removeMsg = new RemoveChunkSubscriberMessage(key.world, key.x, key.z, unsubscriberName);
            for (ServerConnection connection : notifyRemove) {
                connection.send(removeMsg);
            }
        }
    }

    public static void syncSubscribers(ServerConnection serverConnection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        boolean needSubscribe;
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> subscribers = chunkSubscribers.get(key);
            needSubscribe = subscribers == null || !subscribers.contains(serverConnection);
        }

        if (needSubscribe) {
            // subscribe() handles its own stripe-lock acquire + outside-lock fan-out.
            subscribe(serverConnection, world, cx, cz);
        }

        ChunkSubscribersSyncMessage reply;
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> subscribers = chunkSubscribers.get(key);
            String[] subscriberNames = subscribers == null
                    ? new String[0]
                    : subscribers.stream()
                            .filter(s -> s != serverConnection)
                            .map(ServerConnection::getBungeeCordName)
                            .toArray(String[]::new);
            List<ServerConnection> owners = chunkLocks.get(key);
            String ownerName = (owners != null && !owners.isEmpty()) ? owners.get(0).getBungeeCordName() : "";
            reply = new ChunkSubscribersSyncMessage(world, cx, cz, ownerName, subscriberNames);
        }
        serverConnection.send(reply);
    }

    public static void unsubscribeAndUnlockAll(ServerConnection serverConnection) {
        Set<ChunkKey> locked = lockedChunks.remove(serverConnection);
        if (locked != null) {
            locked.forEach(chunk -> unlock(serverConnection, chunk));
        }
        Set<ChunkKey> subscribed = subscribedChunks.remove(serverConnection);
        if (subscribed != null) {
            subscribed.forEach(chunk -> unsubscribe(serverConnection, chunk));
        }
    }

    public static List<ServerConnection> getSubscribers(String world, int cx, int cz) {
        return chunkSubscribers.getOrDefault(new ChunkKey(world, cx, cz), Collections.emptyList());
    }

    /**
     * Snapshot every chunk in {@code (world, cxLow..cxHigh, czLow..czHigh)}
     * that currently has an owner. Used by hotspot offload to figure out
     * which chunks in a region are worth transferring (chunks no one owns
     * have no state to move and would only generate empty lock churn).
     *
     * Coordinates are returned packed as {@code (cx << 32) | (cz & 0xFFFFFFFFL)}.
     */
    public static long[] lockedChunksInRange(String world, int cxLow, int cxHigh, int czLow, int czHigh) {
        // Iterate the map rather than scanning the (potentially huge) range:
        // chunkLocks is bounded by "chunks loaded somewhere right now".
        List<Long> hits = new ArrayList<>();
        chunkLocks.forEach((key, owners) -> {
            if (owners == null || owners.isEmpty()) return;
            if (!key.world.equals(world)) return;
            if (key.x < cxLow || key.x > cxHigh) return;
            if (key.z < czLow || key.z > czHigh) return;
            hits.add(((long) key.x << 32) | (key.z & 0xFFFFFFFFL));
        });
        long[] out = new long[hits.size()];
        for (int i = 0; i < hits.size(); i++) out[i] = hits.get(i);
        return out;
    }
}
