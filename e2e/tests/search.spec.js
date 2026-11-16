const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test('a manager finds a dish without its accents and jumps to a screen from the keyboard', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'the shortcut is for a keyboard; phones use the magnifier, checked below');
  await signIn(page, 'admin');
  await page.keyboard.press('Control+k');
  const field = page.getByRole('combobox', { name: 'Quick search' });
  await expect(field).toBeFocused();

  await field.fill('kunefe');
  const dish = page.getByTestId('search-result').filter({ hasText: 'Künefe' }).first();
  await expect(dish).toContainText('Dish');
  await expect(dish.locator('mark')).toHaveText('Künefe');

  await field.fill('dash');
  await expect(page.getByTestId('search-result').first()).toContainText('Dashboard');
  await field.press('Enter');
  await expect(page).toHaveURL(/#\/dashboard/);
  await expect(page.locator('.search-modal')).toHaveCount(0);
});

test('a waiter opens the search from the top bar and only sees screens they may open', async ({ page }) => {
  await signIn(page, 'waiter');
  await page.getByTestId('open-search').click();
  const field = page.getByRole('combobox', { name: 'Quick search' });
  await field.fill('Dashboard');
  await expect(page.locator('.search-empty')).toBeVisible();
  await field.fill('Floor');
  await expect(page.getByTestId('search-result').first()).toContainText('Floor');
  await page.getByTestId('search-result').first().click();
  await expect(page).toHaveURL(/#\/floor/);
});
