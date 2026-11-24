// Inflation tools, from the menu screen: a manager raises the drinks by 10 %, sees every new price first, applies it
// and takes it back from the toast; a dish whose ingredients take too much of its price is flagged with a suggested
// price that is applied in one tap.
const { test, expect } = require('@playwright/test');
const { signIn, call } = require('./helpers');

test.beforeEach(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'the manager\'s screens, once');
});

test('the drinks go up 10 % after a preview, and the change is taken back from the toast', async ({ page }) => {
  await signIn(page, 'admin');
  const before = Object.fromEntries((await call(page, 'GET', '/menu?central=true')).filter((d) => d.category === 'DRINK').map((d) => [d.name, d.price]));
  await page.goto('/#/menu');
  await page.getByTestId('bulk-open').click();
  const modal = page.getByTestId('bulk-pricing');
  await modal.getByTestId('bulk-cat-DRINK').click();
  await modal.getByTestId('bulk-value').fill('10');
  await modal.getByTestId('bulk-rounding').selectOption('ONE');
  // Ayran 77 + 10 % = 84.70, to the next lira 85; nothing has changed yet
  await expect(modal.getByTestId('bulk-line').filter({ hasText: 'Ayran' })).toContainText('85.00');
  await expect(modal.getByTestId('bulk-summary')).toContainText(`${Object.keys(before).length} prices change`);
  expect((await call(page, 'GET', '/menu?central=true')).find((d) => d.name === 'Ayran').price).toBe(77);

  await page.getByTestId('bulk-apply').click();
  const toast = page.locator('.toast', { hasText: 'prices updated' });
  await expect(toast).toBeVisible();
  await expect(page.locator('.menu-table tr', { hasText: 'Ayran' }).locator('.m-price')).toContainText('85.00');
  await toast.getByTestId('toast-action').click();
  await expect(page.locator('.toast', { hasText: 'back as they were' })).toBeVisible();
  const after = Object.fromEntries((await call(page, 'GET', '/menu?central=true')).filter((d) => d.category === 'DRINK').map((d) => [d.name, d.price]));
  expect(after).toEqual(before);
});

test('a dish whose ingredients take too much is flagged and set to the suggested price', async ({ page }) => {
  await signIn(page, 'admin');
  const name = `Margin E2E ${Date.now().toString(36)}`;
  const dish = await call(page, 'POST', '/menu', { name, category: 'MAIN', price: 110 });
  const meat = await call(page, 'POST', '/stock/ingredients', { name: `Meat ${name}`, unit: 'GRAM', cost: 500, trackStock: false });
  await call(page, 'PUT', `/recipes/${dish.id}`, { lines: [{ ingredientId: meat.id, quantity: 100 }] });
  try {
    await page.goto('/#/menu');
    await expect(page.getByTestId('margin-banner')).toBeVisible();
    await page.getByTestId('margin-open').click();
    // only this test's dish: the demo's own dishes keep their prices
    const others = page.getByTestId('margin-row').filter({ hasNotText: name }).locator('input[type=checkbox]:checked');
    while (await others.count()) await others.first().uncheck();
    const row = page.getByTestId('margin-row').filter({ hasText: name });
    // 50 of a 100 price before VAT: 50 %; at a 30 % target the price should be 184
    await expect(row).toContainText('184.00');
    await page.getByTestId('margins-apply').click();
    await expect(page.locator('.toast', { hasText: 'prices updated' })).toBeVisible();
    expect((await call(page, 'GET', `/menu/${dish.id}`)).price).toBe(184);
  } finally {
    await call(page, 'DELETE', `/menu/${dish.id}`);
    await call(page, 'DELETE', `/stock/ingredients/${meat.id}`);
  }
});
