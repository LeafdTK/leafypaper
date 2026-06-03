# Local cluster

A docker-compose setup that runs a leafypaper cluster on your laptop:

```
       ┌───────────────────────────────┐
       │  master + built-in proxy      │
       │  (35353 cluster, 25577 proxy) │
       └───────────────┬───────────────┘
               ┌───────┴───────┐
       ┌───────┴────────┐ ┌────┴───────────┐
       │  server1       │ │  server2       │
       │  25565         │ │  25566         │
       │  (default)     │ │  (crowd pool)  │
       └────────────────┘ └────────────────┘
```

Players connect to **localhost:25577**. The proxy picks one of the game
servers; standing on a chunk boundary you can walk into the other server's
slice of the world via the existing MultiPaper subscription system.

There is no dedicated "crowd server" in this layout — both `server1` and
`server2` are identical, the way k8s pods are. The master picks a transfer
target by current load (which active hotspots it's already absorbing). The
threshold is set to 20 players so you can trigger a transfer with a single
test session if you want to see the dry-run logs.

## Bring it up

```bash
./local-dev/up.sh             # build jars, then docker compose up
./local-dev/up.sh --no-build  # skip the gradle build
./local-dev/up.sh logs        # tail container logs
./local-dev/up.sh down        # tear down (includes the bot fleet)
./local-dev/up.sh stress      # launch the bot fleet against a running cluster
```

First build takes 5–15 minutes (paperweight downloads upstream Purpur).
Subsequent builds reuse Gradle's cache and are seconds.

## E2E bot fleet (real-player behaviors)

There's a fourth container (`bots`) gated behind the `stress` compose
profile so it doesn't run during a normal `up`. Each bot picks a role
from a weighted list and runs a loop that produces the kind of packets
a real player would generate. Together they exercise the full leafypaper
patch chain end-to-end.

```bash
# default fleet (20 bots, mixed roles)
./local-dev/up.sh stress

# 100 bots, only fighters → fastest way to trigger the hotspot threshold
BOT_COUNT=100 ROLE_OVERRIDE=fighter ./local-dev/up.sh stress

# 50 travelers to churn chunk subscriptions across regions
BOT_COUNT=50 ROLE_OVERRIDE=traveler ./local-dev/up.sh stress

# detach so you can keep working
docker compose -f local-dev/docker-compose.yml --profile stress up -d bots
```

Roles (weighted random when `ROLE_OVERRIDE` is unset):

| Role       | Weight | Exercises |
|------------|--------|-----------|
| `walker`   | 4      | pathfinder + cross-server position sync |
| `fighter`  | 3      | attack packets, swing animations, damage events, density toward the cluster anchor |
| `builder`  | 2      | block place packets (`SendUpdatePacket`) and inventory changes |
| `miner`    | 2      | block break packets (`SendUpdatePacket`), inventory pickup |
| `forager`  | 2      | eat / consume / equip across servers |
| `sleeper`  | 1      | bed interaction across servers |
| `traveler` | 2      | walks 200-800 blocks; churns chunk subscribe/unsubscribe across regions |
| `horseman` | 1      | mount / dismount; passenger NBT propagation |

Knobs:

| Var               | Default | What it does |
|-------------------|---------|--------------|
| `BOT_COUNT`       | 20      | total bots in the fleet |
| `CLUSTER_RADIUS`  | 12      | walkers and fighters roam inside this many blocks of the anchor |
| `STAGGER_MS`      | 200     | delay between successive bot logins (avoid login pipeline storm) |
| `ROLE_OVERRIDE`   | _unset_ | force every bot into one role (debug) |

The first bot to spawn anchors the cluster. Walkers and fighters stay
within `CLUSTER_RADIUS` of the anchor so the hotspot threshold can fire.
Travelers and horseman explore further out to exercise chunk-subscription
churn and entity dependency propagation.

Tail the master log while the fleet is running to see the scheduler's
breakdown of every transfer decision:

```bash
./local-dev/up.sh logs master
```

## Files

- `docker-compose.yml` — three services (master + two game servers).
- `master/`, `server1/`, `server2/` — per-container working dirs. Worlds,
  player data, logs end up here.
- `up.sh` — orchestration script: gradle build → mount jars → compose up.

## Manual config tweaks

- The hotspot dry-run flag is wired into `docker-compose.yml` under the
  `master` service's `command:`. Flip `-Dmultipaper.hotspot.dryRun=false`
  to actually exercise the transfer protocol locally.
- Velocity / external proxies aren't included — the master's built-in proxy
  is sufficient for two-server testing. Plug in your own velocity if you
  want to test the velocity codepath specifically.

## Common operations

```bash
# Watch the master decide whether to transfer
./local-dev/up.sh logs master

# Watch a single game server
./local-dev/up.sh logs server2

# Shell into server1 to look at logs / world / plugins/
docker exec -it leafy-server1 sh

# Wipe everything and start fresh
./local-dev/up.sh down && rm -rf local-dev/server1/world* local-dev/server2/world*
```
