// An owner opens a second branch from Setup. The device then picks the branch it works in at the top: each branch
// has its own tables, and "All branches" shows them together. The branch is closed again at the end, so the rest of
// the suite keeps working with a single restaurant.
const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test('an owner opens a second branch, and each branch keeps its own tables', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/setup');
  const card = page.getByTestId('setup-branches');
  await expect(card).toContainText('One place for now');

  const suffix = Date.now().toString(36).slice(-5);
  const name = `Kadıköy ${suffix}`;
  const code = `kadikoy-${suffix}`;
  await page.getByTestId('branch-add').click();
  await page.locator('#branch-name').fill(name);
  // the code is suggested from the name, without Turkish letters
  await expect(page.locator('#branch-code')).toHaveValue(code);
  await page.getByTestId('branch-save').click();
  await expect(page.getByTestId(`branch-${code}`)).toBeVisible();
  await expect(card).toContainText('2 open branches');

  // with two branches, the device chooses where it works; the new branch starts with an empty floor
  await page.reload();
  const picker = page.getByTestId('branch-picker');
  await picker.selectOption({ label: name });
  await expect(page.getByTestId('branch-picker').locator('option:checked')).toHaveText(name);
  await expect(page.getByTestId('setup-tables')).toContainText('No tables yet');

  // table 1 exists in the main branch; this branch gets a table 1 of its own
  await page.getByTestId('table-add').click();
  await expect(page.locator('#table-code')).toHaveValue('1');
  await page.getByTestId('table-save').click();
  await expect(page.getByTestId('table-1')).toBeVisible();
  await page.goto('/#/floor');
  await expect(page.locator('.table-card')).toHaveCount(1);

  // all branches at once: both tables called 1
  await page.getByTestId('branch-picker').selectOption({ label: 'All branches' });
  await expect(page.locator('[data-testid="table-1"]')).toHaveCount(2);

  // tidy up: the branch's table goes, then the branch closes
  await page.getByTestId('branch-picker').selectOption({ label: name });
  await page.goto('/#/setup');
  await page.getByTestId('table-1').click();
  await page.getByTestId('table-remove').click();
  await page.getByRole('button', { name: 'Remove table' }).last().click();
  await expect(page.getByTestId('setup-tables')).toContainText('No tables yet');

  await page.getByTestId('branch-picker').selectOption({ label: 'All branches' });
  await page.getByTestId(`branch-edit-${code}`).click();
  await page.getByTestId('branch-open').uncheck();
  await page.getByTestId('branch-save').click();
  await expect(page.getByTestId(`branch-${code}`)).toContainText('closed');
  await page.reload();
  await expect(page.getByTestId('branch-picker')).toHaveCount(0);
});
