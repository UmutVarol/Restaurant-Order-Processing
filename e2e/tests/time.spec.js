// Shifts: a waiter clocks in and out; a manager plans the waiter's shift, sees the time clock and clocks someone
// out; the tips tab previews how the day's tips are shared. Runs on a desktop and on a phone.
const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test('a waiter clocks in and a manager plans the rota and closes the clock', async ({ page, browser }) => {
  await signIn(page, 'waiter');
  await page.goto('/#/time');
  // a previous run may have left the demo waiter clocked in
  await expect(page.locator('[data-testid="clock-in"], [data-testid="clock-out"]')).toBeVisible();
  if (await page.getByTestId('clock-out').isVisible()) {
    await page.getByTestId('clock-out').click();
    await expect(page.getByTestId('clock-in')).toBeVisible();
  }
  await page.getByTestId('clock-in').click();
  await expect(page.locator('.toast', { hasText: 'Clocked in' })).toBeVisible();
  await expect(page.getByTestId('clock-out')).toBeVisible();
  await expect(page.locator('.time-card').first()).toContainText('Clocked in since');

  // the manager, on another device
  const context = await browser.newContext({ viewport: page.viewportSize(), locale: 'en-GB', timezoneId: 'Europe/Istanbul' });
  const boss = await context.newPage();
  await signIn(boss, 'admin');
  // the shift this test plans may be there from an earlier run (or the other screen size): start clean
  await boss.evaluate(async () => {
    const session = JSON.parse(sessionStorage.getItem('sofra.session'));
    const call = (method, path) => (window.SOFRA_BACKEND?.fetch ?? fetch)(`/api/v1${path}`, { method, headers: { Authorization: `Bearer ${session.token}` } });
    const day = new Date();
    const iso = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
    const shifts = await (await call('GET', `/shifts?from=${iso}&to=${iso}`)).json();
    for (const shift of shifts.filter((x) => x.note === 'Terrace')) await call('DELETE', `/shifts/${shift.id}`);
  });
  await boss.goto('/#/time');
  await boss.getByTestId('time-tab-rota').click();
  await boss.getByTestId('shift-add').click();
  await boss.locator('#shift-user').selectOption({ label: 'Can Demir · Waiter' });
  await boss.locator('#shift-from').fill('18:00');
  await boss.locator('#shift-to').fill('23:30');
  await boss.locator('#shift-note').fill('Terrace');
  await boss.getByTestId('shift-save').click();
  await expect(boss.locator('.rota-shift', { hasText: 'Terrace' }).first()).toContainText('Can Demir');

  await boss.getByTestId('time-tab-clock').click();
  const open = boss.locator('.time-list li', { hasText: 'Can Demir' }).filter({ has: boss.locator('[data-testid^="entry-out-"]') });
  await expect(open).toHaveCount(1);
  await open.locator('[data-testid^="entry-out-"]').click();
  await boss.getByRole('button', { name: 'Clock out' }).last().click();
  await expect(boss.locator('.time-list li', { hasText: 'Can Demir' }).filter({ has: boss.locator('[data-testid^="entry-out-"]') })).toHaveCount(0);

  await boss.getByTestId('time-tab-tips').click();
  await expect(boss.getByTestId('tips-day')).toBeVisible();
  await expect(boss.locator('.time-card')).toContainText('Tips');
  await context.close();

  // the waiter sees the clock-out and the planned shift
  await page.reload();
  await expect(page.getByTestId('clock-in')).toBeVisible();
});
