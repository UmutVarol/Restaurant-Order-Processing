const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test('order filters live in the URL and survive a reload', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/orders');
  await page.getByRole('button', { name: 'Completed' }).click();
  await page.getByRole('button', { name: 'All time' }).click();
  await page.getByRole('combobox').selectOption('DELIVERY');
  await expect(page).toHaveURL(/#\/orders\?status=completed&range=all&type=DELIVERY/);
  await expect(page.locator('.data-table tbody tr').first()).toBeVisible();

  await page.reload();
  await expect(page.getByRole('button', { name: 'Completed' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('combobox')).toHaveValue('DELIVERY');
  await expect(page.locator('.data-table .pill').first()).toHaveText('Completed');
});

test('rows open the order drawer from the keyboard and Escape closes it', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/orders');
  const row = page.locator('.data-table tbody tr').first();
  await row.focus();
  await page.keyboard.press('Enter');
  const drawer = page.locator('.drawer');
  await expect(drawer.getByRole('heading', { name: /Order #\d+/ })).toBeVisible();
  await expect(page.locator('#app')).toHaveAttribute('inert', '');
  await page.keyboard.press('Escape');
  await expect(drawer).toBeHidden();
  await expect(row).toBeFocused();
});
