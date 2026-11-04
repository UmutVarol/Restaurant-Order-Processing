// The waiter's service screen: a plate the kitchen has ready is carried and marked served in one tap, and once the
// guests have paid the table waits there to be cleared. Runs on a desktop and on a phone.
const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest } = require('./helpers');

async function api(page, method, path, body) {
  return page.evaluate(async ({ method, path, body }) => {
    const session = JSON.parse(sessionStorage.getItem('sofra.session'));
    const response = await (window.SOFRA_BACKEND?.fetch ?? fetch)(`/api/v1${path}`, {
      method, headers: { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    return response.json();
  }, { method, path, body });
}

test('a waiter serves a ready plate and clears the table once the guests have paid', async ({ page }) => {
  await signIn(page, 'waiter');
  const table = (await api(page, 'GET', '/floor/tables')).filter((x) => !x.occupied).at(-1);
  const menu = await api(page, 'GET', '/menu');
  const ayran = menu.find((m) => m.name === 'Ayran');
  const guest = uniqueGuest('Runner');
  const order = await api(page, 'POST', '/orders', {
    type: 'DINE_IN', customerName: guest, tableId: table.id, paymentMethod: 'CASH', items: [{ menuItemId: ayran.id, quantity: 2 }],
  });
  // the kitchen cooks it and marks it ready
  await api(page, 'POST', `/orders/${order.id}/advance`);
  await api(page, 'POST', `/orders/${order.id}/advance`);

  await page.goto('/#/service');
  const plate = page.getByTestId('ready-plate').filter({ hasText: guest });
  await expect(plate).toBeVisible();
  await expect(plate.locator('.service-table')).toHaveText(table.code);
  await expect(plate).toContainText('2× ');
  await expect(plate).toContainText('Ayran');
  await plate.getByTestId('serve').click();
  await expect(page.locator('.toast', { hasText: `Table ${table.code}: served` })).toBeVisible();
  await expect(plate).toHaveCount(0);

  // the guests pay: the table now waits to be cleared, live
  const bill = await api(page, 'GET', `/bills/by-order/${order.id}`);
  await api(page, 'POST', `/bills/${bill.id}/payments`, { method: 'CASH', amount: bill.balance });
  const dirty = page.getByTestId('table-to-clear').filter({ hasText: `Table ${table.code}` });
  await expect(dirty).toBeVisible({ timeout: 10_000 });
  await dirty.getByTestId('cleared').click();
  await expect(page.locator('.toast', { hasText: `Table ${table.code} is laid again` })).toBeVisible();
  await expect(dirty).toHaveCount(0);
  const after = (await api(page, 'GET', '/floor/tables')).find((x) => x.id === table.id);
  expect(after.needsClearingSince).toBeNull();
});
