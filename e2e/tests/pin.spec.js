const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

async function typePin(page, pin) {
  for (const digit of pin) await page.locator('.pin-pad').getByRole('button', { name: digit, exact: true }).click();
  await page.getByTestId('pin-submit').click();
}

test('a registered till switches staff with a PIN', async ({ page }) => {
  await signIn(page, 'admin');
  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByLabel('Name').fill('E2E till');
  await page.getByTestId('register-device').click();
  await expect(page.locator('.toast')).toContainText('“E2E till” is registered');
  await expect(page.getByText('Registered as “E2E till”')).toBeVisible();

  // switch to the waiter: wrong PIN first, then the right one
  await page.getByTestId('switch-user').click();
  await expect(page.getByRole('heading', { name: "Who's working?" })).toBeVisible();
  await page.getByTestId('staff-waiter').click();
  await typePin(page, '1358');
  await expect(page.locator('.lock .form-error')).toHaveText('That PIN is not right.');
  await typePin(page, '1357');
  await expect(page.locator('.user-card .name')).toHaveText('Can Demir');
  await expect(page).toHaveURL(/#\/pos/);

  // back to the manager, typing the PIN on the keyboard
  await page.getByTestId('switch-user').click();
  await page.getByTestId('staff-admin').click();
  await page.keyboard.type('2468');
  await page.keyboard.press('Enter');
  await expect(page.locator('.user-card .name')).toHaveText('Elif Yılmaz');

  // the password form is one tap away from the lock screen
  await page.getByTestId('switch-user').click();
  await page.getByRole('button', { name: 'Sign in with password' }).click();
  await expect(page.getByLabel('Username')).toBeVisible();
  await page.getByRole('button', { name: 'Switch with a PIN' }).click();
  await expect(page.getByTestId('staff-chef')).toBeVisible();
});

test('a revoked till falls back to the password', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/settings');
  await page.getByLabel('Name').fill('Lost tablet');
  await page.getByTestId('register-device').click();
  await expect(page.getByText('Registered as “Lost tablet”')).toBeVisible();
  const row = page.locator('tr', { hasText: 'Lost tablet' });
  await row.getByRole('button', { name: 'Revoke' }).click();
  await page.locator('.modal').getByRole('button', { name: 'Revoke' }).click();
  await expect(page.locator('.toast', { hasText: '“Lost tablet” revoked' })).toBeVisible();
  await expect(page.getByText('Not registered')).toBeVisible();
});

test('staff set their own PIN with their password', async ({ page }) => {
  await signIn(page, 'chef');
  await page.goto('/#/settings');
  await page.getByLabel('Password').fill('chef123');
  await page.getByLabel('New PIN (4–6 digits)').fill('1111');
  await page.getByLabel('Repeat PIN').fill('1111');
  await page.getByTestId('save-pin').click();
  await expect(page.locator('.settings-form .form-error')).toContainText('not a run or a repeat');
  await page.getByLabel('New PIN (4–6 digits)').fill('9024');
  await page.getByLabel('Repeat PIN').fill('9024');
  await page.getByTestId('save-pin').click();
  await expect(page.locator('.toast')).toContainText('PIN saved');
  await expect(page.getByText('Ask a manager to register this till')).toBeVisible();
});
