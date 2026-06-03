# Built by .github/workflows/images.yml on every push to main.
# The CI workflow runs `./gradlew createReobfPaperclipJar` first, then
# COPYs the resulting jar in at this stage.

FROM eclipse-temurin:17-jre

WORKDIR /work

# Vanilla Minecraft client port. The master proxy (on a different pod)
# bridges Minecraft clients to whichever game server is least loaded.
EXPOSE 25565

# Provided by the build context — CI copies the paperclip jar to
# `docker/server/paperclip.jar` before running `docker build`.
COPY server/paperclip.jar /work/paperclip.jar
COPY server/eula.txt      /work/eula.txt
COPY server/server.properties /work/server.properties
COPY server/spigot.yml    /work/spigot.yml
COPY server/multipaper.yml /work/multipaper.yml

# JVM heap defaults safe for a 2 CPU / 4 GB pod. Override at deploy time.
ENV JVM_OPTS="-Xms1G -Xmx2G" \
    MULTIPAPER_OPTS="" \
    MASTER_ADDRESS="master:35353" \
    SERVER_NAME=""

# Bot worlds get persisted in /work/world*. Mount a PVC on /work in the Helm
# chart so worlds survive pod restarts and rescheduling.
VOLUME ["/work/world", "/work/world_nether", "/work/world_the_end", "/work/cache", "/work/logs", "/work/plugins"]

ENTRYPOINT ["/bin/sh", "-c", "exec java \
    -Dmultipaper.master-connection.my-name=\"${SERVER_NAME:-$HOSTNAME}\" \
    -Dmultipaper.master-connection.master-address=\"$MASTER_ADDRESS\" \
    $JVM_OPTS $MULTIPAPER_OPTS \
    -jar /work/paperclip.jar nogui"]
