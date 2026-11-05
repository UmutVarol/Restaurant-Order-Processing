// The guest's menu at the table: each dish says what allergens it contains, a guest hides what they cannot eat
// (remembered on their phone), and the cart suggests what goes well with it. Managers declare the allergens.
// Runs on a desktop and on a phone.
const { test, expect } = require('@playwright/test');
const { signIn } = require('./helpers');

async function api(page, method, path, body) {
  return page.evaluate(async ({ method, path, body }) => {
    const session = JSON.parse(sessionStorage.getItem('sofra.session'));
    const response = await (window.SOFRA_BACKEND?.fetch ?? fetch)(`/api/v1${path}`, {
      method, headers: { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    return response.json();
  }, { method, path, body });
}

test('a guest hides what they cannot eat and the cart suggests a drink', async ({ page, context }) => {
  await signIn(page, 'admin');
  const table = (await api(page, 'GET', '/floor/tables')).filter((x) => !x.occupied).at(0);
  const { token } = await api(page, 'POST', `/tables/${table.id}/qr`);

  const guest = await context.newPage();
  await guest.addInitScript(() => { if (!sessionStorage.getItem('e2e.lang')) { localStorage.setItem('sofra.lang', 'en'); localStorage.removeItem('sofra.guest.avoid'); sessionStorage.setItem('e2e.lang', '1'); } });
  await guest.goto(`/#/t/${token}`);
  const dish = (name) => guest.locator('.table-item', { hasText: name });
  await expect(dish('Künefe').getByTestId('allergen-line')).toHaveText(/^Contains: Gluten, Milk,? and Tree nuts$/);
  await expect(dish('Çoban Salata').getByTestId('allergen-line')).toHaveCount(0);
  await expect(dish('Çoban Salata').locator('.tag.t-VEGAN')).toHaveText('Vegan');
  await expect(dish('Künefe').locator('.tag.t-POPULAR')).toHaveText('Popular');

  // no milk: those dishes leave the menu, and the phone remembers it
  await guest.getByTestId('allergies').click();
  await guest.locator('.modal').getByTestId('avoid-MILK').check();
  await guest.getByTestId('allergies-done').click();
  await expect(guest.getByTestId('allergies')).toHaveText('Allergies · 1');
  await expect(dish('Künefe')).toHaveCount(0);
  await expect(dish('Ayran')).toHaveCount(0);
  await expect(guest.getByTestId('allergy-hidden')).toContainText('dishes are hidden because of your allergies');
  await expect(guest.getByTestId('allergy-hidden')).toContainText('Avoiding Milk');
  await guest.reload();
  await expect(dish('Lahmacun')).toBeVisible();
  await expect(dish('Künefe')).toHaveCount(0);

  // a main in the cart: a drink is suggested (never one with milk), and one tap adds it
  await dish('Lahmacun').locator('button.add').click();
  await guest.locator('.modal').getByTestId('options-apply').click();
  await guest.getByTestId('open-cart').click();
  const cart = guest.locator('.modal').first();
  await expect(cart.locator('.suggest .label')).toHaveText('Goes well with');
  const picks = cart.getByTestId('suggest-item');
  // the kitchen's popular drinks
  await expect(picks).toHaveCount(2);
  await expect(picks.filter({ hasText: 'Türk Kahvesi' })).toHaveCount(1);
  await expect(picks.filter({ hasText: 'Demlik Çay' })).toHaveCount(1);
  await expect(picks.filter({ hasText: 'Ayran' })).toHaveCount(0);
  await picks.filter({ hasText: 'Türk Kahvesi' }).click();
  // it has options (how sweet), asked as when adding it from the menu
  await guest.getByTestId('options-apply').click();
  await expect(cart.locator('.cart-line')).toHaveCount(2);
  await expect(cart.locator('.cart-line', { hasText: 'Türk Kahvesi' })).toBeVisible();
  // with a drink in the cart, no more drinks are suggested (and every dessert has milk)
  await expect(picks).toHaveCount(0);
});

test('a manager declares what a dish contains', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/#/menu');
  const row = page.locator('tr', { hasText: 'Çoban Salata' });
  await row.getByRole('button', { name: 'Edit: Çoban Salata' }).click();
  const modal = page.locator('.modal');
  await modal.getByTestId('menu-allergen-MUSTARD').check();
  await modal.getByRole('button', { name: 'Save' }).click();
  await expect(row.locator('.allergen-line')).toHaveText('Contains: Mustard');
  // put the salad back as it was
  const salad = (await api(page, 'GET', '/menu')).find((m) => m.name === 'Çoban Salata');
  expect(salad.allergens).toEqual(['MUSTARD']);
  await api(page, 'PUT', `/menu/${salad.id}`, { ...salad, allergens: [] });
});
