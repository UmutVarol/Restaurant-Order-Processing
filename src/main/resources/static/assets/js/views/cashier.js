// The till: bills still open, and for managers the day's takings (Z report) and the end-of-day cash count.
import { debounce, h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { dateTime, elapsed, money } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { toast } from '../core/ui.js';
import { openBill, parseCents } from './bill.js';
import { withRecentSignIn } from './reauth.js';

const METHOD_ICONS = { CASH: 'cash', CARD: 'card', ONLINE: 'online' };

export default {
  title: 'cashier.title',
  subtitle: 'cashier.sub',
  mount(container, { user }) {
    const manager = user.role === 'ADMIN';
    let bills = null;
    let report = null;
    let date = today();
    const billsCard = h('div', { class: 'card' });
    const reportCard = h('div', { class: 'card' });
    const kpis = h('div', { class: 'kpis cashier-kpis' });
    mount(container, manager ? kpis : null, h('div', { class: manager ? 'cashier-grid' : '' }, billsCard, manager ? reportCard : null));

    function renderBills() {
      const head = h('div', { class: 'card-head' }, h('div', null, h('div', { class: 'card-title' }, t('cashier.openBills')),
        h('div', { class: 'card-sub' }, bills ? t('cashier.openCount', { n: bills.length }) : '')));
      if (!bills) {
        mount(billsCard, head, h('div', { class: 'card-body' }, [1, 2, 3].map(() => h('div', { class: 'skeleton', style: { height: '48px', marginBottom: '8px' } }))));
        return;
      }
      if (!bills.length) {
        mount(billsCard, head, h('div', { class: 'empty' }, h('div', { class: 'art' }, '🧾'), h('h4', null, t('cashier.noOpen'))));
        return;
      }
      mount(billsCard, head, h('div', { class: 'table-wrap' }, h('table', { class: 'data-table' },
        h('thead', null, h('tr', null, h('th', null, t('cashier.who')), h('th', { class: 'hide-sm' }, t('cashier.since')),
          h('th', { class: 'right hide-sm' }, t('bill.total')), h('th', { class: 'right hide-sm' }, t('bill.paidSoFar')), h('th', { class: 'right' }, t('bill.due')))),
        h('tbody', null, bills.map((bill) => h('tr', {
          tabindex: 0, 'data-bill': bill.id,
          onclick: () => openBill({ billId: bill.id }, user),
          onkeydown: (event) => { if (event.key === 'Enter') openBill({ billId: bill.id }, user); },
        },
        h('td', { class: 'customer' }, h('b', null, bill.tableCode ? `${t('pos.table')} ${bill.tableCode}` : bill.customerName),
          h('small', null, `${t('bill.number', { id: bill.id })} · ${bill.orderIds.map((id) => `#${id}`).join(', ')}`)),
        h('td', { class: 'when hide-sm' }, elapsed(bill.openedAt)),
        h('td', { class: 'right num hide-sm' }, money(bill.total)),
        h('td', { class: 'right num hide-sm' }, money(bill.paid)),
        h('td', { class: 'right num' }, h('b', { class: Number(bill.balance) > 0 ? 'due' : 'settled' }, money(bill.balance)),
          Number(bill.pending) > 0 ? h('div', { class: 'faint', style: { fontSize: '11.5px' } }, t('cashier.pendingOnline', { amount: money(bill.pending) })) : null)))))));
    }

    function renderReport() {
      if (!manager) return;
      const picker = h('input', { type: 'date', class: 'input sm date-pick', value: date, max: today(), 'aria-label': t('cashier.date'),
        onchange: (event) => { date = event.target.value || today(); report = null; renderReport(); loadReport(); } });
      const head = h('div', { class: 'card-head' }, h('div', null, h('div', { class: 'card-title' }, t('cashier.zReport')),
        h('div', { class: 'card-sub' }, t('cashier.zSub'))), h('div', { class: 'spacer', style: { flex: 1 } }), picker);
      if (!report) {
        mount(reportCard, head, h('div', { class: 'card-body' }, h('div', { class: 'skeleton', style: { height: '220px' } })));
        mount(kpis, [1, 2, 3, 4].map(() => h('div', { class: 'skeleton', style: { height: '96px', borderRadius: '20px' } })));
        return;
      }
      const summary = report.summary;
      const by = Object.fromEntries(summary.methods.map((m) => [m.method, m]));
      mount(kpis,
        kpi('cash', t('pay.CASH'), money(Number(by.CASH.amount) - Number(by.CASH.refunds)), t('cashier.count', { n: by.CASH.count })),
        kpi('card', t('pay.CARD'), money(Number(by.CARD.amount) - Number(by.CARD.refunds)), t('cashier.count', { n: by.CARD.count })),
        kpi('online', t('pay.ONLINE'), money(Number(by.ONLINE.amount) - Number(by.ONLINE.refunds)), t('cashier.count', { n: by.ONLINE.count })),
        kpi('coins', t('bill.tips'), money(summary.tips), null));

      const row = (cells, cls = '') => h('tr', { class: cls }, cells.map((cell, i) => h(i === 0 ? 'th' : 'td', { class: i ? 'right num' : '' }, cell)));
      mount(reportCard, head, h('div', { class: 'card-body' },
        h('table', { class: 'z-table' },
          h('thead', null, row([t('cashier.method'), t('cashier.payments'), t('cashier.sales'), t('bill.tips'), t('cashier.refunds')])),
          h('tbody', null, summary.methods.map((m) => row([
            h('span', { class: 'row', style: { gap: '6px' } }, icon(METHOD_ICONS[m.method], 'sm'), t(`pay.${m.method}`)),
            m.count, money(m.amount), money(m.tips), Number(m.refunds) > 0 ? `−${money(m.refunds)}` : '–']))),
          h('tfoot', null, row([t('cashier.totalRow'), summary.payments, money(summary.sales), money(summary.tips),
            Number(summary.refunds) > 0 ? `−${money(summary.refunds)}` : '–'], 'total'))),
        h('div', { class: 'expected-cash' }, h('span', null, t('cashier.expectedCash')), h('b', { 'data-testid': 'expected-cash' }, money(summary.expectedCash)),
          h('small', { class: 'faint' }, t('cashier.expectedHint'))),
        report.closing ? closing(report.closing) : closeForm(summary)));
    }

    function closing(c) {
      const variance = Number(c.variance);
      return h('div', { class: 'day-closed' },
        h('div', { class: 'row' }, icon('check', 'sm'), h('b', null, t('cashier.closedBy', { name: c.closedBy, at: dateTime(c.closedAt) }))),
        h('dl', { class: 'kv' },
          h('dt', null, t('cashier.counted')), h('dd', null, money(c.countedCash)),
          h('dt', null, t('cashier.variance')), h('dd', { class: variance === 0 ? '' : variance < 0 ? 'short' : 'over' },
            `${variance > 0 ? '+' : ''}${money(variance)}`),
          c.note ? [h('dt', null, t('cashier.note')), h('dd', null, c.note)] : null));
    }

    function closeForm(summary) {
      const counted = h('input', { class: 'input', id: 'counted-cash', inputmode: 'decimal', placeholder: money(summary.expectedCash) });
      const note = h('input', { class: 'input', id: 'close-note', maxlength: 255, placeholder: t('cashier.notePlaceholder') });
      const variance = h('span', { class: 'faint' });
      counted.addEventListener('input', () => {
        const diff = parseCents(counted.value) - Math.round(Number(summary.expectedCash) * 100);
        variance.textContent = counted.value.trim() ? `${t('cashier.variance')}: ${diff > 0 ? '+' : ''}${money(diff / 100)}` : '';
        variance.className = diff === 0 ? 'faint' : diff < 0 ? 'short' : 'over';
      });
      return h('div', { class: 'close-day' },
        h('div', { class: 'grid-2' },
          h('div', { class: 'field' }, h('label', { for: 'counted-cash' }, t('cashier.counted')), counted, variance),
          h('div', { class: 'field' }, h('label', { for: 'close-note' }, t('cashier.note')), note)),
        h('button', { class: 'btn dark block', 'data-testid': 'close-day', onclick: async () => {
          if (!counted.value.trim()) { counted.classList.add('invalid'); counted.focus(); return; }
          try {
            const closed = await withRecentSignIn(() => api.closeDay(date, (parseCents(counted.value) / 100).toFixed(2), note.value.trim() || null));
            if (!closed) return;
            report = closed;
            toast({ title: t('cashier.dayClosed') });
            renderReport();
          } catch (error) {
            toast({ title: errorMessage(error), kind: 'error' });
          }
        } }, icon('archive', 'sm'), t('cashier.closeDay')));
    }

    function kpi(iconName, label, value, sub) {
      return h('div', { class: 'card kpi' },
        h('div', { class: 'label' }, h('span', { class: 'ic' }, icon(iconName, 'sm')), label),
        h('div', { class: 'row', style: { alignItems: 'baseline' } }, h('div', { class: 'value' }, value), sub ? h('span', { class: 'muted' }, sub) : null));
    }

    async function loadBills() {
      try {
        bills = await api.openBills();
        renderBills();
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    async function loadReport() {
      if (!manager) return;
      const asked = date;
      try {
        const result = await api.dayReport(asked);
        if (asked !== date) return;
        report = result;
        renderReport();
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    const reload = debounce(() => { loadBills(); if (date === today()) loadReport(); }, 250);
    const offPayment = bus.on('payment', reload);
    const offOrder = bus.on('order', reload);
    renderBills();
    renderReport();
    loadBills();
    loadReport();
    return {
      destroy() {
        offPayment();
        offOrder();
        reload.cancel();
      },
    };
  },
};

/** Today in the restaurant's local calendar (the browser's, which is where the till stands). */
function today() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}
