// The app on a phone: most shifts are worked from one. Runs in the phone project only.
const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest } = require('./helpers');

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

/** Whether the element sits on the bottom edge of the screen and spans its width, like a sheet. */
async function expectSheet(page, locator) {
  const viewport = page.viewportSize();
  await expect(async () => {
    const box = await locator.boundingBox();
    expect(Math.round(box.y + box.height)).toBe(viewport.height);
    expect(Math.round(box.width)).toBe(viewport.width);
  }).toPass();
}

test('a waiter moves between screens from the tab bar and the More sheet', async ({ page }) => {
  await signIn(page, 'waiter');
  await expect(page.locator('.sidebar')).toBeHidden();
  const tabbar = page.locator('.tabbar');
  await expect(tabbar).toBeVisible();
  await expect(tabbar.locator('.tab-label')).toHaveText(['Service', 'Order', 'Floor', 'Cashier', 'More']);
  await expect(page.getByTestId('tab-pos')).toHaveClass(/active/);

  await page.getByTestId('tab-floor').click();
  await expect(page).toHaveURL(/#\/floor/);
  await expect(page.getByTestId('tab-floor')).toHaveAttribute('aria-current', 'page');

  await page.getByTestId('tab-more').click();
  const sheet = page.locator('.modal');
  await expectSheet(page, sheet);
  await expect(sheet).toContainText('Can Demir');
  await sheet.getByTestId('more-kitchen').click();
  await expect(page).toHaveURL(/#\/kitchen/);
  await expect(page.locator('.modal')).toHaveCount(0);
  // a screen that is not in the tab bar lights up "More"
  await expect(page.getByTestId('tab-more')).toHaveClass(/active/);
});

test('a sheet follows the finger and closes when dragged down', async ({ page }) => {
  await signIn(page, 'waiter');
  await page.getByTestId('tab-more').click();
  const sheet = page.locator('.modal');
  await expectSheet(page, sheet);
  const head = sheet.locator('.modal-head h2');
  const box = await head.boundingBox();
  // a short drag springs back
  await page.mouse.move(box.x + 20, box.y + 10);
  await page.mouse.down();
  await page.mouse.move(box.x + 20, box.y + 40, { steps: 6 });
  await page.mouse.up();
  await expect(sheet).toBeVisible();
  await expectSheet(page, sheet);
  // a long one closes it
  await page.mouse.move(box.x + 20, box.y + 10);
  await page.mouse.down();
  await page.mouse.move(box.x + 20, box.y + 260, { steps: 10 });
  await page.mouse.up();
  await expect(page.locator('.modal')).toHaveCount(0);
});

test('a waiter rings up a takeaway on a phone from the ticket sheet', async ({ page }) => {
  await signIn(page, 'waiter');
  const guest = uniqueGuest('Phone');
  await page.locator('.menu-card', { hasText: 'Ayran' }).click();
  await page.locator('.menu-card', { hasText: 'Ayran' }).click();
  const bar = page.getByTestId('open-ticket');
  await expect(bar).toContainText('2');
  await expect(bar).toContainText('₺');

  // the ticket is hidden until the bar opens it, as a sheet
  const ticket = page.locator('.order-panel');
  await expect(ticket).toBeHidden();
  await bar.click();
  await expectSheet(page, ticket);
  await ticket.getByRole('radio', { name: 'Takeaway' }).click();
  await ticket.getByLabel('Guest name').fill(guest);
  await ticket.getByRole('button', { name: /Send to kitchen/ }).click();
  await expect(page.getByRole('status').filter({ hasText: /sent to the kitchen/ })).toBeVisible();
  await expect(ticket).toBeHidden();
  await expect(bar).toBeHidden();

  const placed = (await api(page, 'GET', '/orders/active')).find((o) => o.customerName === guest);
  expect(placed.type).toBe('TAKEAWAY');
  await api(page, 'POST', `/orders/${placed.id}/cancel`, { reason: 'E2E clean-up' });
});

test('orders read as cards and an order opens as a sheet', async ({ page }) => {
  await signIn(page, 'admin');
  await page.getByTestId('tab-orders').click();
  const table = page.locator('.orders-table');
  await expect(table.locator('thead')).toBeHidden();
  const first = table.locator('tbody tr').first();
  await expect(first.locator('.order-no')).toBeVisible();
  await expect(first.locator('.state')).toBeVisible();
  await first.click();
  await expectSheet(page, page.locator('.drawer'));
  // every screen starts at its top, wherever the last one was scrolled to
  await page.locator('.drawer .drawer-head').getByRole('button', { name: 'Close' }).click();
  await page.mouse.wheel(0, 800);
  await page.getByTestId('tab-floor').click();
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
});
