const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test.beforeEach(async ({ page }) => {
  await signIn(page, 'waiter');
});

test('the search and category chips filter the menu', async ({ page }) => {
  await page.getByRole('searchbox').fill('künefe');
  await expect(page.locator('.menu-card')).toHaveCount(1);
  await page.getByRole('searchbox').fill('');
  await page.getByRole('tab', { name: /Drinks/ }).click();
  await expect(page.locator('.menu-card[data-cat="MAIN"]')).toHaveCount(0);
  await expect(page.locator('.menu-card[data-cat="DRINK"]').first()).toBeVisible();
});

test('cards show what is on the ticket and counts use proper plurals', async ({ page }) => {
  const card = page.locator('.menu-card', { hasText: 'Ayran' });
  await card.click();
  await expect(page.locator('.order-panel .card-sub')).toHaveText('1 item');
  await card.click();
  await expect(card.locator('.qty-bubble')).toHaveText('2');
  await expect(page.locator('.order-panel .card-sub')).toHaveText('2 items');
});

test('a line stops at the server limit of 50', async ({ page }) => {
  test.slow();
  await page.locator('.menu-card', { hasText: 'Demlik Çay' }).click();
  const more = page.getByRole('button', { name: 'Add one Demlik Çay' });
  for (let i = 1; i < 50; i++) await more.click();
  await expect(page.locator('.cart-line .stepper span')).toHaveText('50');
  await expect(more).toBeDisabled();
});

test('clearing the ticket can be undone', async ({ page }) => {
  await page.locator('.menu-card', { hasText: 'Mantı' }).click();
  await page.locator('.menu-card', { hasText: 'Künefe' }).click();
  await page.getByRole('button', { name: 'Clear' }).click();
  await expect(page.locator('.cart-line')).toHaveCount(0);
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(page.locator('.cart-line')).toHaveCount(2);
});

test('the kitchen note is sent with the order', async ({ page }) => {
  await page.getByRole('radio', { name: 'Takeaway' }).click();
  await page.locator('.menu-card', { hasText: 'Lahmacun' }).click();
  await page.getByLabel('Guest name').fill('Note Test');
  await page.getByLabel('Note for the kitchen').fill('Extra lemon, please');
  await page.getByRole('button', { name: /Send to kitchen/ }).click();
  await page.getByRole('button', { name: 'View' }).click();
  await expect(page.locator('.drawer .sticky-note')).toHaveText('Extra lemon, please');
});

test('a coupon is validated by the server and priced into the total', async ({ page }) => {
  await page.locator('.menu-card', { hasText: 'Adana Kebap' }).click();
  await page.locator('.menu-card', { hasText: 'İskender' }).click();
  await page.getByLabel('Coupon code').fill('nosuchcode');
  await page.getByRole('button', { name: 'Apply' }).click();
  await expect(page.locator('.order-panel .field.invalid .error')).toContainText('does not exist');
  await page.getByLabel('Coupon code').fill('HOSGELDIN');
  await page.getByRole('button', { name: 'Apply' }).click();
  await expect(page.locator('.coupon-applied')).toContainText('HOSGELDIN');
  await expect(page.locator('.totals .line.discount')).toBeVisible();
});
