// No screen overflows, clips or overlaps in the languages with the longest words (German, Russian) or the other
// direction (Arabic), on a desktop and on a phone. Runs in both projects.
const { test, expect } = require('@playwright/test');
const { findLayoutProblems } = require('./support/layout-check');

const ROUTES = ['dashboard', 'pos', 'kitchen', 'floor', 'orders', 'cashier', 'menu', 'stock', 'channels', 'settings'];

for (const lang of ['de', 'ru', 'ar']) {
  test(`every screen fits in ${lang}`, async ({ page }) => {
    test.setTimeout(90_000);
    await page.addInitScript((code) => {
      if (!sessionStorage.getItem('e2e.init')) {
        localStorage.setItem('sofra.lang', code);
        localStorage.setItem('sofra.theme', '"light"');
        localStorage.setItem('sofra.sound', 'false');
        sessionStorage.setItem('e2e.init', '1');
      }
    }, lang);
    await page.goto('/');
    expect(await page.evaluate(findLayoutProblems), 'sign-in').toEqual([]);
    await page.getByRole('button', { name: /Elif Yılmaz/ }).click();
    await expect(page.locator('.shell')).toBeVisible();
    for (const route of ROUTES) {
      await page.goto(`/#/${route}`);
      await expect(page.locator('.skeleton')).toHaveCount(0);
      // let charts and lazy rows settle
      await page.waitForTimeout(300);
      expect(await page.evaluate(findLayoutProblems), route).toEqual([]);
    }
    await page.goto('/#/orders');
    await page.locator('.orders-table tbody tr').first().click();
    await expect(page.locator('.drawer')).toBeVisible();
    await page.waitForTimeout(400);
    expect(await page.evaluate(findLayoutProblems), 'order drawer').toEqual([]);
  });
}
