const { test, expect } = require('@playwright/test');
const { signIn, freeTables, uniqueGuest } = require('./helpers');

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

test('a seated table shows how long its guests have waited, and a device can turn it off', async ({ page }) => {
  await signIn(page, 'admin');
  const table = (await freeTables(page, 1))[0];
  const menu = await api(page, 'GET', '/menu');
  const order = await api(page, 'POST', '/orders', {
    type: 'DINE_IN', tableId: table.id, customerName: uniqueGuest('Wait'), paymentMethod: 'CASH',
    items: [{ menuItemId: menu.find((m) => m.name === 'Ayran').id, quantity: 1 }],
  });
  await page.goto('/#/floor');
  const wait = page.getByTestId(`table-${table.code}`).getByTestId('table-wait');
  // just taken: the order is the latest attention the guests had
  await expect(wait).toContainText('On track');
  await expect(wait.locator('.wait-arc')).toHaveCount(1);

  await page.getByTestId('floor-wait').selectOption('off');
  await expect(wait).toHaveCount(0);
  await page.reload();
  await expect(page.getByTestId('floor-wait')).toHaveValue('off');
  await page.getByTestId('floor-wait').selectOption('12-20');
  await expect(page.getByTestId(`table-${table.code}`).getByTestId('table-wait')).toBeVisible();
  await api(page, 'POST', `/orders/${order.id}/cancel`, { reason: 'Test finished' });
});
