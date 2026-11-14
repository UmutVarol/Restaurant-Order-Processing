#!/usr/bin/env node
// A busy evening, compressed: 40 tables, 12 waiters, 3 kitchen screens and guests ordering from the table QR codes,
// all at once against a running Sofra. Reports latency per endpoint and every error, and exits non-zero when the
// targets in docs/LOAD_TEST.md are missed. Node 20+, no dependencies.
//
//   BASE_URL=http://localhost:8080 ADMIN_PASSWORD=admin123 DURATION=300 node ops/loadtest/evening.mjs
//
// The server must allow this many requests from one address (all simulated devices share it):
//   --restaurant.security.rate-limit.api-requests-per-minute=1000000 (and the public limit likewise).
// It creates its own staff accounts and tables, so run it against a test database, never a restaurant's.

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const DURATION_S = Number(process.env.DURATION ?? 300);
const TABLES = Number(process.env.TABLES ?? 40);
const WAITERS = Number(process.env.WAITERS ?? 12);
const KITCHENS = Number(process.env.KITCHENS ?? 3);
const SPEED = Number(process.env.SPEED ?? 10); // how much faster than a real evening people act
const TARGET_P95_MS = Number(process.env.TARGET_P95_MS ?? 300);
const TARGET_ERROR_RATE = Number(process.env.TARGET_ERROR_RATE ?? 0.001);

const stats = new Map();
const failures = new Map();
let stopping = false;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const think = (realSeconds) => sleep((realSeconds * 1000 * (0.5 + Math.random())) / SPEED);
const pick = (list) => list[Math.floor(Math.random() * list.length)];

async function call(name, method, path, { token, body, expect = [200, 201, 204] } = {}) {
  const started = performance.now();
  let status = 0;
  let json = null;
  try {
    const response = await fetch(`${BASE}/api/v1${path}`, {
      method,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    status = response.status;
    const text = await response.text();
    json = text ? JSON.parse(text) : null;
  } catch (error) {
    status = -1;
    json = { code: error.cause?.code ?? error.message };
  }
  const ms = performance.now() - started;
  const s = stats.get(name) ?? { count: 0, errors: 0, times: [] };
  s.count++;
  s.times.push(ms);
  const ok = expect.includes(status);
  if (!ok) {
    s.errors++;
    const key = `${name} → ${status} ${json?.code ?? ''}`.trim();
    failures.set(key, (failures.get(key) ?? 0) + 1);
  }
  stats.set(name, s);
  return { ok, status, json };
}

// ---------------------------------------------------------------- setup: accounts, tables, QR codes
async function setUp() {
  const admin = await call('setup', 'POST', '/auth/login', { body: { username: 'admin', password: process.env.ADMIN_PASSWORD ?? 'admin123' } });
  if (!admin.ok) throw new Error(`cannot sign in as admin: ${admin.status}`);
  const token = admin.json.accessToken;
  const run = Date.now().toString(36);
  const account = async (role, i) => {
    const username = `load-${role.toLowerCase()}-${run}-${i}`;
    const created = await call('setup', 'POST', '/staff', { token, body: { username, displayName: `${role} ${i}`, role } });
    if (!created.ok) throw new Error(`cannot create ${username}: ${created.status} ${created.json?.code}`);
    const first = await call('setup', 'POST', '/auth/login', { body: { username, password: created.json.temporaryPassword } });
    const changed = await call('setup', 'POST', '/auth/password', {
      token: first.json.accessToken, body: { currentPassword: created.json.temporaryPassword, newPassword: `busy evening ${run} ${i}` },
    });
    return changed.json.accessToken;
  };
  const waiters = [];
  for (let i = 1; i <= WAITERS; i++) waiters.push(await account('WAITER', i));
  const kitchens = [];
  for (let i = 1; i <= KITCHENS; i++) kitchens.push(await account('KITCHEN', i));

  let tables = (await call('setup', 'GET', '/tables', { token })).json;
  for (let n = tables.length + 1; tables.length < TABLES; n++) {
    const added = await call('setup', 'POST', '/tables', { token, body: { code: `L${run.slice(-4)}${n}`, seats: 4, zone: 'INDOOR' }, expect: [201, 409] });
    if (added.ok && added.status === 201) tables.push(added.json);
  }
  tables = tables.slice(0, TABLES);
  const qr = {};
  for (const table of tables) qr[table.id] = (await call('setup', 'POST', `/tables/${table.id}/qr`, { token })).json.token;

  const menu = (await call('setup', 'GET', '/menu')).json
    .filter((item) => item.available !== false && !(item.optionGroups ?? []).some((g) => g.minSelect > 0));
  if (!menu.length) throw new Error('no dish can be ordered without choosing options');
  return { admin: token, waiters, kitchens, tables, qr, menu };
}

const lines = (menu, max = 5) => Array.from({ length: 1 + Math.floor(Math.random() * max) }, () => ({ menuItemId: pick(menu).id, quantity: 1 + Math.floor(Math.random() * 2) }));

// ---------------------------------------------------------------- actors
async function waiter(token, myTables, menu) {
  // the floor plan and the service screen stay open on a waiter's phone and refresh on their own
  (async () => { while (!stopping) { await call('floor plan', 'GET', '/floor/tables', { token }); await sleep(5000); } })();
  (async () => { while (!stopping) { await call('guest requests', 'GET', '/guest-requests', { token }); await sleep(4000); } })();
  while (!stopping) {
    const table = pick(myTables);
    const placed = await call('place order', 'POST', '/orders', {
      token, body: { type: 'DINE_IN', customerName: `Masa ${table.code}`, tableId: table.id, paymentMethod: 'CASH', items: lines(menu) },
      expect: [201, 409, 422],
    });
    if (placed.status !== 201) { await think(30); continue; }
    const id = placed.json.id;
    // accept whatever guests sent meanwhile
    const inbox = await call('guest requests', 'GET', '/guest-requests', { token });
    for (const request of (Array.isArray(inbox.json) ? inbox.json : []).filter((r) => r.kind === 'ORDER' && r.status === 'OPEN').slice(0, 2)) {
      await call('accept guest cart', 'POST', `/guest-requests/${request.id}/accept`, { token, expect: [200, 409, 422] });
    }
    // wait for the kitchen, then carry it out
    for (let i = 0; i < 120 && !stopping; i++) {
      const order = await call('order', 'GET', `/orders/${id}`, { token });
      if (order.json?.status === 'READY') { await call('serve', 'POST', `/orders/${id}/advance`, { token, expect: [200, 409] }); break; }
      await sleep(1000);
    }
    await think(40 * 60 / 3); // they eat
    if (Math.random() < 0.4) {
      await call('second round', 'POST', '/orders', {
        token, body: { type: 'DINE_IN', customerName: `Masa ${table.code}`, tableId: table.id, paymentMethod: 'CASH', items: lines(menu, 2) },
        expect: [201, 409, 422],
      });
      await think(15 * 60 / 3);
    }
    // the bill: everything still open at the table must be served before it can be paid in full
    const open = await call('active orders', 'GET', '/orders/active', { token });
    for (const o of (Array.isArray(open.json) ? open.json : []).filter((x) => x.table?.code === table.code && x.status === 'READY')) {
      await call('serve', 'POST', `/orders/${o.id}/advance`, { token, expect: [200, 409] });
    }
    const bill = await call('bill', 'GET', `/bills/by-order/${id}`, { token, expect: [200, 404] });
    if (bill.ok && bill.json.balance > 0) {
      const card = Math.random() < 0.6;
      await call('take payment', 'POST', `/bills/${bill.json.id}/payments`, {
        token, expect: [201, 409, 422],
        body: { method: card ? 'CARD' : 'CASH', amount: bill.json.balance, tip: Math.random() < 0.5 ? Math.min(50, Math.round(bill.json.balance * 0.1)) : 0, ...(card ? { approvalCode: `A${Date.now() % 1e6}` } : {}) },
      });
    }
    await call('clear table', 'POST', `/floor/tables/${table.id}/cleared`, { token, expect: [200] });
    await think(5 * 60 / 3);
  }
}

async function kitchen(token, index) {
  // the live stream a kitchen screen keeps open
  (async () => {
    while (!stopping) {
      try {
        const response = await fetch(`${BASE}/api/v1/events/orders`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(DURATION_S * 1000 + 5000) });
        const s = stats.get('event stream') ?? { count: 0, errors: 0, times: [] };
        s.count++;
        if (!response.ok) { s.errors++; failures.set(`event stream → ${response.status}`, (failures.get(`event stream → ${response.status}`) ?? 0) + 1); }
        stats.set('event stream', s);
        const reader = response.body.getReader();
        while (!stopping) { const { done } = await reader.read(); if (done) break; }
        reader.cancel().catch(() => {});
      } catch { /* the run ended or the stream dropped: reconnect */ }
      if (!stopping) await sleep(1000);
    }
  })();
  while (!stopping) {
    const board = await call('kitchen board', 'GET', '/orders/active', { token });
    const active = Array.isArray(board.json) ? board.json : [];
    // each screen works its share of the tickets, like stations
    for (const order of active.filter((o) => o.id % KITCHENS === index && ['PENDING', 'PREPARING'].includes(o.status)).slice(0, 4)) {
      await call('kitchen advance', 'POST', `/orders/${order.id}/advance`, { token, expect: [200, 409] });
    }
    await sleep(2000);
  }
}

async function guest(table, token, menu) {
  while (!stopping) {
    await think(25 * 60 / 3);
    if (stopping) break;
    await call('guest menu', 'GET', '/menu');
    await call('guest table', 'GET', `/public/table/${token}`);
    if (Math.random() < 0.7) {
      await call('guest cart', 'POST', `/public/table/${token}/orders`, { body: { guestName: 'Misafir', items: lines(menu, 3).map((l) => ({ ...l, quantity: Math.min(l.quantity, 3) })) }, expect: [200, 201, 409, 422, 429] });
    } else {
      await call('guest call', 'POST', `/public/table/${token}/calls`, { body: { kind: 'CALL_WAITER' }, expect: [200, 201, 409, 429] });
    }
    await call('guest status', 'GET', `/public/table/${token}/orders`, { expect: [200] });
  }
}

// ---------------------------------------------------------------- run and report
const percentile = (sorted, p) => sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))] : 0;

async function main() {
  console.log(`Setting up ${TABLES} tables, ${WAITERS} waiters, ${KITCHENS} kitchen screens against ${BASE}…`);
  const world = await setUp();
  stats.clear();
  failures.clear();
  console.log(`Running a busy evening for ${DURATION_S}s at ${SPEED}× speed…`);
  const started = Date.now();
  const actors = [];
  world.waiters.forEach((token, i) => actors.push(waiter(token, world.tables.filter((_, t) => t % WAITERS === i), world.menu)));
  world.kitchens.forEach((token, i) => actors.push(kitchen(token, i)));
  world.tables.forEach((table) => actors.push(guest(table, world.qr[table.id], world.menu)));
  const metrics = (async () => {
    while (!stopping) {
      await sleep(15000);
      const pool = await call('metrics', 'GET', '/../../actuator/metrics/hikaricp.connections.pending', { token: world.admin, expect: [200, 404] });
      const pending = pool.json?.measurements?.[0]?.value;
      if (pending !== undefined) console.log(`  ${Math.round((Date.now() - started) / 1000)}s: database connections waiting ${pending}`);
    }
  })();
  await sleep(DURATION_S * 1000);
  stopping = true;
  await Promise.race([Promise.allSettled([...actors, metrics]), sleep(30000)]);

  const seconds = (Date.now() - started) / 1000;
  let total = 0;
  let errors = 0;
  const rows = [...stats.entries()].filter(([name]) => !['setup', 'metrics'].includes(name)).sort((a, b) => b[1].count - a[1].count).map(([name, s]) => {
    const sorted = [...s.times].sort((a, b) => a - b);
    total += s.count;
    errors += s.errors;
    return { endpoint: name, requests: s.count, errors: s.errors, p50: Math.round(percentile(sorted, 50)), p95: Math.round(percentile(sorted, 95)), p99: Math.round(percentile(sorted, 99)), max: Math.round(sorted.at(-1) ?? 0) };
  });
  console.table(rows);
  const all = rows.filter((r) => r.endpoint !== 'event stream');
  const worstP95 = Math.max(...all.map((r) => r.p95));
  const errorRate = errors / Math.max(1, total);
  console.log(`${total} requests in ${seconds.toFixed(0)}s (${(total / seconds).toFixed(1)}/s), ${errors} errors (${(errorRate * 100).toFixed(2)}%), worst p95 ${worstP95} ms`);
  if (failures.size) console.log('Errors:', Object.fromEntries(failures));
  const passed = worstP95 <= TARGET_P95_MS && errorRate <= TARGET_ERROR_RATE;
  console.log(passed ? 'PASS' : `FAIL: targets are p95 ≤ ${TARGET_P95_MS} ms and errors ≤ ${(TARGET_ERROR_RATE * 100).toFixed(1)}%`);
  process.exit(passed ? 0 : 1);
}

main().catch((error) => { console.error(error); process.exit(2); });
