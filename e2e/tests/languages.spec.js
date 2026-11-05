// Six languages: the picker switches the whole app at once, Arabic lays the app out right to left, and Russian
// counts with its own plural forms.
const { test, expect } = require('@playwright/test');

const ACCOUNT = 'Elif Yılmaz';

async function openFresh(page) {
  await page.addInitScript(() => {
    if (!sessionStorage.getItem('e2e.init')) {
      localStorage.setItem('sofra.lang', 'en');
      localStorage.setItem('sofra.theme', '"light"');
      localStorage.setItem('sofra.sound', 'false');
      sessionStorage.setItem('e2e.init', '1');
    }
  });
  await page.goto('/');
}

async function pick(page, code) {
  await page.getByTestId('lang-button').first().click();
  await page.locator('.modal').getByTestId(`lang-${code}`).click();
  await expect(page.locator('html')).toHaveAttribute('lang', code);
}

test('the language picker offers six languages and switches the sign-in screen', async ({ page }) => {
  await openFresh(page);
  await page.getByTestId('lang-button').first().click();
  await expect(page.locator('.modal .lang-option')).toHaveText([/Türkçe/, /English/, /Deutsch/, /Français/, /Русский/, /العربية/]);
  await page.locator('.modal').getByTestId('lang-de').click();
  await expect(page.getByRole('heading', { name: 'Willkommen zurück' })).toBeVisible();
  await pick(page, 'fr');
  await expect(page.getByRole('heading', { name: 'Bon retour' })).toBeVisible();
  // the choice is remembered
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Bon retour' })).toBeVisible();
});

test('Arabic lays the app out right to left without anything spilling sideways', async ({ page }) => {
  await openFresh(page);
  await pick(page, 'ar');
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await page.getByRole('button', { name: new RegExp(ACCOUNT) }).click();
  await expect(page.locator('.shell')).toBeVisible();
  await expect(page.locator('.nav')).toContainText('طلب جديد');
  // the sidebar sits on the right
  const viewport = page.viewportSize();
  const sidebar = await page.locator('.sidebar').boundingBox();
  expect(sidebar.x).toBeGreaterThan(viewport.width / 2);
  for (const route of ['dashboard', 'pos', 'kitchen', 'floor', 'orders', 'cashier', 'menu', 'settings']) {
    await page.goto(`/#/${route}`);
    await expect(page.locator('.skeleton')).toHaveCount(0);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `${route} scrolls sideways`).toBeLessThanOrEqual(0);
  }
  // prices keep Western digits
  await page.goto('/#/pos');
  await expect(page.locator('.menu-card .price').first()).toHaveText(/[0-9]/);
});

test('Russian counts seats with its own plural forms', async ({ page }) => {
  await openFresh(page);
  await pick(page, 'ru');
  await page.getByRole('button', { name: new RegExp(ACCOUNT) }).click();
  await expect(page.locator('.shell')).toBeVisible();
  await page.goto('/#/floor');
  // the floor counts seats in Russian; which tables are free depends on the time of day (the demo data follows it)
  await expect(page.locator('.table-card .seats').filter({ hasText: /^\d+ мест[ао]?$/ }).first()).toBeVisible();
  // 1 and 21 take "место", 2 to 4 take "места", 5 to 20 take "мест", through the app's own texts
  const counts = await page.evaluate(async () => {
    const { tn } = await import('/assets/js/core/i18n.js');
    return [1, 2, 4, 5, 6, 8, 11, 21].map((n) => tn('floor.seats', n));
  });
  expect(counts).toEqual(['1 место', '2 места', '4 места', '5 мест', '6 мест', '8 мест', '11 мест', '21 место']);
});
