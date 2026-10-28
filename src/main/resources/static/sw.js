// Sofra service worker: keeps the app shell (pages, scripts, styles, fonts) so a till opens and keeps working when
// the restaurant's internet drops. Only same-origin static files are cached. API responses are never cached: they
// hold guest names, phone numbers and bills. Orders taken offline wait in the till's own queue (core/offline.js).
'use strict';

const CACHE = 'sofra-shell-v2';

// Every file of the app shell. A build test checks that each static asset is listed here.
const SHELL = [
  './',
  'index.html',
  'manifest.webmanifest',
  'favicon.svg',
  'assets/css/app.css',
  'assets/fonts/fraunces-latin-ext-opsz-italic.woff2',
  'assets/fonts/fraunces-latin-ext-opsz-normal.woff2',
  'assets/fonts/fraunces-latin-opsz-italic.woff2',
  'assets/fonts/fraunces-latin-opsz-normal.woff2',
  'assets/fonts/inter-latin-ext-wght-normal.woff2',
  'assets/fonts/inter-latin-wght-normal.woff2',
  'assets/js/app.js',
  'assets/js/theme-init.js',
  'assets/js/core/api.js',
  'assets/js/core/charts.js',
  'assets/js/core/dom.js',
  'assets/js/core/format.js',
  'assets/js/core/i18n.js',
  'assets/js/core/icons.js',
  'assets/js/core/offline.js',
  'assets/js/core/realtime.js',
  'assets/js/core/store.js',
  'assets/js/core/ui.js',
  'assets/js/vendor/qrcode.mjs',
  'assets/js/views/adjust.js',
  'assets/js/views/bill.js',
  'assets/js/views/cashier.js',
  'assets/js/views/channels.js',
  'assets/js/views/dashboard.js',
  'assets/js/views/floor.js',
  'assets/js/views/inbox.js',
  'assets/js/views/kitchen.js',
  'assets/js/views/lock.js',
  'assets/js/views/login.js',
  'assets/js/views/menu.js',
  'assets/js/views/options.js',
  'assets/js/views/orders.js',
  'assets/js/views/outbox.js',
  'assets/js/views/pay.js',
  'assets/js/views/pos.js',
  'assets/js/views/qr.js',
  'assets/js/views/reauth.js',
  'assets/js/views/settings.js',
  'assets/js/views/shared.js',
  'assets/js/views/stock.js',
  'assets/js/views/table.js',
];

// Paths that are never cached, whatever they return.
const NEVER = ['/api/', '/actuator/', '/v3/', '/swagger-ui', '/.well-known/'];
// A page that has not answered by then is taken from the cache: "connected" Wi-Fi that leads nowhere is common.
const NETWORK_TIMEOUT_MS = 4000;

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;
  const path = url.pathname.slice(new URL(self.registration.scope).pathname.length - 1);
  if (NEVER.some((prefix) => path.startsWith(prefix)) || request.headers.has('Authorization')) return;
  event.respondWith(networkFirst(request));
});

/** The network while it answers, so a new release is picked up at once; the cached copy when it does not. */
async function networkFirst(request) {
  const cache = await caches.open(CACHE);
  try {
    const response = await withTimeout(fetch(request), NETWORK_TIMEOUT_MS);
    if (response.ok && response.type === 'basic') cache.put(request, response.clone());
    return response;
  } catch {
    const cached = await cache.match(request, { ignoreSearch: request.mode === 'navigate' });
    if (cached) return cached;
    if (request.mode === 'navigate') {
      const shell = await cache.match('index.html');
      if (shell) return shell;
    }
    return Response.error();
  }
}

function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), ms);
    promise.then((value) => { clearTimeout(timer); resolve(value); }, (error) => { clearTimeout(timer); reject(error); });
  });
}
