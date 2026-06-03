# Mechanic Risk Tracker

Running log of performance/architecture changes that could *potentially* alter vanilla or Purpur gameplay behavior. Each entry: what changed, why it's a possible mechanic risk, severity guess, and what to test.

Severity scale:
- **none** — code change is internal plumbing; behavior provably unchanged
- **low** — timing or ordering change at sub-tick scale; unlikely to be observable
- **medium** — change affects cross-server ordering or could shift tick alignment
- **high** — change alters the order/visibility of game events; needs manual playtesting

---

## 2026-06-03 — week 3-4: master orchestrates entity handoff on hotspot transfer

**Files:**
- `MultiPaper-MasterMessagingProtocol/.../serverbound/RequestEntitiesForHandoffMessage.java` (new)
- `MultiPaper-MasterMessagingProtocol/.../serverbound/TransferEntitiesMessage.java` (new)
- `MultiPaper-MasterMessagingProtocol/.../masterbound/EntitiesForHandoffMessage.java` (new)
- `MultiPaper-Master/.../hotspot/EntityHandoffCoordinator.java` (new) — 3-hop orchestrator with 2s timeout
- `MultiPaper-Master/.../hotspot/HotspotCoordinator.java` — `beginHandoff()` called before `TransferRegionOwnershipMessage`

**What changed (master side):** For every live chunk being transferred during a hotspot offload, master now asks the old owner for an entity NBT blob and forwards it to the new owner. Today the server-side handlers are no-ops, so the message round-trip is exercised but the new owner still falls back to disk NBT.

**Severity:** **none** (today: server-side handlers do nothing)

**TODO (server-side patches):** Two patches in `patches/server/`:
1. **Old-owner side:** override `handle(RequestEntitiesForHandoffMessage)` to walk live entities in the chunk, write each to NBT with `CompoundTag` (including AI goals, momentum, equipment, effects), pack into one byte array, reply with `EntitiesForHandoffMessage`.
2. **New-owner side:** override `handle(TransferEntitiesMessage)` to deserialise the blob and `level.addFreshEntity()` each entity before the chunk's next tick. Guard against duplicate spawn if the chunk was already re-read from disk (skip entities whose UUID is already loaded).

**What to test once server-side lands:**
- Spawn a zombie with a player target, force a hotspot transfer of the chunk, verify the zombie keeps its target and continues attacking instead of resetting AI.
- Spawn a creeper that's begun its fuse, transfer mid-fuse, verify fuse continues from same tick rather than resetting.
- Concurrent transfers: trigger 10 chunk handoffs simultaneously, verify no entity duplication and no entities are dropped.

**Status:** master orchestration in place + timeout cleanup + disconnect cleanup; server-side patches not yet written.

---

## 2026-06-03 — week 1: master broadcasts `HotRegionsMessage` for view-distance shrinking

**Files:**
- `MultiPaper-MasterMessagingProtocol/.../HotRegionsMessage.java` (new)
- `MultiPaper-Master/.../hotspot/HotspotCoordinator.java` — `broadcastHotRegions()` runs each loop tick
- `MultiPaper-Master/.../hotspot/HotspotConfig.java` — `VIEW_SHRINK_THRESHOLD_PLAYERS`, `HOT_VIEW_DISTANCE`, `HOT_SIMULATION_DISTANCE` knobs

**What changed (master side):** Every hotspot-loop tick, the master sends every connected server the current list of hot regions plus the view/sim distance to apply for clients inside them. Default no-op on the server until the matching server-side patch lands.

**Severity:** **none** (today: server ignores the message)

**TODO (server-side patch follow-up):** add a `patches/server/00XX-Apply-hot-region-view-distance.patch` that:
- Overrides `MultiPaperConnection`'s `ServerBoundMessageHandler.handle(HotRegionsMessage)` to cache the latest hot-region snapshot
- On the player-tick hook (or chunk-load), checks if the player's `(world, chunkX>>regionShift, chunkZ>>regionShift)` is in the snapshot
- If yes: `player.setViewDistance(hotViewDistance)` + `setSimulationDistance(hotSimulationDistance)`; else: defaults
- Apply hysteresis: only change distance when crossing the snapshot boundary, not every tick

**What to test once server-side lands:** drop 100 bots into a 16x16 region with threshold=40 and observe each bot's effective view distance reduce from 10 to 4 within one hotspot-loop interval.

**Status:** master-side broadcast in place; server-side patch not yet written.

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

## 2026-06-03 — hotspot offload step 5: kubernetes-style scoring scheduler

**Files added / modified:**
- `MultiPaper-Master/.../hotspot/HotspotScheduler.java` (new)
- `MultiPaper-Master/.../hotspot/HotspotCoordinator.java` — uses the scheduler

**What changed:**
- Removed the assumption that a separate "crowd server" pool exists. The default deployment shape is now homogeneous: every connected server is a candidate (matches a Kubernetes deployment where all pods are identical).
- New `HotspotScheduler` runs k8s-scheduler-style two-phase selection:
  - **Filter** drops candidates that are offline, are themselves the current owner of the hot region, or have TPS below `multipaper.hotspot.minTps` (default 17 — already struggling).
  - **Score** sums weighted signals: active-transfer load, TPS headroom, player count, locality bonus (already-subscribed servers ship less chunk data on takeover).
- Each weight is JVM-property tunable: `multipaper.hotspot.score.load|tps|players|locality`. Logs include the full breakdown so you can see which signal carried the decision.
- An explicit `multipaper.hotspot.crowdServers=...` list still works as an override — the scheduler runs over the explicit pool instead of every connection.

**Severity:** **none** (still dry-run gated)

**Why this matches a Kubernetes setup:**
- No hardcoded role differentiation: any pod can absorb a hotspot, picked by current load.
- Filtering is "predicates" in k8s parlance, scoring is "priorities" — same shape, simpler implementation suited to ~tens of nodes instead of thousands.
- TPS-floor filter prevents dumping load on a pod that's already in trouble — analogous to k8s avoiding NotReady nodes.

**Status:** Scheduler in place. Still dry-run by default.

---

## 2026-06-03 — hotspot offload step 4: reverse path (release when cooled)

**Files modified:**
- `MultiPaper-Master/.../hotspot/HotspotConfig.java` — added `RELEASE_THRESHOLD_PLAYERS` and `RELEASE_HOLD_SECONDS` knobs
- `MultiPaper-Master/.../hotspot/RegionDensityTracker.java` — exposed `regionTotal(world, rx, rz)`
- `MultiPaper-Master/.../hotspot/HotspotCoordinator.java` — added active-transfer table, `sweepReleases()`, `releaseRegion()`, `forgetServer()`
- `MultiPaper-Master/.../server/ServerConnection.java` — calls `HotspotCoordinator.forgetServer` on disconnect

**What this completes:**
- The coordinator now tracks every region it has handed to a crowd server.
- Each tick, before scoring new candidates, it sweeps active transfers. When a region's combined density falls at or below `RELEASE_THRESHOLD_PLAYERS` (default: half the offload threshold) and stays there for `RELEASE_HOLD_SECONDS` (default 30s), the coordinator force-unlocks every chunk in the region from the crowd server.
- `ChunkSubscriptionManager.unlock` already promotes the next-in-line server (the previous owner before the transfer) and broadcasts `SetChunkOwnerMessage` to every subscriber. The crowd server, still subscribed, receives the broadcast and stops ticking those chunks.
- If the crowd server disconnects mid-transfer, `forgetServer` drops the entry. The chunk locks themselves are released by the existing `unsubscribeAndUnlockAll` on `channelInactive`, so no separate cleanup is needed.

**Severity:** **none** (gated by dry-run; release sweep is also no-op when dry-run is on because nothing is in the active table)

**What to test:**
- Trigger a transfer (200 players → spawn → crowd server takes over)
- Players disperse → density drops below `releaseThresholdPlayers`
- Wait `releaseHoldSeconds` → master logs "released region ..." → previous owner resumes ticking
- Verify no entity duplication, no chunks owned by no-one stuck after release
- Crowd server disconnect mid-load: chunks should fall back to previous owners cleanly

**Whole hotspot offload feature is now functionally complete in dry-run mode** (patches 0157-0159 + this master-only change). To operationalize on a fleet:
1. Pick a small staging server pool. Add their names to `-Dmultipaper.hotspot.crowdServers=...`.
2. Set a low threshold (e.g. 20) for testing: `-Dmultipaper.hotspot.thresholdPlayers=20`.
3. Flip `-Dmultipaper.hotspot.dryRun=false`.
4. Watch the master logs and verify transfer + release work end-to-end.
5. Tune thresholds to production targets.

**Status:** Reverse path implemented. Feature complete in dry-run.

---

## 2026-06-03 — hotspot offload step 3: handover state machine (still dry-run gated)

**Files added / modified:**
- `MultiPaper-Server/.../HotspotHandover.java` (new)
- `MultiPaper-Server/.../MultiPaperConnection.java` — dispatches the transfer message to `HotspotHandover.claimRegion`
- `MultiPaper-MasterMessagingProtocol/.../LockChunkMessage.java` — extended with optional trailing `force` flag (wire-compat: older writers don't emit, older readers default to false)
- `MultiPaper-Master/.../handlers/LockChunkHandler.java` — passes `force` through to `ChunkSubscriptionManager.lock`
- Patch `0159-claim-chunks-on-TransferRegionOwnership-receive.patch`

**What this completes:**
When the crowd server receives `TransferRegionOwnershipMessage`, it now actually claims the region. For each of the 256 chunks in `(rx*16..rx*16+15, rz*16..rz*16+15)`:
1. Sends `SubscribeChunkMessage` → master tells the current owner to ship chunk data via the existing peer-to-peer chunk delivery path.
2. Sends `LockChunkMessage(force=true)` → master force-promotes the crowd server to chunk owner and broadcasts `SetChunkOwnerMessage` to all subscribers (including the previous owner).
3. The previous owner's existing `SetChunkOwnerMessage` handler releases ticking responsibility automatically — this hooks into infrastructure that already exists for normal player chunk-boundary crossings, so the path is well-trodden.

Steady-state operationalization is now **one config flip away**: set `multipaper.hotspot.crowdServers=...` and `multipaper.hotspot.dryRun=false`.

**Severity:** **medium** (when enabled)
- 256 subscribe + 256 lock messages per region per transfer — that's 512 master-bound sends in a tight loop. All async via the pending queue we added earlier, so no main-thread block. Could briefly spike master ingress.
- The crowd server starts ticking entities + simulating physics for the region as soon as ownership flips, before chunk data fully arrives. Existing chunk-not-loaded checks in handler paths should swallow this (mob spawns and entity ticks gate on chunk full status), but it's worth observing during the first staged rollout.
- A piston / hopper / dispenser straddling the region edge might receive block updates from both old and new owner during the ~1–2s handover window. Vanilla / Purpur logic should idempotently apply, but redstone systems running active chains during the exact transfer moment are the highest-risk gameplay scenario.
- The reverse path is NOT YET implemented — once a region transfers to a crowd server, it stays there. This is fine for short events but means crowd servers gradually accumulate dormant ownership of cooled-down regions over time. A periodic "release low-density region" sweep is the planned follow-up.

**What to test:**
- Single transfer at low population (e.g. 10-player threshold): verify the crowd server logs "claiming region", the master broadcasts new ownership, and players observe stable mirroring across servers throughout.
- Pistons + hopper chain straddling a region boundary mid-transfer.
- 100 players standing in spawn → trigger transfer → observe stable TPS on crowd server, no entity duplication or vanish.
- Crowd server dies mid-transfer: chunks should fall back to whichever server has subscribers (existing chunk-orphan logic handles this).

**Still on the roadmap (post-this-commit):**
- Reverse path: detect cooled-down regions and transfer back to original owner.
- Hysteresis on the threshold so we don't flap around the boundary.
- Crowd server health/load awareness in the selection policy.
- Per-region cooldown window is currently 60s — needs tuning under real load.

**Status:** Handover loop implemented. Gated by dry-run. Awaits a staged rollout.

---

## 2026-06-03 — hotspot offload step 2: transfer protocol (dry-run by default)

**Files added:**
- `MultiPaper-MasterMessagingProtocol/.../TransferRegionOwnershipMessage.java` (server-bound)
- `MultiPaper-Master/.../hotspot/HotspotCoordinator.java`
- Patch `0158-log-TransferRegionOwnership-stub-on-receive.patch`

**What changed:**
- New server-bound message `TransferRegionOwnershipMessage` (registered at the tail of `ServerBoundProtocol` — append-only). Default handler in `ServerBoundMessageHandler` is a no-op so any minimal handler that doesn't override still compiles.
- New `HotspotCoordinator` runs on its own daemon scheduler (5s interval by default). Each tick: snapshot above-threshold regions, enforce per-region cooldown, pick a crowd server from the configured pool, emit the transfer message (or log it under dry-run).
- Crowd-server pool is JVM-property configured: `-Dmultipaper.hotspot.crowdServers=name1,name2` (default empty → coordinator stays in log-only mode).
- Server side overrides `handle(TransferRegionOwnershipMessage)` on `MultiPaperConnection` with a log line — no chunk-state changes yet. That's the handover state machine (task #29).

**Severity:** **none** (dry-run gated)
- Default `multipaper.hotspot.dryRun=true` means no transfer message is ever emitted.
- Even with dry-run off, an empty crowd-server pool means the coordinator picks no target and logs the candidate.
- Even if a transfer message IS sent, the server-side handler currently only logs receipt — chunk locks/subscriptions are unchanged until step 3 lands.

**Still to build before this is operational (task #29):**
1. On `TransferRegionOwnershipMessage` receipt: subscribe to every chunk in the region; send `LockChunkMessage` with the force flag for each so the master reassigns ownership and broadcasts to subscribers.
2. Old owner: gracefully release ticking responsibility once the new `SetChunkOwnerMessage` arrives (mostly works today via existing chunk-ownership wiring — needs verification under load).
3. Entity migration: passenger/leashed entity state needs to follow the chunk owner.
4. Reverse path: when density drops, reassign back to original owners.

**Status:** Protocol defined and wired end-to-end in dry-run mode. Logs show what *would* happen under load. Operationalization requires step 3.

---

## 2026-06-03 — hotspot offload foundation (density reporting only)

**Files added:**
- `MultiPaper-MasterMessagingProtocol/.../ReportRegionDensityMessage.java`
- `MultiPaper-Master/.../hotspot/{HotspotConfig,RegionDensityTracker}.java`
- `MultiPaper-Master/.../handlers/ReportRegionDensityHandler.java`
- `MultiPaper-Server/.../HotspotDensityReporter.java`
- Patch `0157-add-hotspot-density-reporter.patch`

**What's in this commit:**
- New protocol message `ReportRegionDensityMessage` registered in `MasterBoundProtocol`. Wire-compatible append-only addition; older servers that don't send it are unaffected.
- Server side reports player counts per region every 40 ticks (2s), delta-encoded (only emits when a count changes).
- Master side aggregates contributions across all servers in `RegionDensityTracker`. Above-threshold regions are flagged.
- Hotspot detection runs in **dry-run mode** by default — it logs candidates but performs **no ownership transfer**. The transfer protocol, crowd-server pool, and handover are deliberate follow-ups.

**Severity:** **none** (in dry-run mode)
- Master receives extra messages but does nothing irreversible with them.
- Server emits ~hundreds of bytes/sec at steady state under normal play.
- Region size, threshold, and cooldown are all JVM-property tunable.

**Still to build before this is operational:**
1. `TransferRegionOwnershipMessage` and the handover state machine.
2. Crowd-server pool config (which servers are eligible to absorb hotspots).
3. Selection policy (which crowd server takes which region under what tie-breaker).
4. Subscriber re-sync after ownership transfer.
5. Cooldowns and flap protection.

**Status:** Foundation merged. Dry-run safe. Operationalization is a follow-up.

---

## 2026-06-03 — optional multi-channel peer-to-peer connection

**Patch:** `0156-add-optional-bulk-channel-for-peer-to-peer-connectio.patch`

**What changed:** New opt-in config `peerConnection.separateBulkChannel = true` (defaults to false; behavior unchanged unless enabled). When enabled, each pair of peer servers establishes a second TCP socket dedicated to bulk traffic — large chunk transfers (`SendChunkPacket`), entity dumps (`SendEntitiesPacket`), and full-entity NBT (`EntityUpdateNBTPacket`). Realtime traffic (entity position updates, player actions, etc.) stays on the primary channel.

**Why this addresses the freeze**: under heavy fighting + chunk activity, a single TCP socket can fill its send buffer with a 1MB chunk transfer; position updates queued behind it wait until the kernel acknowledges the chunk. Splitting bulk and realtime onto separate sockets means TCP-level head-of-line blocking can't cross lanes.

**Severity:** **low** (when disabled: none; when enabled: low)

**Why it could matter (gameplay) when enabled:**
- Cross-lane ordering between the bulk and realtime channels is not preserved. A chunk transfer can land after a position update that was sent later. In practice this is fine — chunk arrival is processed asynchronously (`runSync` on receive); positions overwrite their target field; no chunk-to-position causal dependency exists.
- If the bulk channel disconnects without taking the primary down, packets fall back to the primary. We log on bulk close but don't reconnect automatically — a follow-up will add that. Until then, after a bulk-only disconnect the peer pair effectively reverts to single-channel until both sides reconnect.
- Wire-protocol extension is backward compatible: `HelloPacket` writes the `isBulkChannel` boolean only when set, and older readers see end-of-buffer and default to false. A mixed-version fleet still works (older peers just don't get the bulk channel).

**What to test:**
- Enable on all servers in the fleet (`peerConnection.separateBulkChannel: true`), kill+restart a peer, observe the log shows both "Connected to external server X" and "Opening bulk channel to X:Y" / "Attaching bulk channel for external server X". Confirm chunk transfers still complete and entity positions still sync.
- 300-cluster fight with bulk channel enabled: verify mirrored players track smoothly even while chunks are being loaded.
- Verify rolling deploy: a server with this config off and one with it on can still pair — the off-side just doesn't open the bulk leg.

**Status:** Implemented. Compiles. Not load-tested. Bulk-channel reconnect logic is a known follow-up.

---

## 2026-06-03 — bump Netty event-loop thread cap 3 → 8

**Patch:** `0155-raise-default-netty-event-loop-thread-cap-from-3-to-.patch`

**What changed:** Default thread count for the shared Netty event-loop pool (used by both master and peer-to-peer connections) was capped at `min(processors, 3)`. With 20 peers + 1 master connection per server, that's 21 channels sharing 3 threads — each thread handles 7+ channels, contention is high. Bumped to `min(processors, 8)` for better fan-out under cluster load.

**Severity:** **none** — purely a thread-pool sizing change. Behavior identical. Operator can still override via `-Dmultipaper.netty.threads=N`.

---

## 2026-06-03 — more entity-sync hot-path inefficiencies

**Patches:**
- `0153-pre-encode-PlayerActionPacket-and-SendUpdatePacket-f.patch`
- `0154-skip-CompletableFuture-chain-in-ExternalServerConnec.patch`

**What changed:**
- `PlayerActionPacket` and `SendUpdatePacket` now pre-encode their inner Minecraft packet in the constructor (same pattern as `EntityUpdatePacket`). Saves N× serialization when broadcast to N peer servers — relevant under combat (player actions) and under piston/redstone activity (block updates).
- `ExternalServerConnection.send()` previously did `onConnect.thenRun(...)` on every call, allocating a CompletableFuture continuation even when the connection was already established. Now we check `onConnect.isDone()` and inline the dispatch in the (overwhelmingly common) post-handshake case. Also removed `new IOException(...).printStackTrace()` on closed-channel sends — the channel state is observable via `isOpen()`, no need to allocate + log per dropped send.

**Severity:** **none** — these are pure CPU/allocation optimizations. Wire bytes identical, message ordering identical, semantics identical. The only observable change is fewer per-second allocations and JIT-friendlier fast paths.

---

## 2026-06-03 — entity update coalescing + broadcast pre-encoding

**Patch:** `patches/server/0152-coalesce-entity-position-updates-pre-encode-broadcas.patch`

**What changed (two bundled fixes targeting the mirrored-player-freeze symptom):**

1. **Receive-side coalescing.** Every incoming `EntityUpdatePacket` used to schedule its own task on the main thread. Under 300+ clustered players, the queue grew faster than it drained and mirrored players appeared frozen because their latest position was stuck behind hundreds of stale ones. New `EntityUpdateCoalescer` keeps only the most recent packet per `(entityUuid, packetClass)` for position-style packets (`ClientboundMoveEntityPacket`, `ClientboundTeleportEntityPacket`, `ClientboundSetEntityMotionPacket`, `ClientboundRotateHeadPacket`) and drains them in a single per-tick task. Non-position packets (entity data, equipment, attributes, animations, damage events) still go through the immediate-dispatch path.

2. **Send-side pre-encoding.** `EntityUpdatePacket.write()` and `EntityUpdateWithDependenciesPacket.write()` used to call `packet.write(...)` once per peer connection in the broadcast loop, re-serializing identical bytes 19× when fanning out to 19 peers. Moved the inner-packet serialization into the constructor (runs once on the sending main thread). Each peer's `write()` now just emits the cached byte array.

**Severity:** **medium-low**

**Why it could matter (gameplay):**
- Coalescer drops superseded position updates. Vanilla Minecraft clients already tolerate this category of loss (every entity tracker re-broadcasts position periodically, and `ClientboundTeleportEntityPacket` is sent every ~400 ticks to re-anchor anyway). The maximum staleness added is **one server tick (50ms)** which is invisible to humans.
- Coalescer ONLY touches packets where last-write-wins is semantically correct. Animation/damage/equipment/metadata are explicitly excluded.
- A teleport packet still wins over earlier movement packets for the same entity because `ClientboundTeleportEntityPacket` is a different `packetClass` than `ClientboundMoveEntityPacket` — both buffered separately, both applied in arrival order on next tick. (Caveat: arrival order between different packet classes for the same entity may differ from current behavior. If a teleport and a move land in the same tick, both apply; the order depends on `ConcurrentHashMap.forEach` iteration order, which is not deterministic. In practice the absolute teleport always wins because it overwrites all axes; this matches vanilla last-write-wins semantics.)
- Pre-encoding moves `packet.write(...)` from the per-peer Netty event loop to the sending main thread. The `threadsWritingUpdatePackets` gate in `LivingEntity.java` now fires on the main thread instead of a Netty thread, which is actually MORE correct — the gate's purpose is to suppress recursive update generation, and recursion can only happen on the main thread anyway.

**What to test:**
- 200+ players clustered fighting: mirrored players should no longer freeze. Their positions may visibly lag by 1 tick (50ms) under extreme load, which is invisible to human perception.
- A player teleporting while another player is shooting projectiles at them: verify both packets apply correctly in the same tick.
- Mob attack animations across servers: should still play correctly (not coalesced).
- Entity health bars across servers: should still update correctly (data packet, not coalesced).
- Equipment changes across servers (player swapping weapons): should still appear correctly (equipment packet, not coalesced).

**Status:** Implemented. Compiles. Not yet tested under load.

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
