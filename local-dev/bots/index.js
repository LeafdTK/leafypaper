// Bot fleet for hotspot stress testing.
//
// Spawns BOT_COUNT mineflayer clients, staggered so we don't hit the proxy
// with a synchronized login burst, lets them cluster within CLUSTER_RADIUS
// blocks of spawn, and jiggles their movement every JITTER_MS milliseconds
// to generate the same kind of cross-server position-update traffic that
// makes mirrored players appear to freeze in real combat.
//
// Env vars (with defaults shown):
//   BOT_COUNT=30          how many bots to spawn
//   MC_HOST=master        proxy hostname (container name in docker-compose)
//   MC_PORT=25577         proxy port
//   BOT_NAME_PREFIX=Bot   resulting usernames look like Bot007
//   CLUSTER_RADIUS=8      how tight the cluster is, in blocks
//   JITTER_MS=2000        how often each bot moves a short distance

const mineflayer = require('mineflayer');

const BOT_COUNT = parseInt(process.env.BOT_COUNT, 10) || 30;
const MC_HOST = process.env.MC_HOST || 'master';
const MC_PORT = parseInt(process.env.MC_PORT, 10) || 25577;
const PREFIX = process.env.BOT_NAME_PREFIX || 'Bot';
const RADIUS = parseFloat(process.env.CLUSTER_RADIUS || '8');
const JITTER_MS = parseInt(process.env.JITTER_MS, 10) || 2000;
// Stagger login so we don't fire all bots at once and overwhelm the
// proxy login pipeline. 200ms per bot is comfortable for ~100 bots.
const STAGGER_MS = parseInt(process.env.STAGGER_MS, 10) || 200;

console.log(`fleet config: count=${BOT_COUNT} host=${MC_HOST}:${MC_PORT} radius=${RADIUS} jitter=${JITTER_MS}ms`);

let alive = 0;
let target = null; // first bot's spawn becomes the cluster center

for (let i = 0; i < BOT_COUNT; i++) {
  setTimeout(() => spawnBot(i), i * STAGGER_MS);
}

// Periodic heartbeat so you can see whether the fleet is healthy from the
// logs without scrolling past 100 spawn messages.
setInterval(() => console.log(`fleet alive: ${alive}/${BOT_COUNT}`), 5000);

function spawnBot(idx) {
  const username = `${PREFIX}${String(idx).padStart(3, '0')}`;
  const bot = mineflayer.createBot({
    host: MC_HOST,
    port: MC_PORT,
    username,
    version: '1.20.1',
    auth: 'offline',
  });

  bot.on('spawn', () => {
    alive++;
    console.log(`${username} spawned at ${formatVec(bot.entity.position)}`);
    if (target === null) {
      target = bot.entity.position.clone();
      console.log(`cluster target locked at ${formatVec(target)}`);
    }
    // Drive the bot toward the cluster target, then jiggle.
    bot.once('forcedMove', () => {});
    walkLoop(bot, username);
  });

  bot.on('kicked', (reason) => {
    console.warn(`${username} kicked: ${reason}`);
  });

  bot.on('error', (err) => {
    console.warn(`${username} error: ${err.message}`);
  });

  bot.on('end', () => {
    alive = Math.max(0, alive - 1);
    console.log(`${username} disconnected`);
  });
}

function walkLoop(bot, name) {
  const tick = () => {
    if (!bot.entity || !target) {
      setTimeout(tick, JITTER_MS);
      return;
    }
    // Pick a point inside the cluster radius and walk toward it.
    const angle = Math.random() * Math.PI * 2;
    const r = Math.random() * RADIUS;
    const goal = target.offset(Math.cos(angle) * r, 0, Math.sin(angle) * r);
    bot.lookAt(goal, true).catch(() => {});
    bot.setControlState('forward', true);
    setTimeout(() => bot.setControlState('forward', false), JITTER_MS * 0.6);
    setTimeout(tick, JITTER_MS);
  };
  tick();
}

function formatVec(v) {
  return `(${v.x.toFixed(1)}, ${v.y.toFixed(1)}, ${v.z.toFixed(1)})`;
}
