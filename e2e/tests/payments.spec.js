// Settling the bill: split and cash at the till, a guest paying their share by QR code, and the cashier screen.
const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest } = require('./helpers');

/** Places a dine-in order through the API and walks it to SERVED, as the kitchen and waiter would. */
async function servedDineInOrder(request, guest) {
  const login = await request.post('/api/v1/auth/login', { data: { username: 'waiter', password: 'waiter123' } });
  const headers = { Authorization: `Bearer ${(await login.json()).accessToken}` };
  const [tables, menu] = await Promise.all([
    request.get('/api/v1/floor/tables', { headers }).then((r) => r.json()),
    request.get('/api/v1/menu').then((r) => r.json()),
  ]);
  const table = tables.find((t) => !t.occupied) || tables[0];
  const dish = (name) => menu.find((m) => m.name === name).id;
  const placed = await request.post('/api/v1/orders', {
    headers,
    data: { type: 'DINE_IN', tableId: table.id, customerName: guest, paymentMethod: 'CARD',
      items: [{ menuItemId: dish('Adana Kebap'), quantity: 2 }, { menuItemId: dish('Ayran'), quantity: 2 }] },
  });
  const order = await placed.json();
  for (let step = 0; step < 3; step += 1) await request.post(`/api/v1/orders/${order.id}/advance`, { headers });
  return order;
}

async function openBillFromOrders(page, guest) {
  await page.goto(`/#/orders?q=${encodeURIComponent(guest)}`);
  await page.locator('.data-table tbody tr', { hasText: guest }).click();
  await page.locator('.drawer').getByRole('button', { name: 'Bill', exact: true }).click();
  const bill = page.getByRole('dialog', { name: 'Bill' });
  await expect(bill.locator('.bill-pay')).toBeVisible();
  return bill;
}

test('completing an unpaid order opens its bill; paying it in two cash halves completes the order', async ({ page, request }) => {
  const guest = uniqueGuest('Split');
  const order = await servedDineInOrder(request, guest);   // 2 × 520 + 2 × 70 = 1180 + 10 % VAT = 1298
  await signIn(page, 'waiter');
  await page.goto(`/#/orders?q=${encodeURIComponent(guest)}`);
  await page.locator('.data-table tbody tr', { hasText: guest }).click();

  // The order cannot be completed while its bill is unpaid: the bill opens instead.
  await page.locator('.drawer').getByRole('button', { name: 'Close the bill' }).click();
  const bill = page.getByRole('dialog', { name: 'Bill' });
  await expect(bill.getByTestId('bill-due')).toHaveText('₺1,298.00');

  await bill.locator('.split-chip').getByRole('button', { name: 'More people' }).click();
  await expect(bill.locator('#pay-amount')).toHaveValue('649.00');
  await bill.getByRole('button', { name: '₺700.00' }).click();
  await expect(bill.getByTestId('change-due')).toHaveText('₺51.00');
  await bill.getByTestId('take-payment').click();
  await expect(page.getByRole('status').filter({ hasText: '₺649.00 taken' })).toBeVisible();
  await expect(bill.getByTestId('bill-due')).toHaveText('₺649.00');

  await bill.getByTestId('take-payment').click();
  await expect(bill.locator('.pill.b-CLOSED')).toHaveText('Paid and closed');
  await expect(bill.locator('.bill-payment')).toHaveCount(2);
  await bill.getByRole('button', { name: 'Close', exact: true }).last().click();
  await expect(page.locator('.drawer .pill').first()).toHaveText('Completed');
  expect(order.id).toBeGreaterThan(0);
});

test('a guest pays their share by QR code and the bill updates live', async ({ page, request, context }) => {
  const guest = uniqueGuest('QR');
  await servedDineInOrder(request, guest);
  await signIn(page, 'waiter');
  const bill = await openBillFromOrders(page, guest);

  await bill.getByRole('radio', { name: 'QR / phone' }).click();
  await bill.locator('.split-chip').getByRole('button', { name: 'More people' }).click();
  await bill.getByTestId('create-link').click();
  await expect(bill.getByTestId('qr-code')).toBeVisible();
  await expect(bill.locator('.qr-info')).toContainText('Pays ₺649.00');
  const url = await bill.locator('.qr-info a').getAttribute('href');

  // The guest's phone: no sign-in needed.
  const phone = await context.browser().newContext({ locale: 'en-GB', viewport: { width: 390, height: 844 } });
  const guestPage = await phone.newPage();
  await guestPage.addInitScript(() => localStorage.setItem('sofra.lang', 'en'));
  await guestPage.goto(url);
  await expect(guestPage.locator('.guest-share')).toContainText('₺649.00');
  await guestPage.getByRole('button', { name: 'No tip' }).click();
  await guestPage.getByTestId('guest-pay').click();
  await expect(guestPage.locator('.sim-badge')).toBeVisible();
  await guestPage.getByTestId('sim-approve').click();
  await expect(guestPage.locator('.guest-banner.success')).toContainText('Thank you! ₺649.00 received.');
  await phone.close();

  // The till sees the payment arrive without a reload.
  await expect(bill.getByTestId('bill-due')).toHaveText('₺649.00');
  await expect(bill.locator('.bill-payment').first()).toContainText('by the guest (QR)');
});

test('the cashier screen lists open bills and the day report for managers', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/cashier');
  await expect(page.locator('.page-title')).toHaveText('Cashier');
  await expect(page.locator('.z-table tbody tr')).toHaveCount(3);
  await expect(page.getByTestId('expected-cash')).toContainText('₺');
  await expect(page.locator('.data-table tbody tr').first()).toBeVisible();
});
