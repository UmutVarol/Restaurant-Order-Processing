// Sofra web client: boots the session, renders the app shell and routes between screens.
import { h, initials, mount } from './core/dom.js';
import { icon, logoMark } from './core/icons.js';
import { onLangChange, t } from './core/i18n.js';
import { api, clearSession, currentDevice, currentSession, saveSession } from './core/api.js';
import { bus, chime, prefs } from './core/store.js';
import { realtimeStatus, startRealtime, stopRealtime } from './core/realtime.js';
import { time } from './core/format.js';
import { langButton, themeButton, toast } from './core/ui.js';

const ALL = ['ADMIN', 'WAITER', 'KITCHEN'];
const ROUTES = [
  { id: 'pos', icon: 'pos', roles: ['ADMIN', 'WAITER'], section: 'service', load: () => import('./views/pos.js') },
  { id: 'kitchen', icon: 'kitchen', roles: ALL, section: 'service', load: () => import('./views/kitchen.js') },
  { id: 'floor', icon: 'floor', roles: ALL, section: 'service', load: () => import('./views/floor.js') },
  { id: 'orders', icon: 'orders', roles: ALL, section: 'service', load: () => import('./views/orders.js') },
  { id: 'cashier', icon: 'coins', roles: ['ADMIN', 'WAITER'], section: 'service', load: () => import('./views/cashier.js') },
  { id: 'dashboard', icon: 'dashboard', roles: ['ADMIN'], section: 'manage', load: () => import('./views/dashboard.js') },
  { id: 'menu', icon: 'menu', roles: ['ADMIN'], section: 'manage', load: () => import('./views/menu.js') },
  { id: 'stock', icon: 'box', roles: ['ADMIN'], section: 'manage', load: () => import('./views/stock.js') },
  // reached from the user card, not the navigation
  { id: 'settings', icon: 'settings', roles: ALL, section: null, load: () => import('./views/settings.js') },
];
const HOME = { ADMIN: 'dashboard', WAITER: 'pos', KITCHEN: 'kitchen' };

const root = document.getElementById('app');
let user = null;
let activeView = null;
let renderToken = 0;
let kitchenCount = 0;
let guestCount = 0;

// ------------------------------------------------------------------------------- routing

function parseHash() {
  const [path, search = ''] = location.hash.replace(/^#\/?/, '').split('?');
  return { route: path || '', params: Object.fromEntries(new URLSearchParams(search)) };
}

export function navigate(route, params) {
  const search = params ? `?${new URLSearchParams(params)}` : '';
  const target = `#/${route}${search}`;
  if (location.hash === target) render();
  else location.hash = target;
}

function allowedRoutes() {
  return ROUTES.filter((route) => route.roles.includes(user.role));
}

async function render() {
  const token = ++renderToken;
  activeView?.destroy?.();
  activeView = null;

  // A guest paying by QR code needs no account and sees none of the staff app.
  const tableCode = parseHash().route.match(/^t\/([A-Za-z0-9_-]{20,100})$/);
  if (tableCode) {
    const { renderTable } = await import('./views/table.js');
    if (token !== renderToken) return;
    activeView = { destroy: renderTable(root, tableCode[1]) };
    return;
  }
  const guest = parseHash().route.match(/^(pay|sim-checkout)\/([A-Za-z0-9_-]{6,100})$/);
  if (guest) {
    const { renderGuest } = await import('./views/pay.js');
    if (token !== renderToken) return;
    activeView = { destroy: renderGuest(root, guest[1], guest[2]) };
    document.title = `${t('guest.yourBill')} · Sofra`;
    return;
  }

  if (!user) {
    const { default: login } = await import('./views/login.js');
    if (token !== renderToken) return;
    mount(root, login({ onSignedIn }));
    return;
  }

  const { route: requested, params } = parseHash();
  const route = allowedRoutes().find((r) => r.id === requested);
  if (!route) {
    navigate(HOME[user.role]);
    return;
  }

  const shell = renderShell(route);
  mount(root, shell.element);
  try {
    const { default: view } = await route.load();
    if (token !== renderToken) return;
    shell.setTitle(t(view.title), t(view.subtitle));
    activeView = view.mount(shell.content, { user, params, navigate, actions: shell.actions }) || null;
  } catch (error) {
    console.error(error);
    mount(shell.content, h('div', { class: 'empty' }, h('div', { class: 'art' }, '😕'), h('h4', null, t('common.loadFailed')),
      h('button', { class: 'btn', onclick: render }, icon('refresh', 'sm'), t('common.retry'))));
  }
}

// ------------------------------------------------------------------------------- shell

function renderShell(current) {
  const title = h('h1', { class: 'page-title' });
  const subtitle = h('div', { class: 'page-sub' });
  const actions = h('div', { class: 'topbar-tools' });
  const content = h('section', { class: 'view', id: 'view', tabindex: -1 });

  const navItems = (section) => allowedRoutes().filter((r) => r.section === section).map((route) => {
    const index = allowedRoutes().indexOf(route) + 1;
    return h('a', {
      class: ['nav-item', route.id === current.id ? 'active' : ''],
      href: `#/${route.id}`,
      'aria-current': route.id === current.id ? 'page' : false,
      title: t(`nav.${route.id}`),
    }, icon(route.icon), h('span', null, t(`nav.${route.id}`)),
    route.id === 'kitchen' ? h('span', { class: 'badge', id: 'kitchen-badge', hidden: kitchenCount === 0 }, kitchenCount) : h('kbd', null, `Alt ${index}`));
  });

  const sections = ['service', 'manage'].filter((section) => allowedRoutes().some((r) => r.section === section));
  const sidebar = h('aside', { class: 'sidebar' },
    h('div', { class: 'brand' }, logoMark(), h('div', null, h('div', { class: 'brand-name' }, 'Sofra'), h('div', { class: 'brand-sub' }, t('app.tagline')))),
    h('nav', { class: 'nav', 'aria-label': 'Main' }, sections.map((section) => [
      h('div', { class: 'nav-label' }, t(`nav.section.${section}`)),
      navItems(section),
    ])),
    h('div', { class: 'sidebar-footer' },
      h('div', { class: 'user-card' },
        h('div', { class: 'avatar' }, initials(user.displayName)),
        h('div', { class: 'who' }, h('div', { class: 'name' }, user.displayName), h('div', { class: 'role' }, t(`role.${user.role}`))),
        h('a', { class: 'icon-btn sm', href: '#/settings', title: t('nav.settings'), 'aria-label': t('nav.settings') }, icon('settings', 'sm')),
        currentDevice()
          ? h('button', { class: 'icon-btn sm', title: t('lock.switch'), 'aria-label': t('lock.switch'), 'data-testid': 'switch-user', onclick: signOut }, icon('switchUser', 'sm'))
          : h('button', { class: 'icon-btn sm', title: t('action.logout'), 'aria-label': t('action.logout'), onclick: signOut }, icon('logout', 'sm')))));

  const live = h('div', { class: `live ${realtimeStatus()}`, id: 'live-indicator' }, h('span', { class: 'dot' }),
    h('span', null, realtimeStatus() === 'on' ? t('live.on') : t('live.off')));
  const clock = h('span', { class: 'clock', id: 'clock' }, time(new Date().toISOString()));

  // carts and calls sent from tables reach whoever takes orders
  const inbox = ['ADMIN', 'WAITER'].includes(user.role) ? h('button', {
    class: 'icon-btn inbox-btn', title: t('inbox.title'), 'aria-label': t('inbox.title'), 'data-testid': 'open-inbox',
    onclick: async () => { const { openInbox } = await import('./views/inbox.js'); openInbox(user); },
  }, icon('bell'), h('span', { class: 'badge', id: 'guest-badge', hidden: guestCount === 0 }, guestCount)) : null;

  const topbar = h('header', { class: 'topbar' },
    h('div', null, title, subtitle),
    h('div', { class: 'spacer' }),
    actions,
    h('div', { class: 'topbar-tools' },
      inbox,
      live,
      clock,
      langButton(),
      themeButton()));

  const skip = h('a', { class: 'skip-link', href: '#view', onclick: (event) => { event.preventDefault(); content.focus(); } }, t('common.skip'));
  const element = h('div', { class: 'shell' }, skip, sidebar, h('main', { class: 'main' }, topbar, content));
  return {
    element,
    content,
    actions,
    setTitle(text, sub) {
      title.textContent = text;
      subtitle.textContent = sub;
      document.title = `${text} · Sofra`;
    },
  };
}

function updateLiveIndicator(status) {
  const el = document.getElementById('live-indicator');
  if (!el) return;
  el.className = `live ${status}`;
  el.lastChild.textContent = status === 'on' ? t('live.on') : t('live.off');
}

async function refreshGuestBadge() {
  if (!user || !currentSession() || !['ADMIN', 'WAITER'].includes(user.role)) return;
  try {
    guestCount = (await api.guestRequests()).length;
    const badge = document.getElementById('guest-badge');
    if (badge) {
      badge.textContent = guestCount;
      badge.hidden = guestCount === 0;
    }
  } catch { /* badge is best effort */ }
}

bus.on('guest', (event) => {
  if (!user || !['ADMIN', 'WAITER'].includes(user.role)) return;
  refreshGuestBadge();
  if (event.status === 'OPEN') {
    if (prefs.sound) chime();
    toast({ title: t(`inbox.new.${event.kind}`, { table: event.tableCode }), kind: 'info',
      action: { label: t('inbox.open'), run: async () => { const { openInbox } = await import('./views/inbox.js'); openInbox(user); } } });
  }
});

// a kitchen ticket that could not be printed must not go unnoticed
bus.on('print', (event) => {
  if (!user || !['ADMIN', 'WAITER'].includes(user.role) || event.status !== 'FAILED') return;
  if (prefs.sound) chime();
  toast({ title: event.orderId ? t('printing.failedOrder', { printer: event.printer, id: event.orderId }) : t('printing.failedTest', { printer: event.printer }),
    text: event.error || '', kind: 'error', timeout: 12000 });
});

// managers hear about stock running low; everyone's menu is refreshed by the screens that show it
bus.on('stock', (event) => {
  if (!user || user.role !== 'ADMIN') return;
  if (event.lowIngredients?.length) {
    toast({ title: t('stock.lowAlert', { list: event.lowIngredients.join(', ') }), kind: 'info', timeout: 9000,
      action: { label: t('stock.open'), run: () => navigate('stock') } });
  }
  if (event.soldOut?.length) toast({ title: t('stock.soldOutAlert', { list: event.soldOut.join(', ') }), kind: 'error', timeout: 9000 });
});

async function refreshKitchenBadge() {
  if (!user || !currentSession()) return;
  try {
    const active = await api.activeOrders();
    kitchenCount = active.filter((o) => ['PENDING', 'PREPARING', 'READY'].includes(o.status)).length;
    const badge = document.getElementById('kitchen-badge');
    if (badge) {
      badge.textContent = kitchenCount;
      badge.hidden = kitchenCount === 0;
    }
  } catch { /* badge is best effort */ }
}

// ------------------------------------------------------------------------------- session

// On a shared till the screen locks itself after a few idle minutes, back to the staff PIN screen. Kitchen screens
// are watched rather than touched, so they stay signed in.
const IDLE_LOCK_MS = () => (currentDevice()?.idleMinutes ?? 5) * 60_000;
let lastActivity = Date.now();
['pointerdown', 'keydown', 'wheel', 'touchstart'].forEach((type) => document.addEventListener(type, () => { lastActivity = Date.now(); }, { passive: true, capture: true }));
setInterval(() => {
  const device = currentDevice();
  if (!user || !device || user.role === 'KITCHEN' || device.idleMinutes === 0) return;
  if (Date.now() - lastActivity >= IDLE_LOCK_MS()) {
    signOut();
    toast({ title: t('lock.locked'), kind: 'info' });
  }
}, 10_000);

async function onSignedIn(tokenResponse) {
  lastActivity = Date.now();
  saveSession(tokenResponse);
  user = tokenResponse.user;
  startRealtime();
  refreshKitchenBadge();
  refreshGuestBadge();
  if (user.mfaSetupRequired) {
    // the restaurant requires two-step sign-in for managers: set it up before anything money-sensitive
    toast({ title: t('totp.setupRequired'), kind: 'info', timeout: 9000 });
    navigate('settings');
    return;
  }
  navigate(HOME[user.role]);
}

function signOut() {
  stopRealtime();
  // Revoke the token on the server too; the local sign-out does not wait for it or depend on it.
  if (currentSession()) api.logout().catch(() => {});
  clearSession();
  user = null;
  history.replaceState(null, '', location.pathname + location.search);
  render();
}

bus.on('session:expired', () => {
  if (!user) return;
  clearTimeout(badgeTimer);
  toast({ title: t('error.UNAUTHORIZED'), kind: 'info' });
  signOut();
});

bus.on('realtime:status', updateLiveIndicator);
let badgeTimer;
bus.on('order', () => {
  clearTimeout(badgeTimer);
  badgeTimer = setTimeout(refreshKitchenBadge, 300);
});
bus.on('theme', (theme) => document.getElementById('theme-btn')?.replaceChildren(icon(theme === 'dark' ? 'sun' : 'moon')));
onLangChange(() => render());
window.addEventListener('hashchange', render);

setInterval(() => {
  const clock = document.getElementById('clock');
  if (clock) clock.textContent = time(new Date().toISOString());
}, 10_000);

document.addEventListener('keydown', (event) => {
  if (!user || !event.altKey || event.ctrlKey || event.metaKey) return;
  const index = Number(event.key) - 1;
  const route = allowedRoutes()[index];
  if (route) {
    event.preventDefault();
    navigate(route.id);
  }
});

async function boot() {
  const session = currentSession();
  if (session) {
    try {
      user = await api.me();
      startRealtime();
      refreshKitchenBadge();
      refreshGuestBadge();
    } catch {
      clearSession();
      user = null;
    }
  }
  render();
}

boot();
