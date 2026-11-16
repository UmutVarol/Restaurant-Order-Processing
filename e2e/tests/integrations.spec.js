const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test('the owner connects a delivery platform with its own secret and sees where to point it', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'one browser is enough for a settings form');
  await signIn(page, 'admin');
  await page.goto('/#/settings');
  const card = page.getByTestId('integrations-card');
  const getir = card.getByTestId('integration-GETIR');
  await expect(getir).toBeVisible();
  await getir.locator('summary').click();
  await expect(getir.getByTestId('int-GETIR-webhook')).toContainText('/api/v1/channels/getir/webhook/');

  // a private address is refused before anything is stored
  await getir.locator('#int-GETIR-secret').fill('getir-webhook-secret-4321');
  await getir.locator('#int-GETIR-url').fill('https://192.168.1.20/api');
  await getir.locator('#int-GETIR-key').fill('getir-api-token-12345678');
  await getir.getByTestId('int-GETIR-save').click();
  await expect(getir.locator('.form-error')).toContainText('private network');

  await getir.locator('#int-GETIR-url').fill('');
  await getir.locator('#int-GETIR-key').fill('');
  await getir.getByTestId('int-GETIR-save').click();
  await expect(card.getByTestId('int-GETIR-status')).toContainText('Connected · key …4321');

  // taking it off again leaves the platform as it was
  await card.getByTestId('integration-GETIR').locator('summary').click();
  await card.getByTestId('int-GETIR-remove').click();
  await page.locator('.modal').getByRole('button', { name: 'Disconnect' }).click();
  await expect(card.getByTestId('int-GETIR-status')).toContainText('Not connected');
});
