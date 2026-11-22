// Shared steps. Every test signs in through the real login screen with a demo account.
const { expect } = require('@playwright/test');

const ACCOUNTS = { admin: 'Elif Yılmaz', waiter: 'Can Demir', chef: 'Mehmet Usta' };

/** Opens the app in English, light theme, with sound off, and signs in as the given demo account. */
async function signIn(page, account = 'admin') {
  await page.addInitScript(() => {
    if (!sessionStorage.getItem('e2e.init')) {
      localStorage.setItem('sofra.lang', 'en');
      localStorage.setItem('sofra.theme', '"light"');
      localStorage.setItem('sofra.sound', 'false');
      sessionStorage.setItem('e2e.init', '1');
    }
  });
  await page.goto('/');
  await page.getByRole('button', { name: new RegExp(ACCOUNTS[account]) }).click();
  await expect(page.locator('.shell')).toBeVisible();
}

/** A guest name no other test (or the seeded demo data) uses. */
function uniqueGuest(prefix = 'E2E') {
  return `${prefix} ${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
}

/** Calls the API as the signed-in page (or with the given token). */
async function call(page, method, path, body, token) {
  return page.evaluate(async ({ method, path, body, token }) => {
    const bearer = token ?? JSON.parse(sessionStorage.getItem('sofra.session')).token;
    const response = await fetch(`/api/v1${path}`, {
      method, headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    return response.status === 204 ? null : response.json();
  }, { method, path, body, token });
}

/**
 * Tables a test may use, at least `count` of them. The demo seats guests at random and by the time of day, so in a
 * busy hour every table can be taken: then the test adds tables of its own (as the demo manager) instead of failing.
 */
async function freeTables(page, count = 1, accept = (t) => !t.occupied && !t.needsClearingSince) {
  let usable = (await call(page, 'GET', '/floor/tables')).filter(accept);
  if (usable.length >= count) return usable;
  const session = await page.evaluate(() => JSON.parse(sessionStorage.getItem('sofra.session')));
  const manager = session.user?.role === 'ADMIN' ? session.token
    : await page.evaluate(async () => (await (await fetch('/api/v1/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin123' }),
    })).json()).accessToken);
  for (let i = usable.length; i < count; i++) {
    const code = `E${Date.now().toString(36).slice(-5)}${i}`;
    await call(page, 'POST', '/tables', { code, seats: 4, zone: 'INDOOR' }, manager);
  }
  usable = (await call(page, 'GET', '/floor/tables')).filter(accept);
  return usable;
}

module.exports = { signIn, uniqueGuest, freeTables, call };
