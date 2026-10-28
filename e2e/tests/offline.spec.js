const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest } = require('./helpers');

/** Calls the API from outside the till's browser, so it works while the till is offline. */
async function asAdmin(request) {
  const login = await request.post('/api/v1/auth/login', { data: { username: 'admin', password: 'admin123' } });
  return { Authorization: `Bearer ${(await login.json()).accessToken}` };
}

async function setPrice(request, headers, name, price) {
  const menu = await (await request.get('/api/v1/menu')).json();
  const item = menu.find((m) => m.name === name);
  const response = await request.put(`/api/v1/menu/${item.id}`, { headers, data: {
    name: item.name, description: item.description, category: item.category, price, icon: item.icon,
    prepTimeMinutes: item.prepTimeMinutes, popular: item.popular, tags: item.tags, stationCode: item.stationCode,
  } });
  expect(response.ok()).toBeTruthy();
}

async function takeaway(page, dish, guest) {
  await page.getByRole('radio', { name: 'Takeaway' }).click();
  await page.locator('.menu-card', { hasText: dish }).click();
  await page.getByLabel('Guest name').fill(guest);
}

test('a till keeps taking orders offline and sends each one once when the connection is back', async ({ page, context, request }) => {
  test.slow();
  const admin = await asAdmin(request);
  await signIn(page, 'waiter');
  const [first, second, third] = [uniqueGuest('Off'), uniqueGuest('Off'), uniqueGuest('Off')];

  // the connection drops with a ticket on screen: the order is kept on the till
  await takeaway(page, 'Ayran', first);
  await expect(page.locator('.order-panel .totals .grand')).toContainText('77');
  await context.setOffline(true);
  await page.getByRole('button', { name: /Send to kitchen|Save offline/ }).click();
  await expect(page.locator('.toast', { hasText: `${first}'s order is saved on this till` })).toBeVisible();
  await expect(page.getByTestId('connection')).toHaveText('Offline');
  await expect(page.getByTestId('outbox')).toHaveText('1 waiting');

  // offline, only the till's own payments: cash taken now goes with the order
  await takeaway(page, 'Demlik Çay', second);
  await expect(page.getByTestId('estimate')).toBeVisible();
  await page.getByTestId('paid-now').check();
  await page.getByRole('button', { name: /Save offline/ }).click();
  await expect(page.getByTestId('outbox')).toHaveText('2 waiting');

  // meanwhile the manager changes a price the first order used
  await setPrice(request, admin, 'Ayran', 80);
  try {
    await context.setOffline(false);
    await expect(page.locator('.toast', { hasText: '1 offline order(s) sent' })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('outbox')).toHaveText('1 waiting');
    await expect(page.getByTestId('outbox')).toHaveClass(/needs-review/);

    // the changed order waits for the waiter
    await page.getByTestId('outbox').click();
    const list = page.getByTestId('outbox-list');
    await expect(list.getByTestId('outbox-problem')).toContainText('₺77.00 is now ₺88.00');
    await list.getByTestId('outbox-send').click();
    await expect(list).toContainText('Everything has been sent.');
    await page.locator('.modal').getByRole('button', { name: 'Close' }).last().click();
    await expect(page.getByTestId('outbox')).toBeHidden();
  } finally {
    await setPrice(request, admin, 'Ayran', 70);
  }

  // each order exists once, and the cash taken offline is on the second bill
  const waiter = { Authorization: `Bearer ${await page.evaluate(() => JSON.parse(sessionStorage.getItem('sofra.session')).token)}` };
  for (const guest of [first, second]) {
    const found = await (await request.get(`/api/v1/orders?q=${encodeURIComponent(guest)}`, { headers: waiter })).json();
    expect(found.totalElements).toBe(1);
  }
  const paid = (await (await request.get(`/api/v1/orders?q=${encodeURIComponent(second)}`, { headers: waiter })).json()).content[0];
  const bill = await (await request.get(`/api/v1/bills/by-order/${paid.id}`, { headers: waiter })).json();
  expect(Number(bill.balance)).toBe(0);

  // signing out with orders still waiting asks first
  await context.setOffline(true);
  await takeaway(page, 'Ayran', third);
  await page.getByRole('button', { name: /Save offline|Send to kitchen/ }).click();
  await expect(page.getByTestId('outbox')).toHaveText('1 waiting');
  await page.getByTestId('logout').click();
  const dialog = page.locator('.modal');
  await expect(dialog).toContainText('1 order(s) not sent yet');
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  await expect(page.locator('.shell')).toBeVisible();
  await context.setOffline(false);
  await expect(page.getByTestId('outbox')).toBeHidden({ timeout: 15_000 });
});

test('a till that is reloaded offline opens from the app shell with the menu it saw last', async ({ page, context }) => {
  await signIn(page, 'waiter');
  await expect(page.locator('.menu-card').first()).toBeVisible();
  // the service worker registers through the Trusted Types policy and takes control of the page
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.reload();
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);

  await context.setOffline(true);
  try {
    await page.reload();
    await expect(page.locator('.shell')).toBeVisible();
    await expect(page.getByTestId('connection')).toHaveText('Offline');
    await expect(page.locator('.menu-card', { hasText: 'Lahmacun' })).toBeVisible();
    // online payments need the provider, so they are not offered
    await page.getByRole('radio', { name: 'Delivery' }).click();
    await expect(page.locator('.segmented button', { hasText: 'Online' })).toBeDisabled();
  } finally {
    await context.setOffline(false);
  }
});
