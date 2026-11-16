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

/** Drags a card sideways by a share of its width, the way a finger would. */
async function swipe(page, card, share) {
  const box = await card.boundingBox();
  const rtl = await card.evaluate((el) => getComputedStyle(el).direction === 'rtl');
  const y = box.y + box.height / 2;
  const from = rtl ? box.x + box.width - 20 : box.x + 20;
  await page.mouse.move(from, y);
  await page.mouse.down();
  for (let i = 1; i <= 8; i++) await page.mouse.move(from + (rtl ? -1 : 1) * box.width * share * (i / 8), y + 1);
  await page.mouse.up();
}

test('a cook swipes a ticket on, takes it back, and the kitchen hears of it only after the undo time', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'one pointer is enough to check the gesture');
  await signIn(page, 'admin');
  const menu = await api(page, 'GET', '/menu');
  const guest = uniqueGuest('Swipe');
  const order = await api(page, 'POST', '/orders', {
    type: 'TAKEAWAY', customerName: guest, paymentMethod: 'CASH',
    items: [{ menuItemId: menu.find((m) => m.name === 'Ayran').id, quantity: 1 }],
  });
  await page.goto('/#/kitchen');
  const fresh = page.locator('.kds-col[aria-label="New"] .ticket', { hasText: guest });
  const cooking = page.locator('.kds-col[aria-label="Preparing"] .ticket', { hasText: guest });
  await expect(fresh).toBeVisible();

  // a short pull is not enough: the ticket slides back
  await swipe(page, fresh, 0.2);
  await expect(fresh).toBeVisible();

  // a full swipe moves it on at once; the toast can take it back and nothing reached the server
  await swipe(page, fresh, 0.7);
  await expect(cooking).toBeVisible();
  await page.locator('.toast.counting').getByTestId('toast-action').click();
  await expect(fresh).toBeVisible();
  expect((await api(page, 'GET', `/orders/${order.id}`)).status).toBe('PENDING');

  // the button still works, and once the undo time is over the server has it
  await fresh.getByRole('button', { name: 'Start cooking' }).click();
  await expect(cooking).toBeVisible();
  await expect.poll(async () => (await api(page, 'GET', `/orders/${order.id}`)).status, { timeout: 10_000 }).toBe('PREPARING');
  await api(page, 'POST', `/orders/${order.id}/cancel`, { reason: 'Test finished' });
});
