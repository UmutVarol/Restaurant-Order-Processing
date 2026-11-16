// Push notifications on this device (Web Push): new orders for the kitchen, ready plates and guests calling for
// waiters. The browser keeps the subscription; Sofra keeps who it belongs to and what it wants to hear of.
import { api } from './api.js';
import { lang } from './i18n.js';

/** What each role hears of unless it chooses otherwise. */
export const DEFAULT_TOPICS = { KITCHEN: ['NEW_ORDER'], WAITER: ['READY', 'GUEST_CALL'], ADMIN: ['NEW_ORDER', 'GUEST_CALL'] };

/** Why this device cannot get notifications, or null when it can. */
export function unsupportedReason() {
  if (window.Capacitor?.isNativePlatform?.()) return 'native';
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

/** This device's subscription in the browser, or null. */
export async function currentSubscription() {
  const ready = await registration();
  return ready ? ready.pushManager.getSubscription() : null;
}

function keyBytes(base64url) {
  const padded = base64url.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((base64url.length + 3) % 4);
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
}

/** Asks the person's permission, subscribes this browser and tells Sofra what it wants to hear of. */
export async function enablePush(topics) {
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

/** Stops notifications on this device, in the browser and at Sofra. */
export async function disablePush() {
  const subscription = await currentSubscription();
  if (!subscription) return;
  await api.unsubscribePush(subscription.endpoint).catch(() => {});
  await subscription.unsubscribe().catch(() => {});
}
