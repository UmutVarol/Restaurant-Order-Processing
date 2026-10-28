const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest } = require('./helpers');

test('a waiter holds the mains, fires them later, and the grill ticks them off', async ({ page, context }) => {
  await signIn(page, 'waiter');
  const guest = uniqueGuest('Courses');
  await page.getByRole('radio', { name: 'Takeaway' }).click();
  await page.locator('.menu-card', { hasText: 'Mercimek Çorbası' }).click();
  await page.locator('.menu-card', { hasText: 'Izgara Tavuk' }).click();
  await page.getByLabel('Guest name').fill(guest);

  // courses come from the menu category and can be changed per line
  await page.getByTestId('hold-courses').check();
  const chicken = page.locator('.cart-line', { hasText: 'Izgara Tavuk' }).getByTestId('line-course');
  await expect(page.locator('.cart-line', { hasText: 'Mercimek' }).getByTestId('line-course')).toHaveText('Course 1');
  await expect(chicken).toHaveText('Course 2');
  await chicken.click();
  await expect(chicken).toHaveText('Course 3');
  await chicken.click();
  await chicken.click();
  await expect(chicken).toHaveText('Course 2');
  await page.getByRole('button', { name: /Send to kitchen/ }).click();
  await page.getByRole('button', { name: 'View' }).click();
  const drawer = page.locator('.drawer');
  await expect(drawer.getByTestId('held-courses')).toContainText('Waiting: course 2');
  await expect(drawer.locator('.line-item', { hasText: 'Izgara Tavuk' }).getByTestId('line-held')).toBeVisible();
  await expect(drawer.locator('.line-item', { hasText: 'Mercimek' }).getByTestId('line-held')).toHaveCount(0);

  // the grill screen does not see a held dish
  const chef = await context.newPage();
  await signIn(chef, 'chef');
  await chef.goto('/#/kitchen');
  const ticket = chef.locator('.ticket', { hasText: guest });
  await expect(ticket).toContainText('Mercimek');
  await expect(ticket.getByTestId('kitchen-held')).toContainText('course 2');
  await chef.getByTestId('kitchen-station').selectOption('HOT');
  await expect(ticket).toHaveCount(0);

  // the waiter fires the mains
  await drawer.getByTestId('fire-course-2').click();
  await expect(page.locator('.toast', { hasText: 'Course 2 sent to the kitchen' })).toBeVisible();
  await expect(drawer.getByTestId('held-courses')).toHaveCount(0);
  await expect(drawer.locator('.adjustments')).toContainText('Course 2 fired');

  // ...and the grill cooks them, line by line
  await expect(ticket).toContainText('Izgara Tavuk');
  await expect(ticket).not.toContainText('Mercimek');
  const line = ticket.getByTestId('kitchen-line');
  await line.click();
  await expect(line).toHaveAttribute('aria-pressed', 'true');
  await expect(ticket.getByTestId('course-ready')).toBeVisible();

  // the station choice is remembered on this screen
  await chef.reload();
  await expect(chef.getByTestId('kitchen-station')).toHaveValue('HOT');

  await drawer.getByRole('button', { name: 'Cancel order' }).click();
  await page.locator('.modal').getByLabel('Reason').fill('E2E clean-up');
  await page.locator('.modal').getByRole('button', { name: 'Cancel order' }).click();
  await expect(page.locator('.toast', { hasText: 'cancelled' })).toBeVisible();
});

test('a manager adds, renames and removes a kitchen station', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/settings');
  const code = `E2E${Date.now().toString(36).toUpperCase().slice(-6)}`;
  await page.getByTestId('add-station').click();
  const modal = page.locator('.modal');
  await modal.getByLabel('Name').fill(`Grill ${code}`);
  await modal.getByLabel('Code').fill(code);
  await modal.getByLabel('Target (minutes)').fill('12');
  await modal.getByTestId('save-station').click();
  const list = page.getByTestId('station-list');
  await expect(list).toContainText(`Grill ${code}`);
  await expect(list).toContainText(`${code} · target 12 min`);

  // a station's name must be unique
  await page.getByTestId(`edit-station-${code}`).click();
  await modal.getByLabel('Name').fill('Bar');
  await modal.getByTestId('save-station').click();
  await expect(modal.locator('.form-error')).toContainText('A station with that name exists');
  await modal.getByLabel('Name').fill(`Ocakbaşı ${code}`);
  await modal.getByTestId('save-station').click();
  await expect(list).toContainText(`Ocakbaşı ${code}`);

  // a station in use cannot go
  await page.getByTestId('edit-station-HOT').click();
  await modal.getByRole('button', { name: 'Remove' }).first().click();
  await page.locator('.modal').last().getByRole('button', { name: 'Remove' }).click();
  await expect(page.locator('.modal').first().locator('.form-error')).toContainText('Dishes are still made at this station');
  await page.keyboard.press('Escape');

  await page.getByTestId(`edit-station-${code}`).click();
  await page.locator('.modal').getByRole('button', { name: 'Remove' }).first().click();
  await page.locator('.modal').last().getByRole('button', { name: 'Remove' }).click();
  await expect(list).not.toContainText(code);
});
