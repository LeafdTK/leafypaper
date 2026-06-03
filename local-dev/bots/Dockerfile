FROM node:20-slim

WORKDIR /app

# install once at build time, not on every container start
COPY package.json package-lock.json* ./
RUN npm install --omit=dev

COPY index.js .

ENV BOT_COUNT=30 \
    MC_HOST=master \
    MC_PORT=25577 \
    BOT_NAME_PREFIX=Bot \
    CLUSTER_RADIUS=8 \
    JITTER_MS=2000

CMD ["node", "index.js"]
