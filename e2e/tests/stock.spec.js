const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test('a manager receives, wastes and counts stock, and costs a dish with a recipe', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/stock');
  await expect(page.locator('tr', { hasText: 'Antep fıstığı' })).toBeVisible();

  const name = `E2E Flour ${Date.now().toString(36)}`;
  await page.getByTestId('add-ingredient').click();
  const modal = page.locator('.modal');
  await modal.getByLabel('Name').fill(name);
  await modal.getByLabel('Price per kg').fill('40');
  await modal.getByLabel('Warn below (kg)').fill('1');
  await modal.getByTestId('save-ingredient').click();
  const row = page.locator('tr', { hasText: name });
  await expect(row.getByTestId('stock-out')).toBeVisible();

  // a delivery in kilograms
  await row.getByTestId('record-delivery').click();
  await modal.getByLabel('Quantity').fill('2');
  await modal.getByLabel('Supplier').fill('Değirmen');
  await modal.getByTestId('save-movement').click();
  await expect(row.getByTestId('on-hand')).toHaveText('2 kg');

  // waste needs a reason
  await row.getByTestId('record-waste').click();
  await modal.getByLabel('Quantity').fill('250');
  await modal.getByLabel('Counted by').selectOption('G');
  await modal.getByTestId('save-movement').click();
  await expect(modal.locator('.form-error')).toContainText('Say why');
  await modal.getByRole('button', { name: 'Spoiled' }).click();
  await modal.getByTestId('save-movement').click();
  await expect(row.getByTestId('on-hand')).toHaveText('1.75 kg');

  // a count records the difference and the ingredient is now low
  await row.getByTestId('record-count').click();
  await expect(modal).toContainText('The ledger says 1.75 kg');
  await modal.getByLabel('On the shelf').fill('0.9');
  await modal.getByTestId('save-movement').click();
  await expect(row.getByTestId('on-hand')).toHaveText('900 g');
  await expect(row.getByTestId('stock-low')).toBeVisible();

  await row.getByRole('button', { name: `Movements: ${name}` }).click();
  const movements = page.locator('.drawer').getByTestId('movement-list');
  await expect(movements.locator('li')).toHaveCount(3);
  await expect(movements.locator('li').first()).toContainText('850 g');
  await page.locator('.drawer').getByRole('button', { name: 'Close' }).click();

  await page.getByTestId('stock-tab-report').click();
  await expect(page.getByTestId('stock-report')).toContainText('Count difference');
  await expect(page.locator('tr', { hasText: name })).toContainText('250 g');

  // the recipe costs the dish on the menu screen
  await page.goto('/#/menu');
  const dish = page.locator('tr', { hasText: 'San Sebastian Cheesecake' });
  await dish.getByTestId('edit-recipe').click();
  await modal.getByTestId('add-recipe-line').click();
  await modal.getByTestId('recipe-ingredient').last().selectOption({ label: name });
  await modal.getByTestId('recipe-quantity').last().fill('100');
  await expect(modal.getByTestId('recipe-cost')).toContainText('₺4.00');
  await modal.getByTestId('save-recipe').click();
  await expect(dish.getByTestId('food-cost')).toContainText('₺4.00');

  // leave the seeded dish as it was
  await dish.getByTestId('edit-recipe').click();
  await modal.getByRole('button', { name: 'Remove line' }).last().click();
  await modal.getByTestId('save-recipe').click();
  await expect(dish.getByTestId('food-cost')).toHaveCount(0);
});
