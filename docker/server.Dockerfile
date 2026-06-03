# Game server image built on top of itzg/minecraft-server.
#
# itzg handles all the "obvious" Minecraft server lifecycle stuff for us:
# EULA agreement, server.properties generation, RCON, BungeeCord, plugin
# install, modpack install (packwiz / modrinth / curseforge / FTB), JVM
# memory wiring, log rotation, world backup hooks, etc.
#
# We just bake the leafypaper paperclip jar in and point itzg's TYPE=CUSTOM
# mode at it. Everything else stays the upstream itzg defaults plus whatever
# the Helm chart sets via env vars.

FROM itzg/minecraft-server:java17

# Provided by the build context — CI copies the paperclip jar to
# `docker/server/paperclip.jar` before running `docker build`.
COPY server/paperclip.jar /server-jar/paperclip.jar

# itzg's CUSTOM_SERVER picks up the path on startup, copies the jar into
# /data, and treats it as the server jar. We pin TYPE here so operators
# don't accidentally override it and end up downloading vanilla.
ENV TYPE=CUSTOM \
    CUSTOM_SERVER=/server-jar/paperclip.jar \
    EULA=TRUE \
    BUNGEECORD=TRUE \
    ENABLE_RCON=true
