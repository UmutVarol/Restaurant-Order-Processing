const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

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

const DEFAULTS = { mode: 'MIXED', autoAcceptLimit: 1500, firstCartNeedsStaff: true };

test('in QR-first mode a guest cart goes straight to the kitchen', async ({ page, context }) => {
  await signIn(page, 'admin');
  try {
    // the manager switches the restaurant to QR first
    await page.goto('/#/settings');
    const card = page.locator('.service-card');
    await expect(card.getByTestId('service-mode-MIXED')).toHaveClass(/active/);
    await expect(card.getByLabel('Straight to the kitchen up to (₺)')).toHaveValue('1500');
    await card.getByTestId('service-mode-QR_FIRST').click();
    await expect(card.locator('.service-rules')).toBeHidden();
    await card.getByTestId('save-service').click();
    await expect(page.locator('.toast').first()).toBeVisible();
    expect((await api(page, 'GET', '/service-settings')).mode).toBe('QR_FIRST');

    // a guest at an empty table orders a drink; the last free table, so the other QR tests keep theirs
    const table = (await api(page, 'GET', '/floor/tables')).filter((x) => !x.occupied).at(-1);
    await page.getByTestId(`qr-${table.code}`).click();
    if (await page.locator('.modal').getByRole('button', { name: 'Make new codes' }).isVisible()) {
      await page.locator('.modal').getByRole('button', { name: 'Make new codes' }).click();
    }
    const link = await page.getByTestId('qr-link').getAttribute('href');
    await page.locator('.modal').getByRole('button', { name: 'Close' }).last().click();

    const guest = await context.newPage();
    await guest.addInitScript(() => localStorage.setItem('sofra.lang', 'en'));
    await guest.goto(link);
    await guest.locator('.table-item', { hasText: 'Ayran' }).locator('button.add').click();
    await guest.getByTestId('open-cart').click();
    await expect(guest.locator('.modal')).toContainText('Your order goes straight to the kitchen.');
    await guest.getByTestId('send-cart').click();
    await expect(guest.locator('.toast', { hasText: 'Sent straight to the kitchen' })).toBeVisible();
    await expect(guest.getByTestId('sent-status')).toHaveCount(0);
    await expect(guest.getByTestId('order-track').getByTestId('track-status')).toHaveText('Received by the kitchen');

    // nobody had to accept it: the order is already in progress at the table
    const placed = (await api(page, 'GET', '/orders/active')).filter((o) => o.table?.code === table.code);
    expect(placed).toHaveLength(1);
    expect(placed[0].createdBy).toBe('table-qr');
    await api(page, 'POST', `/orders/${placed[0].id}/cancel`, { reason: 'E2E clean-up' });
  } finally {
    await api(page, 'PUT', '/service-settings', DEFAULTS);
  }
});
