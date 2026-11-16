const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test('a screen on the kitchen wall switches to the far view and keeps it', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'the far view is for a wall screen or a tablet');
  await signIn(page, 'chef');
  await page.goto('/#/kitchen');
  await expect(page.locator('.kds-col-head .shape')).toHaveCount(4);
  const far = page.getByTestId('kitchen-far');
  await expect(far).toHaveAttribute('aria-pressed', 'false');
  await far.click();
  await expect(page.locator('.kds.far')).toBeVisible();
  // each column still says what it is in words, next to its shape
  await expect(page.locator('.kds-col-head h3').first()).toBeVisible();
  await page.reload();
  await expect(page.locator('.kds.far')).toBeVisible();
  await page.getByTestId('kitchen-far').click();
  await expect(page.locator('.kds.far')).toHaveCount(0);
});
