# Built by .github/workflows/images.yml on every push to main.
# The CI workflow runs `./gradlew :MultiPaper-Master:shadowJar` first, then
# COPYs the resulting jar in at this stage.

FROM eclipse-temurin:17-jre

WORKDIR /work

# Master listens for game servers on 35353 and serves Minecraft clients via
# the built-in proxy on 25577.
EXPOSE 35353 25577

# Provided by the build context — the CI workflow copies the shadowjar to
# `docker/master/multipaper-master.jar` before running `docker build`.
COPY master/multipaper-master.jar /work/multipaper-master.jar

# JVM_OPTS lets operators add -Xmx, -Xms, GC tuning etc. without rebuilding.
# MULTIPAPER_OPTS adds -Dmultipaper.hotspot.* knobs the same way.
ENV JVM_OPTS="" \
    MULTIPAPER_OPTS="" \
    MASTER_BIND=0.0.0.0:35353 \
    PROXY_PORT=25577

ENTRYPOINT ["/bin/sh", "-c", "exec java $JVM_OPTS $MULTIPAPER_OPTS -jar /work/multipaper-master.jar $MASTER_BIND $PROXY_PORT"]
