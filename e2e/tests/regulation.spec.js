// What Turkish law asks of prices: the menu price includes VAT with each dish's own rate (the till shows the VAT within
// the total, by rate), the price list at the door is printed in lira with the date prices last changed and exported
// for the Ministry, and no service, cover or table charge can be put on the menu. Guests see the same note under
// the menu at their table.
const { test, expect } = require('@playwright/test');
const { signIn, freeTables, call } = require('./helpers');

test.beforeEach(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'the manager\'s screens, once');
});

test('a 20 % drink is priced with its VAT inside and the till shows VAT by rate', async ({ page }) => {
  await signIn(page, 'admin');
  const name = `Rakı E2E ${Date.now().toString(36)}`;
  await page.goto('/#/menu');
  await page.getByRole('button', { name: 'Add dish' }).click();
  const modal = page.locator('.modal').last();
  await modal.locator('[data-field=name] input').fill(name);
  await modal.locator('[data-field=category] select').selectOption('DRINK');
  await modal.locator('[data-field=price] input').fill('240');
  await expect(modal.getByTestId('menu-vat-rate')).toHaveValue('10');
  await modal.getByTestId('menu-vat-rate').selectOption('20');
  await modal.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('.toast', { hasText: name })).toBeVisible();
  const dish = (await call(page, 'GET', '/menu')).find((item) => item.name === name);
  try {
    expect(dish.vatRate).toBe(20);
    await page.goto('/#/pos');
    await page.getByRole('radio', { name: 'Takeaway' }).click();
    await page.locator('.menu-card', { hasText: name }).click();
    await page.locator('.menu-card', { hasText: 'Ayran' }).click();
    const totals = page.locator('.order-panel .totals');
    // 240 + 77: the guest pays what the menu says; the VAT inside it is listed by rate
    await expect(totals.locator('.grand')).toContainText('317.00');
    await expect(totals.getByTestId('vat-line').filter({ hasText: '10%' })).toContainText('7.00');
    await expect(totals.getByTestId('vat-line').filter({ hasText: '20%' })).toContainText('40.00');
  } finally {
    await call(page, 'DELETE', `/menu/${dish.id}`);
  }
});

test('the price list for the door is in lira with VAT and the last change, and exports for the Ministry', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/prices');
  const sheet = page.getByTestId('price-sheet-preview').getByTestId('price-sheet');
  await expect(sheet).toContainText('Fiyat Listesi');
  await expect(sheet.getByTestId('price-row').filter({ hasText: 'Ayran' })).toContainText('77,00 TL');
  await expect(sheet).toContainText('Fiyatlarımıza KDV dahildir.');
  await expect(sheet).toContainText('Servis, kuver veya masa ücreti alınmaz.');
  await expect(sheet.getByTestId('price-sheet-changed')).toContainText(/Son fiyat değişikliği: \d{2}\.\d{2}\.\d{4}/);
  await page.getByTestId('prices-size-a5').click();
  await expect(sheet).toHaveClass(/a5/);

  const download = page.waitForEvent('download');
  await page.getByTestId('prices-export-csv').click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^fiyat-listesi-.+\.csv$/);
});

test('a service, cover or table charge cannot be put on the menu', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/menu');
  await page.getByRole('button', { name: 'Add dish' }).click();
  const modal = page.locator('.modal').last();
  await modal.locator('[data-field=name] input').fill('Servis ücreti');
  await modal.locator('[data-field=price] input').fill('50');
  await modal.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('.toast, .modal .form-error').filter({ hasText: 'cannot be added' }).first()).toBeVisible();
  expect((await call(page, 'GET', '/menu')).some((item) => item.name === 'Servis ücreti')).toBe(false);
});

test('guests read that prices include VAT and when they last changed', async ({ page, context }) => {
  await signIn(page, 'admin');
  const table = (await freeTables(page, 1)).at(0);
  const { token } = await call(page, 'POST', `/tables/${table.id}/qr`);
  const guest = await context.newPage();
  await guest.addInitScript(() => localStorage.setItem('sofra.lang', 'en'));
  await guest.goto(`/#/t/${token}`);
  await expect(guest.getByTestId('menu-price-note')).toContainText('Prices include VAT.');
  await expect(guest.getByTestId('menu-price-note')).toContainText('Prices last changed on');
});
