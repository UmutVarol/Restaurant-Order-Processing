const { test, expect } = require('@playwright/test');

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('sofra.lang', 'en'));
});

test('the login screen offers the three demo accounts', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.demo-account')).toHaveCount(3);
  await expect(page.locator('.demo-account').first()).toContainText('admin');
});

test('wrong credentials show an inline error and keep the form usable', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill('not-the-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toHaveText('Invalid username or password.');
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeEnabled();
});

test('each demo role lands on its home screen', async ({ page }) => {
  for (const [name, title] of [['Elif Yılmaz', 'Dashboard'], ['Can Demir', 'New order'], ['Mehmet Usta', 'Kitchen display']]) {
    await page.goto('/');
    await page.getByRole('button', { name: new RegExp(name) }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(title);
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.locator('.login-card')).toBeVisible();
  }
});
