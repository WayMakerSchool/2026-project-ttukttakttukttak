# BLOCK RIVALS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Browser-based first-person team deathmatch (red vs blue, 4 weapons, max 10 players/room) with a single authoritative Node.js WebSocket server, deployable to Render free tier.

**Architecture:** One Node.js server (Express static + `ws`) owns hit detection, damage, kills, respawns, scores, and win condition; clients send position/input at 20Hz and the server broadcasts room snapshots at 20Hz. Client is Three.js + Vite + TypeScript with client-authoritative movement (server sanity-checks speed) and snapshot interpolation for remote players. Map and weapon stats live in `game/shared/` JSON consumed by both sides.

**Tech Stack:** Node.js 22 (ESM), `express`, `ws`, `vitest` (server tests), Three.js, Vite, TypeScript.

**Spec:** `docs/superpowers/specs/2026-06-11-block-rivals-design.md`

**중요 (환경):** 이 저장소는 Google Drive 스트리밍 폴더라 첫 `npm install`/빌드가 느릴 수 있다. `npm install`은 `run_in_background`로 실행할 것. Node 22는 keg-only로 설치되어 있음 — 모든 npm/node 명령 앞에 `export PATH="/opt/homebrew/opt/node@22/bin:$PATH"`를 붙인다 (이하 명령에서 `<NODE22>` 로 표기).

---

## File Structure

```
game/
  package.json            # server deps (express, ws) + scripts (build/start/test/dev)
  server/
    index.js              # HTTP(static) + WebSocket wiring, 20Hz tick loop
    rooms.js              # room registry: quick join / create / join by code
    game.js               # per-room authoritative state: input, shoot, kill, respawn, score, win
    geometry.js           # pure math: ray-vs-AABB, player hitbox, shot resolution
    protocol.js           # message shape validation (pure)
  server/test/
    geometry.test.js
    game.test.js
    rooms.test.js
    protocol.test.js
  shared/
    map.json              # arena blocks + team spawn points
    weapons.json          # weapon stats (single source of truth)
  client/
    package.json          # three, vite, typescript
    vite.config.ts
    index.html            # canvas + all HUD/menu DOM overlays
    tsconfig.json
    src/
      main.ts             # boot, menu screens, game loop glue
      net.ts              # WebSocket client + message types
      world.ts            # scene, lights, map meshes from map.json
      player.ts           # local controller: pointer lock, WASD, jump, sprint, AABB collision
      remotes.ts          # blocky avatars + snapshot interpolation + name tags
      shooting.ts         # fire/reload timers, tracer, send shoot
      hud.ts              # health/ammo/score/killfeed/scoreboard/death overlay
  test-bot.js             # headless ws bot for multiplayer smoke test
```

각 태스크 완료 시 커밋. 커밋은 반드시 `git add <명시된 파일만>` — 이 저장소에는 북스토어의 미커밋 변경이 많으므로 절대 `git add -A` / `git add .` 금지.

---

### Task 1: Scaffold server package + static serving

**Files:**
- Create: `game/package.json`
- Create: `game/server/index.js` (최소 골격)
- Create: `game/client/index.html` (placeholder)

- [ ] **Step 1: Write `game/package.json`**

```json
{
  "name": "block-rivals",
  "private": true,
  "type": "module",
  "scripts": {
    "start": "node server/index.js",
    "dev": "node server/index.js",
    "build": "cd client && npm install && npm run build",
    "test": "vitest run server/test"
  },
  "dependencies": {
    "express": "^4.19.2",
    "ws": "^8.18.0"
  },
  "devDependencies": {
    "vitest": "^2.1.9"
  }
}
```

- [ ] **Step 2: Write minimal `game/server/index.js`**

```js
import express from "express";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.join(__dirname, "..", "client", "dist");

const app = express();
app.get("/healthz", (_req, res) => res.json({ ok: true }));
app.use(express.static(DIST));

const server = http.createServer(app);
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`[block-rivals] listening on :${PORT}`));
```

- [ ] **Step 3: Write placeholder `game/client/index.html`** (Task 6에서 교체됨)

```html
<!doctype html><html><body>BLOCK RIVALS — building...</body></html>
```

- [ ] **Step 4: Install and verify**

Run (background): `cd game && <NODE22> npm install`
Then: `cd game && <NODE22> npm start` (background), `curl -s http://localhost:3000/healthz`
Expected: `{"ok":true}` — 확인 후 서버 종료.

- [ ] **Step 5: Commit**

```bash
git add game/package.json game/package-lock.json game/server/index.js game/client/index.html
git commit -m "feat(game): scaffold block-rivals server"
```

---

### Task 2: Shared data — weapons + map

**Files:**
- Create: `game/shared/weapons.json`
- Create: `game/shared/map.json`

- [ ] **Step 1: Write `game/shared/weapons.json`**

```json
{
  "rifle":   { "name": "돌격소총", "damage": 20,  "fireDelayMs": 150,  "magazine": 30, "reloadMs": 1800, "pellets": 1, "spread": 0.012, "range": 150, "auto": true },
  "shotgun": { "name": "샷건",     "damage": 12,  "fireDelayMs": 800,  "magazine": 6,  "reloadMs": 2400, "pellets": 8, "spread": 0.06,  "range": 45,  "auto": false },
  "smg":     { "name": "SMG",      "damage": 12,  "fireDelayMs": 80,   "magazine": 40, "reloadMs": 1600, "pellets": 1, "spread": 0.028, "range": 90,  "auto": true },
  "sniper":  { "name": "스나이퍼", "damage": 100, "fireDelayMs": 1500, "magazine": 5,  "reloadMs": 2600, "pellets": 1, "spread": 0.001, "range": 400, "auto": false }
}
```

- [ ] **Step 2: Write `game/shared/map.json`**

블록 규약: `pos` = [중심x, 바닥y, 중심z], `size` = [가로, 높이, 세로] (전체 길이). 맵은 80×80, 외벽 높이 6. 팀 스폰은 동/서 끝.

```json
{
  "bounds": { "x": 80, "z": 80 },
  "blocks": [
    { "pos": [0, -1, 0],    "size": [80, 1, 80], "color": "#8a9b6e" },
    { "pos": [0, 0, -41],   "size": [82, 6, 2],  "color": "#7d8a99" },
    { "pos": [0, 0, 41],    "size": [82, 6, 2],  "color": "#7d8a99" },
    { "pos": [-41, 0, 0],   "size": [2, 6, 82],  "color": "#7d8a99" },
    { "pos": [41, 0, 0],    "size": [2, 6, 82],  "color": "#7d8a99" },
    { "pos": [0, 0, 0],     "size": [10, 3, 10], "color": "#c9b458" },
    { "pos": [0, 3, 0],     "size": [6, 0.6, 6], "color": "#d8c878" },
    { "pos": [-14, 0, -14], "size": [4, 2.2, 4], "color": "#b85c5c" },
    { "pos": [14, 0, -14],  "size": [4, 2.2, 4], "color": "#5c7ab8" },
    { "pos": [-14, 0, 14],  "size": [4, 2.2, 4], "color": "#b85c5c" },
    { "pos": [14, 0, 14],   "size": [4, 2.2, 4], "color": "#5c7ab8" },
    { "pos": [0, 0, -22],   "size": [12, 1.2, 3], "color": "#9a9a9a" },
    { "pos": [0, 0, 22],    "size": [12, 1.2, 3], "color": "#9a9a9a" },
    { "pos": [-26, 0, 0],   "size": [3, 1.2, 14], "color": "#9a9a9a" },
    { "pos": [26, 0, 0],    "size": [3, 1.2, 14], "color": "#9a9a9a" },
    { "pos": [-8, 0, -28],  "size": [5, 1.6, 2],  "color": "#a87f4f" },
    { "pos": [8, 0, 28],    "size": [5, 1.6, 2],  "color": "#a87f4f" },
    { "pos": [-20, 0, -26], "size": [2, 4, 2],    "color": "#6e6e6e" },
    { "pos": [20, 0, 26],   "size": [2, 4, 2],    "color": "#6e6e6e" }
  ],
  "spawns": {
    "red":  [[-34, 0, -10], [-34, 0, 0], [-34, 0, 10], [-30, 0, -5], [-30, 0, 5]],
    "blue": [[34, 0, -10],  [34, 0, 0],  [34, 0, 10],  [30, 0, -5],  [30, 0, 5]]
  }
}
```

- [ ] **Step 3: Validate JSON parses**

Run: `<NODE22> node -e "JSON.parse(require('fs').readFileSync('game/shared/weapons.json'));JSON.parse(require('fs').readFileSync('game/shared/map.json'));console.log('OK')"`
Expected: `OK`

- [ ] **Step 4: Commit**

```bash
git add game/shared/weapons.json game/shared/map.json
git commit -m "feat(game): shared weapon stats and arena map data"
```

---

### Task 3: Server geometry (TDD)

**Files:**
- Create: `game/server/geometry.js`
- Test: `game/server/test/geometry.test.js`

플레이어 히트박스: `pos`=발 중심, 폭 0.9, 높이 1.8.

- [ ] **Step 1: Write the failing tests**

```js
import { describe, it, expect } from "vitest";
import { rayAABB, blockAABB, playerAABB, resolveShot } from "../geometry.js";

describe("rayAABB", () => {
  const min = [-1, -1, -1], max = [1, 1, 1];
  it("hits a box straight ahead", () => {
    expect(rayAABB([0, 0, -5], [0, 0, 1], min, max)).toBeCloseTo(4);
  });
  it("misses a box to the side", () => {
    expect(rayAABB([0, 5, -5], [0, 0, 1], min, max)).toBeNull();
  });
  it("returns 0 when origin is inside", () => {
    expect(rayAABB([0, 0, 0], [0, 0, 1], min, max)).toBe(0);
  });
  it("ignores boxes behind the ray", () => {
    expect(rayAABB([0, 0, 5], [0, 0, 1], min, max)).toBeNull();
  });
});

describe("blockAABB", () => {
  it("converts pos(center-x, base-y, center-z) + size to min/max", () => {
    const { min, max } = blockAABB({ pos: [0, 0, 0], size: [4, 2, 6] });
    expect(min).toEqual([-2, 0, -3]);
    expect(max).toEqual([2, 2, 3]);
  });
});

describe("resolveShot", () => {
  const players = [
    { id: "a", alive: true, pos: [0, 0, 10] },
    { id: "b", alive: true, pos: [0, 0, 20] },
    { id: "dead", alive: false, pos: [0, 0, 5] },
  ];
  it("hits the nearest living player, skipping shooter and dead", () => {
    const r = resolveShot([0, 1, 0], [0, 0, 1], "self", players, [], 100);
    expect(r.hitId).toBe("a");
  });
  it("is blocked by a wall in front of the player", () => {
    const wall = { pos: [0, 0, 7], size: [10, 5, 1] };
    const r = resolveShot([0, 1, 0], [0, 0, 1], "self", players, [wall], 100);
    expect(r.hitId).toBeNull();
  });
  it("respects weapon range", () => {
    const r = resolveShot([0, 1, 0], [0, 0, 1], "self", players, [], 8);
    expect(r.hitId).toBeNull();
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd game && <NODE22> npx vitest run server/test/geometry.test.js`
Expected: FAIL — `Cannot find module '../geometry.js'`

- [ ] **Step 3: Write `game/server/geometry.js`**

```js
// 슬랩 기법 ray-vs-AABB. 전방 교차 거리(t>=0) 또는 null.
export function rayAABB(origin, dir, min, max) {
  let tmin = 0;
  let tmax = Infinity;
  for (let i = 0; i < 3; i++) {
    if (Math.abs(dir[i]) < 1e-9) {
      if (origin[i] < min[i] || origin[i] > max[i]) return null;
    } else {
      let t1 = (min[i] - origin[i]) / dir[i];
      let t2 = (max[i] - origin[i]) / dir[i];
      if (t1 > t2) [t1, t2] = [t2, t1];
      tmin = Math.max(tmin, t1);
      tmax = Math.min(tmax, t2);
      if (tmin > tmax) return null;
    }
  }
  return tmin;
}

export function blockAABB(block) {
  const [x, y, z] = block.pos;
  const [sx, sy, sz] = block.size;
  return { min: [x - sx / 2, y, z - sz / 2], max: [x + sx / 2, y + sy, z + sz / 2] };
}

export const PLAYER_W = 0.9;
export const PLAYER_H = 1.8;

export function playerAABB(pos) {
  const h = PLAYER_W / 2;
  return { min: [pos[0] - h, pos[1], pos[2] - h], max: [pos[0] + h, pos[1] + PLAYER_H, pos[2] + h] };
}

// 한 발(펠릿 1개)의 판정: 벽까지 거리 안에서 가장 가까운 생존자.
export function resolveShot(origin, dir, shooterId, players, blocks, range) {
  let limit = range;
  for (const b of blocks) {
    const { min, max } = blockAABB(b);
    const t = rayAABB(origin, dir, min, max);
    if (t !== null && t < limit) limit = t;
  }
  let hitId = null;
  let hitT = limit;
  for (const p of players) {
    if (p.id === shooterId || !p.alive) continue;
    const { min, max } = playerAABB(p.pos);
    const t = rayAABB(origin, dir, min, max);
    if (t !== null && t < hitT) {
      hitT = t;
      hitId = p.id;
    }
  }
  return { hitId, dist: hitT };
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd game && <NODE22> npx vitest run server/test/geometry.test.js`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add game/server/geometry.js game/server/test/geometry.test.js
git commit -m "feat(game): ray-AABB shot resolution with tests"
```

---

### Task 4: Server game state — damage, kills, respawn, score, win (TDD)

**Files:**
- Create: `game/server/game.js`
- Test: `game/server/test/game.test.js`

- [ ] **Step 1: Write the failing tests**

```js
import { describe, it, expect } from "vitest";
import { Game, WIN_KILLS, RESPAWN_MS, RESET_MS } from "../game.js";

// rng를 0.5로 고정하면 spread 오프셋이 (2*0.5-1)=0 → 탄퍼짐 없음.
// 실제 맵은 중앙에 단상 블록이 있어 테스트 사선이 막히므로, 장애물 없는 테스트 맵 사용.
const TEST_MAP = {
  bounds: { x: 80, z: 80 },
  blocks: [],
  spawns: { red: [[-34, 0, 0]], blue: [[34, 0, 0]] },
};
const makeGame = () => new Game({ map: TEST_MAP, rng: () => 0.5 });

// a(red)가 b(blue)를 정면에서 쏘도록 배치
function setupDuel(g, now = 1000) {
  const a = g.addPlayer("a", "철수", "rifle", now);
  const b = g.addPlayer("b", "영희", "rifle", now);
  a.pos = [0, 0, 0];
  b.pos = [0, 0, 10];
  return { a, b };
}
const shootOnce = (g, now) => g.shoot("a", [0, 1.6, 0], [0, 0, 1], now);

describe("teams", () => {
  it("balances teams on join", () => {
    const g = makeGame();
    const teams = ["p1", "p2", "p3", "p4"].map((id) => g.addPlayer(id, id, "rifle", 0).team);
    expect(teams.filter((t) => t === "red").length).toBe(2);
    expect(teams.filter((t) => t === "blue").length).toBe(2);
  });
});

describe("shooting", () => {
  it("applies rifle damage on hit", () => {
    const g = makeGame();
    const { b } = setupDuel(g);
    const events = shootOnce(g, 2000);
    expect(b.hp).toBe(80);
    expect(events.some((e) => e.type === "hit" && e.victim === "b")).toBe(true);
  });
  it("enforces fire rate", () => {
    const g = makeGame();
    const { b } = setupDuel(g);
    shootOnce(g, 2000);
    shootOnce(g, 2010); // 150ms 쿨다운 무시하고 연사 시도
    expect(b.hp).toBe(80);
  });
  it("rejects an origin far from the player (cheat guard)", () => {
    const g = makeGame();
    const { b } = setupDuel(g);
    g.shoot("a", [0, 1.6, 9], [0, 0, 1], 2000); // 9m 떨어진 위치에서 발사 주장
    expect(b.hp).toBe(100);
  });
  it("kills at 0hp, scores the kill, schedules respawn", () => {
    const g = makeGame();
    const { a, b } = setupDuel(g);
    let now = 2000;
    let events = [];
    for (let i = 0; i < 5; i++) { events = shootOnce(g, now); now += 200; }
    expect(b.alive).toBe(false);
    expect(b.respawnAt).toBe(now - 200 + RESPAWN_MS);
    expect(a.kills).toBe(1);
    expect(b.deaths).toBe(1);
    expect(g.scores[a.team]).toBe(1);
    expect(events.some((e) => e.type === "kill" && e.killer === "a")).toBe(true);
  });
  it("sniper kills in one shot", () => {
    const g = makeGame();
    const { b } = setupDuel(g);
    g.players.get("a").weapon = "sniper";
    shootOnce(g, 2000);
    expect(b.alive).toBe(false);
  });
});

describe("respawn & reset", () => {
  it("respawns a dead player after RESPAWN_MS at a team spawn", () => {
    const g = makeGame();
    const { b } = setupDuel(g);
    b.alive = false; b.hp = 0; b.respawnAt = 5000;
    const events = g.tick(5001);
    expect(b.alive).toBe(true);
    expect(b.hp).toBe(100);
    expect(g.map.spawns[b.team].some((s) => s[0] === b.pos[0] && s[2] === b.pos[2])).toBe(true);
    expect(events.some((e) => e.type === "respawn" && e.id === "b")).toBe(true);
  });
  it("declares winner at WIN_KILLS and resets after RESET_MS", () => {
    const g = makeGame();
    const { a } = setupDuel(g);
    g.scores[a.team] = WIN_KILLS - 1;
    const events = shootOnce(g, 2000).concat(
      shootOnce(g, 2200), shootOnce(g, 2400), shootOnce(g, 2600), shootOnce(g, 2800));
    expect(g.winner).toBe(a.team);
    expect(events.some((e) => e.type === "gameover" && e.winner === a.team)).toBe(true);
    const resetEvents = g.tick(2800 + RESET_MS + 1);
    expect(g.winner).toBeNull();
    expect(g.scores).toEqual({ red: 0, blue: 0 });
    expect(a.kills).toBe(0);
    expect(resetEvents.some((e) => e.type === "reset")).toBe(true);
  });
  it("only allows weapon change while dead", () => {
    const g = makeGame();
    const { b } = setupDuel(g);
    g.setWeapon("b", "shotgun");
    expect(b.weapon).toBe("rifle");
    b.alive = false;
    g.setWeapon("b", "shotgun");
    expect(b.weapon).toBe("shotgun");
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd game && <NODE22> npx vitest run server/test/game.test.js`
Expected: FAIL — `Cannot find module '../game.js'`

- [ ] **Step 3: Write `game/server/game.js`**

```js
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { resolveShot } from "./geometry.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SHARED = path.join(__dirname, "..", "shared");
export const WEAPONS = JSON.parse(fs.readFileSync(path.join(SHARED, "weapons.json"), "utf8"));
export const MAP = JSON.parse(fs.readFileSync(path.join(SHARED, "map.json"), "utf8"));

export const WIN_KILLS = 30;
export const RESPAWN_MS = 3000;
export const RESET_MS = 10000;
const MAX_SPEED = 14;        // 달리기 12 + 여유
const FIRE_TOLERANCE = 0.8;  // 네트워크 지터 허용 비율
const EYE_HEIGHT = 1.6;
const MAX_ORIGIN_DRIFT = 2.5;

export class Game {
  constructor({ map = MAP, weapons = WEAPONS, rng = Math.random } = {}) {
    this.map = map;
    this.weapons = weapons;
    this.rng = rng;
    this.players = new Map();
    this.scores = { red: 0, blue: 0 };
    this.winner = null;
    this.resetAt = null;
  }

  pickTeam() {
    let red = 0, blue = 0;
    for (const p of this.players.values()) p.team === "red" ? red++ : blue++;
    return red <= blue ? "red" : "blue";
  }

  spawnPos(team) {
    const list = this.map.spawns[team];
    return [...list[Math.floor(this.rng() * list.length) % list.length]];
  }

  addPlayer(id, nick, weapon, now) {
    const team = this.pickTeam();
    const p = {
      id, nick, team,
      weapon: this.weapons[weapon] ? weapon : "rifle",
      pos: this.spawnPos(team), yaw: 0, pitch: 0, anim: "idle",
      hp: 100, alive: true, kills: 0, deaths: 0,
      lastShotAt: 0, respawnAt: null, lastInputAt: now,
    };
    this.players.set(id, p);
    return p;
  }

  removePlayer(id) { this.players.delete(id); }

  setWeapon(id, weapon) {
    const p = this.players.get(id);
    if (p && !p.alive && this.weapons[weapon]) p.weapon = weapon;
  }

  applyInput(id, pos, yaw, pitch, anim, now) {
    const p = this.players.get(id);
    if (!p || !p.alive || this.winner) return;
    const dt = Math.max((now - p.lastInputAt) / 1000, 0.01);
    const dist = Math.hypot(pos[0] - p.pos[0], pos[2] - p.pos[2]);
    if (dist <= MAX_SPEED * dt * 1.5) {
      p.pos = [pos[0], Math.max(-5, Math.min(50, pos[1])), pos[2]];
    }
    const bx = this.map.bounds.x / 2, bz = this.map.bounds.z / 2;
    p.pos[0] = Math.max(-bx, Math.min(bx, p.pos[0]));
    p.pos[2] = Math.max(-bz, Math.min(bz, p.pos[2]));
    p.yaw = yaw; p.pitch = pitch; p.anim = anim;
    p.lastInputAt = now;
  }

  applySpread(dir, spread) {
    const d = [
      dir[0] + (this.rng() * 2 - 1) * spread,
      dir[1] + (this.rng() * 2 - 1) * spread,
      dir[2] + (this.rng() * 2 - 1) * spread,
    ];
    const len = Math.hypot(d[0], d[1], d[2]) || 1;
    return [d[0] / len, d[1] / len, d[2] / len];
  }

  shoot(id, origin, dir, now) {
    const events = [];
    const p = this.players.get(id);
    if (!p || !p.alive || this.winner) return events;
    const w = this.weapons[p.weapon];
    if (now - p.lastShotAt < w.fireDelayMs * FIRE_TOLERANCE) return events;
    const eye = [p.pos[0], p.pos[1] + EYE_HEIGHT, p.pos[2]];
    const drift = Math.hypot(origin[0] - eye[0], origin[1] - eye[1], origin[2] - eye[2]);
    if (drift > MAX_ORIGIN_DRIFT) return events;
    p.lastShotAt = now;

    const damageBy = new Map();
    const alive = [...this.players.values()];
    for (let i = 0; i < w.pellets; i++) {
      const d = this.applySpread(dir, w.spread);
      const { hitId } = resolveShot(origin, d, id, alive, this.map.blocks, w.range);
      if (hitId) damageBy.set(hitId, (damageBy.get(hitId) || 0) + w.damage);
    }

    for (const [victimId, dmg] of damageBy) {
      const v = this.players.get(victimId);
      if (!v || !v.alive) continue;
      v.hp = Math.max(0, v.hp - dmg);
      events.push({ type: "hit", victim: victimId, shooter: id, hp: v.hp });
      if (v.hp === 0) {
        v.alive = false;
        v.deaths += 1;
        v.respawnAt = now + RESPAWN_MS;
        p.kills += 1;
        this.scores[p.team] += 1;
        events.push({
          type: "kill", killer: id, killerNick: p.nick, victim: victimId,
          victimNick: v.nick, weapon: p.weapon, scores: { ...this.scores },
        });
        if (!this.winner && this.scores[p.team] >= WIN_KILLS) {
          this.winner = p.team;
          this.resetAt = now + RESET_MS;
          events.push({ type: "gameover", winner: p.team, scores: { ...this.scores } });
        }
      }
    }
    return events;
  }

  tick(now) {
    const events = [];
    for (const p of this.players.values()) {
      if (!p.alive && p.respawnAt !== null && now >= p.respawnAt && !this.winner) {
        p.alive = true; p.hp = 100; p.respawnAt = null;
        p.pos = this.spawnPos(p.team);
        p.lastInputAt = now;
        events.push({ type: "respawn", id: p.id, pos: p.pos });
      }
    }
    if (this.winner && now >= this.resetAt) {
      this.winner = null;
      this.resetAt = null;
      this.scores = { red: 0, blue: 0 };
      for (const p of this.players.values()) {
        p.kills = 0; p.deaths = 0; p.hp = 100; p.alive = true; p.respawnAt = null;
        p.pos = this.spawnPos(p.team);
        p.lastInputAt = now;
        // 클라이언트가 새 스폰 위치로 이동하도록 리스폰 이벤트도 함께 방송
        events.push({ type: "respawn", id: p.id, pos: p.pos });
      }
      events.push({ type: "reset" });
    }
    return events;
  }

  snapshot() {
    return {
      players: [...this.players.values()].map((p) => ({
        id: p.id, nick: p.nick, team: p.team, pos: p.pos, yaw: p.yaw, pitch: p.pitch,
        anim: p.anim, hp: p.hp, alive: p.alive, kills: p.kills, deaths: p.deaths, weapon: p.weapon,
      })),
      scores: this.scores,
      winner: this.winner,
    };
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd game && <NODE22> npx vitest run server/test/game.test.js`
Expected: PASS (9 tests)

- [ ] **Step 5: Commit**

```bash
git add game/server/game.js game/server/test/game.test.js
git commit -m "feat(game): authoritative game state with kill/respawn/win logic"
```

---

### Task 5: Protocol validation + room manager (TDD)

**Files:**
- Create: `game/server/protocol.js`, `game/server/rooms.js`
- Test: `game/server/test/protocol.test.js`, `game/server/test/rooms.test.js`

- [ ] **Step 1: Write the failing tests**

`game/server/test/protocol.test.js`:

```js
import { describe, it, expect } from "vitest";
import { validate, sanitizeNick } from "../protocol.js";

describe("sanitizeNick", () => {
  it("trims and caps at 12 chars", () => {
    expect(sanitizeNick("  철수  ")).toBe("철수");
    expect(sanitizeNick("a".repeat(30))).toBe("a".repeat(12));
  });
  it("rejects empty or non-string", () => {
    expect(sanitizeNick("   ")).toBeNull();
    expect(sanitizeNick(5)).toBeNull();
  });
});

describe("validate", () => {
  it("accepts well-formed messages", () => {
    expect(validate({ type: "join", mode: "quick", nick: "철수" })).toBe(true);
    expect(validate({ type: "join", mode: "join", nick: "철수", code: "1234" })).toBe(true);
    expect(validate({ type: "input", pos: [0, 1, 2], yaw: 0, pitch: 0, anim: "run" })).toBe(true);
    expect(validate({ type: "shoot", origin: [0, 0, 0], dir: [0, 0, 1] })).toBe(true);
    expect(validate({ type: "weapon", weapon: "smg" })).toBe(true);
  });
  it("rejects malformed messages", () => {
    expect(validate(null)).toBe(false);
    expect(validate({ type: "hack" })).toBe(false);
    expect(validate({ type: "join", mode: "join", nick: "x" })).toBe(false);
    expect(validate({ type: "input", pos: [0, NaN, 2], yaw: 0, pitch: 0, anim: "run" })).toBe(false);
    expect(validate({ type: "shoot", origin: [0, 0], dir: [0, 0, 1] })).toBe(false);
  });
});
```

`game/server/test/rooms.test.js`:

```js
import { describe, it, expect } from "vitest";
import { RoomManager, MAX_PLAYERS } from "../rooms.js";

describe("RoomManager", () => {
  it("quick join reuses the PUBLIC room", () => {
    const rm = new RoomManager();
    expect(rm.quickJoin()).toBe(rm.quickJoin());
    expect(rm.quickJoin().code).toBe("PUBLIC");
  });
  it("create makes a 4-digit code room", () => {
    const rm = new RoomManager();
    expect(rm.create().code).toMatch(/^\d{4}$/);
  });
  it("join by unknown code fails", () => {
    expect(new RoomManager().join("0000").error).toBe("no_room");
  });
  it("join rejects a full room", () => {
    const rm = new RoomManager();
    const room = rm.create();
    for (let i = 0; i < MAX_PLAYERS; i++) room.clients.set(`p${i}`, {});
    expect(rm.join(room.code).error).toBe("full");
  });
  it("removes a room when the last player leaves", () => {
    const rm = new RoomManager();
    const room = rm.create();
    room.clients.set("p1", {});
    room.game.addPlayer("p1", "철수", "rifle", 0);
    rm.leave(room, "p1");
    expect(rm.rooms.has(room.code)).toBe(false);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd game && <NODE22> npx vitest run server/test/protocol.test.js server/test/rooms.test.js`
Expected: FAIL — modules not found

- [ ] **Step 3: Write `game/server/protocol.js`**

```js
const isNum = (v) => typeof v === "number" && Number.isFinite(v);
const isVec3 = (v) => Array.isArray(v) && v.length === 3 && v.every(isNum);

export function sanitizeNick(nick) {
  if (typeof nick !== "string") return null;
  const t = nick.trim().slice(0, 12);
  return t.length ? t : null;
}

export function validate(msg) {
  if (!msg || typeof msg !== "object") return false;
  switch (msg.type) {
    case "join":
      return ["quick", "create", "join"].includes(msg.mode)
        && sanitizeNick(msg.nick) !== null
        && (msg.mode !== "join" || typeof msg.code === "string")
        && (msg.weapon === undefined || typeof msg.weapon === "string");
    case "input":
      return isVec3(msg.pos) && isNum(msg.yaw) && isNum(msg.pitch) && typeof msg.anim === "string";
    case "shoot":
      return isVec3(msg.origin) && isVec3(msg.dir);
    case "weapon":
      return typeof msg.weapon === "string";
    default:
      return false;
  }
}
```

- [ ] **Step 4: Write `game/server/rooms.js`**

```js
import { Game } from "./game.js";

export const MAX_PLAYERS = 10;

export class RoomManager {
  constructor(rng = Math.random) {
    this.rooms = new Map(); // code -> { code, game, clients: Map<playerId, ws> }
    this.rng = rng;
  }

  makeCode() {
    let code;
    do { code = String(Math.floor(this.rng() * 9000) + 1000); } while (this.rooms.has(code));
    return code;
  }

  getOrCreate(code) {
    if (!this.rooms.has(code)) {
      this.rooms.set(code, { code, game: new Game(), clients: new Map() });
    }
    return this.rooms.get(code);
  }

  quickJoin() { return this.getOrCreate("PUBLIC"); }
  create() { return this.getOrCreate(this.makeCode()); }

  join(code) {
    const room = this.rooms.get(code);
    if (!room) return { error: "no_room" };
    if (room.clients.size >= MAX_PLAYERS) return { error: "full" };
    return { room };
  }

  leave(room, playerId) {
    room.clients.delete(playerId);
    room.game.removePlayer(playerId);
    if (room.clients.size === 0) this.rooms.delete(room.code);
  }
}
```

- [ ] **Step 5: Run all server tests**

Run: `cd game && <NODE22> npm test`
Expected: PASS — geometry + game + protocol + rooms 모두 통과

- [ ] **Step 6: Commit**

```bash
git add game/server/protocol.js game/server/rooms.js game/server/test/protocol.test.js game/server/test/rooms.test.js
git commit -m "feat(game): message validation and room manager"
```

---

### Task 6: WebSocket wiring + 20Hz tick loop

**Files:**
- Modify: `game/server/index.js` (전체 교체)

- [ ] **Step 1: Replace `game/server/index.js`**

```js
import express from "express";
import http from "node:http";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { WebSocketServer } from "ws";
import { RoomManager } from "./rooms.js";
import { validate, sanitizeNick } from "./protocol.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.join(__dirname, "..", "client", "dist");

const app = express();
app.get("/healthz", (_req, res) => res.json({ ok: true }));
app.use(express.static(DIST));

const server = http.createServer(app);
const wss = new WebSocketServer({ server });
const rooms = new RoomManager();

function send(ws, msg) {
  if (ws.readyState === 1) ws.send(JSON.stringify(msg));
}
function broadcast(room, msg) {
  const s = JSON.stringify(msg);
  for (const ws of room.clients.values()) if (ws.readyState === 1) ws.send(s);
}

wss.on("connection", (ws) => {
  let room = null;
  let playerId = null;

  ws.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (!validate(msg)) return;
    const now = Date.now();

    if (msg.type === "join" && !room) {
      // quickJoin/create/join 모두 { room } 또는 { error } 반환 (정원·코드 고갈 검사는 RoomManager 책임)
      let res;
      if (msg.mode === "quick") res = rooms.quickJoin();
      else if (msg.mode === "create") res = rooms.create();
      else res = rooms.join(String(msg.code).trim());
      if (res.error) return send(ws, { type: "error", error: res.error });
      const r = res.room;
      playerId = crypto.randomUUID().slice(0, 8);
      room = r;
      room.clients.set(playerId, ws);
      const p = room.game.addPlayer(playerId, sanitizeNick(msg.nick), msg.weapon, now);
      send(ws, { type: "welcome", id: playerId, team: p.team, code: room.code, pos: p.pos });
      broadcast(room, { type: "joined", id: playerId, nick: p.nick, team: p.team });
      return;
    }

    if (!room || !playerId) return;
    if (msg.type === "input") {
      room.game.applyInput(playerId, msg.pos, msg.yaw, msg.pitch, msg.anim, now);
    } else if (msg.type === "shoot") {
      for (const e of room.game.shoot(playerId, msg.origin, msg.dir, now)) broadcast(room, e);
    } else if (msg.type === "weapon") {
      room.game.setWeapon(playerId, msg.weapon);
    }
  });

  ws.on("close", () => {
    if (room && playerId) {
      const r = room;
      rooms.leave(r, playerId);
      broadcast(r, { type: "left", id: playerId });
      room = null;
    }
  });
});

setInterval(() => {
  const now = Date.now();
  for (const room of rooms.rooms.values()) {
    for (const e of room.game.tick(now)) broadcast(room, e);
    broadcast(room, { type: "state", ...room.game.snapshot() });
  }
}, 50);

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`[block-rivals] listening on :${PORT}`));
```

- [ ] **Step 2: Smoke test with a ws client**

서버 실행(background): `cd game && <NODE22> npm start`
그다음:

```bash
cd game && <NODE22> node -e '
import("ws").then(({ default: WebSocket }) => {
  const ws = new WebSocket("ws://localhost:3000");
  ws.on("open", () => ws.send(JSON.stringify({ type: "join", mode: "quick", nick: "봇" })));
  ws.on("message", (d) => {
    const m = JSON.parse(d);
    if (m.type === "welcome") console.log("WELCOME team=" + m.team);
    if (m.type === "state") { console.log("STATE players=" + m.players.length); process.exit(0); }
  });
  setTimeout(() => { console.error("TIMEOUT"); process.exit(1); }, 4000);
});'
```

Expected: `WELCOME team=red` 와 `STATE players=1` 출력 후 종료. 확인 후 서버 종료.

- [ ] **Step 3: Commit**

```bash
git add game/server/index.js
git commit -m "feat(game): websocket join/input/shoot wiring with 20Hz broadcast"
```

---

### Task 7: Client scaffold + world rendering

**Files:**
- Create: `game/client/package.json`, `game/client/vite.config.ts`, `game/client/tsconfig.json`
- Create: `game/client/src/world.ts`, `game/client/src/main.ts` (1차 버전)
- Modify: `game/client/index.html` (전체 교체)

- [ ] **Step 1: Write `game/client/package.json`**

```json
{
  "name": "block-rivals-client",
  "private": true,
  "type": "module",
  "scripts": { "dev": "vite", "build": "vite build" },
  "dependencies": { "three": "^0.169.0" },
  "devDependencies": { "@types/three": "^0.169.0", "typescript": "^5.6.3", "vite": "^5.4.19" }
}
```

- [ ] **Step 2: Write `game/client/vite.config.ts`** (shared/ JSON을 루트 밖에서 import 허용)

```ts
import { defineConfig } from "vite";
export default defineConfig({
  server: { fs: { allow: [".."] } },
});
```

- [ ] **Step 3: Write `game/client/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "lib": ["ES2022", "DOM"],
    "strict": true,
    "resolveJsonModule": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["src"]
}
```

- [ ] **Step 4: Replace `game/client/index.html`** (메뉴 + HUD DOM 전부 포함)

```html
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>BLOCK RIVALS</title>
  <style>
    * { margin: 0; box-sizing: border-box; font-family: 'Trebuchet MS', sans-serif; }
    body { overflow: hidden; background: #111; }
    canvas { display: block; }
    .hidden { display: none !important; }
    #menu { position: fixed; inset: 0; background: linear-gradient(160deg, #1a2440, #3b1f4e);
      display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; color: #fff; z-index: 30; }
    #menu h1 { font-size: 52px; letter-spacing: 4px; text-shadow: 0 4px 0 #000; }
    #menu input { padding: 12px 16px; font-size: 18px; border-radius: 8px; border: none; width: 240px; text-align: center; }
    #menu button { padding: 12px 28px; font-size: 18px; border-radius: 8px; border: none; cursor: pointer;
      background: #f5c542; font-weight: bold; }
    #menu button:hover { background: #ffd95e; }
    #menu .row { display: flex; gap: 8px; }
    #weaponPick { display: flex; gap: 8px; }
    #weaponPick button { background: #2c3e63; color: #fff; font-size: 14px; padding: 10px 14px; }
    #weaponPick button.sel { background: #f5c542; color: #000; }
    #menuError { color: #ff8080; min-height: 20px; }
    #hud { position: fixed; inset: 0; pointer-events: none; z-index: 20; color: #fff; }
    #crosshair { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); font-size: 22px; }
    #scores { position: absolute; top: 12px; left: 50%; transform: translateX(-50%);
      font-size: 28px; font-weight: bold; background: rgba(0,0,0,.45); padding: 6px 20px; border-radius: 10px; }
    #scoreRed { color: #ff6b6b; } #scoreBlue { color: #6b9bff; }
    #roomCode { position: absolute; top: 12px; right: 14px; background: rgba(0,0,0,.45); padding: 6px 12px; border-radius: 8px; }
    #killfeed { position: absolute; top: 64px; right: 14px; text-align: right; font-size: 14px; }
    #killfeed div { background: rgba(0,0,0,.45); margin-top: 4px; padding: 4px 10px; border-radius: 6px; }
    #health { position: absolute; bottom: 18px; left: 18px; font-size: 30px; font-weight: bold; text-shadow: 0 2px 0 #000; }
    #ammo { position: absolute; bottom: 18px; right: 18px; font-size: 26px; font-weight: bold; text-shadow: 0 2px 0 #000; }
    #banner { position: absolute; top: 30%; left: 50%; transform: translateX(-50%);
      font-size: 40px; font-weight: bold; text-shadow: 0 3px 0 #000; }
    #deathOverlay { position: absolute; inset: 0; background: rgba(120,0,0,.35);
      display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; pointer-events: auto; }
    #deathOverlay h2 { font-size: 36px; text-shadow: 0 3px 0 #000; }
    #deathWeapons { display: flex; gap: 8px; }
    #deathWeapons button { padding: 10px 16px; border: none; border-radius: 8px; cursor: pointer;
      background: #2c3e63; color: #fff; font-size: 15px; }
    #deathWeapons button.sel { background: #f5c542; color: #000; }
    #scoreboard { position: absolute; top: 15%; left: 50%; transform: translateX(-50%);
      background: rgba(0,0,0,.75); padding: 16px 24px; border-radius: 12px; min-width: 420px; }
    #scoreboard table { width: 100%; border-collapse: collapse; font-size: 15px; }
    #scoreboard td, #scoreboard th { padding: 4px 10px; text-align: left; }
    #flash { position: absolute; inset: 0; background: rgba(255,0,0,.25); opacity: 0; transition: opacity .25s; }
  </style>
</head>
<body>
  <div id="menu">
    <h1>BLOCK RIVALS</h1>
    <input id="nick" maxlength="12" placeholder="닉네임" />
    <div id="weaponPick"></div>
    <button id="quickBtn">⚡ 빠른 입장</button>
    <div class="row">
      <button id="createBtn">방 만들기</button>
      <input id="codeInput" maxlength="6" placeholder="방 코드" style="width:120px" />
      <button id="joinBtn">코드 입장</button>
    </div>
    <div id="menuError"></div>
  </div>
  <div id="hud" class="hidden">
    <div id="flash"></div>
    <div id="crosshair">+</div>
    <div id="scores"><span id="scoreRed">0</span> : <span id="scoreBlue">0</span></div>
    <div id="roomCode"></div>
    <div id="killfeed"></div>
    <div id="health">100</div>
    <div id="ammo">30 / 30</div>
    <div id="banner" class="hidden"></div>
    <div id="scoreboard" class="hidden"></div>
    <div id="deathOverlay" class="hidden">
      <h2 id="deathMsg">사망!</h2>
      <div>리스폰까지 <span id="respawnTimer">3</span>초 — 무기 변경 가능</div>
      <div id="deathWeapons"></div>
    </div>
  </div>
  <script type="module" src="/src/main.ts"></script>
</body>
</html>
```

- [ ] **Step 5: Write `game/client/src/world.ts`**

```ts
import * as THREE from "three";
import mapData from "../../shared/map.json";

export interface BlockBox { min: THREE.Vector3; max: THREE.Vector3; }

export function createWorld() {
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x87ceeb);
  scene.fog = new THREE.Fog(0x87ceeb, 70, 170);
  scene.add(new THREE.HemisphereLight(0xffffff, 0x88aa66, 0.9));
  const sun = new THREE.DirectionalLight(0xffffff, 1.2);
  sun.position.set(30, 50, 20);
  scene.add(sun);

  const colliders: BlockBox[] = [];
  for (const b of mapData.blocks) {
    const [x, y, z] = b.pos;
    const [sx, sy, sz] = b.size;
    const mesh = new THREE.Mesh(
      new THREE.BoxGeometry(sx, sy, sz),
      new THREE.MeshLambertMaterial({ color: b.color }),
    );
    mesh.position.set(x, y + sy / 2, z);
    scene.add(mesh);
    colliders.push({
      min: new THREE.Vector3(x - sx / 2, y, z - sz / 2),
      max: new THREE.Vector3(x + sx / 2, y + sy, z + sz / 2),
    });
  }
  return { scene, colliders, mapData };
}
```

- [ ] **Step 6: Write 1차 `game/client/src/main.ts`** (맵 확인용 — Task 9에서 교체)

```ts
import * as THREE from "three";
import { createWorld } from "./world";

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(innerWidth, innerHeight);
document.body.appendChild(renderer.domElement);
const camera = new THREE.PerspectiveCamera(75, innerWidth / innerHeight, 0.1, 500);
camera.position.set(0, 25, 45);
camera.lookAt(0, 0, 0);
const { scene } = createWorld();
addEventListener("resize", () => {
  renderer.setSize(innerWidth, innerHeight);
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
});
renderer.setAnimationLoop(() => renderer.render(scene, camera));
```

- [ ] **Step 7: Install and verify visually**

Run (background): `cd game/client && <NODE22> npm install`
Then (background): `cd game/client && <NODE22> npm run dev`
브라우저(또는 스크린샷 도구)로 `http://localhost:5173` 열기.
Expected: 하늘색 배경 위 블록 아레나(초록 바닥, 회색 외벽, 중앙 노란 단상)가 부감으로 보임. 메뉴 오버레이가 화면을 덮고 있으면 정상 (`#menu`가 위에 표시됨).

- [ ] **Step 8: Commit**

```bash
git add game/client/package.json game/client/package-lock.json game/client/vite.config.ts game/client/tsconfig.json game/client/index.html game/client/src/world.ts game/client/src/main.ts
git commit -m "feat(game): client scaffold with arena world rendering"
```

---

### Task 8: Local player controller (pointer lock + collision)

**Files:**
- Create: `game/client/src/player.ts`

- [ ] **Step 1: Write `game/client/src/player.ts`**

```ts
import * as THREE from "three";
import type { BlockBox } from "./world";

const WALK = 8, SPRINT = 12, JUMP = 9, GRAVITY = 25;
const HALF = 0.45, HEIGHT = 1.8, EYE = 1.6;

export class LocalPlayer {
  pos = new THREE.Vector3(0, 5, 0);
  vel = new THREE.Vector3();
  yaw = 0;
  pitch = 0;
  grounded = false;
  enabled = false; // 살아있고 포인터락 상태일 때만 true
  private keys = new Set<string>();

  constructor(public camera: THREE.PerspectiveCamera, private colliders: BlockBox[]) {
    this.camera.rotation.order = "YXZ";
    addEventListener("keydown", (e) => this.keys.add(e.code));
    addEventListener("keyup", (e) => this.keys.delete(e.code));
    addEventListener("mousemove", (e) => {
      if (!this.enabled || !document.pointerLockElement) return;
      this.yaw -= e.movementX * 0.0023;
      this.pitch = Math.max(-1.5, Math.min(1.5, this.pitch - e.movementY * 0.0023));
    });
  }

  spawn(p: [number, number, number]) {
    this.pos.set(p[0], p[1] + 0.1, p[2]);
    this.vel.set(0, 0, 0);
  }

  get anim(): string {
    const moving = ["KeyW", "KeyA", "KeyS", "KeyD"].some((k) => this.keys.has(k));
    return this.enabled && moving ? "run" : "idle";
  }

  update(dt: number) {
    if (this.enabled) {
      const speed = this.keys.has("ShiftLeft") ? SPRINT : WALK;
      const f = new THREE.Vector3(-Math.sin(this.yaw), 0, -Math.cos(this.yaw));
      const r = new THREE.Vector3(-f.z, 0, f.x);
      const move = new THREE.Vector3();
      if (this.keys.has("KeyW")) move.add(f);
      if (this.keys.has("KeyS")) move.sub(f);
      if (this.keys.has("KeyD")) move.add(r);
      if (this.keys.has("KeyA")) move.sub(r);
      if (move.lengthSq() > 0) move.normalize().multiplyScalar(speed);
      this.vel.x = move.x;
      this.vel.z = move.z;
      if (this.keys.has("Space") && this.grounded) { this.vel.y = JUMP; this.grounded = false; }
    } else {
      this.vel.x = 0;
      this.vel.z = 0;
    }
    this.vel.y -= GRAVITY * dt;

    this.moveAxis("x", this.vel.x * dt);
    this.moveAxis("z", this.vel.z * dt);
    this.grounded = false;
    this.moveAxis("y", this.vel.y * dt);
    if (this.pos.y < -20) this.pos.set(0, 10, 0); // 낙사 안전망

    this.camera.position.set(this.pos.x, this.pos.y + EYE, this.pos.z);
    this.camera.rotation.y = this.yaw;
    this.camera.rotation.x = this.pitch;
  }

  private overlaps(b: BlockBox) {
    return this.pos.x + HALF > b.min.x && this.pos.x - HALF < b.max.x
      && this.pos.y + HEIGHT > b.min.y && this.pos.y < b.max.y
      && this.pos.z + HALF > b.min.z && this.pos.z - HALF < b.max.z;
  }

  private moveAxis(axis: "x" | "y" | "z", delta: number) {
    if (delta === 0) return;
    this.pos[axis] += delta;
    for (const b of this.colliders) {
      if (!this.overlaps(b)) continue;
      if (axis === "y") {
        if (delta < 0) { this.pos.y = b.max.y; this.vel.y = 0; this.grounded = true; }
        else { this.pos.y = b.min.y - HEIGHT; this.vel.y = 0; }
      } else if (axis === "x") {
        this.pos.x = delta > 0 ? b.min.x - HALF : b.max.x + HALF;
      } else {
        this.pos.z = delta > 0 ? b.min.z - HALF : b.max.z + HALF;
      }
    }
  }
}
```

- [ ] **Step 2: Wire into `main.ts` temporarily and verify**

`main.ts`의 카메라 고정 코드 대신 아래로 교체해 직접 걸어보기:

```ts
import * as THREE from "three";
import { createWorld } from "./world";
import { LocalPlayer } from "./player";

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(innerWidth, innerHeight);
document.body.appendChild(renderer.domElement);
const camera = new THREE.PerspectiveCamera(75, innerWidth / innerHeight, 0.1, 500);
const { scene, colliders } = createWorld();
const player = new LocalPlayer(camera, colliders);
player.spawn([-34, 0, 0]);
document.getElementById("menu")!.classList.add("hidden");
renderer.domElement.addEventListener("click", () => renderer.domElement.requestPointerLock());
document.addEventListener("pointerlockchange", () => { player.enabled = !!document.pointerLockElement; });
addEventListener("resize", () => {
  renderer.setSize(innerWidth, innerHeight);
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
});
let last = performance.now();
renderer.setAnimationLoop(() => {
  const now = performance.now();
  const dt = Math.min((now - last) / 1000, 0.05);
  last = now;
  player.update(dt);
  renderer.render(scene, camera);
});
```

수동 확인 (`http://localhost:5173`, 클릭 후): WASD 이동, 마우스 시점, 스페이스 점프, Shift 달리기, 블록 위에 올라감, 벽 통과 불가, 낙하 시 중앙 복귀.

- [ ] **Step 3: Commit**

```bash
git add game/client/src/player.ts game/client/src/main.ts
git commit -m "feat(game): first-person controller with AABB collision"
```

---

### Task 9: Networking + remote avatars + menu flow

**Files:**
- Create: `game/client/src/net.ts`, `game/client/src/remotes.ts`
- Modify: `game/client/src/main.ts` (전체 교체 — 메뉴/접속/원격 플레이어)

- [ ] **Step 1: Write `game/client/src/net.ts`**

```ts
export interface NetPlayer {
  id: string; nick: string; team: "red" | "blue";
  pos: [number, number, number]; yaw: number; pitch: number; anim: string;
  hp: number; alive: boolean; kills: number; deaths: number; weapon: string;
}

export class Net {
  private ws!: WebSocket;
  private handlers = new Map<string, (m: any) => void>();
  onClose: () => void = () => {};

  connect(): Promise<void> {
    // vite dev(5173)에서는 게임 서버(3000)로, 배포 환경에서는 같은 호스트로 접속
    const url = location.port === "5173"
      ? `ws://${location.hostname}:3000`
      : `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}`;
    this.ws = new WebSocket(url);
    this.ws.onmessage = (ev) => {
      let m: any;
      try { m = JSON.parse(ev.data); } catch { return; }
      this.handlers.get(m.type)?.(m);
    };
    this.ws.onclose = () => this.onClose();
    return new Promise((resolve, reject) => {
      this.ws.onopen = () => resolve();
      this.ws.onerror = () => reject(new Error("connect failed"));
    });
  }

  on(type: string, fn: (m: any) => void) { this.handlers.set(type, fn); }
  send(msg: object) { if (this.ws?.readyState === 1) this.ws.send(JSON.stringify(msg)); }
}
```

- [ ] **Step 2: Write `game/client/src/remotes.ts`**

```ts
import * as THREE from "three";
import type { NetPlayer } from "./net";

const TEAM_COLORS: Record<string, number> = { red: 0xd64545, blue: 0x4569d6 };
const DELAY_MS = 120;

interface Snap { t: number; pos: [number, number, number]; yaw: number; }

function makeAvatar(team: string, nick: string) {
  const g = new THREE.Group();
  const box = (w: number, h: number, d: number, c: number, x: number, y: number, z: number) => {
    const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), new THREE.MeshLambertMaterial({ color: c }));
    m.position.set(x, y, z);
    g.add(m);
  };
  const tc = TEAM_COLORS[team] ?? 0x999999;
  box(0.22, 0.7, 0.22, 0x444455, -0.14, 0.35, 0);
  box(0.22, 0.7, 0.22, 0x444455, 0.14, 0.35, 0);
  box(0.6, 0.6, 0.32, tc, 0, 1.0, 0);
  box(0.2, 0.6, 0.2, 0xf0c987, -0.42, 1.0, 0);
  box(0.2, 0.6, 0.2, 0xf0c987, 0.42, 1.0, 0);
  box(0.42, 0.42, 0.42, 0xf0c987, 0, 1.55, 0);
  const canvas = document.createElement("canvas");
  canvas.width = 256; canvas.height = 64;
  const ctx = canvas.getContext("2d")!;
  ctx.font = "bold 36px sans-serif";
  ctx.textAlign = "center";
  ctx.fillStyle = "#fff";
  ctx.strokeStyle = "#000";
  ctx.lineWidth = 6;
  ctx.strokeText(nick, 128, 44);
  ctx.fillText(nick, 128, 44);
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(canvas), depthTest: false }));
  sprite.scale.set(2, 0.5, 1);
  sprite.position.y = 2.15;
  g.add(sprite);
  return g;
}

class RemotePlayer {
  group: THREE.Group;
  buf: Snap[] = [];
  constructor(team: string, nick: string) { this.group = makeAvatar(team, nick); }
}

export class Remotes {
  private players = new Map<string, RemotePlayer>();
  constructor(private scene: THREE.Scene) {}

  applyState(players: NetPlayer[], myId: string) {
    const seen = new Set<string>();
    for (const p of players) {
      if (p.id === myId) continue;
      seen.add(p.id);
      let rp = this.players.get(p.id);
      if (!rp) {
        rp = new RemotePlayer(p.team, p.nick);
        this.scene.add(rp.group);
        this.players.set(p.id, rp);
      }
      rp.buf.push({ t: performance.now(), pos: p.pos, yaw: p.yaw });
      if (rp.buf.length > 30) rp.buf.shift();
      rp.group.visible = p.alive;
    }
    for (const [id, rp] of this.players) {
      if (!seen.has(id)) { this.scene.remove(rp.group); this.players.delete(id); }
    }
  }

  update() {
    const t = performance.now() - DELAY_MS;
    for (const rp of this.players.values()) {
      const buf = rp.buf;
      while (buf.length >= 2 && buf[1].t <= t) buf.shift();
      if (buf.length === 0) continue;
      const a = buf[0];
      if (buf.length === 1) {
        rp.group.position.set(a.pos[0], a.pos[1], a.pos[2]);
        rp.group.rotation.y = a.yaw;
        continue;
      }
      const b = buf[1];
      const k = Math.max(0, Math.min(1, (t - a.t) / (b.t - a.t || 1)));
      rp.group.position.set(
        a.pos[0] + (b.pos[0] - a.pos[0]) * k,
        a.pos[1] + (b.pos[1] - a.pos[1]) * k,
        a.pos[2] + (b.pos[2] - a.pos[2]) * k,
      );
      rp.group.rotation.y = a.yaw + (b.yaw - a.yaw) * k;
    }
  }
}
```

- [ ] **Step 3: Replace `game/client/src/main.ts`** (메뉴 → 접속 → 동기화)

```ts
import * as THREE from "three";
import { createWorld } from "./world";
import { LocalPlayer } from "./player";
import { Net, NetPlayer } from "./net";
import { Remotes } from "./remotes";
import weapons from "../../shared/weapons.json";

const $ = (id: string) => document.getElementById(id)!;

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(innerWidth, innerHeight);
document.body.appendChild(renderer.domElement);
const camera = new THREE.PerspectiveCamera(75, innerWidth / innerHeight, 0.1, 500);
const { scene, colliders } = createWorld();
const player = new LocalPlayer(camera, colliders);
const net = new Net();
const remotes = new Remotes(scene);

let myId = "";
let alive = true;
let selectedWeapon = "rifle";

// ── 메뉴: 무기 선택 버튼 ──
for (const [key, w] of Object.entries(weapons) as [string, { name: string }][]) {
  const btn = document.createElement("button");
  btn.textContent = w.name;
  btn.dataset.key = key;
  if (key === selectedWeapon) btn.classList.add("sel");
  btn.onclick = () => {
    selectedWeapon = key;
    $("weaponPick").querySelectorAll("button").forEach((b) => b.classList.remove("sel"));
    btn.classList.add("sel");
  };
  $("weaponPick").appendChild(btn);
}

async function join(mode: "quick" | "create" | "join") {
  const nick = ($("nick") as HTMLInputElement).value.trim();
  if (!nick) { $("menuError").textContent = "닉네임을 입력하세요"; return; }
  try { await net.connect(); } catch { $("menuError").textContent = "서버에 연결할 수 없어요"; return; }
  const code = ($("codeInput") as HTMLInputElement).value.trim();
  net.send({ type: "join", mode, nick, code, weapon: selectedWeapon });
}
$("quickBtn").onclick = () => join("quick");
$("createBtn").onclick = () => join("create");
$("joinBtn").onclick = () => join("join");

net.on("error", (m) => {
  $("menuError").textContent = m.error === "no_room" ? "그 코드의 방이 없어요" : "방이 꽉 찼어요";
});

net.on("welcome", (m) => {
  myId = m.id;
  $("menu").classList.add("hidden");
  $("hud").classList.remove("hidden");
  $("roomCode").textContent = m.code === "PUBLIC" ? "공개 방" : `방 코드: ${m.code}`;
  player.spawn(m.pos);
  renderer.domElement.requestPointerLock();
});

net.on("state", (m) => {
  remotes.applyState(m.players, myId);
  $("scoreRed").textContent = m.scores.red;
  $("scoreBlue").textContent = m.scores.blue;
  const me = (m.players as NetPlayer[]).find((p) => p.id === myId);
  if (me) {
    $("health").textContent = String(me.hp);
    alive = me.alive;
  }
});

net.onClose = () => {
  $("hud").classList.add("hidden");
  $("menu").classList.remove("hidden");
  $("menuError").textContent = "연결이 끊겼어요 — 다시 입장하려면 새로고침";
};

renderer.domElement.addEventListener("click", () => {
  if (myId && alive) renderer.domElement.requestPointerLock();
});
document.addEventListener("pointerlockchange", () => {
  player.enabled = !!document.pointerLockElement && alive;
});

// 입력 20Hz 전송
setInterval(() => {
  if (!myId || !alive) return;
  net.send({
    type: "input",
    pos: [player.pos.x, player.pos.y, player.pos.z],
    yaw: player.yaw, pitch: player.pitch, anim: player.anim,
  });
}, 50);

addEventListener("resize", () => {
  renderer.setSize(innerWidth, innerHeight);
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
});

let last = performance.now();
renderer.setAnimationLoop(() => {
  const now = performance.now();
  const dt = Math.min((now - last) / 1000, 0.05);
  last = now;
  player.enabled = !!document.pointerLockElement && alive;
  player.update(dt);
  remotes.update();
  renderer.render(scene, camera);
});
```

- [ ] **Step 4: Two-window manual test**

서버(background): `cd game && <NODE22> npm start` / 클라(background): `cd game/client && <NODE22> npm run dev`
브라우저 창 2개로 `http://localhost:5173` 접속, 각각 닉네임 입력 후 빠른 입장.
Expected: 서로 다른 팀(red/blue) 스폰, 상대 창에서 움직이면 내 창에서 블록 캐릭터가 부드럽게 따라 움직임, 머리 위 닉네임 표시. 한 창을 닫으면 상대 캐릭터가 사라짐.

- [ ] **Step 5: Commit**

```bash
git add game/client/src/net.ts game/client/src/remotes.ts game/client/src/main.ts
git commit -m "feat(game): realtime multiplayer sync with interpolated avatars"
```

---

### Task 10: Shooting, HUD, death/respawn, game over

**Files:**
- Create: `game/client/src/hud.ts`, `game/client/src/shooting.ts`
- Modify: `game/client/src/main.ts` (전체 교체 — 최종본)

- [ ] **Step 1: Write `game/client/src/hud.ts`**

```ts
import type { NetPlayer } from "./net";

const $ = (id: string) => document.getElementById(id)!;
const esc = (s: string) =>
  s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]!));

export class Hud {
  setAmmo(cur: number, mag: number) { $("ammo").textContent = `${cur} / ${mag}`; }
  setReloading() { $("ammo").textContent = "재장전..."; }

  killfeed(text: string) {
    const div = document.createElement("div");
    div.textContent = text;
    $("killfeed").prepend(div);
    setTimeout(() => div.remove(), 5000);
    while ($("killfeed").children.length > 5) $("killfeed").lastChild!.remove();
  }

  flash() {
    const f = $("flash") as HTMLElement;
    f.style.opacity = "1";
    setTimeout(() => (f.style.opacity = "0"), 120);
  }

  banner(text: string, ms = 0) {
    const b = $("banner");
    b.textContent = text;
    b.classList.remove("hidden");
    if (ms) setTimeout(() => b.classList.add("hidden"), ms);
  }

  showDeath(weapons: Record<string, { name: string }>, selected: string, onPick: (k: string) => void) {
    const wrap = $("deathWeapons");
    wrap.innerHTML = "";
    for (const [key, w] of Object.entries(weapons)) {
      const btn = document.createElement("button");
      btn.textContent = w.name;
      if (key === selected) btn.classList.add("sel");
      btn.onclick = () => {
        onPick(key);
        wrap.querySelectorAll("button").forEach((b) => b.classList.remove("sel"));
        btn.classList.add("sel");
      };
      wrap.appendChild(btn);
    }
    $("deathOverlay").classList.remove("hidden");
  }
  setRespawnTimer(sec: number) { $("respawnTimer").textContent = String(sec); }
  hideDeath() { $("deathOverlay").classList.add("hidden"); }

  scoreboard(players: NetPlayer[], myId: string, show: boolean) {
    const sb = $("scoreboard");
    sb.classList.toggle("hidden", !show);
    if (!show) return;
    const rows = [...players].sort((a, b) => b.kills - a.kills).map((p) =>
      `<tr style="color:${p.team === "red" ? "#ff8a8a" : "#8ab0ff"};${p.id === myId ? "font-weight:bold" : ""}">` +
      `<td>${esc(p.nick)}${p.id === myId ? " (나)" : ""}</td><td>${p.team === "red" ? "빨강" : "파랑"}</td>` +
      `<td>${p.kills}</td><td>${p.deaths}</td></tr>`).join("");
    sb.innerHTML = `<table><tr><th>플레이어</th><th>팀</th><th>킬</th><th>데스</th></tr>${rows}</table>`;
  }
}
```

- [ ] **Step 2: Write `game/client/src/shooting.ts`**

```ts
import * as THREE from "three";
import weapons from "../../shared/weapons.json";
import type { Hud } from "./hud";

type WeaponKey = keyof typeof weapons;

export class Shooting {
  weaponKey: WeaponKey = "rifle";
  ammo = weapons.rifle.magazine;
  private reloading = false;
  private reloadDoneAt = 0;
  private lastShot = 0;
  private mouseDown = false;
  private tracers: { line: THREE.Line; die: number }[] = [];

  constructor(
    private scene: THREE.Scene,
    private camera: THREE.PerspectiveCamera,
    private send: (m: object) => void,
    private hud: Hud,
  ) {
    addEventListener("mousedown", (e) => { if (e.button === 0) this.mouseDown = true; });
    addEventListener("mouseup", (e) => { if (e.button === 0) this.mouseDown = false; });
    addEventListener("keydown", (e) => { if (e.code === "KeyR") this.reload(); });
  }

  setWeapon(key: string) {
    if (!(key in weapons)) return;
    this.weaponKey = key as WeaponKey;
    this.ammo = weapons[this.weaponKey].magazine;
    this.reloading = false;
    this.hud.setAmmo(this.ammo, weapons[this.weaponKey].magazine);
  }

  reload() {
    const w = weapons[this.weaponKey];
    if (this.reloading || this.ammo === w.magazine) return;
    this.reloading = true;
    this.reloadDoneAt = performance.now() + w.reloadMs;
    this.hud.setReloading();
  }

  update(canShoot: boolean) {
    const now = performance.now();
    const w = weapons[this.weaponKey];
    if (this.reloading && now >= this.reloadDoneAt) {
      this.reloading = false;
      this.ammo = w.magazine;
      this.hud.setAmmo(this.ammo, w.magazine);
    }
    if (this.mouseDown && canShoot && !this.reloading && this.ammo > 0 && now - this.lastShot >= w.fireDelayMs) {
      if (!w.auto) this.mouseDown = false; // 단발 무기는 클릭당 1발
      this.lastShot = now;
      this.ammo -= 1;
      this.hud.setAmmo(this.ammo, w.magazine);
      const o = this.camera.position;
      const d = this.camera.getWorldDirection(new THREE.Vector3());
      this.send({ type: "shoot", origin: [o.x, o.y, o.z], dir: [d.x, d.y, d.z] });
      this.spawnTracer(o, d, Math.min(w.range, 80));
      if (this.ammo === 0) this.reload();
    }
    this.tracers = this.tracers.filter((t) => {
      if (now > t.die) { this.scene.remove(t.line); return false; }
      return true;
    });
  }

  private spawnTracer(origin: THREE.Vector3, dir: THREE.Vector3, len: number) {
    const start = origin.clone().addScaledVector(dir, 0.5).add(new THREE.Vector3(0, -0.15, 0));
    const end = origin.clone().addScaledVector(dir, len);
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints([start, end]),
      new THREE.LineBasicMaterial({ color: 0xffe066 }),
    );
    this.scene.add(line);
    this.tracers.push({ line, die: performance.now() + 60 });
  }
}
```

- [ ] **Step 3: Replace `game/client/src/main.ts` with the final version**

```ts
import * as THREE from "three";
import { createWorld } from "./world";
import { LocalPlayer } from "./player";
import { Net, NetPlayer } from "./net";
import { Remotes } from "./remotes";
import { Hud } from "./hud";
import { Shooting } from "./shooting";
import weapons from "../../shared/weapons.json";

const $ = (id: string) => document.getElementById(id)!;

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(innerWidth, innerHeight);
document.body.appendChild(renderer.domElement);
const camera = new THREE.PerspectiveCamera(75, innerWidth / innerHeight, 0.1, 500);
const { scene, colliders } = createWorld();
const player = new LocalPlayer(camera, colliders);
const net = new Net();
const remotes = new Remotes(scene);
const hud = new Hud();
const shooting = new Shooting(scene, camera, (m) => net.send(m), hud);

let myId = "";
let alive = true;
let selectedWeapon = "rifle";
let lastPlayers: NetPlayer[] = [];
let showScores = false;
let respawnIv: ReturnType<typeof setInterval> | null = null;

// ── 메뉴: 무기 선택 ──
for (const [key, w] of Object.entries(weapons) as [string, { name: string }][]) {
  const btn = document.createElement("button");
  btn.textContent = w.name;
  if (key === selectedWeapon) btn.classList.add("sel");
  btn.onclick = () => {
    selectedWeapon = key;
    $("weaponPick").querySelectorAll("button").forEach((b) => b.classList.remove("sel"));
    btn.classList.add("sel");
  };
  $("weaponPick").appendChild(btn);
}

async function join(mode: "quick" | "create" | "join") {
  const nick = ($("nick") as HTMLInputElement).value.trim();
  if (!nick) { $("menuError").textContent = "닉네임을 입력하세요"; return; }
  try { await net.connect(); } catch { $("menuError").textContent = "서버에 연결할 수 없어요"; return; }
  const code = ($("codeInput") as HTMLInputElement).value.trim();
  net.send({ type: "join", mode, nick, code, weapon: selectedWeapon });
}
$("quickBtn").onclick = () => join("quick");
$("createBtn").onclick = () => join("create");
$("joinBtn").onclick = () => join("join");

net.on("error", (m) => {
  $("menuError").textContent = m.error === "no_room" ? "그 코드의 방이 없어요" : "방이 꽉 찼어요";
});

net.on("welcome", (m) => {
  myId = m.id;
  $("menu").classList.add("hidden");
  $("hud").classList.remove("hidden");
  $("roomCode").textContent = m.code === "PUBLIC" ? "공개 방" : `방 코드: ${m.code}`;
  player.spawn(m.pos);
  shooting.setWeapon(selectedWeapon);
  renderer.domElement.requestPointerLock();
});

net.on("state", (m) => {
  remotes.applyState(m.players, myId);
  lastPlayers = m.players;
  $("scoreRed").textContent = m.scores.red;
  $("scoreBlue").textContent = m.scores.blue;
  const me = (m.players as NetPlayer[]).find((p) => p.id === myId);
  if (me) $("health").textContent = String(me.hp);
  if (showScores) hud.scoreboard(lastPlayers, myId, true);
});

net.on("hit", (m) => { if (m.victim === myId) hud.flash(); });

function onDeath(killerNick: string) {
  alive = false;
  document.exitPointerLock();
  $("deathMsg").textContent = `${killerNick} 에게 당했다!`;
  hud.showDeath(weapons, selectedWeapon, (k) => {
    selectedWeapon = k;
    net.send({ type: "weapon", weapon: k });
  });
  let n = 3;
  hud.setRespawnTimer(n);
  if (respawnIv) clearInterval(respawnIv);
  respawnIv = setInterval(() => { n -= 1; if (n > 0) hud.setRespawnTimer(n); else clearInterval(respawnIv!); }, 1000);
}

net.on("kill", (m) => {
  hud.killfeed(`${m.killerNick} 🔫 ${m.victimNick}`);
  if (m.victim === myId) onDeath(m.killerNick);
  else if (m.killer === myId) hud.banner("+1 킬!", 800);
});

net.on("respawn", (m) => {
  if (m.id !== myId) return;
  alive = true;
  hud.hideDeath();
  player.spawn(m.pos);
  shooting.setWeapon(selectedWeapon);
  renderer.domElement.requestPointerLock();
});

net.on("gameover", (m) => hud.banner(m.winner === "red" ? "🏆 빨강 팀 승리!" : "🏆 파랑 팀 승리!"));
net.on("reset", () => hud.banner("새 게임 시작!", 2000));

net.onClose = () => {
  $("hud").classList.add("hidden");
  $("menu").classList.remove("hidden");
  $("menuError").textContent = "연결이 끊겼어요 — 새로고침 후 다시 입장하세요";
};

addEventListener("keydown", (e) => {
  if (e.code === "Tab") { e.preventDefault(); showScores = true; hud.scoreboard(lastPlayers, myId, true); }
});
addEventListener("keyup", (e) => {
  if (e.code === "Tab") { showScores = false; hud.scoreboard(lastPlayers, myId, false); }
});

renderer.domElement.addEventListener("click", () => {
  if (myId && alive) renderer.domElement.requestPointerLock();
});

setInterval(() => {
  if (!myId || !alive) return;
  net.send({
    type: "input",
    pos: [player.pos.x, player.pos.y, player.pos.z],
    yaw: player.yaw, pitch: player.pitch, anim: player.anim,
  });
}, 50);

addEventListener("resize", () => {
  renderer.setSize(innerWidth, innerHeight);
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
});

let last = performance.now();
renderer.setAnimationLoop(() => {
  const now = performance.now();
  const dt = Math.min((now - last) / 1000, 0.05);
  last = now;
  const locked = !!document.pointerLockElement;
  player.enabled = locked && alive;
  player.update(dt);
  remotes.update();
  shooting.update(locked && alive && !!myId);
  renderer.render(scene, camera);
});
```

- [ ] **Step 4: Two-window duel test (manual)**

서버 + vite dev 실행 후 창 2개로 입장.
Expected:
- 클릭 발사 → 노란 트레이서, 탄약 감소, 0발이면 자동 재장전
- 상대를 맞히면 상대 화면 빨간 플래시 + 체력 감소
- 5발(소총)로 처치 → 킬 피드 `닉 🔫 닉`, 죽은 쪽은 빨간 오버레이 + 3초 카운트다운 + 무기 변경 버튼 → 자기 팀 스폰에 부활
- Tab 누르는 동안 스코어보드
- 점수판 상단 갱신 (킬당 +1)

- [ ] **Step 5: Typecheck and commit**

Run: `cd game/client && <NODE22> npx tsc --noEmit`
Expected: 에러 없음

```bash
git add game/client/src/hud.ts game/client/src/shooting.ts game/client/src/main.ts
git commit -m "feat(game): shooting, HUD, killfeed, death/respawn, win banner"
```

---

### Task 11: Production build + bot smoke test

**Files:**
- Create: `game/test-bot.js`
- Modify: `.gitignore` (저장소 루트)

- [ ] **Step 1: Ensure `.gitignore` covers game artifacts**

저장소 루트 `.gitignore`에 아래 줄이 없으면 추가:

```
game/node_modules/
game/client/node_modules/
game/client/dist/
```

- [ ] **Step 2: Production build and serve**

Run (background): `cd game && <NODE22> npm run build`
Expected: `game/client/dist/index.html` 생성.
서버 실행(background): `cd game && <NODE22> npm start` 후 브라우저로 `http://localhost:3000` 접속.
Expected: vite 없이 메뉴 → 입장 → 게임 전체 동작 (배포와 동일한 형태).

- [ ] **Step 3: Write `game/test-bot.js`**

```js
// 사용법: node test-bot.js [봇 수] [서버 주소]
// 예: node test-bot.js 4 ws://localhost:3000
import WebSocket from "ws";

const count = Number(process.argv[2] || 1);
const url = process.argv[3] || "ws://localhost:3000";

for (let i = 0; i < count; i++) {
  const ws = new WebSocket(url);
  let id = null;
  let pos = [0, 0, 0];
  let yaw = (i / count) * Math.PI * 2;
  ws.on("open", () => ws.send(JSON.stringify({ type: "join", mode: "quick", nick: `봇${i + 1}`, weapon: "smg" })));
  ws.on("message", (d) => {
    const m = JSON.parse(d);
    if (m.type === "welcome") { id = m.id; pos = [...m.pos]; console.log(`봇${i + 1} 입장 (${m.team})`); }
    if (m.type === "respawn" && m.id === id) pos = [...m.pos];
    if (m.type === "kill") console.log(`킬: ${m.killerNick} → ${m.victimNick} (${m.scores.red}:${m.scores.blue})`);
  });
  ws.on("error", (e) => console.error(`봇${i + 1} 오류:`, e.message));
  setInterval(() => {
    if (!id || ws.readyState !== 1) return;
    if (Math.random() < 0.05) yaw = Math.random() * Math.PI * 2;
    pos[0] = Math.max(-38, Math.min(38, pos[0] - Math.sin(yaw) * 0.3));
    pos[2] = Math.max(-38, Math.min(38, pos[2] - Math.cos(yaw) * 0.3));
    ws.send(JSON.stringify({ type: "input", pos, yaw, pitch: 0, anim: "run" }));
    if (Math.random() < 0.1) {
      ws.send(JSON.stringify({
        type: "shoot",
        origin: [pos[0], pos[1] + 1.6, pos[2]],
        dir: [-Math.sin(yaw), 0, -Math.cos(yaw)],
      }));
    }
  }, 50);
}
```

- [ ] **Step 4: Bot smoke test**

서버 켜둔 채: `cd game && <NODE22> node test-bot.js 4` (background, 30초 후 종료)
브라우저 `http://localhost:3000` 에서 빠른 입장.
Expected: 봇 4명이 맵을 뛰어다니며 사격, 콘솔에 입장/킬 로그, 봇끼리 죽고 리스폰. 내 화면 킬 피드에도 표시.

- [ ] **Step 5: Run full server test suite once more**

Run: `cd game && <NODE22> npm test`
Expected: 전부 PASS

- [ ] **Step 6: Commit**

```bash
git add .gitignore game/test-bot.js
git commit -m "feat(game): headless bot smoke test and build artifacts ignore"
```

---

### Task 12: Deploy to Render (사용자 계정 필요)

**Files:** 없음 (대시보드 설정)

- [ ] **Step 1: Push to GitHub**

```bash
git push origin main
```

- [ ] **Step 2: Render 웹 서비스 생성 — 사용자 안내**

이 단계는 사용자의 Render 계정 로그인이 필요하므로, 아래 안내를 사용자에게 전달하고 완료를 기다린다:

1. https://dashboard.render.com 접속 (GitHub 계정으로 가입/로그인 — 카드 불필요)
2. **New → Web Service** → GitHub 저장소 `jhan56936-ship-it/project` 연결
3. 설정값:
   - Name: `block-rivals` (원하는 이름)
   - Branch: `main`
   - **Root Directory: `game`**
   - Build Command: `npm install && npm run build`
   - Start Command: `npm start`
   - Instance Type: **Free**
4. **Environment Variables**: `NODE_VERSION` = `22`
5. **Create Web Service** → 빌드/배포 완료까지 대기 (수 분)

- [ ] **Step 3: Verify live**

배포 URL(예: `https://block-rivals.onrender.com`)에서:
- `https://<url>/healthz` → `{"ok":true}`
- 브라우저 2개(가능하면 다른 기기/네트워크)로 접속해 같은 방에서 플레이 확인
- 무료 티어 특성 안내: 15분 유휴 후 슬립, 첫 접속 시 ~1분 웨이크업

- [ ] **Step 4: Update plan checkboxes and report the live URL to the user**

---

## Manual QA Checklist (최종)

- [ ] 닉네임 없이 입장 시 에러 문구
- [ ] 방 만들기 → 코드 표시 → 다른 창에서 코드 입장
- [ ] 틀린 코드 입장 시 "방이 없어요"
- [ ] 무기 4종 각각 발사감(연사/단발) 및 데미지 확인 (스나이퍼 1방)
- [ ] 30킬 도달 → 승리 배너 → 10초 후 리셋되어 전원 스폰 복귀
- [ ] 창 닫기 → 상대 화면에서 캐릭터 제거
- [ ] 서버 재시작 → 클라이언트 메뉴 복귀 + 끊김 안내

