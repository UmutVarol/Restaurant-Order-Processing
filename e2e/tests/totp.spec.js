const crypto = require('node:crypto');
const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

function unbase32(text) {
  let bits = 0; let value = 0; const out = [];
  for (const c of text.replace(/\s/g, '')) {
    value = (value << 5) | B32.indexOf(c); bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 255); bits -= 8; }
  }
  return Buffer.from(out);
}

/** RFC 6238, as an authenticator app computes it. */
function totp(secret, offset = 0) {
  const step = Math.floor(Date.now() / 30000) + offset;
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hash = crypto.createHmac('sha1', unbase32(secret)).update(counter).digest();
  const o = hash[19] & 15;
  return String((hash.readUInt32BE(o) & 0x7fffffff) % 1000000).padStart(6, '0');
}

test('a manager turns on two-step sign-in and then signs in with a code', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/settings');
  await page.getByTestId('totp-setup').click();
  const modal = page.locator('.modal');
  await modal.getByLabel('Password').fill('admin123');
  await modal.getByTestId('totp-next').click();
  await expect(modal.locator('[data-testid="qr-code"]')).toBeVisible();
  const secret = await modal.getByTestId('totp-secret').innerText();
  await modal.getByLabel('Six-digit code').fill(totp(secret));
  await modal.getByTestId('totp-next').click();
  await expect(modal.getByTestId('recovery-codes').locator('li')).toHaveCount(10);
  const recovery = await modal.getByTestId('recovery-codes').locator('li').allInnerTexts();
  await modal.getByTestId('totp-next').click();

  try {
    await page.getByRole('button', { name: 'Sign out' }).click();
    await page.getByLabel('Username').fill('admin');
    await page.getByLabel('Password').fill('admin123');
    await page.getByRole('button', { name: /^Sign in/ }).click();
    const code = page.getByLabel('Code from your authenticator app');
    await expect(code).toBeVisible();
    await code.fill('000000');
    await page.getByRole('button', { name: /^Sign in/ }).click();
    await expect(page.locator('.login-card .form-error')).toContainText('not right');
    await code.fill(totp(secret, 1)); // the enrolment step is used up: replays are refused
    await page.getByRole('button', { name: /^Sign in/ }).click();
    await expect(page.locator('.user-card .name')).toHaveText('Elif Yılmaz');
  } finally {
    // the other tests sign in as the manager with the password alone
    const status = await page.evaluate(async (code) => {
      const session = JSON.parse(sessionStorage.getItem('sofra.session') || 'null');
      if (!session) return 'no session';
      const response = await fetch('/api/v1/auth/totp/disable', {
        method: 'POST', headers: { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: 'admin123', code }),
      });
      return response.status;
    }, recovery[0]);
    expect(status).toBe(204);
  }
});
