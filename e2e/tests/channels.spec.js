const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

test('platform orders reach the kitchen, and one with an unmapped item waits until it is mapped', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/channels');
  const getir = page.getByTestId('channel-GETIR');
  await expect(getir).toContainText('Simulation');

  // a mapped order goes straight to the kitchen, already paid on the platform
  await getir.getByTestId('simulate-GETIR').click();
  await expect(page.locator('.toast', { hasText: 'Getir Yemek order arrived' })).toBeVisible();
  await expect(page.locator('.channel-orders tbody tr').first().getByTestId('channel-placed')).toBeVisible();

  // an item nobody mapped stops the order instead of being guessed
  await getir.getByTestId('simulate-unmapped-GETIR').click();
  await expect(page.locator('.toast', { hasText: 'is waiting for you' })).toBeVisible();
  const waitingRow = page.locator('.channel-orders tbody tr.attention').first();
  const externalId = await waitingRow.getAttribute('data-external-id');
  const row = page.locator(`.channel-orders tbody tr[data-external-id="${externalId}"]`);
  await expect(row).toContainText('Günün çorbası');
  await expect(getir.getByTestId('channel-attention')).toBeVisible();

  await row.getByTestId('map-unmapped').click();
  const modal = page.locator('.modal');
  await modal.getByTestId('mapping-dish').selectOption({ label: 'Mercimek Çorbası' });
  await modal.getByTestId('save-mapping').click();
  await row.getByTestId('channel-retry').click();
  await expect(page.locator('.toast', { hasText: 'Sent to the kitchen as order' })).toBeVisible();
  await row.getByTestId('channel-placed').click();
  await expect(page.locator('.drawer').getByTestId('channel-badge')).toHaveText('Getir Yemek');
  await expect(page.locator('.drawer')).toContainText('Mercimek Çorbası');

  // leave the demo mapping list as it was
  const mapped = await page.evaluate(async () => {
    // through the app's own transport, so this works against the server and the in-browser demo alike
    const send = window.SOFRA_BACKEND ? (url, init) => window.SOFRA_BACKEND.fetch(url, init) : (url, init) => fetch(url, init);
    const session = JSON.parse(sessionStorage.getItem('sofra.session'));
    const headers = { Authorization: `Bearer ${session.token}` };
    const list = await (await send('api/v1/channels/getir/mappings', { headers })).json();
    const added = list.filter((m) => m.externalId.startsWith('NEW-'));
    for (const m of added) await send(`api/v1/channels/getir/mappings/${m.externalId}`, { method: 'DELETE', headers });
    return added.length;
  });
  expect(mapped).toBeGreaterThan(0);
});
