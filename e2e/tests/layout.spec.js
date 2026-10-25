// Runs on desktop and on a phone viewport: no screen may scroll sideways, and the shell must stay usable.
const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

const ROUTES = ['dashboard', 'pos', 'kitchen', 'floor', 'orders', 'menu'];

for (const theme of ['light', 'dark']) {
  test(`every screen fits the viewport in the ${theme} theme`, async ({ page }) => {
    await signIn(page, 'admin');
    if (theme === 'dark') await page.getByRole('button', { name: 'Toggle dark mode' }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
    for (const route of ROUTES) {
      await page.goto(`/#/${route}`);
      await expect(page.locator('.skeleton')).toHaveCount(0);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `${route} scrolls sideways`).toBeLessThanOrEqual(0);
    }
  });
}

test('the skip link moves focus to the page content', async ({ page }) => {
  await signIn(page, 'admin');
  await page.keyboard.press('Tab');
  const skip = page.getByRole('link', { name: 'Skip to content' });
  await expect(skip).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('#view')).toBeFocused();
});

test('the theme toggle switches the colour scheme', async ({ page }) => {
  await signIn(page, 'admin');
  await page.getByRole('button', { name: 'Toggle dark mode' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
});
