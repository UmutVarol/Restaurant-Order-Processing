const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

// Closing a day can happen once per day and branch, so this test closes today only when nobody has yet, and
// otherwise opens the card from the closed day; both ways end at the same card.
test('closing today shows the day as a card, which can be saved as a picture', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'one close per day is enough');
  await signIn(page, 'admin');
  await page.goto('/#/cashier');
  const close = page.getByTestId('close-day');
  const reopen = page.getByTestId('open-day-card');
  await expect(close.or(reopen)).toBeVisible();
  if (await close.isVisible()) {
    await page.locator('#counted-cash').fill('0');
    await close.click();
    const password = page.locator('#reauth-password');
    if (await password.isVisible({ timeout: 2000 }).catch(() => false)) {
      await password.fill('admin123');
      await page.getByTestId('reauth-confirm').click();
    }
  } else {
    await reopen.click();
  }
  const card = page.getByTestId('day-card');
  await expect(card).toBeVisible();
  await expect(card).toContainText('Today at the table');
  await expect(card.locator('[data-fig="orders"]')).toBeVisible();

  // no share sheet in a desktop browser: the picture is saved instead
  const download = page.waitForEvent('download');
  await page.getByTestId('day-card-share').click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^sofra-\d{4}-\d{2}-\d{2}\.png$/);
});
