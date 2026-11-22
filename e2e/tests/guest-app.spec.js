// Round three, the guest app: the restaurant places itself in the directory, a guest standing inside opens Sofra and
// is greeted with its sign (G2), confirms, sees its page and menu (H1–H2) and how to bind to the table (H3). A paid
// visit is rated from the receipt (P2) and the restaurant answers it from the panel (R1). Runs on a desktop and a phone.
const { test, expect } = require('@playwright/test');
const { signIn, call, uniqueGuest } = require('./helpers');

const HERE = { latitude: 40.98712, longitude: 29.02871 };

/** As the demo manager: the first branch's directory entry. */
async function place(page, entry) {
  const branches = await call(page, 'GET', '/branches');
  await call(page, 'PUT', `/directory/${branches[0].id}`, entry);
  return branches[0];
}

/** A guest's phone like the project's device, on the same server as the signed-in page. */
function device(page) {
  const { viewport, userAgent, deviceScaleFactor, isMobile, hasTouch } = test.info().project.use;
  return { viewport, userAgent, deviceScaleFactor, isMobile, hasTouch, baseURL: new URL(page.url()).origin };
}

function english(context) {
  return context.addInitScript(() => {
    localStorage.setItem('sofra.lang', 'en');
    localStorage.setItem('sofra.theme', '"light"');
  });
}

test('a guest inside the restaurant opens Sofra, is greeted with its sign and sees its page', async ({ page, browser }) => {
  await signIn(page, 'admin');
  // the manager stands in the restaurant: Setup › Appear in the guest app
  await page.context().grantPermissions(['geolocation']);
  await page.context().setGeolocation({ ...HERE, accuracy: 8 });
  await page.goto('/#/setup');
  const card = page.getByTestId('setup-directory');
  const branch = (await call(page, 'GET', '/branches'))[0];
  await card.getByTestId(`dir-here-${branch.id}`).click();
  await expect(card.getByTestId(`dir-where-${branch.id}`)).toContainText('40.98712');
  await card.locator(`#dir-address-${branch.id}`).fill('Moda Cd. 12, Kadıköy');
  await card.getByTestId(`dir-listed-${branch.id}`).check();
  await card.getByTestId(`dir-save-${branch.id}`).click();
  await expect(page.locator('.toast', { hasText: 'You now appear in the guest app' })).toBeVisible();

  try {
    const guest = await browser.newContext({
      ...device(page), geolocation: { latitude: HERE.latitude + 0.00008, longitude: HERE.longitude + 0.00004, accuracy: 12 },
      permissions: ['geolocation'],
    });
    await english(guest);
    const phone = await guest.newPage();
    await phone.goto('/#/sofra');
    // one clear match: the restaurant's own sign, then its page
    const sign = phone.getByTestId('venue-sign');
    await expect(sign).toBeVisible();
    await expect(sign).toContainText('Welcome');
    await expect(sign).toContainText('Moda Cd. 12');
    // "not this place" offers the list instead, with this restaurant chosen
    await phone.getByTestId('here-no').click();
    await expect(phone.getByTestId('nearby')).toBeVisible();
    await expect(phone.getByTestId('near-row').first()).toHaveAttribute('aria-checked', 'true');
    await phone.getByTestId('near-continue').click();

    const venue = phone.getByTestId('venue-page');
    await expect(venue.locator('.venue-hero')).toBeVisible();
    await expect(venue.getByTestId('venue-dish').filter({ hasText: 'Ayran' })).toBeVisible();
    // calling a waiter needs the table first: the binding sheet explains how
    await venue.getByTestId('venue-act-bell').click();
    await expect(phone.getByTestId('bind-sheet')).toBeVisible();
    await expect(phone.getByTestId('bind-qr')).toBeVisible();
    await guest.close();

    // far away, nobody is offered
    const away = await browser.newContext({ ...device(page), geolocation: { latitude: HERE.latitude + 0.05, longitude: HERE.longitude, accuracy: 10 }, permissions: ['geolocation'] });
    await english(away);
    const far = await away.newPage();
    await far.goto('/#/sofra');
    await expect(far.getByTestId('nearby-none')).toBeVisible();
    await away.close();

    // the panel counts the guest who checked in
    await page.goto('/#/dashboard');
    await expect(page.getByTestId('guest-inside')).not.toHaveText('0');
  } finally {
    await place(page, { latitude: null, longitude: null, address: null, listed: false });
  }
});

test('a paid visit is rated from the receipt and the restaurant answers it', async ({ page, browser }) => {
  await signIn(page, 'admin');
  const menu = await call(page, 'GET', '/menu');
  const ayran = menu.find((item) => item.name === 'Ayran');
  const order = await call(page, 'POST', '/orders', {
    type: 'TAKEAWAY', customerName: uniqueGuest('Rate'), paymentMethod: 'CASH', items: [{ menuItemId: ayran.id, quantity: 2 }],
  });
  const bill = await call(page, 'GET', `/bills/by-order/${order.id}`);
  const link = await call(page, 'POST', `/bills/${bill.id}/links`, {});
  await call(page, 'POST', `/bills/${bill.id}/payments`, { method: 'CASH', amount: bill.balance });

  const guest = await browser.newContext({ ...device(page) });
  await english(guest);
  const phone = await guest.newPage();
  await phone.goto(`/#/pay/${link.token}`);
  const rate = phone.getByTestId('pay-rate');
  await expect(rate).toBeVisible();
  await rate.getByTestId('pay-rate-star-4').click();
  await expect(rate.getByTestId('pay-rate-star-4')).toHaveAttribute('aria-checked', 'true');
  await rate.getByTestId('pay-rate-tag-SPEED').click();
  await rate.getByTestId('pay-rate-dish-0').click();
  const words = `Fast and cold ${Date.now().toString(36)}`;
  await rate.locator('#pay-rate-comment').fill(words);
  await rate.getByTestId('pay-rate-send').click();
  await expect(phone.getByTestId('pay-rate-thanks')).toBeVisible();
  // once only
  expect((await call(page, 'GET', `/ratings?limit=5`)).find((r) => r.comment === words)?.stars).toBe(4);
  await phone.reload();
  await expect(phone.getByTestId('paid-receipt').or(phone.locator('.guest-banner'))).toBeVisible();
  await expect(phone.getByTestId('pay-rate')).toHaveCount(0);
  await guest.close();

  // R1: the restaurant reads it and answers in public
  await page.goto('/#/dashboard');
  await page.getByTestId('ratings-all').click();
  const row = page.locator('.drawer [data-testid=review-row]', { hasText: words });
  await expect(row).toContainText('Quick service');
  await row.getByTestId('review-reply').click();
  await page.locator('#review-reply-text').fill('Thank you, see you soon!');
  await page.getByTestId('review-reply-save').click();
  await expect(page.locator('.toast', { hasText: 'Reply saved' })).toBeVisible();
  await expect(page.locator('.drawer [data-testid=review-row]', { hasText: words })).toContainText('Thank you, see you soon!');
});
