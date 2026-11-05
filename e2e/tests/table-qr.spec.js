const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest } = require('./helpers');

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

test('a guest orders from the table QR code and a waiter accepts it', async ({ page, context }) => {
  // the guest follows the order to the table, through the page's slower look once everything is served
  test.setTimeout(90_000);
  await signIn(page, 'admin');
  const tables = await api(page, 'GET', '/floor/tables');
  const table = tables.find((x) => !x.occupied);

  // the manager prints a new code for the table
  await page.goto('/#/settings');
  await page.getByTestId(`qr-${table.code}`).click();
  if (await page.locator('.modal').getByRole('button', { name: 'Make new codes' }).isVisible()) {
    await page.locator('.modal').getByRole('button', { name: 'Make new codes' }).click();
  }
  await expect(page.locator('.modal [data-testid="qr-code"]')).toBeVisible();
  const link = await page.getByTestId('qr-link').getAttribute('href');
  await page.locator('.modal').getByRole('button', { name: 'Close' }).last().click();

  // the guest's phone
  const guest = await context.newPage();
  await guest.addInitScript(() => localStorage.setItem('sofra.lang', 'en'));
  await guest.goto(link);
  await expect(guest.getByRole('heading', { name: `Table ${table.code}` })).toBeVisible();
  const lahmacun = guest.locator('.table-item', { hasText: 'Lahmacun' });
  await lahmacun.locator('button.add').click();
  await guest.locator('.modal').getByRole('radio', { name: 'Acılı' }).click();
  await guest.locator('.modal').getByTestId('options-apply').click();
  await guest.getByTestId('open-cart').click();
  const name = uniqueGuest('QR');
  await guest.getByLabel('Your name (optional)').fill(name);
  await guest.getByTestId('send-cart').click();
  await expect(guest.getByTestId('sent-status')).toContainText('a waiter is checking your order');
  // in the default mixed mode, the first cart at an empty table waits for a waiter, and the guest is told why
  await expect(guest.getByTestId('sent-wait')).toHaveText('Your waiter confirms the first order at the table.');
  await guest.getByTestId('call-CALL_WAITER').click();
  await expect(guest.getByTestId('call-CALL_WAITER')).toHaveText('Waiter on the way');

  // the till hears about it and accepts the cart
  await page.goto('/#/floor');
  await expect(page.locator('#guest-badge')).not.toBeHidden();
  await page.getByTestId('open-inbox').click();
  const drawer = page.locator('.drawer');
  const cart = drawer.locator('[data-testid="inbox-ORDER"]', { hasText: name });
  await expect(cart).toContainText('Lahmacun');
  await expect(cart).toContainText('Acılı');
  await cart.getByTestId('inbox-accept').click();
  await expect(page.locator('.toast', { hasText: 'sent to the kitchen' })).toBeVisible();
  await page.locator('.drawer').last().getByRole('button', { name: 'Close' }).first().click();
  const call = page.locator('.drawer [data-testid="inbox-CALL_WAITER"]', { hasText: `Table ${table.code} ·` });
  await call.getByTestId('inbox-done').click();

  // the guest sees it went through, and follows it as the kitchen works on it
  const track = guest.getByTestId('order-track');
  await expect(track.getByTestId('track-status')).toHaveText('Received by the kitchen', { timeout: 15_000 });
  await expect(track).toContainText('1 × Lahmacun');
  await expect(track.getByTestId('track-eta')).toContainText(/Ready in about \d+ min|Any minute now/);
  await expect(guest.getByTestId('sent-status')).toHaveCount(0);
  await expect(guest.getByTestId('call-CALL_WAITER')).toHaveText('Call the waiter', { timeout: 15_000 });
  const placed = (await api(page, 'GET', '/orders/active')).find((o) => o.customerName === name);
  await api(page, 'POST', `/orders/${placed.id}/advance`);
  await expect(track.getByTestId('track-status')).toHaveText('Being prepared', { timeout: 15_000 });
  await api(page, 'POST', `/orders/${placed.id}/advance`);
  await expect(track.getByTestId('track-status')).toHaveText('Ready — on its way to you', { timeout: 15_000 });
  await expect(guest.locator('.toast', { hasText: 'Your order is ready and on its way' })).toBeVisible();
  await expect(track.getByTestId('track-eta')).toHaveCount(0);

  // served and paid at the till, so the table is free for the tests that need one
  await api(page, 'POST', `/orders/${placed.id}/advance`);
  await expect(track.getByTestId('track-status')).toHaveText('Served — enjoy your meal!', { timeout: 15_000 });
  const bill = await api(page, 'GET', `/bills/by-order/${placed.id}`);
  await api(page, 'POST', `/bills/${bill.id}/payments`, { method: 'CASH', amount: bill.balance });
  await api(page, 'POST', `/orders/${placed.id}/advance`);
  // once everything is served the page looks less often (20 s): the guests share the restaurant's Wi-Fi address
  await expect(track).toHaveCount(0, { timeout: 45_000 });
  await api(page, 'POST', `/floor/tables/${table.id}/cleared`);
});
