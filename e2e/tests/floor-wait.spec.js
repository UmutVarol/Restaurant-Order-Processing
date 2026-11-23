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

const RULES = { mode: 'MIXED', autoAcceptLimit: 1500, firstCartNeedsStaff: true };

test('a seated table shows how long its guests have waited; the manager sets the limits for every device', async ({ page }) => {
  await signIn(page, 'admin');
  const table = (await freeTables(page, 1))[0];
  const menu = await api(page, 'GET', '/menu');
  const order = await api(page, 'POST', '/orders', {
    type: 'DINE_IN', tableId: table.id, customerName: uniqueGuest('Wait'), paymentMethod: 'CASH',
    items: [{ menuItemId: menu.find((m) => m.name === 'Ayran').id, quantity: 1 }],
  });
  try {
    await page.goto('/#/floor');
    const wait = page.getByTestId(`table-${table.code}`).getByTestId('table-wait');
    // just taken: the order is the latest attention the guests had
    await expect(wait).toContainText('On track');
    await expect(wait.locator('.wait-arc')).toHaveCount(1);
    await expect(page.getByTestId('floor-wait')).toContainText('12 / 20 min');

    // Settings › Service mode: off for the whole business
    await page.goto('/#/settings');
    await page.getByTestId('service-wait').selectOption('0-0');
    await page.getByTestId('save-service').click();
    await expect(page.locator('.toast', { hasText: 'Saved' }).first()).toBeVisible();
    expect((await api(page, 'GET', '/service-settings')).waitLongMinutes).toBe(0);
    await page.goto('/#/floor');
    await expect(page.getByTestId('floor-wait')).toContainText('Off');
    await expect(page.getByTestId(`table-${table.code}`)).toBeVisible();
    await expect(wait).toHaveCount(0);
  } finally {
    await api(page, 'PUT', '/service-settings', { ...RULES, waitLookMinutes: 12, waitLongMinutes: 20 });
    await api(page, 'POST', `/orders/${order.id}/cancel`, { reason: 'Test finished' });
  }
});

test('the waiter screen puts the tables waiting longest first', async ({ page }) => {
  await signIn(page, 'waiter');
  const [first, second] = await freeTables(page, 2);
  const menu = await api(page, 'GET', '/menu');
  const ayran = menu.find((m) => m.name === 'Ayran').id;
  const place = (table) => api(page, 'POST', '/orders', {
    type: 'DINE_IN', tableId: table.id, customerName: uniqueGuest('Long'), paymentMethod: 'CASH', items: [{ menuItemId: ayran, quantity: 1 }],
  });
  const older = await place(first);
  await new Promise((resolve) => setTimeout(resolve, 1100));
  const newer = await place(second);
  try {
    // the page's clock runs 13 minutes ahead: both tables are past the amber limit (12), none past red (20)
    await page.clock.install();
    await page.goto('/#/service');
    const waiting = page.getByTestId('waiting-table');
    const row = (table) => waiting.filter({ has: page.locator('.service-table', { hasText: new RegExp(`^${table.code}$`) }) });
    // let the screen load first: jumping the clock fires each request's timeout, aborting one still on its way
    await expect(page.getByTestId('service-summary').locator('.service-count').first()).toBeVisible();
    // only this test's tables are checked: the demo's own seated tables may already have waited past the limit
    await expect(row(first)).toHaveCount(0);
    await expect(row(second)).toHaveCount(0);
    await page.clock.fastForward('13:00');
    await expect(row(first)).toBeVisible();
    await expect(row(second)).toBeVisible();
    const codes = await waiting.locator('.service-table').allTextContents();
    expect(codes.indexOf(first.code)).toBeLessThan(codes.indexOf(second.code));
    await expect(waiting.filter({ hasText: 'Check in' }).first()).toBeVisible();
    await page.clock.fastForward('08:00');
    await expect(row(first)).toContainText('Waiting long');
  } finally {
    for (const order of [older, newer]) await api(page, 'POST', `/orders/${order.id}/cancel`, { reason: 'Test finished' });
  }
});

test('clearing a table on the floor plan can be taken back (E1)', async ({ page }) => {
  await signIn(page, 'waiter');
  const table = (await freeTables(page, 1)).at(-1);
  const menu = await api(page, 'GET', '/menu');
  const order = await api(page, 'POST', '/orders', {
    type: 'DINE_IN', tableId: table.id, customerName: uniqueGuest('Clear'), paymentMethod: 'CASH',
    items: [{ menuItemId: menu.find((m) => m.name === 'Ayran').id, quantity: 1 }],
  });
  for (let i = 0; i < 3; i++) await api(page, 'POST', `/orders/${order.id}/advance`);
  const bill = await api(page, 'GET', `/bills/by-order/${order.id}`);
  await api(page, 'POST', `/bills/${bill.id}/payments`, { method: 'CASH', amount: bill.balance });

  await page.goto('/#/floor');
  const clear = page.getByTestId(`clear-${table.code}`);
  await clear.click();
  await expect(clear).toHaveCount(0);
  await page.locator('.toast', { hasText: `Table ${table.code} is laid again` }).getByTestId('toast-action').click();
  await expect(clear).toBeVisible();
  expect((await api(page, 'GET', '/floor/tables')).find((x) => x.id === table.id).needsClearingSince).not.toBeNull();

  await clear.click();
  await expect(clear).toHaveCount(0);
  await expect.poll(async () => (await api(page, 'GET', '/floor/tables')).find((x) => x.id === table.id).needsClearingSince,
    { timeout: 10_000 }).toBeNull();
  await expect(clear).toHaveCount(0);
});
