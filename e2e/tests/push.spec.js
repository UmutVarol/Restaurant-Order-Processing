const crypto = require('crypto');
const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

// The browser's push service cannot be reached from a test, so the browser's subscription is stood in for by one
// with real keys; everything from Sofra's side is the real thing.
test('a cook turns on new-order notifications for this device and off again', async ({ page, context }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'one browser is enough for a settings switch');
  const { publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const raw = publicKey.export({ format: 'der', type: 'spki' }).subarray(-65).toString('base64url');
  const auth = crypto.randomBytes(16).toString('base64url');
  const endpoint = `https://fcm.googleapis.com/fcm/send/e2e-${crypto.randomUUID()}`;
  await context.grantPermissions(['notifications']);
  await page.addInitScript(({ endpoint, raw, auth }) => {
    let current = null;
    const fake = { endpoint, toJSON: () => ({ endpoint, keys: { p256dh: raw, auth } }), unsubscribe: async () => { current = null; return true; } };
    // headless Chromium answers "denied" whatever the context allows: the person's "Allow" is stood in for too
    let permission = 'default';
    Object.defineProperty(Notification, 'permission', { get: () => permission });
    Notification.requestPermission = async () => { permission = 'granted'; return permission; };
    PushManager.prototype.getSubscription = async () => current;
    PushManager.prototype.subscribe = async () => { current = fake; return fake; };
  }, { endpoint, raw, auth });

  await signIn(page, 'chef');
  await page.goto('/#/settings');
  const card = page.getByTestId('push-card');
  await expect(card.getByTestId('push-status')).toContainText('off');
  // a cook hears of new orders unless they choose otherwise
  await expect(card.locator('input[data-topic="NEW_ORDER"]')).toBeChecked();
  await expect(card.locator('input[data-topic="READY"]')).not.toBeChecked();
  await card.getByTestId('push-enable').click();
  await expect(card.getByTestId('push-status')).toContainText('This device gets notifications');
  const devices = await page.evaluate(async () => {
    const session = JSON.parse(sessionStorage.getItem('sofra.session'));
    return (await fetch('/api/v1/push/subscriptions', { headers: { Authorization: `Bearer ${session.token}` } })).json();
  });
  expect(devices.find((device) => device.endpoint === endpoint)?.topics).toEqual(['NEW_ORDER']);

  await card.getByTestId('push-disable').click();
  await expect(card.getByTestId('push-status')).toContainText('off');
});
