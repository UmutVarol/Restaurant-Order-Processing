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
  // 2 and 4 seats take "места", 6 and 8 take "мест"
  const seats = page.locator('.table-card .seats');
  await expect(seats.filter({ hasText: /^4 места$/ }).first()).toBeVisible();
  await expect(seats.filter({ hasText: /^6 мест$/ }).first()).toBeVisible();
});
