// REST client for the Sofra API. All requests go through {@link backendFetch}, which is the browser's fetch
// unless a demo backend has been installed (see demo/mock-backend.js) — the rest of the app cannot tell.
import { bus } from './store.js';
import { has, t } from './i18n.js';
import { money } from './format.js';

const SESSION_KEY = 'sofra.session';
const API_BASE = 'api/v1';
// A request that has not answered by then is abandoned, so a hung connection never freezes the till.
const REQUEST_TIMEOUT_MS = 15_000;

export function backendFetch(url, init) {
  const backend = window.SOFRA_BACKEND;
  return backend ? backend.fetch(url, init) : window.fetch(url, init);
}

/** An RFC 9457 problem returned by the API, or a network failure (status 0). */
export class ApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail || `Request failed with status ${status}`);
    this.status = status;
    this.code = problem?.code || (status === 0 ? 'NETWORK' : 'UNKNOWN');
    this.errors = problem?.errors || [];
    this.parameters = problem?.parameters || {};
    this.retryAfter = Number(problem?.parameters?.retryAfterSeconds) || null;
  }

  /** Field name → message, for highlighting form inputs. */
  fieldErrors() {
    return Object.fromEntries(this.errors.map(({ field, message }) => [field, localiseValidation(message)]));
  }
}

function localiseValidation(message = '') {
  if (/blank|required|must not be (null|empty)/i.test(message)) return t('validation.required');
  if (/phone/i.test(message)) return t('validation.phone');
  return message;
}

/** A human-readable, translated message for any error thrown by this module. */
export function errorMessage(error) {
  if (!(error instanceof ApiError)) return t('error.UNKNOWN');
  const key = `error.${error.code}`;
  if (!has(key)) return error.message || t('error.UNKNOWN');
  const params = { ...error.parameters };
  for (const key of ['minimumSubtotal', 'balance', 'available', 'required', 'refundable']) {
    if (params[key] != null) params[key] = money(params[key]);
  }
  return t(key, params);
}

// ---------------------------------------------------------------------------- session
// Sessions are per tab (sessionStorage): every till, kitchen screen or browser tab signs in as its own user.

let session = loadSession();

function loadSession() {
  try {
    const stored = JSON.parse(sessionStorage.getItem(SESSION_KEY) || 'null');
    if (stored && new Date(stored.expiresAt).getTime() > Date.now() + 60_000) return stored;
  } catch { /* corrupted or unavailable */ }
  return null;
}

export function currentSession() {
  return session;
}

export function saveSession(tokenResponse) {
  session = { token: tokenResponse.accessToken, expiresAt: tokenResponse.expiresAt, user: tokenResponse.user };
  try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(session)); } catch { /* ignore */ }
}

export function clearSession() {
  session = null;
  try { sessionStorage.removeItem(SESSION_KEY); } catch { /* ignore */ }
}

// ---------------------------------------------------------------------------- registered device
// A till a manager registered keeps its device token here, for this browser only. The token alone signs nobody in:
// it only lets staff use their PIN on this device, and a manager can revoke it at any time.
const DEVICE_KEY = 'sofra.device';

export function currentDevice() {
  try {
    const device = JSON.parse(localStorage.getItem(DEVICE_KEY) || 'null');
    return device?.token ? device : null;
  } catch { return null; }
}

export function saveDevice(registration) {
  try {
    localStorage.setItem(DEVICE_KEY, JSON.stringify({ id: registration.device.id, name: registration.device.name, token: registration.token }));
  } catch { /* storage blocked: PIN sign-in stays unavailable */ }
}

/** Till preferences kept next to the registration, such as how soon it locks itself. */
export function updateDevice(changes) {
  const device = currentDevice();
  if (!device) return;
  try { localStorage.setItem(DEVICE_KEY, JSON.stringify({ ...device, ...changes })); } catch { /* ignore */ }
}

export function forgetDevice() {
  try { localStorage.removeItem(DEVICE_KEY); } catch { /* ignore */ }
}

const deviceHeader = () => ({ 'X-Device-Token': currentDevice()?.token ?? '' });

export function authHeader() {
  return session ? { Authorization: `Bearer ${session.token}` } : {};
}

/** Whether {@code candidate} is still the active session object. */
export function isCurrentSession(candidate) {
  return candidate != null && candidate === session;
}

// ---------------------------------------------------------------------------- transport

function query(params = {}) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value == null || value === '') continue;
    (Array.isArray(value) ? value : [value]).forEach((v) => search.append(key, v));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

async function request(method, path, body, { auth = true, headers: extra = {} } = {}) {
  const sentWith = auth ? session : null;
  const headers = { Accept: 'application/json', ...(auth ? authHeader() : {}), ...extra };
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    response = await backendFetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
  } catch {
    throw new ApiError(0, null);
  } finally {
    clearTimeout(timer);
  }

  const text = response.status === 204 ? '' : await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }

  if (!response.ok) {
    // Only a rejection of the *current* token ends the session; a late reply to a request sent before
    // signing out (or as another user) must not sign out whoever is using the till now.
    if (response.status === 401 && sentWith && sentWith === session) {
      clearSession();
      bus.emit('session:expired');
    }
    throw new ApiError(response.status, data);
  }
  return data;
}

export const api = {
  login: (username, password) => request('POST', '/auth/login', { username, password }, { auth: false }),
  me: () => request('GET', '/auth/me'),
  logout: () => request('POST', '/auth/logout'),
  pinLogin: (username, pin) => request('POST', '/auth/pin-login', { username, pin }, { auth: false, headers: deviceHeader() }),
  pinStaff: () => request('GET', '/auth/pin-staff', undefined, { auth: false, headers: deviceHeader() }),
  setPin: (password, pin) => request('PUT', '/auth/pin', { password, pin }),
  devices: () => request('GET', '/devices'),
  registerDevice: (name) => request('POST', '/devices', { name }),
  revokeDevice: (id) => request('DELETE', `/devices/${id}`),

  menu: (params) => request('GET', `/menu${query(params)}`, undefined, { auth: false }),
  createMenuItem: (item) => request('POST', '/menu', item),
  updateMenuItem: (id, item) => request('PUT', `/menu/${id}`, item),
  setAvailability: (id, available) => request('PATCH', `/menu/${id}/availability`, { available }),
  archiveMenuItem: (id) => request('DELETE', `/menu/${id}`),

  quote: (cart) => request('POST', '/orders/quote', cart),
  placeOrder: (order) => request('POST', '/orders', order),
  orders: (params) => request('GET', `/orders${query(params)}`),
  activeOrders: () => request('GET', '/orders/active'),
  order: (id) => request('GET', `/orders/${id}`),
  advance: (id) => request('POST', `/orders/${id}/advance`),
  cancel: (id, reason) => request('POST', `/orders/${id}/cancel`, { reason }),

  tables: () => request('GET', '/floor/tables'),
  coupons: () => request('GET', '/coupons'),
  dashboard: (days) => request('GET', `/analytics/dashboard${query({ days })}`),

  openBills: () => request('GET', '/bills'),
  bill: (id) => request('GET', `/bills/${id}`),
  billForOrder: (orderId) => request('GET', `/bills/by-order/${orderId}`),
  // The key makes a retry after a timeout safe: the server returns the payment already taken.
  takePayment: (billId, payment, idempotencyKey) => request('POST', `/bills/${billId}/payments`, payment,
    { headers: { 'Idempotency-Key': idempotencyKey } }),
  releasePayment: (billId, paymentId) => request('POST', `/bills/${billId}/payments/${paymentId}/release`),
  createPaymentLink: (billId, amount) => request('POST', `/bills/${billId}/links`, amount ? { amount } : {}),
  refund: (paymentId, amount, reason) => request('POST', `/payments/${paymentId}/refunds`, { amount, reason }),
  dayReport: (date) => request('GET', `/payments/day${query({ date })}`),
  closeDay: (date, countedCash, note) => request('POST', '/payments/day/close', { date, countedCash, note }),

  // Guests paying from their phone: no session, the link token is the permission.
  publicBill: (token) => request('GET', `/public/pay/${encodeURIComponent(token)}`, undefined, { auth: false }),
  publicCheckout: (token, body) => request('POST', `/public/pay/${encodeURIComponent(token)}/checkout`, body, { auth: false }),
  simulatedCheckout: (token) => request('GET', `/public/sim-checkout/${encodeURIComponent(token)}`, undefined, { auth: false }),
  simulatedDecision: (token, approve) => request('POST', `/public/sim-checkout/${encodeURIComponent(token)}`, { approve }, { auth: false }),
};

/** A random key for one payment attempt; reused when the same attempt is retried. */
export function newIdempotencyKey() {
  return crypto.randomUUID ? crypto.randomUUID()
    : Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
}

export const EVENTS_URL = `${API_BASE}/events/orders`;
