// Captures the real screens the video is built from, from a running Sofra (demo data) on :8080.
// It plays one evening at one table: a guest orders from the QR menu, the kitchen cooks, the waiter serves, the guest
// pays from their phone. Usage (from e2e/, which has Playwright): node ../marketing/video/capture.js <out dir> [lang]
const { chromium } = require('playwright');
const fs = require('fs');

const out = process.argv[2];
const lang = process.argv[3] || 'tr';
const base = process.env.BASE_URL || 'http://localhost:8080';
// The story is an evening: whatever the hour of the capture, the screens' clocks read about eight in the evening.
const shift = ((new Date().getUTCHours() - 20) + 36) % 24 - 12;
const timezoneId = shift >= 0 ? `Etc/GMT+${shift}` : `Etc/GMT-${-shift}`;
const PHONE = { viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, timezoneId };
const TABLET = { viewport: { width: 1180, height: 760 }, deviceScaleFactor: 2, timezoneId };
// The demo menu is described in English; a video in another language shows the dishes in its own.
const DESCRIPTIONS = { tr: {
  'Mercimek Çorbası': 'Limon ve pul biberli tereyağıyla kırmızı mercimek çorbası', 'Sigara Böreği': 'Beyaz peynir ve maydanozlu çıtır yufka',
  'Humus': 'Tahinli nohut ezmesi, sıcak tereyağı ve çam fıstığı', 'Çoban Salata': 'Domates, salatalık, biber, kırmızı soğan ve sumak',
  'Acılı Ezme': 'Nar ekşili acı domates ve biber ezmesi', 'Paçanga Böreği': 'Pastırma ve kaşarlı altın sarısı börek',
  'Adana Kebap': 'Zırh kıyması kuzu, lavaş ve közlenmiş biber', 'İskender': 'Pide üstünde döner, domates sosu, kızgın tereyağı ve yoğurt',
  'Lahmacun': 'İnce çıtır hamurda baharatlı kıyma, yeşillik ve limon', 'Mantı': 'Sarımsaklı yoğurt ve pul biberli tereyağıyla minik mantı',
  'Izgara Tavuk': 'Marine tavuk but, bulgur pilavı ve közlenmiş patlıcan', 'Köfte Tabağı': 'Izgara köfte, piyaz ve közlenmiş domates',
  'Karnıyarık': 'Kıymalı patlıcan, yanında pirinç pilavı', 'Levrek Izgara': 'Kömürde levrek, roka ve limon',
  'Sebzeli Güveç': 'Çömlekte otlu sebze güveci ve pilav', 'Künefe': 'Sıcak kadayıf, eriyen peynir ve fıstıklı şerbet',
  'Fıstıklı Baklava': 'Kaymaklı dört dilim fıstıklı baklava', 'Fırın Sütlaç': 'Tarçınlı fırın sütlaç',
  'San Sebastian Cheesecake': 'Akışkan kıvamlı yanık Bask cheesecake', 'Türk Kahvesi': 'Lokum eşliğinde Türk kahvesi',
  'Demlik Çay': 'Demlikte Rize çayı', 'Ayran': 'Köpüklü tuzlu ayran', 'Taze Portakal Suyu': 'Taze sıkılmış portakal suyu',
  'Ev Yapımı Limonata': 'Taze naneli ev yapımı limonata', 'Şalgam': 'Acılı ya da acısız şalgam suyu', 'Maden Suyu': 'Doğal maden suyu',
} };
const ACCOUNTS = { admin: 'Elif Yılmaz', waiter: 'Can Demir', chef: 'Mehmet Usta' };

(async () => {
  fs.mkdirSync(out, { recursive: true });
  const login = await (await fetch(`${base}/api/v1/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }) })).json();
  const headers = { Authorization: `Bearer ${login.accessToken}`, 'Content-Type': 'application/json' };
  const call = async (method, path, body) => {
    const response = await fetch(`${base}/api/v1${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined });
    return response.status === 204 ? null : response.json();
  };

  const browser = await chromium.launch();
  const open = async (device, language, account) => {
    const context = await browser.newContext(device);
    const page = await context.newPage();
    await page.addInitScript((l) => {
      if (sessionStorage.getItem('video.init')) return;
      localStorage.setItem('sofra.lang', l); localStorage.setItem('sofra.theme', '"light"'); localStorage.setItem('sofra.sound', 'false');
      localStorage.removeItem('sofra.guest.avoid'); sessionStorage.setItem('video.init', '1');
    }, language);
    if (account) {
      await page.goto(`${base}/`);
      await page.getByRole('button', { name: new RegExp(ACCOUNTS[account]) }).click();
      await page.locator('.shell').waitFor();
    }
    return page;
  };
  const settle = async (page, ms = 700) => {
    await page.locator('.skeleton').first().waitFor({ state: 'detached', timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(ms);
  };
  const shot = async (page, name) => { await page.screenshot({ path: `${out}/${name}.png` }); console.log('shot', name); };
  const scrollTo = (page, selector, offset = 12) => page.evaluate(({ selector, offset }) => {
    const el = document.querySelector(selector);
    window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY - offset);
  }, { selector, offset });

  // a quiet stage: plates the demo data left ready are served, so the waiter's screen shows this table's
  for (const order of await call('GET', '/orders/active')) {
    if (order.type === 'DINE_IN' && order.status === 'READY') await call('POST', `/orders/${order.id}/advance`);
  }
  const free = (await call('GET', '/floor/tables')).filter((x) => !x.occupied && !x.needsClearingSince);
  const table = ['7', '4', '5', '8'].map((code) => free.find((x) => x.code === code)).find(Boolean) ?? free.at(0);
  const menu = await call('GET', '/menu?includeUnavailable=true');
  const describe = async (texts) => {
    for (const dish of menu) {
      const description = texts ? texts[dish.name] : dish.description;
      if (!description || (texts && description === dish.description)) continue;
      await call('PUT', `/menu/${dish.id}`, { name: dish.name, description, category: dish.category, price: dish.price, icon: dish.icon,
        prepTimeMinutes: dish.prepTimeMinutes, popular: dish.popular, tags: dish.tags });
    }
  };
  await describe(DESCRIPTIONS[lang]);
  const { token } = await call('POST', `/tables/${table.id}/qr`);
  await call('PUT', '/service-settings', { mode: 'QR_FIRST', autoAcceptLimit: 1500, firstCartNeedsStaff: false });

  try {
    // ------------------------------------------------------------------ the guest orders
    const guest = await open(PHONE, lang);
    await guest.goto(`${base}/#/t/${token}`);
    await guest.locator('.table-item').first().waitFor();
    await settle(guest);
    await shot(guest, 'menu-top');
    const add = async (name) => {
      await guest.locator('.table-item', { hasText: name }).locator('button.add').click();
      const apply = guest.getByTestId('options-apply');
      if (await apply.isVisible({ timeout: 600 }).catch(() => false)) await apply.click();
      await guest.waitForTimeout(250);
    };
    await guest.locator('.table-cats .chip').nth(2).click();
    await settle(guest, 400);
    await scrollTo(guest, '.table-tools', 16);
    await guest.waitForTimeout(300);
    await shot(guest, 'menu-mains');
    await add('İskender');
    await shot(guest, 'menu-cart-1');
    await add('Mantı');
    await guest.locator('.table-cats .chip').nth(4).click();
    await settle(guest, 300);
    await add('Ayran');
    await add('Ayran');
    await guest.locator('.table-cats .chip').nth(2).click();
    await settle(guest, 400);
    await shot(guest, 'menu-cart-2');
    await guest.getByTestId('open-cart').click();
    await guest.waitForTimeout(700);
    await shot(guest, 'cart');
    // the kitchen is already on: its screen is seen before and after the cart arrives
    const kitchen = await open(TABLET, lang, 'chef');
    await kitchen.goto(`${base}/#/kitchen`);
    await settle(kitchen, 900);
    const sidebar = await kitchen.locator('.sidebar').evaluate((el) => Math.round(el.getBoundingClientRect().width));
    const board = async (name) => {
      await kitchen.screenshot({ path: `${out}/${name}.png`, clip: { x: sidebar, y: 0, width: TABLET.viewport.width - sidebar, height: TABLET.viewport.height } });
      console.log('shot', name);
    };
    await board('kitchen-0');
    await guest.locator('#guest-name').fill('Deniz');
    await guest.getByTestId('send-cart').click();
    const track = guest.getByTestId('order-track');
    await track.waitFor();
    await guest.locator('.toast').first().waitFor({ state: 'detached', timeout: 8000 }).catch(() => {});
    await scrollTo(guest, '.track', 20);
    await guest.waitForTimeout(500);
    await shot(guest, 'track-1');
    const card = async (name) => { await track.screenshot({ path: `${out}/${name}.png` }); console.log('shot', name); };
    await card('card-track-1');
    const order = (await call('GET', '/orders/active')).find((o) => o.table?.id === table.id);

    // ------------------------------------------------------------------ the kitchen cooks
    await kitchen.waitForTimeout(1600); // the arrival highlight settles
    await board('kitchen-1');
    await call('POST', `/orders/${order.id}/advance`);
    const status = track.getByTestId('track-status');
    const first = await status.textContent();
    await guest.waitForFunction((before) => document.querySelector('[data-testid="track-status"]')?.textContent !== before, first, { timeout: 15000 });
    await guest.waitForTimeout(400);
    await shot(guest, 'track-2');
    await card('card-track-2');

    // ------------------------------------------------------------------ ready: the waiter carries it
    const waiter = await open(PHONE, lang, 'waiter');
    await waiter.goto(`${base}/#/service`);
    await settle(waiter);
    await shot(waiter, 'service-0');
    const second = await status.textContent();
    await call('POST', `/orders/${order.id}/advance`);
    await guest.waitForFunction((before) => document.querySelector('[data-testid="track-status"]')?.textContent !== before, second, { timeout: 15000 });
    await guest.waitForTimeout(500);
    await shot(guest, 'track-3');
    await card('card-track-3');
    await waiter.getByTestId('ready-plate').first().waitFor();
    await waiter.waitForTimeout(600);
    await shot(waiter, 'service-1');
    await waiter.getByTestId('ready-plate').first().getByTestId('serve').click();
    await waiter.locator('.toast').first().waitFor();
    await waiter.waitForTimeout(450);
    await shot(waiter, 'service-2');
    const third = await status.textContent();
    await guest.waitForFunction((before) => document.querySelector('[data-testid="track-status"]')?.textContent !== before, third, { timeout: 15000 });
    await guest.locator('.toast').first().waitFor({ state: 'detached', timeout: 8000 }).catch(() => {});
    await guest.waitForTimeout(400);
    await shot(guest, 'track-4');

    // ------------------------------------------------------------------ the guest pays from the table
    await guest.evaluate(() => window.scrollTo(0, 0));
    await guest.getByTestId('pay-bill').waitFor({ timeout: 25000 });
    await guest.waitForTimeout(400);
    await shot(guest, 'table-pay');
    await guest.getByTestId('pay-bill').click();
    await guest.getByTestId('guest-due').waitFor();
    await settle(guest, 500);
    await shot(guest, 'pay-1');
    await scrollTo(guest, '.totals', 24);
    await guest.waitForTimeout(300);
    await shot(guest, 'pay-all');
    await guest.getByTestId('pay-mode-SPLIT').click();
    await guest.waitForTimeout(300);
    await shot(guest, 'pay-split');
    await guest.getByTestId('pay-mode-ITEMS').click();
    await guest.getByTestId('pick-item').nth(0).click();
    await guest.getByTestId('pick-item').nth(2).click();
    await guest.waitForTimeout(300);
    await scrollTo(guest, '.pay-modes', 60);
    await guest.waitForTimeout(300);
    await shot(guest, 'pay-items');
    await guest.getByTestId('guest-pay').click();
    await guest.getByTestId('sim-approve').click();
    await guest.locator('.guest-banner.success').waitFor();
    await guest.evaluate(() => window.scrollTo(0, 0));
    await guest.waitForTimeout(500);
    await shot(guest, 'pay-done');

    // ------------------------------------------------------------------ the manager's evening
    const manager = await open(PHONE, lang, 'admin');
    await manager.goto(`${base}/#/dashboard`);
    await settle(manager, 1200);
    await shot(manager, 'dashboard');
    await manager.goto(`${base}/#/floor`);
    await settle(manager, 900);
    await shot(manager, 'floor');

    // ------------------------------------------------------------------ the same menu in every language
    for (const other of ['en', 'de', 'fr', 'ru', 'ar']) {
      const page = await open(PHONE, other);
      await page.goto(`${base}/#/t/${token}`);
      await page.locator('.table-item').first().waitFor();
      await settle(page);
      await shot(page, `menu-top-${other}`);
      await page.context().close();
    }
    fs.writeFileSync(`${out}/scene.json`, JSON.stringify({ table: table.code, order: order.id, lang }, null, 1));
  } finally {
    await call('PUT', '/service-settings', { mode: 'MIXED', autoAcceptLimit: 1500, firstCartNeedsStaff: true });
    await describe(null);
    // the rest of the table's bill is settled at the till, so the table is free again
    const left = (await call('GET', '/orders/active')).find((o) => o.table?.id === table.id);
    if (left) {
      const bill = await call('GET', `/bills/by-order/${left.id}`);
      if (bill.balance > 0) await call('POST', `/bills/${bill.id}/payments`, { method: 'CASH', amount: bill.balance });
    }
    await call('POST', `/floor/tables/${table.id}/cleared`).catch(() => {});
    await browser.close();
  }
})();
