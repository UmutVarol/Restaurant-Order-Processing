// Push notifications on this device (Web Push): new orders for the kitchen, ready plates and guests calling for
// waiters. The browser keeps the subscription; Sofra keeps who it belongs to and what it wants to hear of.
import { api } from './api.js';
import { lang } from './i18n.js';

/** What each role hears of unless it chooses otherwise. */
export const DEFAULT_TOPICS = { KITCHEN: ['NEW_ORDER'], WAITER: ['READY', 'GUEST_CALL'], ADMIN: ['NEW_ORDER', 'GUEST_CALL'] };

const NATIVE_KEY = 'sofra.push.native';

/** The store app's own notifications (Capacitor), when Sofra runs inside it. */
function nativePush() {
  return window.Capacitor?.isNativePlatform?.() ? window.Capacitor.Plugins?.PushNotifications || null : null;
}

export function isNativeApp() {
  return Boolean(window.Capacitor?.isNativePlatform?.());
}

/** Why this device cannot get notifications, or null when it can. */
export function unsupportedReason() {
  if (isNativeApp()) return nativePush() ? null : 'native';
  if (!window.isSecureContext || !('serviceWorker' in navigator) || !('Notification' in window)) return 'browser';
  const ios = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const installed = window.matchMedia?.('(display-mode: standalone)').matches || navigator.standalone === true;
  if (!('PushManager' in window)) return ios && !installed ? 'ios' : 'browser';
  return null;
}

async function registration() {
  // the service worker is registered at start-up (core/offline.js); wait a little for it, never forever
  return Promise.race([navigator.serviceWorker.ready, new Promise((resolve) => setTimeout(() => resolve(null), 4000))]);
}

function savedNativeToken() {
  try { return localStorage.getItem(NATIVE_KEY); } catch { return null; }
}

/** This device's subscription: in the store app its Firebase token, in a browser its push subscription; or null. */
export async function currentSubscription() {
  if (isNativeApp()) {
    const token = savedNativeToken();
    return token ? { endpoint: `fcm:${token}` } : null;
  }
  const ready = await registration();
  return ready ? ready.pushManager.getSubscription() : null;
}

function keyBytes(base64url) {
  const padded = base64url.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((base64url.length + 3) % 4);
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
}

/** In the store app: asks the phone's permission, gets the Firebase token and gives it to Sofra. */
async function enableNative(topics) {
  const push = nativePush();
  const permission = await push.requestPermissions();
  if (permission.receive !== 'granted') throw new Error('push.denied');
  const token = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('push.noWorker')), 15000);
    push.addListener('registration', (result) => { clearTimeout(timer); resolve(result.value); });
    push.addListener('registrationError', () => { clearTimeout(timer); reject(new Error('push.noWorker')); });
    push.register();
  });
  try { localStorage.setItem(NATIVE_KEY, token); } catch { /* the token is asked for again next time */ }
  return api.subscribeNativePush({ token, topics, lang: lang() });
}

/** In the store app, a tapped notification opens the screen it is about. Called once at start-up. */
export function listenToNativeTaps() {
  const push = nativePush();
  if (!push) return;
  push.addListener('pushNotificationActionPerformed', (action) => {
    const url = action?.notification?.data?.url;
    if (typeof url === 'string' && url.startsWith('#/')) location.hash = url.slice(1);
  });
}

/** Asks the person's permission, subscribes this device and tells Sofra what it wants to hear of. */
export async function enablePush(topics) {
  if (isNativeApp()) return enableNative(topics);
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw new Error(permission === 'denied' ? 'push.denied' : 'push.notAllowed');
  const ready = await registration();
  if (!ready) throw new Error('push.noWorker');
  const { publicKey } = await api.pushKey();
  let subscription = await ready.pushManager.getSubscription();
  if (!subscription) {
    subscription = await ready.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) });
  }
  const json = subscription.toJSON();
  return api.subscribePush({ endpoint: json.endpoint, keys: json.keys, topics, lang: lang() });
}

/** Stops notifications on this device, in the browser (or the store app) and at Sofra. */
export async function disablePush() {
  if (isNativeApp()) {
    const token = savedNativeToken();
    if (token) await api.unsubscribePush(`fcm:${token}`).catch(() => {});
    try { localStorage.removeItem(NATIVE_KEY); } catch { /* nothing kept */ }
    await nativePush()?.unregister?.().catch(() => {});
    return;
  }
  const subscription = await currentSubscription();
  if (!subscription) return;
  await api.unsubscribePush(subscription.endpoint).catch(() => {});
  await subscription.unsubscribe().catch(() => {});
}
