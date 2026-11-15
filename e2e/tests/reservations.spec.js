// Bookings and the walk-in waitlist: a waiter takes a booking for a table, the floor plan shows it, a walk-in is
// seated at a free table and leaves again.
const { test, expect } = require('@playwright/test');
const { signIn, uniqueGuest, freeTables } = require('./helpers');

async function api(page, method, path, body) {
  return page.evaluate(async ({ method, path, body }) => {
    const session = JSON.parse(sessionStorage.getItem('sofra.session'));
    const response = await (window.SOFRA_BACKEND?.fetch ?? fetch)(`/api/v1${path}`, {
      method, headers: { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    return response.status === 204 ? null : response.json();
  }, { method, path, body });
}

test('a booking shows on the floor plan and a walk-in is seated and leaves', async ({ page }) => {
  await signIn(page, 'waiter');
  // a table nobody sits at and nobody has booked, with room for four
  const table = (await freeTables(page, 1, (x) => !x.occupied && !x.seatedParty && !x.nextBooking && x.seats >= 4)).at(-1);
  const booked = uniqueGuest('Booking');
  // about an hour and a half from now, in the browser's (the restaurant's) time zone
  const { day, hhmm, tomorrow } = await page.evaluate(() => {
    const now = new Date();
    const soon = new Date(now.getTime() + 90 * 60_000);
    soon.setMinutes(soon.getMinutes() < 30 ? 0 : 30, 0, 0);
    const pad = (n) => String(n).padStart(2, '0');
    // late in the evening "an hour and a half from now" is already tomorrow
    return { day: `${soon.getFullYear()}-${pad(soon.getMonth() + 1)}-${pad(soon.getDate())}`, hhmm: `${pad(soon.getHours())}:${pad(soon.getMinutes())}`,
      tomorrow: soon.getDate() !== now.getDate() };
  });

  await page.goto('/#/reservations');
  await page.getByTestId('resv-book').click();
  await page.locator('#booking-name').fill(booked);
  await page.locator('#booking-size').fill('4');
  await page.locator('#booking-date').fill(day);
  await page.locator('#booking-time').fill(hhmm);
  await page.locator('#booking-time').dispatchEvent('change');
  await expect(page.locator('#booking-table option', { hasText: new RegExp(`^${table.code} ·`) })).toHaveCount(1);
  await page.locator('#booking-table').selectOption(String(table.id));
  await page.getByTestId('booking-save').click();
  await expect(page.locator('.toast', { hasText: `Booked: ${booked}` })).toBeVisible();
  const row = page.getByTestId('resv-bookings').locator('.resv-row', { hasText: booked });
  await expect(row).toContainText(`Table ${table.code}`);
  await expect(row).toContainText('4 people');

  // the floor plan warns about the booking
  await page.goto('/#/floor');
  await expect(page.getByTestId(`booking-${table.code}`)).toContainText(booked);

  // a walk-in, told ten minutes, is seated at that table only when the host overrides the booking
  const walkIn = uniqueGuest('Walk-in');
  await page.goto('/#/reservations');
  await page.getByTestId('resv-walk-in').click();
  await page.locator('#walkin-name').fill(walkIn);
  await page.locator('#walkin-quoted').fill('10');
  await page.getByTestId('walkin-save').click();
  const waiting = page.getByTestId('resv-waitlist').locator('.resv-row', { hasText: walkIn });
  await expect(waiting).toContainText('quoted 10 min');
  await waiting.getByRole('button', { name: 'Seat' }).click();
  // the booked table is not offered for a party sitting down now
  await expect(page.getByTestId('seat-confirm')).toBeVisible();
  await expect(page.getByTestId(`seat-table-${table.code}`)).toHaveCount(0);
  await page.locator('.table-picker button').first().click();
  await page.getByTestId('seat-confirm').click();
  await expect(page.locator('.toast', { hasText: `${walkIn} seated at table` })).toBeVisible();
  const seated = page.getByTestId('resv-seated').locator('.resv-row', { hasText: walkIn });
  await expect(seated).toBeVisible();

  await page.goto('/#/floor');
  await expect(page.locator('.t-seated', { hasText: walkIn })).toBeVisible();

  // they leave without ordering
  await page.goto('/#/reservations');
  await page.getByTestId('resv-seated').locator('.resv-row', { hasText: walkIn }).getByRole('button', { name: 'Left' }).click();
  await page.getByRole('button', { name: 'They left' }).click();
  await expect(page.getByTestId('resv-seated').locator('.resv-row', { hasText: walkIn })).toHaveCount(0);

  // and the booking is cancelled, which frees the table on the floor plan again
  if (tomorrow) await page.getByRole('button', { name: 'Next day' }).click();
  await page.getByTestId('resv-bookings').locator('.resv-row', { hasText: booked }).getByRole('button', { name: 'Cancel booking' }).click();
  await page.getByRole('button', { name: 'Cancel it' }).click();
  await expect(page.getByTestId('resv-bookings').locator('.resv-row', { hasText: booked })).toContainText('Cancelled');
});

test('the kitchen does not see reservations', async ({ page }) => {
  await signIn(page, 'chef');
  await expect(page.locator('a.nav-item[href="#/reservations"]')).toHaveCount(0);
  const answer = await api(page, 'GET', '/reservations');
  expect(answer.status).toBe(403);
});
