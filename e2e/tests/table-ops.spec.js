const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest, freeTables } = require('./helpers');

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
  const [from, to] = await freeTables(page, 2, (table) => !table.occupied);
  const menu = await api(page, 'GET', '/menu');
  const lahmacun = menu.find((item) => item.name === 'Lahmacun');
  const guest = uniqueGuest('Ops');
  await api(page, 'POST', '/orders', {
    type: 'DINE_IN', tableId: from.id, customerName: guest, paymentMethod: 'CARD',
    items: [{ menuItemId: lahmacun.id, quantity: 3 }],
  });

  await page.goto('/#/floor');
  await page.getByTestId(`table-${from.code}`).click();
  const drawer = page.locator('.drawer');
  await expect(drawer.locator('.line-item')).toContainText('3 × Lahmacun');

  // still pending: a void needs no manager, and can be taken back from the toast (E1); nothing reaches the server
  const modal = page.locator('.modal');
  await drawer.getByTestId('void-line').click();
  await expect(modal.locator('.approval')).toBeHidden();
  await modal.getByRole('button', { name: 'Entered by mistake' }).click();
  await modal.getByTestId('adjust-confirm').click();
  await expect(drawer.locator('.line-item')).toContainText('2 × Lahmacun');
  await page.locator('.toast', { hasText: 'Lahmacun' }).getByTestId('toast-action').click();
  await expect(drawer.locator('.line-item')).toContainText('3 × Lahmacun');
  await expect(drawer.locator('.adj-tag.void')).toHaveCount(0);
  await expect(modal).toHaveCount(0);
  const placed = (await api(page, 'GET', '/orders/active')).find((o) => o.customerName === guest);
  expect(placed.lines[0].voidedQuantity).toBe(0);

  await drawer.getByTestId('void-line').click();
  await modal.getByRole('button', { name: 'Entered by mistake' }).click();
  await modal.getByTestId('adjust-confirm').click();
  await expect(drawer.locator('.line-item')).toContainText('2 × Lahmacun');
  await expect(drawer.locator('.adj-tag.void')).toHaveText('1 voided');
  // once the undo time is over, the server has it
  await expect.poll(async () => (await api(page, 'GET', `/orders/${placed.id}`)).lines[0].voidedQuantity, { timeout: 10_000 }).toBe(1);
  // the dialog has finished closing before the next one opens (two open at once would share their buttons)
  await expect(modal).toHaveCount(0);

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
  await expect(modal).toHaveCount(0);

  // a 10% discount; the manager's name is remembered
  await drawer.getByTestId('discount-order').click();
  await modal.getByRole('button', { name: '10%' }).click();
  await modal.getByRole('button', { name: 'Regular guest' }).click();
  await expect(modal.getByLabel('Manager username')).toHaveValue('admin');
  await modal.getByLabel('Manager PIN').fill('2468');
  await modal.getByTestId('adjust-confirm').click();
  await expect(drawer.locator('.totals .line.discount')).toContainText('22');
  await drawer.getByRole('button', { name: 'Close' }).first().click();

  // the seating moves to a free table
  await page.getByTestId(`move-${from.code}`).click();
  await modal.getByTestId(`pick-${to.code}`).click();
  await modal.getByTestId('transfer-confirm').click();
  await expect(page.locator('.toast', { hasText: `${from.code} moved to ${to.code}` })).toBeVisible();
  await expect(page.getByTestId(`table-${to.code}`)).toContainText(guest);

  // leave the table free for the tests that need one
  const moved = (await api(page, 'GET', '/orders/active')).find((o) => o.customerName === guest);
  await api(page, 'POST', `/orders/${moved.id}/cancel`, { reason: 'E2E clean-up' });
});
