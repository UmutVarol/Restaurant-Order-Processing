const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest } = require('./helpers');

test('a dish is ordered with its options, priced and shown to the kitchen', async ({ page }) => {
  await signIn(page, 'waiter');
  await page.getByRole('radio', { name: 'Takeaway' }).click();
  const adana = page.locator('.menu-card', { hasText: 'Adana Kebap' });
  await adana.click();
  const line = page.locator('.cart-line').first();
  // required groups start on their first, free choice
  await expect(line.getByTestId('line-options')).toHaveText('Normal');

  await line.getByTestId('line-options').click();
  const modal = page.locator('.modal');
  await modal.getByRole('radio', { name: 'Bol acılı' }).click();
  await modal.getByRole('checkbox', { name: /Ekstra lavaş/ }).click();
  await modal.getByRole('checkbox', { name: /Közlenmiş domates/ }).click();
  await expect(modal.locator('.options-total')).toHaveText(/565/);
  await modal.getByTestId('options-apply').click();
  await expect(line.getByTestId('line-options')).toHaveText('Bol acılı · Közlenmiş domates · Ekstra lavaş');
  await expect(line.locator('.total')).toHaveText(/565/);

  // the same dish as it comes is a separate line
  await adana.click();
  await expect(page.locator('.cart-line')).toHaveCount(2);
  await expect(adana.locator('.qty-bubble')).toHaveText('2');

  const guest = uniqueGuest('Options');
  await page.getByLabel('Guest name').fill(guest);
  await page.getByRole('button', { name: /Send to kitchen/ }).click();
  await page.getByRole('button', { name: 'View' }).click();
  await expect(page.locator('.drawer .line-item .opts').first()).toHaveText('Bol acılı · Közlenmiş domates · Ekstra lavaş');
});

test('a manager edits the option groups of a dish', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/menu');
  await page.getByRole('button', { name: 'Edit: Lahmacun' }).click();
  const editor = page.locator('.modal .options-editor');
  await expect(editor.locator('.option-group-edit')).toHaveCount(1);
  await editor.getByRole('button', { name: 'Add choice' }).click();
  await editor.getByLabel('Choice, e.g. Extra spicy').last().fill('Bol limonlu');
  await page.locator('.modal').getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('.toast')).toContainText('Lahmacun');

  await page.goto('/#/pos');
  await page.locator('.menu-card', { hasText: 'Lahmacun' }).click();
  await page.locator('.cart-line').getByTestId('line-options').click();
  await expect(page.locator('.modal').getByRole('radio', { name: 'Bol limonlu' })).toBeVisible();
});
