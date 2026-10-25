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

module.exports = { signIn, uniqueGuest };
