// The page behind a table's QR code: the menu, a cart, and buttons to call the waiter or ask for the bill. Guests
// need no account; the code in the link is the permission. The restaurant's service rules decide whether a cart goes
// straight to the kitchen or waits for a waiter, and the guest is told which, and why.
import { h, mount } from '../core/dom.js';
import { icon, logoMark } from '../core/icons.js';
import { t, tn } from '../core/i18n.js';
import { money, time } from '../core/format.js';
import { api, ApiError, errorMessage } from '../core/api.js';
import { haptic, langButton, openModal, themeButton, toast } from '../core/ui.js';
import { chooseOptions, chosen, defaultOptionIds, optionKey, optionsPrice } from './options.js';
import { rememberTable } from './pay.js';
import { allergenList, allergenPicker } from './allergens.js';

const CATEGORIES = ['STARTER', 'MAIN', 'DESSERT', 'DRINK'];
const POLL_MS = 5000;
/** How often the table's bill is looked at again while nothing else is waiting. */
const BILL_POLL_MS = 20000;
const MAX_LINES = 20;
const MAX_QUANTITY = 20;
const AVOID_KEY = 'sofra.guest.avoid';
/** Suggested with the cart: what it lacks of these, most popular first. */
const PAIRINGS = [['DRINK', (cats) => cats.has('MAIN') || cats.has('STARTER')], ['DESSERT', (cats) => cats.has('MAIN')]];
const MAX_SUGGESTIONS = 3;
/** The four steps of an order the guest sees; READY lights the third. */
const STEPS = ['PENDING', 'PREPARING', 'READY', 'SERVED'];

/** Renders the table page into {@code root}. Returns a cleanup function. */
export function renderTable(root, token) {
  const sentKey = `sofra.table.${token.slice(0, 12)}`;
  const payKey = `sofra.table.pay.${token.slice(0, 12)}`;
  const state = { table: null, menu: null, error: null, category: 'ALL', cart: [], sent: readSent(), calls: new Set(), busy: false, bill: null, orders: [], avoid: readAvoid() };
  let timer = null;
  let stopped = false;

  const body = h('main', { class: 'table-page' });
  const bar = h('div', { class: 'cart-bar-slot' });
  mount(root, h('div', { class: 'guest table-guest' },
    h('header', { class: 'guest-top' }, logoMark(), h('b', null, 'Sofra'), h('span', { class: 'spacer' }), langButton(), themeButton()),
    body, bar));

  /** What this guest cannot eat, remembered on the phone for their next visit. */
  function readAvoid() {
    try { return JSON.parse(localStorage.getItem(AVOID_KEY) || '[]').filter((code) => typeof code === 'string').slice(0, 14); } catch { return []; }
  }

  function saveAvoid() {
    try { localStorage.setItem(AVOID_KEY, JSON.stringify(state.avoid)); } catch { /* private mode */ }
  }

  const unsafe = (item) => (item.allergens || []).some((code) => state.avoid.includes(code));

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
    const inCategory = state.menu.filter((item) => item.available && (state.category === 'ALL' || item.category === state.category));
    const items = inCategory.filter((item) => !unsafe(item));
    const hidden = inCategory.length - items.length;
    mount(body,
      h('section', { class: 'card table-hero' },
        h('div', null, h('div', { class: 'faint' }, state.table.restaurant),
          h('h1', { class: 'guest-title' }, t('guest.table', { table: state.table.tableCode }))),
        h('div', { class: 'table-calls' },
          callButton('CALL_WAITER', 'bell'),
          callButton('REQUEST_BILL', 'receipt')),
        state.bill?.payable ? h('div', { class: 'table-pay' },
          h('button', { class: 'btn primary lg block', 'data-testid': 'pay-bill', disabled: state.busy, onclick: payBill },
            icon('card', 'sm'), t('table.payBill', { amount: money(state.bill.due) })),
          h('small', { class: 'faint' }, t('table.payHint'))) : null),
      state.orders.length ? h('section', { class: 'track', 'aria-live': 'polite' },
        h('h2', { class: 'track-title' }, t('track.title')), state.orders.map(orderCard)) : null,
      // a cart the kitchen took shows up as an order above; only carts still waiting or turned down stay here
      state.sent.filter((sent) => sent.view.status !== 'ACCEPTED').map(sentCard),
      h('div', { class: 'table-tools' },
        h('div', { class: 'chips table-cats', role: 'tablist' }, ['ALL', ...CATEGORIES].map((category) => h('button', {
          class: ['chip', state.category === category ? 'active' : ''], role: 'tab', 'aria-selected': String(state.category === category),
          onclick: () => { state.category = category; render(); },
        }, t(`cat.${category}`)))),
        h('button', { class: ['btn', 'sm', 'allergy-btn', state.avoid.length ? 'on' : ''], 'data-testid': 'allergies', onclick: openAllergies },
          icon('wheat', 'sm'), h('span', null, state.avoid.length ? t('allergy.buttonN', { n: state.avoid.length }) : t('allergy.button')))),
      hidden ? h('div', { class: 'guest-banner info allergy-note', 'data-testid': 'allergy-hidden' }, icon('wheat', 'sm'),
        h('div', null, h('b', null, tn('allergy.hidden', hidden)), h('div', { class: 'sub' }, t('allergy.avoiding', { list: allergenList(state.avoid) }))),
        h('button', { class: 'btn sm ghost', onclick: openAllergies }, t('allergy.change'))) : null,
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
        h('b', null, t(r.status === 'ACCEPTED' && r.automatic ? 'table.status.AUTO' : `table.status.${r.status}`)),
        h('div', { class: 'sub' }, r.status === 'REJECTED' && r.reason ? r.reason
          : `${r.lines.map((line) => `${line.quantity} × ${line.name}`).join(', ')} · ${money(r.total)}`),
        r.status === 'OPEN' && r.waitingFor ? h('div', { class: 'sub', 'data-testid': 'sent-wait' }, t(`table.wait.${r.waitingFor}`)) : null));
  }

  /** An order at the table as the kitchen works on it: a four-step bar, when it should be ready, and each dish. */
  function orderCard(order) {
    const step = STEPS.indexOf(order.status);
    const minutes = order.readyBy ? Math.ceil((new Date(order.readyBy).getTime() - Date.now()) / 60_000) : null;
    return h('article', { class: ['card', 'track-card', `is-${order.status.toLowerCase()}`], 'data-testid': 'order-track' },
      h('div', { class: 'track-head' },
        h('div', { class: 'track-status' },
          h('b', { 'data-testid': 'track-status' }, t(`track.status.${order.status}`)),
          h('small', { class: 'faint' }, t('track.placed', { number: order.number, time: time(order.placedAt) }))),
        h('span', { class: 'track-icon', 'aria-hidden': 'true' }, icon(order.status === 'SERVED' ? 'check' : order.status === 'READY' ? 'bell' : 'flame', 'sm'))),
      h('div', { class: 'track-bar', role: 'img', 'aria-label': t('track.step', { step: step + 1, status: t(`track.status.${order.status}`) }) },
        STEPS.map((_, i) => h('span', { class: i < step ? 'done' : i === step ? 'now' : '' }))),
      minutes != null ? h('div', { class: 'track-eta', 'data-testid': 'track-eta' }, icon('clock', 'sm'),
        minutes > 1 ? t('track.eta', { minutes }) : t('track.soon')) : null,
      h('ul', { class: 'track-lines' }, order.lines.map((line) => h('li', { class: `is-${line.state.toLowerCase()}` },
        h('span', { class: 'emoji', 'aria-hidden': 'true' }, line.icon || '🍽️'),
        // the name keeps its own direction (bdi) while the line lines up with the page, in Arabic too
        h('span', { class: 'n' }, h('span', null, `${line.quantity} × `, h('bdi', null, line.name)), line.options ? h('small', { class: 'faint' }, h('bdi', null, line.options)) : null),
        ['LATER', 'READY', 'SERVED'].includes(line.state) && order.status !== 'SERVED'
          ? h('span', { class: `pill track-pill ${line.state.toLowerCase()}` }, t(`track.line.${line.state}`)) : null))));
  }

  /** Tells the table when the kitchen has an order ready: a buzz and a note, once per order. */
  function announceReady(before, after) {
    const was = new Map(before.map((order) => [order.number, order.status]));
    const ready = after.filter((order) => order.status === 'READY' && was.has(order.number) && was.get(order.number) !== 'READY');
    if (!ready.length) return;
    haptic('success');
    toast({ title: t('track.readyToast') });
  }

  function menuRow(item) {
    const inCart = state.cart.filter((line) => line.item.id === item.id).reduce((n, line) => n + line.quantity, 0);
    return h('div', { class: 'table-item' },
      h('div', { class: 'emoji' }, item.icon || '🍽️'),
      h('div', { class: 'info' },
        h('b', null, item.name),
        item.description ? h('small', { dir: 'auto' }, item.description) : null,
        item.popular || item.tags?.length ? h('div', { class: 'item-tags' },
          item.popular ? h('span', { class: 'tag t-POPULAR' }, t('pos.popular')) : null,
          (item.tags || []).map((tag) => h('span', { class: `tag t-${tag}` }, t(`tag.${tag}`)))) : null,
        item.allergens?.length ? h('small', { class: 'allergen-line', 'data-testid': 'allergen-line' },
          t('allergen.contains', { list: allergenList(item.allergens) })) : null,
        h('div', { class: 'price' }, money(item.price), item.optionGroups?.length ? h('span', { class: 'faint' }, ` · ${t('table.hasOptions')}`) : null,
          item.needsApproval && state.table.mode !== 'CLASSIC' ? h('span', { class: 'faint' }, ` · ${t('table.needsApproval')}`) : null)),
      h('button', {
        class: ['icon-btn', 'add', inCart ? 'has' : ''], 'aria-label': t('table.add', { name: item.name }), 'data-testid': `add-${item.id}`,
        onclick: () => add(item),
      }, inCart ? h('b', null, inCart) : icon('plus', 'sm')));
  }

  /** The guest ticks what they cannot eat; dishes containing it leave the menu on this phone. */
  function openAllergies() {
    const picker = allergenPicker(state.avoid, { testid: 'avoid', onChange: (codes) => { state.avoid = codes; saveAvoid(); render(); } });
    openModal({
      title: t('allergy.title'),
      size: 'sm',
      body: [h('p', { class: 'muted' }, t('allergy.text')), picker.element,
        h('div', { class: 'guest-banner info' }, icon('info', 'sm'), h('div', null, t('allergy.disclaimer')))],
      footer: (handle) => [h('button', { class: 'btn primary', 'data-testid': 'allergies-done', onclick: handle.close }, icon('check', 'sm'), t('common.done'))],
    });
  }

  /** A drink with the food, a dessert after the main: what the cart lacks, the kitchen's popular dishes first. */
  function suggestions() {
    const cats = new Set(state.cart.map((line) => line.item.category));
    const inCart = new Set(state.cart.map((line) => line.item.id));
    return PAIRINGS.filter(([category, wanted]) => !cats.has(category) && wanted(cats))
      .flatMap(([category]) => state.menu
        .filter((item) => item.category === category && item.available && !inCart.has(item.id) && !unsafe(item))
        .sort((a, b) => Number(b.popular) - Number(a.popular))
        .slice(0, 2))
      .slice(0, MAX_SUGGESTIONS);
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
    haptic();
    render();
  }

  function openCart() {
    const name = h('input', { class: 'input', id: 'guest-name', maxlength: 40, autocomplete: 'given-name', placeholder: t('table.namePlaceholder') });
    const note = h('textarea', { class: 'textarea', id: 'guest-note', rows: 2, maxlength: 140, placeholder: t('table.notePlaceholder') });
    const lines = h('div', { class: 'cart-lines' });
    const suggest = h('div', { class: 'suggest' });
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
      const picks = suggestions();
      mount(suggest, picks.length ? [h('div', { class: 'label' }, t('suggest.title')),
        h('div', { class: 'suggest-row' }, picks.map((item) => h('button', {
          type: 'button', class: 'suggest-item', 'data-testid': 'suggest-item', 'aria-label': t('table.add', { name: item.name }),
          onclick: async () => { await add(item); renderLines(); },
        }, h('span', { class: 'emoji', 'aria-hidden': 'true' }, item.icon || '🍽️'),
        h('span', { class: 'n' }, h('bdi', null, item.name), h('small', { class: 'faint' }, money(item.price))),
        icon('plus', 'sm'))))] : null);
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
        suggest,
        h('p', { class: 'faint', style: { fontSize: '12px' } }, t(`table.taxNote.${state.table.mode || 'CLASSIC'}`)),
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
        haptic('success');
        modal.close();
        // a cart the service rules sent straight on shows up as an order; say so, as there is no card for it
        if (view.status === 'ACCEPTED') toast({ title: t('table.status.AUTO') });
        render();
        poll();
      } catch (error) {
        send.disabled = false;
        haptic('error');
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

  /**
   * Opens the table's bill on this phone. The link this phone opened before is used again while it can still be
   * paid, so pressing twice does not leave links behind.
   */
  async function payBill() {
    state.busy = true;
    render();
    try {
      let link = null;
      try { link = sessionStorage.getItem(payKey); } catch { /* private mode */ }
      if (link && !(await api.publicBill(link).then((bill) => bill.payable, () => false))) link = null;
      if (!link) {
        ({ token: link } = await api.openTableBill(token));
        try { sessionStorage.setItem(payKey, link); } catch { /* private mode */ }
      }
      rememberTable(link, token);
      haptic();
      location.hash = `/pay/${link}`;
    } catch (error) {
      state.busy = false;
      render();
      showProblem(error);
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
      const [table, bill, orders] = await Promise.all([api.publicTable(token), api.tableBill(token).catch(() => state.bill),
        api.guestOrders(token).catch(() => state.orders)]);
      announceReady(state.orders, orders);
      state.table = table;
      state.bill = bill;
      state.orders = orders;
      state.calls = new Set(table.openCalls);
    } catch { /* keep what we have */ }
    saveSent();
    if (stopped) return;
    render();
    const waiting = state.sent.some((sent) => sent.view.status === 'OPEN') || state.calls.size
      || state.orders.some((order) => order.status !== 'SERVED');
    timer = setTimeout(poll, waiting ? POLL_MS : BILL_POLL_MS);
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

  // a phone coming back to the page (from the camera, another app) looks again at once
  const onVisible = () => { if (document.visibilityState === 'visible' && state.table) poll(); };
  document.addEventListener('visibilitychange', onVisible);

  render();
  load();
  return () => { stopped = true; clearTimeout(timer); document.removeEventListener('visibilitychange', onVisible); };
}
