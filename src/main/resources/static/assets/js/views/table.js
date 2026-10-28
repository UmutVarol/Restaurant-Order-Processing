// The page behind a table's QR code: the menu, a cart the waiter accepts, and buttons to call the waiter or ask for
// the bill. Guests need no account; the code in the link is the permission, and nothing they send reaches the
// kitchen until a waiter accepts it.
import { h, mount } from '../core/dom.js';
import { icon, logoMark } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { money } from '../core/format.js';
import { api, ApiError, errorMessage } from '../core/api.js';
import { langButton, openModal, themeButton } from '../core/ui.js';
import { chooseOptions, chosen, defaultOptionIds, optionKey, optionsPrice } from './options.js';

const CATEGORIES = ['STARTER', 'MAIN', 'DESSERT', 'DRINK'];
const POLL_MS = 5000;
const MAX_LINES = 20;
const MAX_QUANTITY = 20;

/** Renders the table page into {@code root}. Returns a cleanup function. */
export function renderTable(root, token) {
  const sentKey = `sofra.table.${token.slice(0, 12)}`;
  const state = { table: null, menu: null, error: null, category: 'ALL', cart: [], sent: readSent(), calls: new Set(), busy: false };
  let timer = null;
  let stopped = false;

  const body = h('main', { class: 'table-page' });
  const bar = h('div', { class: 'cart-bar-slot' });
  mount(root, h('div', { class: 'guest table-guest' },
    h('header', { class: 'guest-top' }, logoMark(), h('b', null, 'Sofra'), h('span', { class: 'spacer' }), langButton(), themeButton()),
    body, bar));

  function readSent() {
    try { return JSON.parse(sessionStorage.getItem(sentKey) || '[]').slice(-5); } catch { return []; }
  }

  function saveSent() {
    try { sessionStorage.setItem(sentKey, JSON.stringify(state.sent.slice(-5))); } catch { /* private mode */ }
  }

  // ------------------------------------------------------------------ render
  function render() {
    if (state.error) {
      mount(body, h('div', { class: 'card guest-card' }, h('div', { class: 'empty' }, h('div', { class: 'art' }, '🔍'),
        h('h4', null, t('table.notFound')), h('p', null, t('table.notFoundText')))));
      mount(bar);
      return;
    }
    if (!state.table || !state.menu) {
      mount(body, [120, 60, 220, 220].map((height) => h('div', { class: 'skeleton', style: { height: `${height}px`, borderRadius: '20px' } })));
      return;
    }
    const items = state.menu.filter((item) => item.available && (state.category === 'ALL' || item.category === state.category));
    mount(body,
      h('section', { class: 'card table-hero' },
        h('div', null, h('div', { class: 'faint' }, state.table.restaurant),
          h('h1', { class: 'guest-title' }, t('guest.table', { table: state.table.tableCode }))),
        h('div', { class: 'table-calls' },
          callButton('CALL_WAITER', 'bell'),
          callButton('REQUEST_BILL', 'receipt'))),
      state.sent.map(sentCard),
      h('div', { class: 'chips table-cats', role: 'tablist' }, ['ALL', ...CATEGORIES].map((category) => h('button', {
        class: ['chip', state.category === category ? 'active' : ''], role: 'tab', 'aria-selected': String(state.category === category),
        onclick: () => { state.category = category; render(); },
      }, t(`cat.${category}`)))),
      h('div', { class: 'table-menu' }, items.map(menuRow)));
    renderBar();
  }

  function callButton(kind, iconName) {
    const waiting = state.calls.has(kind);
    return h('button', {
      class: ['btn', waiting ? 'waiting' : ''], disabled: waiting || state.busy, 'data-testid': `call-${kind}`,
      onclick: () => call(kind),
    }, icon(iconName, 'sm'), waiting ? t(`table.${kind}.waiting`) : t(`table.${kind}`));
  }

  function sentCard(sent) {
    const r = sent.view;
    const tone = r.status === 'REJECTED' ? 'error' : r.status === 'OPEN' ? 'info' : 'success';
    return h('div', { class: `guest-banner ${tone}`, 'data-testid': 'sent-status' },
      icon(r.status === 'REJECTED' ? 'alert' : r.status === 'OPEN' ? 'clock' : 'check', 'sm'),
      h('div', null,
        h('b', null, t(`table.status.${r.status}`)),
        h('div', { class: 'sub' }, r.status === 'REJECTED' && r.reason ? r.reason
          : `${r.lines.map((line) => `${line.quantity} × ${line.name}`).join(', ')} · ${money(r.total)}`)));
  }

  function menuRow(item) {
    const inCart = state.cart.filter((line) => line.item.id === item.id).reduce((n, line) => n + line.quantity, 0);
    return h('div', { class: 'table-item' },
      h('div', { class: 'emoji' }, item.icon || '🍽️'),
      h('div', { class: 'info' },
        h('b', null, item.name),
        item.description ? h('small', null, item.description) : null,
        h('div', { class: 'price' }, money(item.price), item.optionGroups?.length ? h('span', { class: 'faint' }, ` · ${t('table.hasOptions')}`) : null)),
      h('button', {
        class: ['icon-btn', 'add', inCart ? 'has' : ''], 'aria-label': t('table.add', { name: item.name }), 'data-testid': `add-${item.id}`,
        onclick: () => add(item),
      }, inCart ? h('b', null, inCart) : icon('plus', 'sm')));
  }

  function renderBar() {
    const count = state.cart.reduce((n, line) => n + line.quantity, 0);
    if (!count) { mount(bar); return; }
    mount(bar, h('button', { class: 'cart-bar', 'data-testid': 'open-cart', onclick: openCart },
      h('span', { class: 'badge' }, count), h('span', null, t('table.cart')), h('b', null, money(subtotal())), icon('arrowRight', 'sm')));
  }

  // ------------------------------------------------------------------ cart
  const unitPrice = (line) => Number(line.item.price) + optionsPrice(line.item, line.optionIds);
  const subtotal = () => state.cart.reduce((sum, line) => sum + unitPrice(line) * line.quantity, 0);

  async function add(item) {
    let ids = defaultOptionIds(item);
    if (item.optionGroups?.length) {
      ids = await chooseOptions(item, ids, { confirmLabel: t('table.addToCart') });
      if (!ids) return;
    }
    const same = state.cart.find((line) => line.item.id === item.id && optionKey(line.optionIds) === optionKey(ids));
    if (same) same.quantity = Math.min(MAX_QUANTITY, same.quantity + 1);
    else if (state.cart.length < MAX_LINES) state.cart.push({ item, optionIds: ids, quantity: 1 });
    render();
  }

  function openCart() {
    const name = h('input', { class: 'input', id: 'guest-name', maxlength: 40, autocomplete: 'given-name', placeholder: t('table.namePlaceholder') });
    const note = h('textarea', { class: 'textarea', id: 'guest-note', rows: 2, maxlength: 140, placeholder: t('table.notePlaceholder') });
    const lines = h('div', { class: 'cart-lines' });
    const total = h('b', { class: 'options-total' });
    const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
    const send = h('button', { class: 'btn primary', 'data-testid': 'send-cart' }, icon('flash', 'sm'), t('table.send'));
    const renderLines = () => {
      mount(lines, state.cart.map((line) => h('div', { class: 'cart-line' },
        h('div', { class: 'emoji' }, line.item.icon || '🍽️'),
        h('div', { class: 'info' }, h('div', { class: 'name' }, line.item.name),
          h('div', { class: 'meta' }, money(unitPrice(line)), chosen(line.item, line.optionIds).length ? ` · ${chosen(line.item, line.optionIds).map((o) => o.choice).join(' · ')}` : '')),
        h('div', { class: 'right' }, h('div', { class: 'stepper' },
          h('button', { 'aria-label': t('pos.decrease', { name: line.item.name }), onclick: () => change(line, -1) }, icon(line.quantity === 1 ? 'trash' : 'minus', 'sm')),
          h('span', null, line.quantity),
          h('button', { 'aria-label': t('pos.increase', { name: line.item.name }), disabled: line.quantity >= MAX_QUANTITY, onclick: () => change(line, 1) }, icon('plus', 'sm')))))));
      total.textContent = money(subtotal());
    };
    const change = (line, delta) => {
      line.quantity += delta;
      if (line.quantity <= 0) state.cart.splice(state.cart.indexOf(line), 1);
      if (!state.cart.length) modal.close();
      renderLines();
      render();
    };
    renderLines();
    const modal = openModal({
      title: t('table.cartTitle'),
      size: 'sm',
      body: [lines,
        h('div', { class: 'row options-sum' }, h('span', { class: 'muted' }, t('table.subtotal')), total),
        h('p', { class: 'faint', style: { fontSize: '12px' } }, t('table.taxNote')),
        h('div', { class: 'field' }, h('label', { for: 'guest-name' }, t('table.name')), name),
        h('div', { class: 'field' }, h('label', { for: 'guest-note' }, t('table.note')), note),
        problem],
      footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), send],
    });
    send.addEventListener('click', async () => {
      send.disabled = true;
      problem.hidden = true;
      try {
        const view = await api.guestOrder(token, {
          guestName: name.value.trim() || null,
          note: note.value.trim() || null,
          items: state.cart.map((line) => ({ menuItemId: line.item.id, quantity: line.quantity, optionIds: line.optionIds })),
        });
        state.sent.push({ id: view.id, view });
        saveSent();
        state.cart = [];
        modal.close();
        render();
        poll();
      } catch (error) {
        send.disabled = false;
        problem.replaceChildren(icon('alert', 'sm'), errorMessage(error));
        problem.hidden = false;
      }
    });
  }

  async function call(kind) {
    state.busy = true;
    render();
    try {
      await api.guestCall(token, { kind });
      state.calls.add(kind);
    } catch (error) {
      showProblem(error);
    } finally {
      state.busy = false;
      render();
    }
  }

  function showProblem(error) {
    openModal({ title: t('table.problem'), size: 'sm', body: h('p', null, errorMessage(error)),
      footer: (handle) => [h('button', { class: 'btn primary', onclick: handle.close }, t('common.close'))] });
  }

  // ------------------------------------------------------------------ live status
  async function poll() {
    clearTimeout(timer);
    if (stopped) return;
    const open = state.sent.filter((sent) => sent.view.status === 'OPEN');
    for (const sent of open) {
      try { sent.view = await api.guestRequest(token, sent.id); } catch { /* try again next time */ }
    }
    try {
      state.table = await api.publicTable(token);
      state.calls = new Set(state.table.openCalls);
    } catch { /* keep what we have */ }
    saveSent();
    render();
    if (state.sent.some((sent) => sent.view.status === 'OPEN') || state.calls.size) timer = setTimeout(poll, POLL_MS);
  }

  async function load() {
    try {
      const [table, menu] = await Promise.all([api.publicTable(token), api.menu({ includeUnavailable: false })]);
      state.table = table;
      state.calls = new Set(table.openCalls);
      state.menu = menu;
      document.title = `${t('guest.table', { table: table.tableCode })} · ${table.restaurant}`;
    } catch (error) {
      state.error = error instanceof ApiError ? error.code : 'NETWORK';
    }
    render();
    if (!state.error) poll();
  }

  render();
  load();
  return () => { stopped = true; clearTimeout(timer); };
}
