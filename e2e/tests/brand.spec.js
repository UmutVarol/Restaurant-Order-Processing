const { test, expect } = require('@playwright/test');
const { signIn, freeTables } = require('./helpers');

// A 16×16 PNG in two colours, drawn for the test.
const LOGO = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAH0lEQVR4nGPQz/fHiv7jAAyjGmiiAZcELoNGNdBEAwBIAfUQDE+z3AAAAABJRU5ErkJggg==', 'base64');

async function api(page, method, path, body) {
  return page.evaluate(async ({ method, path, body }) => {
    const session = JSON.parse(sessionStorage.getItem('sofra.session'));
    const response = await fetch(`/api/v1${path}`, {
      method, headers: { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    return response.json();
  }, { method, path, body });
}

test.afterEach(async ({ page }) => {
  // every other test sees Sofra's own look
  await api(page, 'PUT', '/branding', { accent: null });
  await api(page, 'DELETE', '/branding/logo');
});

test('a manager gives the guest pages the restaurant\'s colour and logo', async ({ page, context }) => {
  await signIn(page, 'admin');
  await page.goto('/#/settings');
  const card = page.getByTestId('brand-card');
  await expect(card.getByTestId('save-brand')).toBeVisible();

  // a light mustard is darkened until white text on it reads
  await card.locator('#brand-custom').fill('#f4d35e');
  await expect(card.getByTestId('brand-readout')).toContainText('Darkened to stay readable');
  await card.getByRole('button', { name: '#2f6f4f' }).click();
  await expect(card.getByTestId('brand-readout')).toContainText('readable');
  await card.getByTestId('save-brand').click();
  await expect(page.locator('.toast', { hasText: 'Saved' }).first()).toBeVisible();

  await card.getByTestId('brand-logo-file').setInputFiles({ name: 'logo.png', mimeType: 'image/png', buffer: LOGO });
  await expect(page.locator('.toast', { hasText: 'Logo saved' }).first()).toBeVisible();
  await expect(card.getByTestId('brand-remove')).toBeVisible();

  // the guest at a table sees the restaurant, not Sofra
  const table = (await freeTables(page, 1, (x) => !x.occupied))[0];
  const { token } = await api(page, 'POST', `/tables/${table.id}/qr`);
  const guest = await context.newPage();
  await guest.addInitScript(() => { localStorage.setItem('sofra.lang', 'en'); localStorage.setItem('sofra.theme', JSON.stringify('light')); });
  await guest.goto(`/#/t/${token}`);
  await expect(guest.getByTestId('guest-logo')).toBeVisible();
  const brand = () => guest.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--brand').trim());
  await expect.poll(brand).toBe('#2f6f4f');
  await expect(guest.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#2f6f4f');

  // in the dark theme the colour is lightened so it still reads on the dark page
  await guest.getByRole('button', { name: 'Toggle dark mode' }).click();
  await expect.poll(brand).not.toBe('#2f6f4f');
  const contrast = await guest.evaluate(() => {
    const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    const lum = (hex) => { const [r, g, b] = rgb(hex); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
    const a = lum(getComputedStyle(document.documentElement).getPropertyValue('--brand').trim());
    const b = lum('#1a1715');
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  });
  expect(contrast).toBeGreaterThanOrEqual(4.5);

  // leaving the restaurant's pages brings Sofra's look back
  await guest.goto('/#/login');
  await expect.poll(brand).not.toBe('#2f6f4f');
  await expect(guest.locator('html')).not.toHaveClass(/branded/);
});
