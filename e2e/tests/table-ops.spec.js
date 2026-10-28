const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest } = require('./helpers');

/** Calls the API as the signed-in user of this tab. */
async function api(page, method, path, body) {
  return page.evaluate(async ({ method, path, body }) => {
    const session = JSON.parse(sessionStorage.getItem('sofra.session'));
    const response = await fetch(`/api/v1${path}`, {
      method, headers: { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    return response.json();
  }, { method, path, body });
}

test('a waiter voids, comps with a manager PIN, discounts and moves a table', async ({ page }) => {
  await signIn(page, 'waiter');
  const tables = await api(page, 'GET', '/floor/tables');
  const [from, to] = tables.filter((table) => !table.occupied);
  const menu = await api(page, 'GET', '/menu');
  const lahmacun = menu.find((item) => item.name === 'Lahmacun');
  const guest = uniqueGuest('Ops');
  await api(page, 'POST', '/orders', {
    type: 'DINE_IN', tableId: from.id, customerName: guest, paymentMethod: 'CARD',
    items: [{ menuItemId: lahmacun.id, quantity: 3 }],
  });

  await page.goto('/#/floor');
  await page.locator('.table-card', { hasText: from.code }).click();
  const drawer = page.locator('.drawer');
  await expect(drawer.locator('.line-item')).toContainText('3 × Lahmacun');

  // still pending: a void needs no manager
  await drawer.getByTestId('void-line').click();
  const modal = page.locator('.modal');
  await expect(modal.locator('.approval')).toBeHidden();
  await modal.getByRole('button', { name: 'Entered by mistake' }).click();
  await modal.getByTestId('adjust-confirm').click();
  await expect(drawer.locator('.line-item')).toContainText('2 × Lahmacun');
  await expect(drawer.locator('.adj-tag.void')).toHaveText('1 voided');

  // a comp needs the manager's PIN
  await drawer.getByTestId('comp-line').click();
  await modal.getByRole('button', { name: 'Long wait' }).click();
  await modal.getByLabel('Manager username').fill('admin');
  await modal.getByLabel('Manager PIN').fill('1111');
  await modal.getByTestId('adjust-confirm').click();
  await expect(modal.locator('.form-error')).toContainText('manager approval was not accepted');
  await modal.getByLabel('Manager PIN').fill('2468');
  await modal.getByTestId('adjust-confirm').click();
  await expect(drawer.locator('.adj-tag.comp')).toHaveText('1 on the house');
  await expect(drawer.locator('.adjustments')).toContainText('approved by admin');

  // a 10% discount; the manager's name is remembered
  await drawer.getByTestId('discount-order').click();
  await modal.getByRole('button', { name: '%10' }).click();
  await modal.getByRole('button', { name: 'Regular guest' }).click();
  await expect(modal.getByLabel('Manager username')).toHaveValue('admin');
  await modal.getByLabel('Manager PIN').fill('2468');
  await modal.getByTestId('adjust-confirm').click();
  await expect(drawer.locator('.totals .line.discount')).toContainText('22');
  await drawer.getByRole('button', { name: 'Close' }).first().click();

  // the seating moves to a free table
  await page.getByTestId(`move-${from.code}`).click();
  await modal.locator('.transfer-picker button', { hasText: to.code }).click();
  await modal.getByTestId('transfer-confirm').click();
  await expect(page.locator('.toast', { hasText: `${from.code} moved to ${to.code}` })).toBeVisible();
  await expect(page.locator('.table-card', { hasText: to.code })).toContainText(guest);
});
