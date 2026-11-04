// Pages a guest sees without signing in: the bill behind a QR code, and the simulated provider's payment page
// used in demos. Card details are only ever entered on the provider's own page, never here.
import { h, mount } from '../core/dom.js';
import { icon, logoMark } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { money, percent, time } from '../core/format.js';
import { api, backendFetch, errorMessage } from '../core/api.js';
import { haptic, langButton, themeButton } from '../core/ui.js';
import { parseCents, typed } from './bill.js';

const TIP_RATES = [0, 0.05, 0.1, 0.15];
const lira = (value) => (value / 100).toFixed(2);

const RETURN_KEY = 'sofra.pay.return';
const RETURN_TTL_MS = 2 * 60 * 60 * 1000;

/** Renders a guest page into {@code root}. Returns a cleanup function. */
export function renderGuest(root, kind, token) {
  if (kind === 'pay' && token === 'return') return returnFromProvider(root);
  return kind === 'pay' ? payPage(root, token) : simulatedCheckoutPage(root, token);
}

/**
 * The provider sends the guest back without the link in the URL (it would be seen by the provider and could end
 * up in logs), so the page remembers on this phone which link it came from.
 */
function remember(token) {
  try { localStorage.setItem(RETURN_KEY, JSON.stringify({ token, at: Date.now() })); } catch { /* private mode */ }
}

function returnFromProvider(root) {
  let saved = null;
  try { saved = JSON.parse(localStorage.getItem(RETURN_KEY) || 'null'); } catch { /* ignore */ }
  if (saved && /^[A-Za-z0-9_-]{20,100}$/.test(saved.token) && Date.now() - saved.at < RETURN_TTL_MS) {
    location.replace(`#/pay/${saved.token}`);
    return () => {};
  }
  mount(root, frame(h('div', { class: 'empty' }, h('div', { class: 'art' }, '🧾'),
    h('h4', null, t('guest.returned')), h('p', null, t('guest.returnedText')))));
  return () => {};
}

function frame(...content) {
  return h('div', { class: 'guest' },
    h('header', { class: 'guest-top' }, logoMark(), h('b', null, 'Sofra'), h('span', { class: 'spacer' }), langButton(), themeButton()),
    h('main', { class: 'guest-card card' }, content),
    h('footer', { class: 'guest-foot faint' }, icon('info', 'sm'), t('guest.secure')));
}

// ---------------------------------------------------------------------------- the guest's bill

const MODES = [['ALL', 'receipt'], ['SPLIT', 'users'], ['ITEMS', 'basket'], ['OTHER', 'edit']];
const MAX_PEOPLE = 20;
const BACK_KEY = 'sofra.pay.back';
const cents = (value) => Math.round(Number(value) * 100);

/** The table page opened this link: the guest can go back to its menu. */
export function rememberTable(link, table) {
  try { sessionStorage.setItem(BACK_KEY, JSON.stringify({ link, table })); } catch { /* private mode */ }
}

function tableOf(link) {
  try {
    const saved = JSON.parse(sessionStorage.getItem(BACK_KEY) || 'null');
    return saved?.link === link && /^[A-Za-z0-9_-]{20,100}$/.test(saved.table) ? saved.table : null;
  } catch {
    return null;
  }
}

function payPage(root, token) {
  const state = {
    bill: null, invoice: null, error: null, formError: null, busy: false,
    mode: 'ALL', people: 2, picked: new Map(), other: null, tipRate: 0.1, name: '',
    wantInvoice: false, buyer: { name: '', taxId: '', address: '' },
  };
  const table = tableOf(token);
  let polls = 0;
  let timer = null;

  async function load() {
    try {
      const [bill, invoice] = await Promise.all([api.publicBill(token), api.guestInvoice(token).catch(() => null)]);
      state.bill = bill;
      state.invoice = invoice;
      state.error = null;
      if (state.other == null) state.other = cents(bill.share ?? bill.due);
      // The provider may take a moment to confirm: look again for a short while.
      clearTimeout(timer);
      if (bill.lastPayment?.status === 'PENDING' && polls++ < 20) timer = setTimeout(load, 3000);
    } catch (error) {
      state.error = error;
    }
    render();
  }

  function render() {
    if (state.error) {
      mount(root, frame(h('div', { class: 'empty' }, h('div', { class: 'art' }, '🔗'),
        h('h4', null, state.error.status === 404 ? t('guest.notFound') : errorMessage(state.error)),
        h('p', null, t('guest.askWaiter')))));
      return;
    }
    const bill = state.bill;
    if (!bill) {
      mount(root, frame(h('div', { class: 'skeleton', style: { height: '320px' } })));
      return;
    }
    const picking = bill.payable && bill.share == null && state.mode === 'ITEMS';
    mount(root, frame(
      h('div', { class: 'guest-head' },
        table ? h('a', { class: 'btn ghost sm guest-back', href: `#/t/${table}`, 'data-testid': 'back-to-menu' },
          icon('chevronLeft', 'sm'), t('guest.backToMenu')) : null,
        h('div', { class: 'muted' }, bill.restaurant),
        h('h1', { class: 'guest-title' }, bill.tableCode ? t('guest.table', { table: bill.tableCode }) : t('guest.yourBill'))),
      status(bill),
      picking ? null : h('div', { class: 'bill-lines guest-lines' }, bill.lines.map((line) => h('div', { class: 'bill-line' },
        h('span', { class: 'emoji' }, line.icon || '🍽️'),
        h('span', { class: 'n' }, `${line.quantity} × ${line.name}`, line.options ? h('small', { class: 'opts' }, line.options) : null),
        h('span', { class: 'num' }, money(line.lineTotal))))),
      h('div', { class: 'totals' },
        h('div', { class: 'line' }, h('span', null, t('bill.total')), h('span', null, money(bill.total))),
        Number(bill.paid) > 0 ? h('div', { class: 'line' }, h('span', null, t('bill.paidSoFar')), h('span', null, money(bill.paid))) : null,
        h('div', { class: 'grand' }, h('span', null, t('bill.due')), h('span', { 'data-testid': 'guest-due' }, money(bill.due)))),
      invoiceNote(),
      bill.payable ? payForm(bill) : null));
  }

  function status(bill) {
    const last = bill.lastPayment;
    if (last?.status === 'SUCCEEDED') {
      return banner('success', 'check', t('guest.thanks', { amount: money(Number(last.amount) + Number(last.tip)) }), time(last.at));
    }
    if (last?.status === 'PENDING') return banner('info', 'clock', t('guest.processing'));
    if (last && ['FAILED', 'EXPIRED'].includes(last.status) && bill.payable) {
      return banner('error', 'alert', t('guest.failed'), last.failureReason);
    }
    if (!bill.payable) return banner('info', 'info', t(`guest.reason.${bill.reason}`));
    return null;
  }

  /** Once someone at the table gave invoice details, everyone sees whose name the invoice is in. */
  function invoiceNote() {
    const invoice = state.invoice;
    if (!invoice?.name) return null;
    return h('div', { class: 'guest-banner info', 'data-testid': 'invoice-note' }, icon('receipt', 'sm'),
      h('div', null, h('b', { dir: 'auto' }, t(invoice.status === 'ISSUED' ? 'guest.invoice.issued' : 'guest.invoice.done', { name: invoice.name }))));
  }

  /** What the guest pays now, in kuruş, before the tip. */
  function amountOf(bill) {
    const due = cents(bill.due);
    if (bill.share != null) return Math.min(cents(bill.share), due);
    switch (state.mode) {
      case 'SPLIT': return Math.ceil(due / state.people);
      case 'ITEMS': return Math.min(itemsTotal(bill), due);
      case 'OTHER': return Math.min(state.other || 0, due);
      default: return due;
    }
  }

  function itemsTotal(bill) {
    return bill.lines.reduce((sum, line, i) => sum + Math.round(cents(line.lineTotal) * (state.picked.get(i) || 0) / line.quantity), 0);
  }

  function payForm(bill) {
    const fixed = bill.share != null;
    const amount = amountOf(bill);
    const tip = Math.round(amount * state.tipRate / 100) * 100;
    return h('div', { class: 'guest-pay' },
      state.formError ? banner('error', 'alert', state.formError) : null,
      fixed
        ? h('div', { class: 'guest-share' }, h('span', null, t('guest.yourShare')), h('b', null, money(lira(amount))))
        : [h('div', { class: 'field' }, h('span', { class: 'label', id: 'pay-mode-label' }, t('guest.how')),
          h('div', { class: 'pay-modes', role: 'radiogroup', 'aria-labelledby': 'pay-mode-label' }, MODES.map(([mode, iconName]) => h('button', {
            type: 'button', role: 'radio', class: state.mode === mode ? 'active' : '', 'aria-checked': String(state.mode === mode),
            'data-testid': `pay-mode-${mode}`, onclick: () => { state.mode = mode; haptic(); render(); },
          }, icon(iconName, 'sm'), h('span', null, t(`guest.mode.${mode}`)))))),
        modeDetail(bill, amount)],
      h('div', { class: 'field' }, h('span', { class: 'label' }, t('guest.tip')),
        h('div', { class: 'chips tip-chips' }, TIP_RATES.map((rate) => h('button', {
          type: 'button', class: ['chip', state.tipRate === rate ? 'active' : ''], 'aria-pressed': String(state.tipRate === rate),
          onclick: () => { state.tipRate = rate; render(); },
        }, rate === 0 ? t('bill.noTip') : percent(rate))))),
      h('div', { class: 'field' }, h('label', { for: 'guest-name' }, t('guest.name')),
        h('input', { class: 'input', id: 'guest-name', maxlength: 60, autocomplete: 'name', value: state.name, placeholder: t('guest.namePlaceholder'),
          oninput: (event) => { state.name = event.target.value; } })),
      invoiceForm(),
      h('button', {
        class: 'btn primary lg block', 'data-testid': 'guest-pay', disabled: state.busy || amount <= 0,
        onclick: () => pay(bill, fixed, amount, tip),
      }, icon('card', 'sm'), t('guest.pay', { amount: money(lira(amount + tip)) })),
      h('div', { class: 'faint guest-note' }, t('guest.cardNote')));
  }

  function modeDetail(bill, amount) {
    const due = cents(bill.due);
    if (state.mode === 'ALL') return h('p', { class: 'muted pay-mode-note' }, t('guest.wholeBill'));
    if (state.mode === 'SPLIT') {
      return h('div', { class: 'pay-split' },
        h('span', { class: 'muted' }, t('guest.people')),
        h('div', { class: 'stepper lg' },
          h('button', { type: 'button', 'aria-label': t('pos.decrease', { name: t('guest.people') }), disabled: state.people <= 2,
            onclick: () => { state.people -= 1; haptic(); render(); } }, icon('minus', 'sm')),
          h('span', { 'data-testid': 'split-people' }, state.people),
          h('button', { type: 'button', 'aria-label': t('pos.increase', { name: t('guest.people') }), disabled: state.people >= MAX_PEOPLE,
            onclick: () => { state.people += 1; haptic(); render(); } }, icon('plus', 'sm'))),
        h('b', { class: 'pay-split-each' }, t('guest.eachPays', { amount: money(lira(amount)) })));
    }
    if (state.mode === 'ITEMS') {
      const total = itemsTotal(bill);
      return h('div', { class: 'pay-items' },
        h('p', { class: 'muted pay-mode-note' }, t('guest.itemsHint')),
        bill.lines.map((line, i) => line.quantity > 0 ? itemRow(line, i) : null),
        h('div', { class: 'row pay-items-total' }, h('span', null, t('guest.itemsTotal')), h('b', { 'data-testid': 'items-total' }, money(lira(amount)))),
        total > due ? h('small', { class: 'faint' }, t('guest.itemsCapped')) : null);
    }
    return h('div', { class: 'field' }, h('label', { for: 'guest-amount' }, t('guest.mode.OTHER')),
      h('input', { class: 'input num-input', id: 'guest-amount', inputmode: 'decimal', value: typed(amount),
        onchange: (event) => { state.other = Math.min(parseCents(event.target.value), due); render(); } }));
  }

  function itemRow(line, i) {
    const count = state.picked.get(i) || 0;
    const set = (next) => {
      if (next > 0) state.picked.set(i, next); else state.picked.delete(i);
      haptic();
      render();
    };
    return h('div', { class: ['pay-item', count ? 'picked' : ''], 'data-testid': 'pay-item' },
      h('span', { class: 'emoji' }, line.icon || '🍽️'),
      h('span', { class: 'n' }, h('span', { dir: 'auto' }, line.name), line.options ? h('small', { class: 'opts' }, line.options) : null,
        h('small', { class: 'faint' }, `${money(lira(Math.round(cents(line.lineTotal) / line.quantity)))} · ${count}/${line.quantity}`)),
      h('div', { class: 'stepper lg' },
        h('button', { type: 'button', 'aria-label': t('pos.decrease', { name: line.name }), disabled: count <= 0, onclick: () => set(count - 1) }, icon('minus', 'sm')),
        h('span', null, count),
        h('button', { type: 'button', 'aria-label': t('pos.increase', { name: line.name }), disabled: count >= line.quantity,
          'data-testid': 'pick-item', onclick: () => set(count + 1) }, icon('plus', 'sm'))));
  }

  /** The guest gives their invoice details here instead of telling them to the waiter; asked for once per bill. */
  function invoiceForm() {
    if (!state.invoice?.available || state.invoice.name) return null;
    const field = (key, label, { hint, ...attrs } = {}) => h('div', { class: 'field' }, h('label', { for: `invoice-${key}` }, label),
      h(attrs.rows ? 'textarea' : 'input', { class: attrs.rows ? 'textarea' : 'input', id: `invoice-${key}`, value: state.buyer[key],
        ...attrs, oninput: (event) => { state.buyer[key] = event.target.value; } }),
      hint ? h('small', { class: 'faint' }, hint) : null);
    return h('div', { class: 'guest-invoice' },
      h('label', { class: 'checkbox' },
        h('input', { type: 'checkbox', checked: state.wantInvoice, 'data-testid': 'want-invoice',
          onchange: (event) => { state.wantInvoice = event.target.checked; render(); } }),
        h('span', null, t('guest.invoice.toggle'))),
      state.wantInvoice ? h('div', { class: 'guest-invoice-form' },
        field('name', t('guest.invoice.name'), { maxlength: 120, autocomplete: 'organization' }),
        field('taxId', t('guest.invoice.taxId'), { maxlength: 11, inputmode: 'numeric', autocomplete: 'off', dir: 'ltr', hint: t('guest.invoice.taxIdHint') }),
        field('address', t('guest.invoice.address'), { maxlength: 255, rows: 2, autocomplete: 'street-address' }),
        h('small', { class: 'faint' }, t('guest.invoice.whole'))) : null);
  }

  async function pay(bill, fixed, amount, tip) {
    state.formError = null;
    const buyer = { name: state.buyer.name.trim(), taxId: state.buyer.taxId.replace(/\D/g, ''), address: state.buyer.address.trim() || null };
    if (state.wantInvoice && state.invoice?.available && !state.invoice.name && (!buyer.name || !buyer.taxId)) {
      state.formError = t('guest.invoice.missing');
      haptic('error');
      render();
      return;
    }
    state.busy = true;
    render();
    try {
      if (state.wantInvoice && state.invoice?.available && !state.invoice.name) {
        state.invoice = await api.requestGuestInvoice(token, buyer);
      }
      const { paymentPageUrl } = await api.publicCheckout(token, {
        amount: fixed ? null : lira(amount), tip: lira(tip), name: state.name.trim() || null,
      });
      remember(token);
      // Only the app itself (the simulated provider) or a secure provider page.
      if (/^\/#\//.test(paymentPageUrl)) location.hash = paymentPageUrl.slice(2);
      else if (/^https:\/\//.test(paymentPageUrl)) location.assign(paymentPageUrl);
    } catch (error) {
      state.busy = false;
      state.formError = errorMessage(error);
      haptic('error');
      load();
    }
  }

  render();
  load();
  return () => clearTimeout(timer);
}

function banner(kind, iconName, title, text) {
  return h('div', { class: `guest-banner ${kind}`, role: kind === 'error' ? 'alert' : 'status' },
    icon(iconName, 'sm'), h('div', null, h('b', null, title), text ? h('div', { class: 'sub' }, text) : null));
}

// ---------------------------------------------------------------------------- simulated provider

function simulatedCheckoutPage(root, token) {
  let session = null;
  let failed = false;
  let busy = false;

  async function load() {
    try {
      session = await api.simulatedCheckout(token);
    } catch {
      failed = true;
    }
    render();
  }

  function render() {
    if (failed) {
      mount(root, frame(h('div', { class: 'empty' }, h('div', { class: 'art' }, '🔗'), h('h4', null, t('guest.notFound')))));
      return;
    }
    if (!session) {
      mount(root, frame(h('div', { class: 'skeleton', style: { height: '260px' } })));
      return;
    }
    mount(root, frame(
      h('div', { class: 'sim-badge' }, icon('alert', 'sm'), t('sim.badge')),
      h('div', { class: 'guest-head' }, h('div', { class: 'muted' }, session.description),
        h('h1', { class: 'guest-title' }, money(session.amount))),
      h('div', { class: 'sim-card', 'aria-hidden': 'true' },
        h('div', { class: 'chip-ic' }), h('div', { class: 'num' }, '4242 4242 4242 4242'),
        h('div', { class: 'row' }, h('span', null, 'SOFRA DEMO'), h('span', { class: 'spacer' }), h('span', null, '12/30'))),
      h('p', { class: 'muted' }, t('sim.text')),
      h('div', { class: 'grid-2' },
        h('button', { class: 'btn lg', 'data-testid': 'sim-decline', disabled: busy, onclick: () => decide(false) }, icon('x', 'sm'), t('sim.decline')),
        h('button', { class: 'btn primary lg', 'data-testid': 'sim-approve', disabled: busy, onclick: () => decide(true) }, icon('check', 'sm'), t('sim.approve')))));
  }

  async function decide(approve) {
    busy = true;
    render();
    try {
      const { callbackUrl } = await api.simulatedDecision(token, approve);
      await returnToShop(callbackUrl);
    } catch (error) {
      busy = false;
      render();
      root.querySelector('.guest-card')?.prepend(banner('error', 'alert', errorMessage(error)));
    }
  }

  /** Comes back the way a real provider does: a form POST of the checkout token to the callback URL. */
  async function returnToShop(callbackUrl) {
    const url = new URL(callbackUrl, location.href);
    if (window.SOFRA_BACKEND) {
      // In-browser demo: there is no server to post to, so the demo backend answers the callback itself.
      const response = await backendFetch(`${url.pathname.replace(/^\//, '')}${url.search}`, {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ token }).toString(),
      });
      location.hash = (response.headers.get('Location') || '/').replace(/^\/?#?/, '');
      return;
    }
    const form = h('form', { method: 'post', action: url.href, hidden: true },
      h('input', { type: 'hidden', name: 'token', value: token }));
    document.body.append(form);
    form.submit();
  }

  render();
  load();
  return () => {};
}
