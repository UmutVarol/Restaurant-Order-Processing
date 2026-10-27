// The bill: what a table or order owes, split it, take cash or card, show a QR code for paying by phone, refund.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { locale, t } from '../core/i18n.js';
import { dateTime, money, time } from '../core/format.js';
import { api, ApiError, errorMessage, newIdempotencyKey } from '../core/api.js';
import { bus } from '../core/store.js';
import { openModal, toast } from '../core/ui.js';
import { qrCode } from './qr.js';
import { withRecentSignIn } from './reauth.js';

const METHODS = [
  { id: 'CASH', icon: 'cash' },
  { id: 'CARD', icon: 'card' },
  { id: 'QR', icon: 'online' },
];
const TIP_RATES = [0, 0.05, 0.1, 0.15];

const cents = (value) => Math.round(Number(value || 0) * 100);
const lira = (value) => (value / 100).toFixed(2);
/** Kuruş as typed in the till's language: "401,50" in Turkish, "401.50" in English. */
export const typed = (value) => (value / 100).toLocaleString(locale(), { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false });

/**
 * Opens the bill of an order (or a bill by id) for staff who take payments.
 *
 * @param {{orderId?: number, billId?: number}} ref
 */
export function openBill(ref, user) {
  let bill = null;
  let failed = false;
  // Form state survives live refreshes, so a waiter mid-entry never loses what they typed.
  const state = { method: 'CASH', amount: null, tipRate: 0, tip: '', tendered: '', approval: '', split: 1, picked: new Set(), link: null, busy: false, key: newIdempotencyKey() };

  const body = h('div', { class: 'bill' });
  const modal = openModal({
    title: t('bill.title'),
    size: 'lg',
    body,
    onClose: () => { offPayment(); offOrder(); },
  });

  async function load() {
    try {
      bill = ref.billId ? await api.bill(ref.billId) : await api.billForOrder(ref.orderId);
      ref = { billId: bill.id };
      failed = false;
      if (state.amount == null || cents(state.amount) > cents(bill.available)) state.amount = bill.available;
    } catch (error) {
      failed = true;
      if (error instanceof ApiError && error.status === 403) toast({ title: errorMessage(error), kind: 'error' });
    }
    render();
  }

  const offPayment = bus.on('payment', (event) => { if (bill && event.billId === bill.id) load(); });
  const offOrder = bus.on('order', (event) => { if (bill?.orders.some((order) => order.id === event.orderId)) load(); });

  function render() {
    if (failed) {
      mount(body, h('div', { class: 'empty' }, h('div', { class: 'art' }, '😕'), h('h4', null, t('common.loadFailed')),
        h('button', { class: 'btn', onclick: load }, icon('refresh', 'sm'), t('common.retry'))));
      return;
    }
    if (!bill) {
      mount(body, h('div', { class: 'bill-grid' }, [260, 320].map((height) => h('div', { class: 'skeleton', style: { height: `${height}px` } }))));
      return;
    }
    const open = bill.status === 'OPEN';
    mount(body,
      h('div', { class: 'bill-head' },
        h('div', null,
          h('div', { class: 'bill-no' }, t('bill.number', { id: bill.id }),
            bill.table ? h('span', { class: 'type-badge' }, icon('dinein', 'sm'), `${t('pos.table')} ${bill.table.code}`) : null,
            h('span', { class: `pill b-${bill.status}` }, t(`bill.status.${bill.status}`))),
          h('div', { class: 'muted', style: { fontSize: '12.5px' } }, t('bill.opened', { at: dateTime(bill.openedAt) }))),
        h('div', { class: 'bill-due' },
          h('span', null, open ? t('bill.due') : t('bill.paid')),
          h('b', { 'data-testid': 'bill-due' }, money(open ? bill.balance : bill.paid)))),
      h('div', { class: 'bill-grid' },
        h('div', { class: 'bill-left' }, ordersSection(), summary(), paymentsSection()),
        open ? payPanel() : closedPanel()));
  }

  // ------------------------------------------------------------------------- left column

  function ordersSection() {
    const splitting = state.split === 'items';
    return h('section', null,
      h('div', { class: 'section-title' }, h('h3', null, t('bill.items')),
        splitting ? h('span', { class: 'faint', style: { fontSize: '12px' } }, t('bill.pickHint')) : null),
      h('div', { class: 'bill-lines' }, bill.orders.map((order) => h('div', { class: ['bill-order', order.status === 'CANCELLED' ? 'cancelled' : ''] },
        h('div', { class: 'bill-order-head' }, h('b', null, `#${order.id}`), h('span', { class: `pill s-${order.status}` }, t(`status.${order.status}`)),
          h('span', { class: 'spacer' }), h('span', { class: 'num' }, money(order.total))),
        order.lines.map((line, index) => {
          const id = `${order.id}:${index}`;
          const content = [h('span', { class: 'emoji' }, line.icon || '🍽️'), h('span', { class: 'n' }, `${line.quantity} × ${line.name}`),
            h('span', { class: 'num' }, money(line.lineTotal))];
          if (!splitting || order.status === 'CANCELLED') return h('div', { class: 'bill-line' }, content);
          return h('label', { class: ['bill-line', 'pick', state.picked.has(id) ? 'on' : ''] },
            h('input', { type: 'checkbox', class: 'checkbox', checked: state.picked.has(id), onchange: (event) => {
              if (event.target.checked) state.picked.add(id); else state.picked.delete(id);
              state.amount = lira(Math.min(pickedShare(), cents(bill.available)));
              render();
            } }), content);
        })))));
  }

  /** Selected lines priced like the order: tax, discount and delivery fee shared out in proportion. */
  function pickedShare() {
    let total = 0;
    for (const order of bill.orders) {
      const subtotal = cents(order.subtotal);
      order.lines.forEach((line, index) => {
        if (state.picked.has(`${order.id}:${index}`) && subtotal > 0) {
          total += Math.round(cents(line.lineTotal) * cents(order.total) / subtotal);
        }
      });
    }
    return total;
  }

  function summary() {
    const row = (label, value, cls = '') => h('div', { class: `line ${cls}` }, h('span', null, label), h('span', null, value));
    return h('div', { class: 'totals' },
      row(t('bill.total'), money(bill.total)),
      row(t('bill.paidSoFar'), money(bill.paid)),
      Number(bill.tips) > 0 ? row(t('bill.tips'), money(bill.tips)) : null,
      Number(bill.refunded) > 0 ? row(t('bill.refunded'), `−${money(bill.refunded)}`, 'discount') : null,
      Number(bill.pending) > 0 ? row(t('bill.pending'), money(bill.pending)) : null,
      h('div', { class: 'grand' }, h('span', null, t('bill.due')), h('span', null, money(Math.max(0, Number(bill.balance))))));
  }

  function paymentsSection() {
    if (!bill.payments.length) return null;
    return h('section', null,
      h('div', { class: 'section-title' }, h('h3', null, t('bill.payments'))),
      h('div', { class: 'bill-payments' }, bill.payments.slice().reverse().map((payment) => h('div', { class: ['bill-payment', `p-${payment.status}`] },
        h('div', { class: 'ic' }, icon(payment.method === 'CASH' ? 'cash' : payment.method === 'CARD' ? 'card' : 'online', 'sm')),
        h('div', { class: 'grow' },
          h('div', null, h('b', null, money(payment.amount)), Number(payment.tip) > 0 ? h('span', { class: 'muted' }, ` + ${t('bill.tip')} ${money(payment.tip)}`) : null),
          h('div', { class: 'sub' }, [
            t(`pay.${payment.method}`),
            payment.approvalCode ? `${t('bill.approval')} ${payment.approvalCode}` : null,
            payment.changeDue && Number(payment.changeDue) > 0 ? `${t('bill.change')} ${money(payment.changeDue)}` : null,
            time(payment.completedAt || payment.createdAt),
            payment.viaLink ? t('bill.byGuest') : payment.createdBy,
          ].filter(Boolean).join(' · ')),
          payment.failureReason && payment.status !== 'SUCCEEDED' ? h('div', { class: 'sub warn' }, payment.failureReason) : null,
          payment.refunds.map((refund) => h('div', { class: 'sub refund' }, icon('refresh', 'sm'),
            `${t('bill.refund')} ${money(refund.amount)} · ${t(`refund.${refund.status}`)} · ${refund.reason}`))),
        payment.status === 'SUCCEEDED' ? null : h('span', { class: `pill p-${payment.status}` },
          payment.underReview ? t('payment.REVIEW') : t(`payment.${payment.status}`)),
        payment.status === 'PENDING' && !payment.underReview && bill.status === 'OPEN'
          ? h('button', { class: 'btn sm ghost', 'data-testid': 'release-payment', title: t('bill.releaseHint'), onclick: () => release(payment) },
            t('bill.release')) : null,
        user.role === 'ADMIN' && Number(payment.refundable) > 0
          ? h('button', { class: 'btn sm ghost', onclick: () => refundDialog(payment) }, t('bill.refund')) : null))));
  }

  // ------------------------------------------------------------------------- taking payment

  function payPanel() {
    const available = cents(bill.available);
    const amount = Math.min(cents(state.amount), available);
    const tip = tipCents(amount);
    const panel = h('div', { class: 'bill-pay card' });

    if (available <= 0) {
      return mount(panel, h('div', { class: 'empty' }, h('div', { class: 'art' }, '⏳'), h('h4', null, t('bill.waitingOnline')),
        h('p', null, t('bill.waitingOnlineText', { amount: money(bill.pending) }))));
    }

    const amountInput = h('input', {
      class: 'input num-input', id: 'pay-amount', inputmode: 'decimal', value: typed(amount), 'aria-label': t('bill.amount'),
      onchange: (event) => { state.amount = parseAmount(event.target.value, available); state.split = 1; state.picked.clear(); render(); },
    });

    const splitChips = h('div', { class: 'chips' },
      h('button', { class: ['chip', state.split === 1 && !state.picked.size ? 'active' : ''], onclick: () => { state.split = 1; state.picked.clear(); state.amount = bill.available; render(); } }, t('bill.full')),
      h('span', { class: ['chip', 'split-chip', typeof state.split === 'number' && state.split > 1 ? 'active' : ''] },
        t('bill.equal'),
        h('span', { class: 'stepper' },
          h('button', { 'aria-label': t('bill.fewer'), disabled: !(state.split > 2), onclick: () => setSplit(Math.max(2, (Number(state.split) || 2) - 1)) }, icon('minus', 'sm')),
          h('span', null, typeof state.split === 'number' && state.split > 1 ? state.split : '–'),
          h('button', { 'aria-label': t('bill.more'), onclick: () => setSplit(Math.min(20, (typeof state.split === 'number' ? state.split : 1) + 1)) }, icon('plus', 'sm')))),
      h('button', { class: ['chip', state.split === 'items' ? 'active' : ''], onclick: () => { state.split = 'items'; state.picked.clear(); state.amount = '0.00'; render(); } }, t('bill.byItems')));

    const tipChips = h('div', { class: 'chips' }, TIP_RATES.map((rate) => h('button', {
      class: ['chip', state.tip === '' && state.tipRate === rate ? 'active' : ''],
      onclick: () => { state.tipRate = rate; state.tip = ''; render(); },
    }, rate === 0 ? t('bill.noTip') : `%${Math.round(rate * 100)}`)),
    h('input', {
      class: 'input sm tip-input', inputmode: 'decimal', placeholder: t('bill.otherTip'), value: state.tip === '' ? '' : typed(parseCents(state.tip)), 'aria-label': t('bill.otherTip'),
      onchange: (event) => { state.tip = event.target.value.trim() ? lira(parseCents(event.target.value)) : ''; render(); },
    }));

    mount(panel,
      h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': t('bill.method') }, METHODS.map((method) => h('button', {
        role: 'radio', 'aria-checked': state.method === method.id, class: state.method === method.id ? 'active' : '',
        'data-method': method.id,
        onclick: () => { state.method = method.id; state.link = null; render(); },
      }, icon(method.icon, 'sm'), t(`bill.method.${method.id}`)))),
      h('div', { class: 'field' }, h('label', { for: 'pay-amount' }, t('bill.amount')), amountInput,
        h('div', { class: 'faint', style: { fontSize: '12px' } }, t('bill.ofDue', { due: money(bill.available) }))),
      splitChips,
      h('div', { class: 'field' }, h('label', null, t('bill.tip')), tipChips),
      state.method === 'CASH' ? cashFields(amount + tip) : null,
      state.method === 'CARD' ? h('div', { class: 'field' }, h('label', { for: 'pay-approval' }, t('bill.approvalCode')),
        h('input', { class: 'input', id: 'pay-approval', maxlength: 12, placeholder: t('bill.approvalPlaceholder'), value: state.approval,
          oninput: (event) => { state.approval = event.target.value.replace(/[^A-Za-z0-9]/g, ''); event.target.value = state.approval; } }),
        h('div', { class: 'faint', style: { fontSize: '12px' } }, t('bill.approvalHint'))) : null,
      state.method === 'QR' ? qrSection(amount) : null,
      state.method === 'QR' ? null : h('button', {
        class: 'btn primary lg block', 'data-testid': 'take-payment', disabled: state.busy || amount <= 0,
        onclick: () => take(amount, tip),
      }, icon('check', 'sm'), t('bill.take', { amount: money(lira(amount + tip)) })));
    return panel;
  }

  function cashFields(charge) {
    const tendered = state.tendered === '' ? charge : parseCents(state.tendered);
    const change = tendered - charge;
    const notes = [...new Set([charge, ...[50, 100, 200, 500, 1000].map((note) => Math.ceil(charge / (note * 100)) * note * 100)])]
      .filter((value) => value >= charge).sort((a, b) => a - b).slice(0, 4);
    return h('div', { class: 'field' },
      h('label', { for: 'pay-tendered' }, t('bill.tendered')),
      h('div', { class: 'chips' }, notes.map((value) => h('button', {
        class: ['chip', tendered === value ? 'active' : ''], onclick: () => { state.tendered = lira(value); render(); },
      }, value === charge ? t('bill.exact') : money(lira(value)))),
      h('input', { class: 'input sm tip-input', id: 'pay-tendered', inputmode: 'decimal', placeholder: t('bill.otherAmount'), value: state.tendered === '' ? '' : typed(parseCents(state.tendered)),
        onchange: (event) => { state.tendered = event.target.value.trim() ? lira(parseCents(event.target.value)) : ''; render(); } })),
      h('div', { class: ['change', change < 0 ? 'short' : ''] },
        h('span', null, change < 0 ? t('bill.short') : t('bill.change')), h('b', { 'data-testid': 'change-due' }, money(lira(Math.abs(change))))));
  }

  function setSplit(n) {
    state.split = n;
    state.picked.clear();
    state.amount = lira(Math.ceil(cents(bill.available) / n));
    render();
  }

  function tipCents(amount) {
    if (state.tip !== '') return Math.min(parseCents(state.tip), amount);
    return Math.round(amount * state.tipRate / 100) * 100;
  }

  async function take(amount, tip) {
    const payment = { method: state.method, amount: lira(amount), tip: lira(tip) };
    if (state.method === 'CASH') payment.tendered = state.tendered === '' ? lira(amount + tip) : state.tendered;
    if (state.method === 'CARD' && state.approval) payment.approvalCode = state.approval;
    state.busy = true;
    render();
    try {
      const result = await api.takePayment(bill.id, payment, state.key);
      state.key = newIdempotencyKey();
      bill = result.bill;
      Object.assign(state, { amount: bill.available, tendered: '', approval: '', tipRate: 0, tip: '', split: 1 });
      state.picked.clear();
      const change = Number(result.payment.changeDue || 0);
      toast({
        title: t('bill.taken', { amount: money(result.payment.amount) }),
        text: change > 0 ? `${t('bill.change')}: ${money(change)}` : bill.status === 'CLOSED' ? t('bill.closed') : null,
      });
    } catch (error) {
      // A network failure may have reached the server: keep the key, so trying again cannot charge twice.
      if (!(error instanceof ApiError) || error.status !== 0) state.key = newIdempotencyKey();
      toast({ title: errorMessage(error), kind: 'error' });
    } finally {
      state.busy = false;
      render();
    }
  }

  // ------------------------------------------------------------------------- QR

  function qrSection(amount) {
    if (state.link) {
      return h('div', { class: 'qr-box', 'data-testid': 'qr-box' },
        qrCode(state.link.url),
        h('div', { class: 'qr-info' },
          h('b', null, state.link.amount ? t('bill.qrShare', { amount: money(state.link.amount) }) : t('bill.qrAll')),
          h('div', { class: 'muted' }, t('bill.qrScan')),
          h('div', { class: 'faint' }, t('bill.qrExpires', { at: time(state.link.expiresAt) })),
          h('div', { class: 'row', style: { gap: '8px', flexWrap: 'wrap' } },
            h('button', { class: 'btn sm', onclick: () => copy(state.link.url) }, icon('note', 'sm'), t('bill.copyLink')),
            h('a', { class: 'btn sm ghost', href: state.link.url, target: '_blank', rel: 'noopener' }, icon('eye', 'sm'), t('bill.openLink')),
            h('button', { class: 'btn sm ghost', onclick: () => { state.link = null; render(); } }, t('bill.newLink')))));
    }
    const share = amount < cents(bill.available);
    return h('div', { class: 'qr-box empty-qr' },
      h('p', { class: 'muted' }, share ? t('bill.qrShareText', { amount: money(lira(amount)) }) : t('bill.qrAllText')),
      h('button', { class: 'btn primary lg block', 'data-testid': 'create-link', disabled: state.busy, onclick: async () => {
        state.busy = true;
        render();
        try {
          state.link = await api.createPaymentLink(bill.id, share ? lira(amount) : null);
        } catch (error) {
          toast({ title: errorMessage(error), kind: 'error' });
        } finally {
          state.busy = false;
          render();
        }
      } }, icon('online', 'sm'), t('bill.showQr')));
  }

  async function copy(text) {
    try {
      await navigator.clipboard.writeText(text);
      toast({ title: t('bill.copied'), kind: 'info', timeout: 2000 });
    } catch {
      toast({ title: text, kind: 'info' });
    }
  }

  // ------------------------------------------------------------------------- after closing

  function closedPanel() {
    return h('div', { class: 'bill-pay card' },
      h('div', { class: 'empty' }, h('div', { class: 'art' }, bill.status === 'VOID' ? '🧾' : '✅'),
        h('h4', null, t(`bill.status.${bill.status}`)),
        bill.closedAt ? h('p', null, t('bill.closedAt', { at: dateTime(bill.closedAt) })) : null),
      h('button', { class: 'btn block', onclick: modal.close }, t('common.close')));
  }

  async function release(payment) {
    try {
      bill = await api.releasePayment(bill.id, payment.id);
      state.amount = bill.available;
      toast({ title: t('bill.released', { amount: money(payment.amount) }), kind: 'info' });
    } catch (error) {
      toast({ title: errorMessage(error), kind: 'error' });
    }
    render();
  }

  function refundDialog(payment) {
    const amountInput = h('input', { class: 'input', id: 'refund-amount', inputmode: 'decimal', value: typed(cents(payment.refundable)) });
    const reasonInput = h('textarea', { class: 'textarea', id: 'refund-reason', rows: 2, maxlength: 255, placeholder: t('bill.refundReasonPlaceholder') });
    openModal({
      title: t('bill.refundTitle', { amount: money(payment.amount) }),
      size: 'sm',
      body: [
        h('p', { class: 'muted' }, payment.method === 'ONLINE' ? t('bill.refundOnline') : t('bill.refundTill')),
        h('div', { class: 'field' }, h('label', { for: 'refund-amount' }, t('bill.amount')), amountInput),
        h('div', { class: 'field' }, h('label', { for: 'refund-reason' }, t('bill.refundReason')), reasonInput),
      ],
      footer: (handle) => [
        h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')),
        h('button', { class: 'btn danger-solid', onclick: async () => {
          if (!reasonInput.value.trim()) { reasonInput.classList.add('invalid'); reasonInput.focus(); return; }
          try {
            const done = await withRecentSignIn(() => api.refund(payment.id, lira(parseCents(amountInput.value)), reasonInput.value.trim()));
            if (!done) return;
            toast({ title: t('bill.refunded'), kind: 'info' });
            handle.close();
            load();
          } catch (error) {
            toast({ title: errorMessage(error), kind: 'error' });
          }
        } }, t('bill.refund')),
      ],
    });
  }

  render();
  load();
  return modal;
}

/** "1.234,50", "1234.5" or "1 234,50" → kuruş. */
export function parseCents(text) {
  let value = String(text ?? '').replace(/[^\d.,]/g, '');
  const comma = value.lastIndexOf(',');
  const dot = value.lastIndexOf('.');
  const decimal = Math.max(comma, dot);
  if (decimal >= 0 && value.length - decimal - 1 <= 2) {
    value = `${value.slice(0, decimal).replace(/[.,]/g, '')}.${value.slice(decimal + 1)}`;
  } else {
    value = value.replace(/[.,]/g, '');
  }
  const number = Number(value);
  return Number.isFinite(number) ? Math.round(number * 100) : 0;
}

function parseAmount(text, max) {
  return lira(Math.min(parseCents(text), max));
}
