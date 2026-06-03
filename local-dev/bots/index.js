// E2E bot fleet for leafypaper.
//
// Each bot picks a role from a weighted list and runs a loop that produces
// the kind of packets a real player would generate. Together they exercise:
//   • cross-server entity sync (movement, head rotation, animations)
//   • cross-server block updates (block place / break)
//   • cross-server item interactions (eating, equipping)
//   • cross-server PvP (attack packets, damage events)
//   • mounting (vehicle/passenger NBT propagation)
//   • chunk subscription churn (travelers walk far)
//   • the hotspot offload trigger (clustered fighters)
//
// Roles:
//   walker     — picks random destinations near spawn, walks via pathfinder
//   fighter    — clusters with other fighters, attacks the nearest entity
//   builder    — places blocks from inventory in a pillar pattern
//   miner      — digs blocks (preferring stone) in a downward spiral
//   forager    — eats periodically when hunger drops
//   sleeper    — finds a bed at night and sleeps
//   traveler   — walks far from spawn to churn chunk subscriptions
//   horseman   — finds + mounts a passive entity, rides around
//
// Configuration via env vars (defaults shown):
//   BOT_COUNT=20
//   MC_HOST=master
//   MC_PORT=25577
//   BOT_NAME_PREFIX=Bot
//   STAGGER_MS=200
//   CLUSTER_RADIUS=12       fighters/walkers cluster within this many blocks
//   ROLE_OVERRIDE=walker    force every bot into a single role (debug)
//
// Each role's loop is intentionally bounded and ignores most errors so a
// single bot's tantrum doesn't take down the fleet.

const mineflayer = require('mineflayer');
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder');

const CONFIG = {
  count: parseInt(process.env.BOT_COUNT, 10) || 20,
  host: process.env.MC_HOST || 'master',
  port: parseInt(process.env.MC_PORT, 10) || 25577,
  prefix: process.env.BOT_NAME_PREFIX || 'Bot',
  staggerMs: parseInt(process.env.STAGGER_MS, 10) || 200,
  clusterRadius: parseFloat(process.env.CLUSTER_RADIUS || '12'),
  roleOverride: process.env.ROLE_OVERRIDE || null,
};

console.log('fleet config:', CONFIG);

// Weighted role table — adjust to bias the fleet toward whatever pattern
// you're stress-testing.
const ROLES = [
  { name: 'walker',   weight: 4 },
  { name: 'fighter',  weight: 3 },
  { name: 'builder',  weight: 2 },
  { name: 'miner',    weight: 2 },
  { name: 'forager',  weight: 2 },
  { name: 'sleeper',  weight: 1 },
  { name: 'traveler', weight: 2 },
  { name: 'horseman', weight: 1 },
];

const ROLE_HANDLERS = {
  walker: roleWalker,
  fighter: roleFighter,
  builder: roleBuilder,
  miner: roleMiner,
  forager: roleForager,
  sleeper: roleSleeper,
  traveler: roleTraveler,
  horseman: roleHorseman,
};

// Shared cluster anchor — the first bot's spawn becomes the cluster
// center, which fighters and walkers use to stay near each other so the
// hotspot threshold can actually fire.
let clusterAnchor = null;

let alive = 0;
const bots = [];

for (let i = 0; i < CONFIG.count; i++) {
  setTimeout(() => spawnBot(i), i * CONFIG.staggerMs);
}

setInterval(() => {
  const counts = {};
  for (const b of bots) counts[b.role] = (counts[b.role] || 0) + 1;
  console.log(`fleet alive: ${alive}/${CONFIG.count} — ${JSON.stringify(counts)}`);
}, 10000);

function pickRole() {
  if (CONFIG.roleOverride) return CONFIG.roleOverride;
  const total = ROLES.reduce((s, r) => s + r.weight, 0);
  let n = Math.random() * total;
  for (const r of ROLES) {
    n -= r.weight;
    if (n <= 0) return r.name;
  }
  return ROLES[0].name;
}

function spawnBot(idx) {
  const username = `${CONFIG.prefix}${String(idx).padStart(3, '0')}`;
  const role = pickRole();
  const bot = mineflayer.createBot({
    host: CONFIG.host,
    port: CONFIG.port,
    username,
    version: '1.20.1',
    auth: 'offline',
  });
  const entry = { username, role, bot };
  bots.push(entry);

  bot.loadPlugin(pathfinder);

  bot.on('spawn', () => {
    alive++;
    console.log(`${username} (${role}) spawned at ${formatVec(bot.entity.position)}`);
    if (clusterAnchor === null) {
      clusterAnchor = bot.entity.position.clone();
      console.log(`cluster anchor locked at ${formatVec(clusterAnchor)}`);
    }
    // Pathfinder needs to know what movements are allowed. Conservative
    // settings: no parkour, no sprinting, no breaking blocks for movement —
    // those generate move deltas the server tags as "invalid_player_movement"
    // and kicks the bot. Tradeoff: bots are slower, but they stay connected.
    const mcData = require('minecraft-data')(bot.version);
    const movements = new Movements(bot, mcData);
    movements.canDig = role === 'miner';
    movements.allowParkour = false;
    movements.allowSprinting = false;
    movements.allow1by1towers = role === 'builder';
    movements.scafoldingBlocks = role === 'builder' && mcData.blocksByName.dirt
      ? [mcData.blocksByName.dirt.id]
      : [];
    bot.pathfinder.setMovements(movements);

    // Wait until the bot is settled on the ground before starting the role
    // loop. Spawning mid-air + immediately walking has the same kick risk.
    waitForGround(bot).then(() => {
      const handler = ROLE_HANDLERS[role];
      if (!handler) return;
      Promise.resolve(handler(bot, entry)).catch((err) => {
        console.warn(`${username} role handler crashed:`, err.message);
      });
    });
  });

  bot.on('kicked', (reason) => console.warn(`${username} kicked: ${truncate(reason)}`));
  bot.on('error', (err)    => console.warn(`${username} error: ${err.message}`));
  bot.on('end', () => {
    alive = Math.max(0, alive - 1);
    console.log(`${username} (${role}) disconnected`);
  });
}

// ── role implementations ──────────────────────────────────────────────────

async function roleWalker(bot, entry) {
  while (bot.entity) {
    const goal = pickClusterDestination();
    await smoothWalkTo(bot, goal, { withinBlocks: 2, timeoutMs: 15000 });
    await wait(2000 + Math.random() * 3000);
    await idleLook(bot, 1500 + Math.random() * 2000);
  }
}

async function roleFighter(bot, entry) {
  while (bot.entity) {
    // Try to find any nearby living entity to swing at. Other bots count.
    const target = bot.nearestEntity((e) =>
      e !== bot.entity &&
      e.position.distanceTo(bot.entity.position) < 6 &&
      (e.type === 'mob' || e.type === 'player' || e.type === 'animal')
    );
    if (target) {
      await bot.lookAt(target.position.offset(0, 1.6, 0), true).catch(() => {});
      bot.attack(target);
      bot.swingArm('right');
      // tiny strafe so the fighter isn't a perfectly stationary turret
      const dir = Math.random() < 0.5 ? 'left' : 'right';
      bot.setControlState(dir, true);
      await wait(200 + Math.random() * 200);
      bot.setControlState(dir, false);
    } else {
      // Drift toward the cluster anchor so fighters concentrate density.
      const dest = pickClusterDestination(4);
      await smoothWalkTo(bot, dest, { withinBlocks: 2, timeoutMs: 8000 });
    }
    await wait(600 + Math.random() * 400);
  }
}

async function roleBuilder(bot, entry) {
  while (bot.entity) {
    const block = bot.inventory.items().find((i) =>
      /dirt|cobblestone|netherrack|wood|planks/i.test(i.name)
    );
    if (!block) {
      // No blocks — try to find one to mine first.
      const target = bot.findBlock({ matching: (b) => /dirt|stone/.test(b.name), maxDistance: 16 });
      if (target) {
        await bot.pathfinder.goto(new goals.GoalGetToBlock(target.position.x, target.position.y, target.position.z)).catch(() => {});
        await bot.dig(target).catch(() => {});
      } else {
        await wait(4000);
      }
      continue;
    }
    await bot.equip(block, 'hand').catch(() => {});
    // Try to place on the block directly below.
    const refBlock = bot.blockAt(bot.entity.position.offset(0, -1, 0));
    if (refBlock) {
      await bot.placeBlock(refBlock, new (require('vec3'))(0, 1, 0)).catch(() => {});
    }
    await wait(1500 + Math.random() * 1500);
  }
}

async function roleMiner(bot, entry) {
  while (bot.entity) {
    const target = bot.findBlock({
      matching: (b) => /stone|cobblestone|coal_ore|iron_ore|dirt|gravel/.test(b.name),
      maxDistance: 24,
    });
    if (target) {
      await bot.pathfinder.goto(new goals.GoalGetToBlock(target.position.x, target.position.y, target.position.z)).catch(() => {});
      await bot.dig(target).catch(() => {});
    } else {
      await wait(3000);
    }
    await wait(500 + Math.random() * 500);
  }
}

async function roleForager(bot, entry) {
  while (bot.entity) {
    if (bot.food < 18) {
      const food = bot.inventory.items().find((i) => /apple|bread|cooked|beef|porkchop|chicken|rabbit|carrot|baked/i.test(i.name));
      if (food) {
        await bot.equip(food, 'hand').catch(() => {});
        await bot.consume().catch(() => {});
      }
    }
    // Also wander a bit so foragers aren't perfectly stationary.
    const dest = pickClusterDestination(16);
    await smoothWalkTo(bot, dest, { withinBlocks: 3, timeoutMs: 12000 });
    await idleLook(bot, 3000 + Math.random() * 3000);
    await wait(2000 + Math.random() * 3000);
  }
}

async function roleSleeper(bot, entry) {
  while (bot.entity) {
    const bed = bot.findBlock({ matching: (b) => /_bed$/.test(b.name), maxDistance: 32 });
    if (bed) {
      try {
        await bot.pathfinder.goto(new goals.GoalNear(bed.position.x, bed.position.y, bed.position.z, 1));
        await bot.sleep(bed);
        await wait(8000);
        await bot.wake().catch(() => {});
      } catch (_) { /* ignored */ }
    }
    await wait(15000);
  }
}

async function roleTraveler(bot, entry) {
  // Walk far from spawn to churn chunk subscriptions across regions.
  // Use smooth walking instead of pathfinder so the motion looks continuous
  // from a third-party observer's perspective.
  while (bot.entity) {
    const angle = Math.random() * Math.PI * 2;
    const dist = 200 + Math.random() * 600;
    const target = {
      x: Math.round(bot.entity.position.x + Math.cos(angle) * dist),
      y: Math.round(bot.entity.position.y),
      z: Math.round(bot.entity.position.z + Math.sin(angle) * dist),
    };
    await smoothWalkTo(bot, target, { withinBlocks: 5, timeoutMs: 60000 });
    await wait(1500 + Math.random() * 1500);
  }
}

async function roleHorseman(bot, entry) {
  while (bot.entity) {
    const mount = bot.nearestEntity((e) => /horse|camel|donkey|mule|boat|pig/i.test(e.name || ''));
    if (mount && mount.position.distanceTo(bot.entity.position) < 24) {
      await bot.pathfinder.goto(new goals.GoalNear(mount.position.x, mount.position.y, mount.position.z, 1)).catch(() => {});
      try { await bot.mount(mount); } catch (_) { /* not always mountable */ }
      await wait(4000);
      // Move while mounted by issuing controlState changes.
      bot.setControlState('forward', true);
      await wait(8000);
      bot.setControlState('forward', false);
      try { bot.dismount(); } catch (_) {}
    }
    await wait(10000);
  }
}

// ── helpers ───────────────────────────────────────────────────────────────

function pickClusterDestination(radius) {
  const r = radius ?? CONFIG.clusterRadius;
  const a = Math.random() * Math.PI * 2;
  const d = Math.random() * r;
  const anchor = clusterAnchor ?? { x: 0, y: 64, z: 0 };
  return {
    x: Math.round(anchor.x + Math.cos(a) * d),
    y: Math.round(anchor.y),
    z: Math.round(anchor.z + Math.sin(a) * d),
  };
}

function wait(ms) {
  return new Promise((res) => setTimeout(res, ms));
}

// Resolves once the bot has touched ground (or after a 5s timeout so a
// stuck bot doesn't block forever). Walking before the spawn fall completes
// produces moves the server tags as "invalid_player_movement".
function waitForGround(bot, timeoutMs = 5000) {
  return new Promise((resolve) => {
    const start = Date.now();
    const tick = () => {
      if (!bot.entity) return resolve();
      if (bot.entity.onGround) return resolve();
      if (Date.now() - start > timeoutMs) return resolve();
      setTimeout(tick, 200);
    };
    tick();
  });
}

// Smooth, continuous walking that looks like a real player from the
// outside: face the target, hold "forward" pressed, periodically re-aim,
// jump if we're stuck against a single-block obstacle, stop when we're
// within `withinBlocks` of the target.
//
// This is intentionally NOT pathfinder — pathfinder issues big movement
// deltas that look choppy from a third-party perspective and burst
// position packets when many bots compute paths simultaneously.
async function smoothWalkTo(bot, target, { withinBlocks = 2, timeoutMs = 20000 } = {}) {
  if (!bot.entity) return;
  const start = Date.now();
  let lastPos = bot.entity.position.clone();
  let stuckCheckTime = Date.now();

  bot.setControlState('forward', true);
  try {
    while (bot.entity) {
      const pos = bot.entity.position;
      const dx = target.x - pos.x;
      const dz = target.z - pos.z;
      const dist = Math.hypot(dx, dz);
      if (dist <= withinBlocks) break;
      if (Date.now() - start > timeoutMs) break;

      // Face the target. The yaw-only lookAt keeps the head level which
      // is what a walking player normally does.
      const yaw = Math.atan2(-dx, -dz);
      await bot.look(yaw, 0, true).catch(() => {});

      // Stuck check: if we haven't moved >0.3 blocks in 1.5s, jump once.
      if (Date.now() - stuckCheckTime > 1500) {
        const moved = pos.distanceTo(lastPos);
        if (moved < 0.3) {
          bot.setControlState('jump', true);
          await wait(150);
          bot.setControlState('jump', false);
        }
        lastPos = pos.clone();
        stuckCheckTime = Date.now();
      }

      await wait(100);
    }
  } finally {
    bot.setControlState('forward', false);
  }
}

// Make the bot turn its head around naturally a few times. Used during
// idle pauses so bots don't look like statues between movements.
async function idleLook(bot, durationMs) {
  if (!bot.entity) return;
  const start = Date.now();
  while (bot.entity && Date.now() - start < durationMs) {
    const yaw = (Math.random() - 0.5) * Math.PI * 2;
    const pitch = (Math.random() - 0.5) * Math.PI / 3;
    await bot.look(yaw, pitch, true).catch(() => {});
    await wait(600 + Math.random() * 800);
  }
}

function formatVec(v) {
  return `(${v.x.toFixed(1)}, ${v.y.toFixed(1)}, ${v.z.toFixed(1)})`;
}

function truncate(s) {
  s = typeof s === 'string' ? s : JSON.stringify(s);
  return s.length > 120 ? s.slice(0, 120) + '…' : s;
}
