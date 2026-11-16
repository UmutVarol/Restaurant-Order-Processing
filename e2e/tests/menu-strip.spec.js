const { test, expect } = require('@playwright/test');
const { signIn, freeTables } = require('./helpers');

test('the guest menu strip follows the scroll and jumps to a category', async ({ page, context }) => {
  await signIn(page, 'admin');
  const table = (await freeTables(page, 1, (x) => !x.occupied))[0];
  const { token } = await page.evaluate(async (id) => {
    const session = JSON.parse(sessionStorage.getItem('sofra.session'));
    return (await fetch(`/api/v1/tables/${id}/qr`, { method: 'POST', headers: { Authorization: `Bearer ${session.token}` } })).json();
  }, table.id);

  const guest = await context.newPage();
  await guest.addInitScript(() => localStorage.setItem('sofra.lang', 'en'));
  await guest.goto(`/#/t/${token}`);
  const strip = guest.getByTestId('menu-categories');
  await expect(guest.locator('.menu-section')).toHaveCount(4);
  await expect(strip.getByRole('button', { name: 'Starters' })).toHaveAttribute('aria-current', 'true');

  // a tap takes the guest to the desserts, and the strip says so
  await strip.getByRole('button', { name: 'Desserts' }).click();
  await expect(strip.getByRole('button', { name: 'Desserts' })).toHaveAttribute('aria-current', 'true');
  await expect.poll(async () => (await guest.getByRole('heading', { name: 'Desserts' }).boundingBox()).y).toBeLessThan(220);
  // the strip stays on screen while the menu scrolls under it
  await expect(strip).toBeInViewport();

  // scrolling back up marks the first category again
  await guest.evaluate(() => window.scrollTo(0, 0));
  await expect(strip.getByRole('button', { name: 'Starters' })).toHaveAttribute('aria-current', 'true');
});
