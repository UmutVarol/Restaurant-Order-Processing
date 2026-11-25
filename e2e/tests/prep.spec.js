// Planning the kitchen's day: the prep list shows the likely sales from past days and a tap marks a dish prepared for
// everyone; the chef records waste from the kitchen screen in a few taps, and the manager finds it, by reason and
// against sales, on the stock screen.
const { test, expect } = require('@playwright/test');
const { signIn, call } = require('./helpers');

test.beforeEach(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'the kitchen and the manager, once');
});

test('the prep list shows likely sales and a tap marks a dish prepared', async ({ page }) => {
  await signIn(page, 'chef');
  await page.goto('/#/prep');
  const dish = page.getByTestId('prep-dish').first();
  await expect(dish).toBeVisible();
  await expect(page.getByTestId('prep-basis')).not.toBeEmpty();
  const wasDone = (await dish.getAttribute('aria-pressed')) === 'true';
  await dish.click();
  await expect(dish).toHaveAttribute('aria-pressed', String(!wasDone));
  // another screen sees it: the server keeps it
  await page.reload();
  await expect(page.getByTestId('prep-dish').first()).toHaveAttribute('aria-pressed', String(!wasDone));
  await page.getByTestId('prep-dish').first().click();
  await expect(page.getByTestId('prep-dish').first()).toHaveAttribute('aria-pressed', String(wasDone));
});

test('the chef records waste from the kitchen and the manager sees it by reason', async ({ page, browser }) => {
  await signIn(page, 'chef');
  await page.goto('/#/kitchen');
  await page.getByTestId('waste-open').click();
  const sheet = page.getByTestId('waste-sheet');
  await sheet.getByTestId('waste-search').fill('Ayran');
  await sheet.getByTestId('waste-item').filter({ hasText: 'Ayran' }).first().click();
  await sheet.getByRole('button', { name: 'One more' }).click();
  await expect(sheet.getByTestId('waste-portions')).toHaveText('2');
  await expect(page.getByTestId('waste-save')).toBeDisabled();
  await sheet.getByTestId('waste-reason-SPOILED').click();
  await page.getByTestId('waste-save').click();
  // sent once the undo window has passed
  await expect.poll(async () => (await call(page, 'GET', '/planning/waste/today')).some((w) => w.name === 'Ayran' && w.reason === 'SPOILED'),
    { timeout: 15_000 }).toBe(true);

  const manager = await browser.newContext({ baseURL: new URL(page.url()).origin, viewport: { width: 1440, height: 900 } });
  await manager.addInitScript(() => localStorage.setItem('sofra.lang', 'en'));
  const office = await manager.newPage();
  await signIn(office, 'admin');
  await office.goto('/#/stock');
  await office.getByTestId('stock-tab-waste').click();
  await office.getByTestId('waste-period-1').click();
  const report = office.getByTestId('waste-report');
  await expect(report).toBeVisible();
  await expect(office.locator('.waste-bars')).toContainText('Went bad');
  await manager.close();
});
