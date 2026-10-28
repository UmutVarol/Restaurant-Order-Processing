// Keeps a till working when the connection drops. It knows whether the server answers, keeps orders taken offline
// (and the cash or card payment taken with them) in an IndexedDB outbox, and sends them when the server is back.
//
// Every order carries an Idempotency-Key made up when it was queued, so sending it twice never places two orders.
// Before sending, the cart is priced again: if a price or a dish changed in the meantime, the order waits for the
// waiter to look at it instead of going out at a total nobody agreed to. The outbox belongs to one user on one till.
import { api, ApiError, currentDevice, currentSession, newIdempotencyKey, serverReachable } from './api.js';
import { bus } from './store.js';

const DB_NAME = 'sofra-outbox';
const STORE = 'entries';
const RETRY_MS = 20_000;

let online = navigator.onLine && serverReachable();
let flushing = null;
let memory = null; // used when IndexedDB is not available (private windows in some browsers)
let dbPromise = null;

// ---------------------------------------------------------------------------- connectivity

export function isOnline() {
  return online;
}

function setOnline(value) {
  if (value === online) return;
  online = value;
  bus.emit('connectivity', online);
  if (online) flush();
}

bus.on('network', (reachable) => setOnline(reachable && navigator.onLine));
window.addEventListener('offline', () => setOnline(false));
// The browser saying it is back is only a hint: sending the outbox is what proves the server answers.
window.addEventListener('online', () => flush());
setInterval(() => { if (!online || pendingCount > 0) flush(); }, RETRY_MS);

// ---------------------------------------------------------------------------- storage

function owner() {
  const user = currentSession()?.user?.username;
  return user ? `${user}@${currentDevice()?.id ?? 'browser'}` : null;
}

function database() {
  if (!('indexedDB' in window)) return Promise.resolve(null);
  dbPromise ??= new Promise((resolve) => {
    try {
      const open = indexedDB.open(DB_NAME, 1);
      open.onupgradeneeded = () => open.result.createObjectStore(STORE, { keyPath: 'key' }).createIndex('owner', 'owner');
      open.onsuccess = () => resolve(open.result);
      open.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
  return dbPromise;
}

async function store(mode, work) {
  const db = await database();
  if (!db) {
    memory ??= new Map();
    return work(null);
  }
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE, mode);
    const request = work(transaction.objectStore(STORE));
    transaction.oncomplete = () => resolve(request?.result);
    transaction.onerror = () => reject(transaction.error);
  });
}

const put = (entry) => store('readwrite', (s) => (s ? s.put(entry) : memory.set(entry.key, entry)));
const remove = (key) => store('readwrite', (s) => (s ? s.delete(key) : memory.delete(key)));

/** This user's entries on this till, oldest first. */
export async function entries() {
  const me = owner();
  if (!me) return [];
  const all = await store('readonly', (s) => (s ? s.index('owner').getAll(me) : { result: [...memory.values()].filter((e) => e.owner === me) }));
  return (all ?? []).sort((a, b) => a.createdAt - b.createdAt);
}

let pendingCount = 0;

async function announce() {
  const list = await entries();
  pendingCount = list.length;
  bus.emit('outbox', { count: list.length, review: list.filter((e) => e.status !== 'pending').length });
}

/**
 * Queues an order placed while the server could not be reached.
 *
 * @param order   the body POST /orders would have got
 * @param seen    what the waiter saw: each line's unit price and, if the cart was priced, the total
 * @param payment {method: 'CASH' | 'CARD'} when the guest already paid, or null
 */
export async function enqueue(order, seen, payment = null) {
  const entry = {
    key: newIdempotencyKey(), paymentKey: payment ? newIdempotencyKey() : null, owner: owner(), createdAt: Date.now(), status: 'pending',
    order, seen, payment, orderId: null, problem: null,
  };
  await put(entry);
  await announce();
  return entry;
}

/** The waiter looked at a changed order: send it at the new prices, or drop it. */
export async function decide(key, send) {
  const entry = (await entries()).find((e) => e.key === key);
  if (!entry) return;
  if (send) {
    await put({ ...entry, status: 'pending', accepted: true, problem: null });
    flush();
  } else {
    await remove(key);
  }
  await announce();
}

/** Signing out drops what this user has not sent; the caller warns first. */
export async function clearOutbox() {
  for (const entry of await entries()) await remove(entry.key);
  await announce();
}

// ---------------------------------------------------------------------------- sending

/** Sends what is waiting, one order at a time and in order. Safe to call often; runs once at a time. */
export function flush() {
  if (!owner()) return Promise.resolve();
  flushing ??= send().finally(() => { flushing = null; });
  return flushing;
}

async function send() {
  let sent = 0;
  try {
    for (const entry of await entries()) {
      if (entry.status !== 'pending') continue;
      const outcome = await sendOne(entry);
      if (outcome === OFFLINE) break;
      if (outcome === SENT) sent += 1;
    }
  } finally {
    await announce();
    if (sent) bus.emit('outbox:sent', { count: sent });
  }
}

const SENT = 'sent';
const KEPT = 'kept';
const OFFLINE = 'offline';

/** @return SENT, KEPT for the waiter to look at, or OFFLINE when the server cannot be reached (the rest waits too) */
async function sendOne(entry) {
  let current = entry;
  try {
    if (current.orderId == null) {
      if (!current.accepted) {
        const change = await requote(current);
        if (change) {
          await put({ ...current, status: 'review', problem: change });
          return KEPT;
        }
      }
      const order = await api.placeOrder(current.order, current.key);
      current = { ...current, orderId: order.id };
      await put(current);
    }
    if (current.payment) {
      const bill = await api.billForOrder(current.orderId);
      if (Number(bill.balance) > 0) {
        await api.takePayment(bill.id, { method: current.payment.method, amount: bill.balance }, current.paymentKey);
      }
    }
    await remove(current.key);
    setOnline(true);
    return SENT;
  } catch (error) {
    if (!(error instanceof ApiError) || error.status === 0) {
      setOnline(false);
      return OFFLINE;
    }
    await put({ ...current, status: 'failed', problem: { code: error.code, message: error.message, parameters: error.parameters } });
    return KEPT;
  }
}

/** Prices the cart again. Returns what changed (a price, a sold-out dish, the coupon), or null if nothing did. */
async function requote(entry) {
  const { type, couponCode, items } = entry.order;
  let quote;
  try {
    quote = await api.quote({ type, couponCode, items });
  } catch (error) {
    if (!(error instanceof ApiError) || error.status === 0) throw error;
    return { code: error.code, message: error.message, parameters: error.parameters };
  }
  const prices = quote.lines.map((line) => Number(line.unitPrice));
  const pricesChanged = prices.some((price, index) => price !== Number(entry.seen.unitPrices[index]));
  const totalChanged = entry.seen.total != null && Number(quote.pricing.total) !== Number(entry.seen.total);
  const couponLost = couponCode && quote.coupon && !quote.coupon.applied;
  if (!pricesChanged && !totalChanged && !couponLost) return null;
  return { code: couponLost ? 'COUPON_CHANGED' : 'PRICES_CHANGED', oldTotal: entry.seen.total, newTotal: quote.pricing.total };
}

// ---------------------------------------------------------------------------- app shell

/**
 * Registers the service worker that keeps the app shell for offline use. Trusted Types are enforced, so the URL
 * goes through a policy that accepts nothing but this one file.
 */
export function registerServiceWorker() {
  if (!('serviceWorker' in navigator) || window.SOFRA_BACKEND || !window.isSecureContext) return;
  let url = 'sw.js';
  if (window.trustedTypes) {
    const policy = window.trustedTypes.createPolicy('sofra-sw', {
      createScriptURL: (value) => {
        if (value !== 'sw.js') throw new TypeError('Only the app service worker can be registered');
        return value;
      },
    });
    url = policy.createScriptURL('sw.js');
  }
  navigator.serviceWorker.register(url, { scope: './' }).catch(() => { /* the app still works online */ });
}

// Menu and floor plan as last seen, so a till that is reloaded offline can still take an order. Neither holds guest
// data: the floor plan is cached without its open orders.
const CACHE_KEY = 'sofra.offline.reference';

export function rememberReference({ menu, tables }) {
  try {
    const previous = JSON.parse(localStorage.getItem(CACHE_KEY) || '{}');
    localStorage.setItem(CACHE_KEY, JSON.stringify({
      menu: menu ?? previous.menu ?? [],
      tables: tables ? tables.map(({ id, code, seats, area }) => ({ id, code, seats, area, occupied: false, openOrders: [] })) : previous.tables ?? [],
    }));
  } catch { /* storage full or blocked */ }
}

export function lastReference() {
  try { return JSON.parse(localStorage.getItem(CACHE_KEY) || 'null'); } catch { return null; }
}

announce();
