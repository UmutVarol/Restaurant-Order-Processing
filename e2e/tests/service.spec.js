// The core loop of a restaurant: the waiter rings up an order and the kitchen sees it appear live.
const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest } = require('./helpers');

test('a takeaway order travels from the till to the kitchen rail in real time', async ({ browser }) => {
  const kitchenContext = await browser.newContext();
  const kitchen = await kitchenContext.newPage();
  await signIn(kitchen, 'chef');
  await expect(kitchen.locator('.live.on')).toBeVisible();

  const tillContext = await browser.newContext();
  const till = await tillContext.newPage();
  await signIn(till, 'waiter');
  const guest = uniqueGuest('Rail');

  await till.getByRole('radio', { name: 'Takeaway' }).click();
  await till.locator('.menu-card', { hasText: 'Adana Kebap' }).click();
  await till.locator('.menu-card', { hasText: 'Ayran' }).click();
  await till.getByLabel('Guest name').fill(guest);
  const send = till.getByRole('button', { name: /Send to kitchen/ });
  await expect(send).toContainText('₺');
  await send.click();
  await expect(till.getByRole('status').filter({ hasText: /sent to the kitchen/ })).toBeVisible();
  await expect(till.locator('.cart-line')).toHaveCount(0);

  // No reload: the ticket arrives over the event stream.
  const ticket = kitchen.locator('.kds-col[aria-label="New"] .ticket', { hasText: guest });
  await expect(ticket).toBeVisible();
  await expect(ticket).toHaveClass(/fresh/);
  await expect(ticket.locator('.ticket-item')).toHaveCount(2);
  // The time-budget bar starts filling as soon as the ticket arrives.
  await expect(ticket.locator('.sla')).toHaveAttribute('style', /--p/);

  await ticket.getByRole('button', { name: 'Start cooking' }).click();
  await expect(kitchen.locator('.kds-col[aria-label="Preparing"] .ticket', { hasText: guest })).toBeVisible();
  await kitchen.locator('.kds-col[aria-label="Preparing"] .ticket', { hasText: guest }).getByRole('button', { name: 'Mark ready' }).click();
  await expect(kitchen.locator('.kds-col[aria-label="Ready"] .ticket', { hasText: guest })).toBeVisible();

  await kitchenContext.close();
  await tillContext.close();
});

test('cancelling an order asks for a reason and records it in the timeline', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/pos');
  const guest = uniqueGuest('Cancel');
  await page.getByRole('radio', { name: 'Takeaway' }).click();
  await page.locator('.menu-card', { hasText: 'Lahmacun' }).click();
  await page.getByLabel('Guest name').fill(guest);
  await page.getByRole('button', { name: /Send to kitchen/ }).click();
  await page.getByRole('button', { name: 'View' }).click();

  const drawer = page.locator('.drawer');
  await drawer.getByRole('button', { name: 'Cancel order' }).click();
  const confirm = page.getByRole('dialog', { name: /Cancel order #\d+\?/ });
  await confirm.getByRole('button', { name: 'Cancel order' }).click();
  await expect(confirm.getByLabel('Reason')).toHaveClass(/invalid/);
  await confirm.getByLabel('Reason').fill('Guest left');
  await confirm.getByRole('button', { name: 'Cancel order' }).click();

  await expect(drawer.locator('.pill')).toHaveText('Cancelled');
  await expect(drawer).toContainText('Reason: Guest left');
});
