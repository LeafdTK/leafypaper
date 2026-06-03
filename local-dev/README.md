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
./local-dev/up.sh down        # tear down and wipe volumes
```

First build takes 5–15 minutes (paperweight downloads upstream Purpur).
Subsequent builds reuse Gradle's cache and are seconds.

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
