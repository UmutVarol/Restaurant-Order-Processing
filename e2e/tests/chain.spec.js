const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

async function api(page, method, path, body) {
  return page.evaluate(async ({ method, path, body }) => {
    const session = JSON.parse(sessionStorage.getItem('sofra.session'));
    const response = await fetch(`/api/v1${path}`, {
      method, headers: { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    return response.status === 204 ? null : response.json();
  }, { method, path, body });
}

test('the owner gives a branch its own price and sees the branches side by side', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'one browser is enough for the owner\'s screens');
  await signIn(page, 'admin');
  const branch = await api(page, 'POST', '/branches', { code: `e2e-${Date.now().toString(36)}`, name: 'Kadıköy E2E' });
  try {
    await page.goto('/#/menu');
    const ayran = page.locator('.menu-table tr', { hasText: 'Ayran' });
    await ayran.getByTestId('branch-prices').click();
    const modal = page.locator('.modal').last();
    await modal.locator(`#bp-${branch.id}`).fill('66');
    await modal.getByTestId(`bp-save-${branch.id}`).click();
    await expect(page.locator('.toast', { hasText: 'Kadıköy E2E saved' }).first()).toBeVisible();
    await expect(modal.locator('.branch-price.differs')).toContainText('Kadıköy E2E');
    // the central menu, as the manager edits it, keeps the central price
    await page.keyboard.press('Escape');
    await expect(ayran.locator('.m-price')).not.toContainText('66');

    await page.goto('/#/dashboard');
    await expect(page.getByTestId('branch-report')).toContainText('Kadıköy E2E');
  } finally {
    const item = (await api(page, 'GET', '/menu?central=true')).find((dish) => dish.name === 'Ayran');
    await api(page, 'PUT', `/menu/${item.id}/branches/${branch.id}`, {});
    await api(page, 'PUT', `/branches/${branch.id}`, { name: 'Kadıköy E2E', active: false });
  }
});
