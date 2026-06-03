# Mechanic Risk Tracker

Running log of performance/architecture changes that could *potentially* alter vanilla or Purpur gameplay behavior. Each entry: what changed, why it's a possible mechanic risk, severity guess, and what to test.

Severity scale:
- **none** — code change is internal plumbing; behavior provably unchanged
- **low** — timing or ordering change at sub-tick scale; unlikely to be observable
- **medium** — change affects cross-server ordering or could shift tick alignment
- **high** — change alters the order/visibility of game events; needs manual playtesting

---

## 2026-06-03 — Tier 0 item 1: removed busy-wait in `MultiPaperConnection.send()`

**Patch:** `patches/server/0007-Add-MultiPaperConnection.patch`

**What changed:** `send()` used to call `waitForActiveChannel()`, a `Thread.sleep(50)` busy-wait that blocked the calling thread (often the server main thread) until the master connection was active. Replaced with a non-blocking pending queue: messages sent while disconnected are buffered as lambdas and drained in FIFO order when the channel reconnects.

**Severity:** **none → low**

**Why it could matter:**
- Previously, if the master connection was down, the server main thread *stalled* until reconnection. Other server work (chunk ticking, entity AI) was paused. Now, the server keeps ticking while messages buffer. This means master-bound state updates may now lag behind the server's local simulation by 1–N ticks during a master outage.
- Cross-server visibility (e.g. player position sync, chunk subscriptions) updates from this server will arrive at the master in a burst on reconnect rather than being delivered the moment the connection comes up.
- FIFO order within the queue is preserved → no out-of-order delivery.
- Callback semantics preserved → `unhandledRequests` tracking is delayed until drain, but each pending lambda re-applies its own bookkeeping.

**What to test:**
- Manually kill the master process for ~10s while players are on a server. Verify that on reconnect, all pending chunk/player updates replay correctly and no players are stuck in a desynced state.
- Verify no `NullPointerException` from `channel` being null inside a lambda (the lambda captures `channel` field by reference, so when it runs in `drainPending` the field is set — but a sanity test is worth it).

**Status:** Implemented. Not yet tested.

---

## 2026-06-03 — Tier 0 item 6: bumped `ChunkLock` stripe count 64 → 4096

**File:** `MultiPaper-Master/src/main/java/puregero/multipaper/server/util/ChunkLock.java`

**What changed:** The stripe lock used by `ChunkSubscriptionManager` for chunk subscribe/lock/unlock had 64 buckets. Adjacent chunks frequently hashed to the same bucket, serializing unrelated chunk operations. Bumped to 4096 buckets (64× more granularity). Memory cost: ~64 KB of `Object` instances, trivial.

**Severity:** **none**

**Why:** The semantics are identical — still a striped lock keyed by `ChunkKey.hashCode()`. The only change is that two different chunks are now 64× less likely to share a bucket. No code path changed, no message order changed, no game state change.

**What to test:** Unit/integration tests of `ChunkSubscriptionManager` (if any) should pass unchanged.

**Status:** Implemented. Not yet tested.

---

## 2026-06-03 — Tier 0 (b): rewrote `ChunkSubscriptionManager` (concurrent maps, no objectPool, fan-out outside stripe lock)

**File:** `MultiPaper-Master/src/main/java/puregero/multipaper/server/ChunkSubscriptionManager.java`

**What changed (three optimizations bundled):**
1. `lockedChunks` and `subscribedChunks` switched from `HashMap` to `ConcurrentHashMap`. The previous code mutated them under stripe locks but read from them in `unsubscribeAndUnlockAll` without any lock — a pre-existing latent thread-safety bug that the new code closes.
2. Dropped the `objectPool` `LinkedList` and its `synchronized(objectPool)` block. Pooling a tiny `ArrayList` saves nothing vs. allocating a fresh one; the global `synchronized` on the pool was a contention source. Now `computeIfAbsent(k -> new ArrayList<>())`.
3. Moved every `connection.send()` outside the stripe-lock-held region. Inside the lock we now capture a snapshot list of `(connection, message_data)`; after `synchronized` block exits we iterate and send. Lock-hold time drops from "broadcast to N subscribers" to "modify map + snapshot".

**Severity:** **medium**

**Why it could matter (gameplay):**
- Order between **independent chunks** is no longer serialized through the master broadcast loop — concurrent `subscribe()`/`unsubscribe()` calls on different chunks can interleave their notifications. Subscribers reconstruct their picture of the chunk-graph from cumulative add/remove ops, so order between independent chunks shouldn't matter — but if any plugin relies on observing master broadcasts in a globally-consistent order (unlikely; no such API is exposed) it could see a different interleaving.
- Order within a **single subscribe/unsubscribe call** is preserved (FIFO send loop after lock release).
- A subscribe-then-immediately-unsubscribe race could send an `AddChunkSubscriberMessage` after the unsubscriber has left. The receiver would emit a "received Add for unknown subscriber" log warn, but functional state remains consistent because the eventual `Remove`/sync messages catch up. This was theoretically possible under the original code too if the subscriber's send completed before the unsubscribe lock was acquired; we just widened the window slightly.
- The original code held the stripe lock during fan-out, which serialized broadcasts about the same chunk. We now release the lock first, so two threads can both be in the fan-out loop for the same chunk at once. This is safe because each `connection.send()` is itself thread-safe (Netty `Channel.writeAndFlush`), and the message contents reflect a snapshot taken under the lock.

**What to test:**
- Players moving rapidly across chunk boundaries between three or more servers: watch for any "subscriber I don't know about" log warnings under load. Expected to be very rare.
- Concurrent player join/leave in the same chunk: ensure subscriber sync on rejoin produces correct state.
- Long-running stress test with no specific assertion changes — if no plugin observes a misordered event, the change is invisible to gameplay.

**Status:** Implemented. Compiles. Not yet tested under load.

---

## 2026-06-03 — Tier 0 item 5 (block update coalescing): NOT IMPLEMENTED, was misframed

**Original idea:** Buffer block-change messages per-tick and flush as one packed message to reduce master traffic.

**Why it wasn't implemented:** While re-reading the patches, I discovered:
- Block updates between servers go **peer-to-peer**, not via the master.
- `PacketConsolidationHandler` (added in patch 0009) already batches P2P writes inside a configurable `peerConnection.consolidationDelay` window.
- Block-change acknowledgements to external players are already coalesced per-tick by `MultiPaperAckBlockChangesHandler` (patch 0023).
- The master only sees chunk-lifecycle / player-lifecycle events (subscribe, lock, save, connect), not per-block traffic.

Implementing further coalescing without a measurable target risks breaking redstone/hopper/piston timing (which is precisely what patch 0063 had to fix in the original codebase). Punted to a measurement-first follow-up (see open task: profile under clustered load).

**Status:** Deferred pending profiling data.

---
