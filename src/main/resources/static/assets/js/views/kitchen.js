// Kitchen display system: live ticket board, oldest first, with SLA timers and one-tap status changes.
import { debounce, h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { elapsed, minutesSince, number } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus, chime, prefs } from '../core/store.js';
import { toast } from '../core/ui.js';
import { optionsText } from './options.js';
import { advanceLabel, advanceOrder, openOrderDrawer, STATUS_COLORS, typeBadge } from './shared.js';

const COLUMNS = ['PENDING', 'PREPARING', 'READY', 'OUT_FOR_DELIVERY'];
// Minutes after which a ticket turns amber / red, per column.
const SLA = {
  PENDING: [3, 6], PREPARING: [15, 25], READY: [4, 8], OUT_FOR_DELIVERY: [25, 40],
};

export default {
  title: 'kitchen.title',
  subtitle: 'kitchen.sub',
  mount(container, { user, actions }) {
    let orders = null;
    const fresh = new Set();
    // Tickets that already played their arrival animation; a re-render (every status change) must not replay it.
    const arrived = new Set();
    const board = h('div', { class: 'kds' });
    const summary = h('div', { class: 'kds-summary' });

    const soundButton = h('button', { class: 'btn sm', title: t('kitchen.sound'), 'aria-label': t('kitchen.sound') });
    const renderSound = () => {
      soundButton.setAttribute('aria-pressed', String(prefs.sound));
      mount(soundButton, icon(prefs.sound ? 'sound' : 'mute', 'sm'), h('span', { class: 'btn-label' }, t('kitchen.sound')));
    };
    soundButton.addEventListener('click', () => {
      prefs.sound = !prefs.sound;
      if (prefs.sound) chime();
      renderSound();
    });
    renderSound();
    mount(actions, soundButton);

    mount(container, h('div', { class: 'toolbar' }, summary), board);

    function render() {
      if (!orders) {
        mount(board, COLUMNS.map(() => h('div', { class: 'kds-col' },
          [1, 2].map(() => h('div', { class: 'skeleton', style: { height: '180px', borderRadius: '16px' } })))));
        return;
      }
      mount(board, COLUMNS.map((status) => {
        const tickets = orders.filter((o) => o.status === status);
        return h('section', { class: 'kds-col', 'aria-label': t(`status.${status}`) },
          h('div', { class: 'kds-col-head' },
            h('span', { class: 'dot', style: { background: STATUS_COLORS[status] } }),
            h('h3', null, t(`status.${status}`)),
            h('span', { class: 'count' }, tickets.length)),
          tickets.length ? tickets.map(ticket) : h('div', { class: 'empty' }, h('div', { class: 'art', style: { fontSize: '30px' } }, '✨'),
            h('div', null, t('kitchen.allClearHint'))));
      }));
      renderSummary();
    }

    function ticket(order) {
      const accent = STATUS_COLORS[order.status];
      const label = advanceLabel(order);
      const button = h('button', {
        class: `btn sm ${order.status === 'PENDING' ? 'primary' : order.status === 'PREPARING' ? 'success' : ''}`,
        onclick: async (event) => {
          event.stopPropagation();
          button.disabled = true;
          if (await advanceOrder(order, user)) await load();
          button.disabled = false;
        },
      }, label, icon('arrowRight', 'sm'));
      const isFresh = fresh.has(order.id);
      const drop = isFresh && !arrived.has(order.id);
      if (isFresh) arrived.add(order.id);
      return h('article', { class: ['ticket', isFresh ? 'fresh' : '', drop ? 'drop' : ''], style: { '--accent': accent } },
        h('div', { class: 'ticket-head' },
          h('button', { class: 'ticket-no', style: { border: 0, background: 'none', padding: 0 }, onclick: () => openOrderDrawer(order.id, user) }, `#${order.id}`),
          typeBadge(order),
          h('span', { class: 'timer', dataset: { since: order.status === 'READY' || order.status === 'OUT_FOR_DELIVERY' ? order.statusSince : order.createdAt, status: order.status } },
            icon('clock', 'sm'), h('span', null, ''))),
        h('div', { class: 'sla', dataset: { since: order.status === 'READY' || order.status === 'OUT_FOR_DELIVERY' ? order.statusSince : order.createdAt, status: order.status } }, h('i')),
        h('div', { class: 'ticket-meta' }, h('span', null, order.customerName), h('span', null, `${order.itemCount} ×`)),
        order.note ? h('div', { class: 'sticky-note' }, icon('note', 'sm'), order.note) : null,
        h('div', { class: 'ticket-items' }, order.lines.map((line) => h('div', { class: ['ticket-item', line.quantity === line.voidedQuantity ? 'voided' : ''] },
          h('span', { class: 'q' }, `${line.quantity - (line.voidedQuantity || 0)}×`),
          h('span', { class: 'n' }, `${line.icon || ''} ${line.name}`),
          line.options?.length ? h('span', { class: 'opts' }, optionsText(line.options)) : null,
          line.voidedQuantity ? h('span', { class: 'void-note' }, t('adjust.kitchenVoided', { n: line.voidedQuantity })) : null,
          line.note ? h('span', { class: 'note' }, `↳ ${line.note}`) : null))),
        label ? h('div', { class: 'actions' }, button) : null);
    }

    function tickTimers() {
      const now = Date.now();
      board.querySelectorAll('.timer').forEach((timer) => {
        const minutes = minutesSince(timer.dataset.since, now);
        const [warn, late] = SLA[timer.dataset.status] || [10, 20];
        timer.classList.toggle('warn', minutes >= warn && minutes < late);
        timer.classList.toggle('late', minutes >= late);
        timer.lastChild.textContent = elapsed(timer.dataset.since, now);
      });
      // Each ticket's bar shows how much of its time budget is used: green, then amber, then red.
      board.querySelectorAll('.sla').forEach((bar) => {
        const minutes = minutesSince(bar.dataset.since, now);
        const [warn, late] = SLA[bar.dataset.status] || [10, 20];
        bar.style.setProperty('--p', Math.min(1, minutes / late));
        bar.classList.toggle('warn', minutes >= warn && minutes < late);
        bar.classList.toggle('late', minutes >= late);
        bar.closest('.ticket').classList.toggle('is-late', minutes >= late);
      });
    }

    function renderSummary() {
      const inKitchen = orders.filter((o) => o.status === 'PENDING' || o.status === 'PREPARING');
      const waits = inKitchen.map((o) => minutesSince(o.createdAt));
      const average = waits.length ? waits.reduce((a, b) => a + b, 0) / waits.length : 0;
      const late = orders.filter((o) => {
        const [, limit] = SLA[o.status] || [10, 20];
        const since = o.status === 'READY' || o.status === 'OUT_FOR_DELIVERY' ? o.statusSince : o.createdAt;
        return COLUMNS.includes(o.status) && minutesSince(since) >= limit;
      }).length;
      mount(summary,
        stat('kitchen', orders.filter((o) => COLUMNS.includes(o.status)).length, t('kitchen.active')),
        stat('timer', t('common.minutes', { n: number(average) }), t('kitchen.avgWait')),
        stat('alert', late, t('kitchen.late'), late > 0 ? 'var(--red)' : null));
    }

    function stat(iconName, value, label, color) {
      return h('div', { class: 'stat' }, h('span', { style: { color: color || 'var(--brand)' } }, icon(iconName, 'sm')),
        h('b', { style: color ? { color } : null }, value), label);
    }

    async function load() {
      try {
        orders = await api.activeOrders();
        render();
        tickTimers();
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    const reload = debounce(load, 150);
    const timeouts = new Set();
    const offOrder = bus.on('order', (event) => {
      if (event.kind === 'CREATED') {
        fresh.add(event.orderId);
        const timeout = setTimeout(() => {
          timeouts.delete(timeout);
          fresh.delete(event.orderId);
          board.querySelectorAll('.ticket.fresh').forEach((el) => el.classList.remove('fresh'));
        }, 8000);
        timeouts.add(timeout);
        if (prefs.sound) chime();
        toast({
          title: t('kitchen.newOrder', { id: event.orderId }),
          text: t('kitchen.newOrderSub', {
            type: event.tableCode ? `${t('pos.table')} ${event.tableCode}` : t(`type.${event.orderType}`), customer: event.customerName,
          }),
          kind: 'info',
        });
      }
      reload();
    });
    const timer = setInterval(() => { tickTimers(); if (orders) renderSummary(); }, 1000);

    render();
    load();
    return {
      destroy() {
        offOrder();
        clearInterval(timer);
        timeouts.forEach(clearTimeout);
        reload.cancel();
      },
    };
  },
};
