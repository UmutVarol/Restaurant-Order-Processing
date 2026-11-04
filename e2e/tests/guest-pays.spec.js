// A guest pays their table's bill from the table's QR code, with no waiter: for what they had, with an invoice,
// and the rest of the table pays what is left. Runs on a desktop and on a phone.
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

test('a guest pays for what they had with an invoice, and the table pays the rest', async ({ page, context }) => {
  // the restaurant side: a seated table with served plates, and its QR code
  await signIn(page, 'admin');
  const table = (await api(page, 'GET', '/floor/tables')).filter((x) => !x.occupied && !x.needsClearingSince).at(-2);
  const { token: code } = await api(page, 'POST', `/tables/${table.id}/qr`);
  const menu = await api(page, 'GET', '/menu');
  const item = (name) => menu.find((m) => m.name === name).id;
  const order = await api(page, 'POST', '/orders', {
    type: 'DINE_IN', customerName: uniqueGuest('Self-pay'), tableId: table.id, paymentMethod: 'ONLINE',
    items: [{ menuItemId: item('Ayran'), quantity: 2 }, { menuItemId: item('Künefe'), quantity: 1 }],
  });
  for (let step = 0; step < 3; step++) await api(page, 'POST', `/orders/${order.id}/advance`);
  const due = order.pricing.total;

  // the guest's phone, from the table's QR code
  const guest = await context.newPage();
  await guest.addInitScript(() => localStorage.setItem('sofra.lang', 'en'));
  await guest.goto(`/#/t/${code}`);
  const payBill = guest.getByTestId('pay-bill');
  await expect(payBill).toContainText(`₺${due.toFixed(2)}`);
  await payBill.click();
  await expect(guest.getByTestId('guest-due')).toHaveText(`₺${due.toFixed(2)}`);
  await expect(guest.getByTestId('back-to-menu')).toBeVisible();

  // one ayran and the künefe are theirs
  await guest.getByTestId('pay-mode-ITEMS').click();
  const rows = guest.getByTestId('pay-item');
  await rows.filter({ hasText: 'Ayran' }).getByTestId('pick-item').click();
  await rows.filter({ hasText: 'Künefe' }).getByTestId('pick-item').click();
  await expect(rows.filter({ hasText: 'Künefe' }).getByTestId('pick-item')).toBeDisabled();
  const ayranLine = order.lines.find((line) => line.name === 'Ayran');
  const kunefeLine = order.lines.find((line) => line.name === 'Künefe');
  const mine = ayranLine.lineTotal / 2 + kunefeLine.lineTotal;
  await expect(guest.getByTestId('items-total')).toHaveText(`₺${mine.toFixed(2)}`);
  await guest.getByRole('button', { name: 'No tip' }).click();

  // with an invoice for the table
  await guest.getByTestId('want-invoice').check();
  await guest.getByTestId('guest-pay').click();
  await expect(guest.locator('.guest-banner.error')).toContainText('Enter a name and a tax number');
  await guest.getByLabel('Name or company').fill('Moda Yazılım Ltd.');
  await guest.getByLabel('VKN or TCKN').fill('1234567890');
  await guest.getByTestId('guest-pay').click();
  await guest.getByTestId('sim-approve').click();
  await expect(guest.locator('.guest-banner.success')).toContainText(`Thank you! ₺${mine.toFixed(2)} received.`);
  await expect(guest.getByTestId('invoice-note')).toHaveText('The invoice will be made out to Moda Yazılım Ltd.');
  await expect(guest.getByTestId('want-invoice')).toHaveCount(0);

  // back at the menu, the table owes what is left; the next guest pays it all
  await guest.getByTestId('back-to-menu').click();
  const rest = due - mine;
  await expect(payBill).toContainText(`₺${rest.toFixed(2)}`);
  await payBill.click();
  await expect(guest.getByTestId('guest-due')).toHaveText(`₺${rest.toFixed(2)}`);
  await guest.getByTestId('pay-mode-SPLIT').click();
  await expect(guest.getByTestId('split-people')).toHaveText('2');
  await guest.getByTestId('pay-mode-ALL').click();
  await guest.getByRole('button', { name: 'No tip' }).click();
  await guest.getByTestId('guest-pay').click();
  await guest.getByTestId('sim-approve').click();
  await expect(guest.locator('.guest-banner.success')).toContainText(`₺${rest.toFixed(2)} received`);
  await expect(guest.getByTestId('invoice-note')).toHaveText('Invoice issued to Moda Yazılım Ltd.');
  await guest.getByTestId('back-to-menu').click();
  await expect(guest.getByRole('heading', { name: `Table ${table.code}` })).toBeVisible();
  await expect(payBill).toHaveCount(0);

  // the restaurant: the order is done, the invoice issued in the guest's name; the table is laid again
  const bill = await api(page, 'GET', `/bills/by-order/${order.id}`);
  expect(bill.status).toBe('CLOSED');
  const invoice = await api(page, 'GET', `/fiscal/bills/${bill.id}`);
  expect(invoice.kind).toBe('E_ARSIV');
  expect(invoice.buyerName).toBe('Moda Yazılım Ltd.');
  await api(page, 'POST', `/floor/tables/${table.id}/cleared`);
});
