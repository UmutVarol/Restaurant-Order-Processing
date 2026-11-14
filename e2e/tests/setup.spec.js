// A manager adds a waiter and a table from the Setup screen; the waiter signs in with the temporary password,
// has to choose their own before anything else, and then works normally. Runs on a desktop and on a phone.
const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test('a manager adds a waiter who chooses their own password at the first sign-in', async ({ page, browser }) => {
  await signIn(page, 'admin');
  await page.goto('/#/setup');
  await expect(page.getByTestId('setup-checklist')).toContainText('Add your staff');

  const suffix = Date.now().toString(36);
  await page.getByTestId('staff-add').click();
  await page.locator('#staff-name').fill(`Ayşe Kaya ${suffix}`);
  // the username is suggested from the name, without Turkish letters
  await expect(page.locator('#staff-username')).toHaveValue(`ayse.kaya.${suffix}`);
  await page.getByTestId('staff-save').click();
  const username = await page.getByTestId('cred-username').textContent();
  const temporary = await page.getByTestId('cred-password').textContent();
  expect(username).toBe(`ayse.kaya.${suffix}`);
  expect(temporary).toMatch(/^[a-z2-9]{4}-[a-z2-9]{4}-[a-z2-9]{4}$/);
  await page.getByRole('button', { name: 'Done' }).click();
  await expect(page.getByTestId(`staff-${username}`)).toContainText('has not signed in yet');

  // a table for the terrace
  const code = `E${suffix.slice(-4)}`;
  await page.getByTestId('table-add').click();
  await page.locator('#table-code').fill(code);
  await page.locator('#table-seats').fill('6');
  await page.locator('#table-zone').selectOption('TERRACE');
  await page.getByTestId('table-save').click();
  await expect(page.getByTestId(`table-${code}`)).toContainText('6');

  // the waiter's first sign-in, in a browser of their own
  const context = await browser.newContext({ viewport: page.viewportSize(), locale: 'en-GB', timezoneId: 'Europe/Istanbul' });
  const waiter = await context.newPage();
  await waiter.addInitScript(() => localStorage.setItem('sofra.lang', 'en'));
  await waiter.goto('/');
  await waiter.locator('#username').fill(username);
  await waiter.locator('#password').fill(temporary);
  await waiter.getByRole('button', { name: /Sign in/ }).click();
  await expect(waiter.getByRole('heading', { name: 'Choose your password' })).toBeVisible();
  await waiter.locator('#pw-current').fill(temporary);
  await waiter.locator('#pw-new').fill('short');
  await waiter.locator('#pw-again').fill('short');
  await waiter.getByTestId('pw-save').click();
  await expect(waiter.locator('.form-error')).toContainText('at least 10');
  await waiter.locator('#pw-new').fill('çay ocağı açık 24');
  await waiter.locator('#pw-again').fill('çay ocağı açık 24');
  await waiter.getByTestId('pw-save').click();
  await expect(waiter.locator('.shell')).toBeVisible();
  // the new table is on their floor plan
  await waiter.goto('/#/floor');
  await expect(waiter.getByTestId(`table-${code}`)).toBeVisible();
  await context.close();

  // the manager switches the account off again and removes the table
  await page.reload();
  await page.getByTestId(`staff-edit-${username}`).click();
  await page.getByTestId('staff-enabled').uncheck();
  await page.getByTestId('staff-save').click();
  await expect(page.getByTestId(`staff-${username}`)).toContainText('switched off');
  await page.getByTestId(`table-${code}`).click();
  await page.getByTestId('table-remove').click();
  await page.getByRole('button', { name: 'Remove table' }).last().click();
  await expect(page.getByTestId(`table-${code}`)).toHaveCount(0);
});
