const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest } = require('./helpers');

test('a guest who asks for an invoice gets an e-Arşiv invoice once the bill is paid', async ({ page }) => {
  await signIn(page, 'waiter');
  const guest = uniqueGuest('Invoice');
  await page.getByRole('radio', { name: 'Takeaway' }).click();
  await page.locator('.menu-card', { hasText: 'Lahmacun' }).click();
  await page.getByLabel('Guest name').fill(guest);
  await page.getByRole('button', { name: /Send to kitchen/ }).click();
  await page.getByRole('button', { name: 'View' }).click();
  const drawer = page.locator('.drawer');

  // the guest asks for an invoice while the bill is open; the tax number is checked
  await drawer.getByTestId('open-bill').click();
  const bill = page.getByRole('dialog', { name: 'Bill' });
  await expect(bill).toContainText('An ÖKC receipt is issued when the bill is paid.');
  await bill.getByTestId('request-invoice').click();
  const form = page.getByRole('dialog', { name: 'Ask for an invoice' });
  await form.getByLabel('Name or company').fill('Sofra Gıda A.Ş.');
  await form.getByLabel('VKN or TCKN').fill('1234567891');
  await form.getByTestId('save-invoice').click();
  await expect(form.locator('.form-error')).toContainText('valid VKN');
  await form.getByLabel('VKN or TCKN').fill('1234567890');
  await form.getByTestId('save-invoice').click();
  await expect(bill.getByTestId('fiscal-waiting')).toContainText('Sofra Gıda A.Ş. (•••••••890)');

  // paid, cooked and picked up: the bill closes and the invoice is issued
  await bill.getByTestId('take-payment').click();
  await expect(page.getByRole('status').filter({ hasText: 'taken' })).toBeVisible();
  await bill.getByRole('button', { name: 'Close', exact: true }).last().click();
  for (const [step, status] of [['Start cooking', 'Preparing'], ['Mark ready', 'Ready'], ['Picked up', 'Completed']]) {
    await drawer.getByRole('button', { name: step }).click();
    await expect(drawer.locator('.pill').first()).toHaveText(status);
  }
  await expect(drawer.locator('.pill').first()).toHaveText('Completed');
  await drawer.getByTestId('open-bill').click();
  await expect(bill.getByTestId('fiscal-issued')).toContainText('e-Arşiv invoice');
  await expect(bill.getByTestId('fiscal-issued')).toContainText('Sofra Gıda A.Ş. · •••••••890');
  await expect(bill.getByTestId('request-invoice')).toHaveCount(0);
  await bill.getByRole('button', { name: 'Close', exact: true }).last().click();

  // the cashier sees it among today's documents
  await page.keyboard.press('Escape');
  await page.goto('/#/cashier');
  await expect(page.getByTestId('fiscal-list')).toContainText('e-Arşiv invoice');
});
