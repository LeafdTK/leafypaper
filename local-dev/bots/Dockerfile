FROM node:20-slim

WORKDIR /app

# install once at build time, not on every container start
COPY package.json package-lock.json* ./
# vec3 and minecraft-data are transitive deps of mineflayer-pathfinder, but
# we require() them directly so list them explicitly.
RUN npm install --omit=dev vec3 minecraft-data && npm install --omit=dev

COPY index.js .

ENV BOT_COUNT=20 \
    MC_HOST=master \
    MC_PORT=25577 \
    BOT_NAME_PREFIX=Bot \
    CLUSTER_RADIUS=12 \
    STAGGER_MS=200

CMD ["node", "--max-old-space-size=2048", "index.js"]
