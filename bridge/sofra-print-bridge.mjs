#!/usr/bin/env node
// Sofra print bridge: runs on any always-on computer in the restaurant (a mini PC, the till's PC, a Raspberry Pi),
// fetches this branch's kitchen tickets from Sofra over HTTPS and sends them to the printers on the local network.
// Only outgoing connections: nothing has to be opened on the router. No dependencies: Node.js 18 or newer.
//
//   SOFRA_URL=https://kebapci.sofra.app SOFRA_BRIDGE_TOKEN=... node sofra-print-bridge.mjs
//
// Optional: SOFRA_PRINTER_PORTS (default 9100-9109), --once (fetch and print once, then stop; for trying it out).
// Guide: docs/PRINT_BRIDGE.md
import net from 'node:net';
import dns from 'node:dns/promises';

const VERSION = '1.0.0';
const BASE = (process.env.SOFRA_URL || '').replace(/\/+$/, '');
const TOKEN = process.env.SOFRA_BRIDGE_TOKEN || '';
const [MIN_PORT, MAX_PORT] = (process.env.SOFRA_PRINTER_PORTS || '9100-9109').split('-').map(Number);
const ONCE = process.argv.includes('--once');
const IDLE_MS = 2000;
const SEND_TIMEOUT_MS = 8000;


const log = (...parts) => console.log(new Date().toISOString(), ...parts);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Private networks only: a ticket never leaves the restaurant, whatever address Sofra was given. */
export function isPrivate(address) {
  if (net.isIPv4(address)) {
    const [a, b] = address.split('.').map(Number);
    return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
  }
  if (net.isIPv6(address)) {
    const lower = address.toLowerCase();
    if (lower === '::1') return true;
    if (lower.startsWith('::ffff:')) return isPrivate(lower.slice(7));
    return /^f[cd]/.test(lower); // fc00::/7, never link-local fe80::/10
  }
  return false;
}

async function resolvePrinter(host, port) {
  if (!Number.isInteger(port) || port < MIN_PORT || port > MAX_PORT) throw new Error(`port ${port} is not a printer port (${MIN_PORT}-${MAX_PORT})`);
  const addresses = net.isIP(host) ? [host] : (await dns.lookup(host, { all: true })).map((r) => r.address);
  const address = addresses.find(isPrivate);
  if (!address) throw new Error(`${host} is not on the local network`);
  return address;
}

function send(address, port, bytes) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: address, port });
    socket.setTimeout(SEND_TIMEOUT_MS, () => socket.destroy(new Error('printer did not answer in time')));
    socket.once('error', reject);
    socket.once('connect', () => socket.end(bytes));
    socket.once('close', (failed) => { if (!failed) resolve(); });
  });
}

async function call(path, body) {
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'X-Bridge-Token': TOKEN, 'User-Agent': `SofraPrintBridge/${VERSION}` },
    body: JSON.stringify(body ?? {}),
    signal: AbortSignal.timeout(15000),
  });
  if (response.status === 401) throw Object.assign(new Error('the bridge token was refused (switched off in Settings?)'), { fatal: true });
  if (!response.ok) throw new Error(`Sofra answered ${response.status}`);
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function printOne(job) {
  let error = null;
  try {
    const address = await resolvePrinter(job.host, job.port);
    await send(address, job.port, Buffer.from(job.bytes, 'base64'));
    log(`printed job ${job.id} on ${job.host}:${job.port}`);
  } catch (failure) {
    error = String(failure.message || failure).slice(0, 250);
    log(`job ${job.id} on ${job.host}:${job.port} failed: ${error}`);
  }
  await call(`/api/v1/bridge/jobs/${job.id}/result`, error ? { ok: false, error } : { ok: true });
}

function checkSettings() {
  if (!/^https?:\/\//.test(BASE) || TOKEN.length < 20) {
    console.error('Set SOFRA_URL (your Sofra address) and SOFRA_BRIDGE_TOKEN (shown once when the bridge was added in Settings > Printers).');
    process.exit(2);
  }
  if (BASE.startsWith('http://') && !/^http:\/\/(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(BASE)) {
    console.error('Refusing to send the bridge token over plain http to a public address: use https.');
    process.exit(2);
  }
}

async function main() {
  checkSettings();
  log(`Sofra print bridge ${VERSION} for ${BASE}`);
  let backoff = 1000;
  for (;;) {
    try {
      const jobs = await call('/api/v1/bridge/jobs/claim', { max: 5 });
      backoff = 1000;
      for (const job of jobs) await printOne(job);
      if (ONCE) return;
      if (!jobs.length) await sleep(IDLE_MS);
    } catch (failure) {
      log(`cannot reach Sofra: ${failure.message}`);
      if (ONCE) process.exit(1);
      await sleep(failure.fatal ? 60000 : backoff);
      backoff = Math.min(backoff * 2, 30000);
    }
  }
}

if (import.meta.url === `file://${process.argv[1]}`) main();
