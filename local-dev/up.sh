#!/usr/bin/env bash
# Builds the paperclip and master jars, then brings up the local 3-container
# cluster (master + server1 + server2). Run from any directory.
#
# Usage:
#   ./local-dev/up.sh             # build + up
#   ./local-dev/up.sh --no-build  # skip the gradle build
#   ./local-dev/up.sh down        # docker compose down
set -euo pipefail

cd "$(dirname "$0")/.."

case "${1:-}" in
    down)
        cd local-dev
        exec docker compose --profile stress down -v
        ;;
    logs)
        cd local-dev
        exec docker compose logs -f "${@:2}"
        ;;
    stress)
        # Bring up the bot fleet against an already-running cluster.
        # Override count: BOT_COUNT=100 ./local-dev/up.sh stress
        cd local-dev
        exec docker compose --profile stress up --build bots
        ;;
    --no-build)
        skip_build=1
        ;;
    *)
        skip_build=0
        ;;
esac

if [ "${skip_build:-0}" -ne 1 ]; then
    echo "==> applying patches"
    ./gradlew applyPatches --stacktrace
    echo "==> building game-server jars (single-threaded to satisfy paperweight task wiring)"
    # paperweight's task graph trips Gradle's parallel-build validation if
    # everything fires in parallel. Force sequential execution for the build
    # phase; configuration cache + Gradle daemon keep this fast on reruns.
    ./gradlew shadowjar createReobfPaperclipJar --no-parallel --stacktrace
    echo "==> building master jar"
    ./gradlew :MultiPaper-Master:shadowJar --no-parallel --stacktrace
fi

# Sanity-check the jars exist before docker tries to mount the dir
ls build/libs/multipaper-paperclip-*-reobf.jar >/dev/null
ls MultiPaper-Master/build/libs/multipaper-master-*-all.jar >/dev/null

# docker-compose expects both jars under one mount-able dir; copy the master
# jar next to the paperclip jar so a single bind mount covers both.
cp MultiPaper-Master/build/libs/multipaper-master-*-all.jar build/libs/

echo "==> starting cluster (master 35353, proxy 25577, server1 25565, server2 25566)"
cd local-dev
docker compose up "${@:1}"
