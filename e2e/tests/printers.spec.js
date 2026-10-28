const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test('a manager adds a kitchen printer on the local network only', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/settings');
  await page.getByTestId('add-printer').click();
  const modal = page.locator('.modal');
  const name = `E2E ${Date.now().toString(36)}`;
  await modal.getByLabel('Name').fill(name);
  await modal.getByLabel('IP address').fill('8.8.8.8');
  await modal.getByLabel('In use').uncheck();
  await modal.getByTestId('save-printer').click();
  await expect(modal.locator('.form-error')).toContainText('your own network');

  await modal.getByLabel('IP address').fill('192.168.1.250');
  await modal.getByTestId('save-printer').click();
  const row = page.locator('.printer-row', { hasText: name });
  await expect(row).toContainText('192.168.1.250:9100');
  await expect(row).toContainText('off');

  await row.getByRole('button', { name: 'Preview' }).click();
  await expect(page.locator('.ticket-preview')).toContainText('TEST SAYFASI');
  await page.locator('.modal').getByRole('button', { name: 'Close' }).last().click();

  await row.getByRole('button', { name: 'Edit' }).click();
  await page.locator('.modal').getByRole('button', { name: 'Remove' }).first().click();
  await page.locator('.modal').last().getByRole('button', { name: 'Remove' }).click();
  await expect(page.locator('.printer-row', { hasText: name })).toHaveCount(0);
});
