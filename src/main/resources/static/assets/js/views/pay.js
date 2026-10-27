// Pages a guest sees without signing in: the bill behind a QR code, and the simulated provider's payment page
// used in demos. Card details are only ever entered on the provider's own page, never here.
import { h, mount } from '../core/dom.js';
import { icon, logoMark } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { money, time } from '../core/format.js';
import { api, backendFetch, errorMessage } from '../core/api.js';
import { langButton, themeButton } from '../core/ui.js';
import { parseCents } from './bill.js';

const TIP_RATES = [0, 0.05, 0.1, 0.15];
const lira = (value) => (value / 100).toFixed(2);

/** Renders a guest page into {@code root}. Returns a cleanup function. */
export function renderGuest(root, kind, token) {
  return kind === 'pay' ? payPage(root, token) : simulatedCheckoutPage(root, token);
}

function frame(...content) {
  return h('div', { class: 'guest' },
    h('header', { class: 'guest-top' }, logoMark(), h('b', null, 'Sofra'), h('span', { class: 'spacer' }), langButton(), themeButton()),
    h('main', { class: 'guest-card card' }, content),
    h('footer', { class: 'guest-foot faint' }, icon('info', 'sm'), t('guest.secure')));
}

// ---------------------------------------------------------------------------- the guest's bill

function payPage(root, token) {
  const state = { bill: null, error: null, formError: null, amount: null, tipRate: 0.1, name: '', busy: false };
  let polls = 0;
  let timer = null;

  async function load() {
    try {
      state.bill = await api.publicBill(token);
      state.error = null;
      if (state.amount == null) state.amount = state.bill.share ?? state.bill.due;
      // The provider may take a moment to confirm: look again for a short while.
      clearTimeout(timer);
      if (state.bill.lastPayment?.status === 'PENDING' && polls++ < 20) timer = setTimeout(load, 3000);
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
    mount(root, frame(
      h('div', { class: 'guest-head' },
        h('div', { class: 'muted' }, bill.restaurant),
        h('h1', { class: 'guest-title' }, bill.tableCode ? t('guest.table', { table: bill.tableCode }) : t('guest.yourBill'))),
      status(bill),
      h('div', { class: 'bill-lines guest-lines' }, bill.lines.map((line) => h('div', { class: 'bill-line' },
        h('span', { class: 'emoji' }, line.icon || '🍽️'), h('span', { class: 'n' }, `${line.quantity} × ${line.name}`),
        h('span', { class: 'num' }, money(line.lineTotal))))),
      h('div', { class: 'totals' },
        h('div', { class: 'line' }, h('span', null, t('bill.total')), h('span', null, money(bill.total))),
        Number(bill.paid) > 0 ? h('div', { class: 'line' }, h('span', null, t('bill.paidSoFar')), h('span', null, money(bill.paid))) : null,
        h('div', { class: 'grand' }, h('span', null, t('bill.due')), h('span', { 'data-testid': 'guest-due' }, money(bill.due)))),
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

  function payForm(bill) {
    const due = Math.round(Number(bill.due) * 100);
    const fixed = bill.share != null;
    const amount = Math.min(Math.round(Number(state.amount) * 100) || 0, due);
    const tip = Math.round(amount * state.tipRate / 100) * 100;
    return h('div', { class: 'guest-pay' },
      state.formError ? banner('error', 'alert', state.formError) : null,
      fixed
        ? h('div', { class: 'guest-share' }, h('span', null, t('guest.yourShare')), h('b', null, money(lira(amount))))
        : h('div', { class: 'field' }, h('label', { for: 'guest-amount' }, t('guest.howMuch')),
          h('input', { class: 'input num-input', id: 'guest-amount', inputmode: 'decimal', value: lira(amount),
            onchange: (event) => { state.amount = lira(Math.min(parseCents(event.target.value), due)); render(); } }),
          h('div', { class: 'chips' }, [1, 2, 3, 4].map((n) => h('button', {
            class: ['chip', amount === Math.ceil(due / n) ? 'active' : ''],
            onclick: () => { state.amount = lira(Math.ceil(due / n)); render(); },
          }, n === 1 ? t('bill.full') : t('guest.splitN', { n }))))),
      h('div', { class: 'field' }, h('label', null, t('guest.tip')),
        h('div', { class: 'chips' }, TIP_RATES.map((rate) => h('button', {
          class: ['chip', state.tipRate === rate ? 'active' : ''], onclick: () => { state.tipRate = rate; render(); },
        }, rate === 0 ? t('bill.noTip') : `%${Math.round(rate * 100)}`)))),
      h('div', { class: 'field' }, h('label', { for: 'guest-name' }, t('guest.name')),
        h('input', { class: 'input', id: 'guest-name', maxlength: 60, autocomplete: 'name', value: state.name, placeholder: t('guest.namePlaceholder'),
          oninput: (event) => { state.name = event.target.value; } })),
      h('button', {
        class: 'btn primary lg block', 'data-testid': 'guest-pay', disabled: state.busy || amount <= 0,
        onclick: async () => {
          state.busy = true;
          state.formError = null;
          render();
          try {
            const { paymentPageUrl } = await api.publicCheckout(token, {
              amount: fixed ? null : lira(amount), tip: lira(tip), name: state.name.trim() || null,
            });
            // Only the app itself or a secure provider page.
            if (/^\/#\//.test(paymentPageUrl) || /^https:\/\//.test(paymentPageUrl)) location.assign(paymentPageUrl);
          } catch (error) {
            state.busy = false;
            state.formError = errorMessage(error);
            load();
          }
        },
      }, icon('card', 'sm'), t('guest.pay', { amount: money(lira(amount + tip)) })),
      h('div', { class: 'faint guest-note' }, t('guest.cardNote')));
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
