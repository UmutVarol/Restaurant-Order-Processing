// Kitchen display system: live ticket board, oldest first, with SLA timers and one-tap status changes. A screen can
// follow one station (grill, bar...): it then shows only that station's fired dishes, which cooks tick off one by one.
import { debounce, h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { elapsed, minutesSince, number } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus, chime, prefs } from '../core/store.js';
import { toast } from '../core/ui.js';
import { optionsText } from './options.js';
import { advanceLabel, advanceOrder, openOrderDrawer, servedInCourses, STATUS_COLORS, typeBadge } from './shared.js';

const COLUMNS = ['PENDING', 'PREPARING', 'READY', 'OUT_FOR_DELIVERY'];
// Minutes after which a ticket turns amber / red, per column.
const SLA = {
  PENDING: [3, 6], PREPARING: [15, 25], READY: [4, 8], OUT_FOR_DELIVERY: [25, 40],
};
// While food is being made, the station's own preparation target sets the limit; amber comes at this share of it.
const COOKING = new Set(['PENDING', 'PREPARING']);
const WARN_SHARE = 0.7;
const STATION_KEY = 'sofra.kitchen.station';

function savedStation() {
  try { return localStorage.getItem(STATION_KEY) || ''; } catch { return ''; }
}

function saveStation(code) {
  try {
    if (code) localStorage.setItem(STATION_KEY, code); else localStorage.removeItem(STATION_KEY);
  } catch { /* private window: the choice lasts for this visit */ }
}

export default {
  title: 'kitchen.title',
  subtitle: 'kitchen.sub',
  mount(container, { user, actions }) {
    let orders = null;
    let stations = [];
    let station = savedStation();
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

    const stationSelect = h('select', {
      class: 'select', id: 'kitchen-station', 'data-testid': 'kitchen-station', 'aria-label': t('stations.screen'),
      onchange: (event) => { station = event.target.value; saveStation(station); render(); tickTimers(); },
    });
    const renderStations = () => mount(stationSelect,
      h('option', { value: '' }, t('stations.all')),
      stations.map((s) => h('option', { value: s.code, selected: s.code === station }, s.name)));
    renderStations();

    mount(container, h('div', { class: 'toolbar' }, summary, h('span', { class: 'spacer' }), stationSelect), board);

    /** The dishes this screen shows: only fired ones (held courses wait with the waiter), of this station if one is chosen. */
    const visibleLines = (order) => order.lines.filter((line) => line.firedAt && (!station || line.station === station));
    const visibleOrders = () => (orders || []).filter((order) => !station || visibleLines(order).length > 0);
    const active = (line) => line.quantity - (line.voidedQuantity || 0) > 0;

    /** Amber and red limits in minutes: the slowest station's target on this ticket while it cooks, fixed ones after. */
    function limits(order) {
      if (!COOKING.has(order.status)) return SLA[order.status] || [10, 20];
      const targets = visibleLines(order).map((line) => stations.find((s) => s.code === line.station)?.targetMinutes).filter(Boolean);
      if (!targets.length) return SLA[order.status];
      const late = Math.max(...targets);
      return [Math.max(1, Math.round(late * WARN_SHARE)), late];
    }

    /** A course fired later starts its own clock. */
    function since(order) {
      if (!COOKING.has(order.status)) return order.statusSince;
      const fired = visibleLines(order).map((line) => line.firedAt).sort();
      return fired.length ? fired[fired.length - 1] : order.createdAt;
    }

    function render() {
      if (!orders) {
        mount(board, COLUMNS.map(() => h('div', { class: 'kds-col' },
          [1, 2].map(() => h('div', { class: 'skeleton', style: { height: '180px', borderRadius: '16px' } })))));
        return;
      }
      mount(board, COLUMNS.map((status) => {
        const tickets = visibleOrders().filter((o) => o.status === status);
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
      const lines = visibleLines(order);
      const cooking = COOKING.has(order.status);
      const [warn, late] = limits(order);
      const clock = { since: since(order), warn, late };
      const counted = lines.filter(active);
      // Expo view: every dish of the courses fired so far is done.
      const allReady = cooking && counted.length > 0 && counted.every((line) => line.readyAt);
      return h('article', { class: ['ticket', isFresh ? 'fresh' : '', drop ? 'drop' : ''], style: { '--accent': accent }, dataset: { order: order.id } },
        h('div', { class: 'ticket-head' },
          h('button', { class: 'ticket-no', style: { border: 0, background: 'none', padding: 0 }, onclick: () => openOrderDrawer(order.id, user) }, `#${order.id}`),
          typeBadge(order),
          h('span', { class: 'timer', dataset: clock }, icon('clock', 'sm'), h('span', null, ''))),
        h('div', { class: 'sla', dataset: clock }, h('i')),
        h('div', { class: 'ticket-meta' }, h('span', null, order.customerName),
          h('span', null, `${counted.reduce((sum, line) => sum + line.quantity - (line.voidedQuantity || 0), 0)} ×`)),
        order.heldCourses?.length ? h('div', { class: 'held-note', 'data-testid': 'kitchen-held' }, icon('clock', 'sm'),
          t('courses.waiting', { list: order.heldCourses.join(', ') })) : null,
        allReady ? h('div', { class: 'ready-note', 'data-testid': 'course-ready' }, icon('check', 'sm'), t('stations.allReady')) : null,
        order.note ? h('div', { class: 'sticky-note' }, icon('note', 'sm'), order.note) : null,
        h('div', { class: 'ticket-items' }, lines.map((line) => ticketItem(order, line, cooking && active(line)))),
        label ? h('div', { class: 'actions' }, button) : null);
    }

    /** One dish on a ticket; while the order cooks, a tap marks it ready (or takes the mark back). */
    function ticketItem(order, line, tappable) {
      const content = [
        h('span', { class: 'q' }, line.readyAt && tappable ? icon('check', 'sm') : `${line.quantity - (line.voidedQuantity || 0)}×`),
        h('span', { class: 'n' }, `${line.icon || ''} ${line.name}`,
          servedInCourses(order) ? h('span', { class: 'course-chip static' }, t('courses.short', { n: line.course })) : null),
        line.options?.length ? h('span', { class: 'opts' }, optionsText(line.options)) : null,
        line.voidedQuantity ? h('span', { class: 'void-note' }, t('adjust.kitchenVoided', { n: line.voidedQuantity })) : null,
        line.note ? h('span', { class: 'note' }, `↳ ${line.note}`) : null,
      ];
      const classes = ['ticket-item', active(line) ? '' : 'voided', line.readyAt ? 'done' : ''];
      if (!tappable) return h('div', { class: classes }, content);
      return h('button', {
        class: [...classes, 'tap'], type: 'button', 'data-testid': 'kitchen-line', 'aria-pressed': String(Boolean(line.readyAt)),
        title: t(line.readyAt ? 'stations.notReady' : 'stations.markReady'),
        onclick: async (event) => {
          const target = event.currentTarget;
          target.disabled = true;
          try {
            const updated = await api.lineReady(order.id, line.id, !line.readyAt);
            orders = orders.map((o) => (o.id === updated.id ? updated : o));
            render();
            tickTimers();
          } catch (error) {
            toast({ title: errorMessage(error), kind: 'error' });
            target.disabled = false;
          }
        },
      }, content);
    }

    function tickTimers() {
      const now = Date.now();
      board.querySelectorAll('.timer').forEach((timer) => {
        const minutes = minutesSince(timer.dataset.since, now);
        const warn = Number(timer.dataset.warn);
        const late = Number(timer.dataset.late);
        timer.classList.toggle('warn', minutes >= warn && minutes < late);
        timer.classList.toggle('late', minutes >= late);
        timer.lastChild.textContent = elapsed(timer.dataset.since, now);
      });
      // Each ticket's bar shows how much of its time budget is used: green, then amber, then red.
      board.querySelectorAll('.sla').forEach((bar) => {
        const minutes = minutesSince(bar.dataset.since, now);
        const warn = Number(bar.dataset.warn);
        const late = Number(bar.dataset.late);
        bar.style.setProperty('--p', Math.min(1, minutes / late));
        bar.classList.toggle('warn', minutes >= warn && minutes < late);
        bar.classList.toggle('late', minutes >= late);
        bar.closest('.ticket').classList.toggle('is-late', minutes >= late);
      });
    }

    function renderSummary() {
      const shown = visibleOrders();
      const inKitchen = shown.filter((o) => COOKING.has(o.status));
      const waits = inKitchen.map((o) => minutesSince(since(o)));
      const average = waits.length ? waits.reduce((a, b) => a + b, 0) / waits.length : 0;
      const late = shown.filter((o) => COLUMNS.includes(o.status) && minutesSince(since(o)) >= limits(o)[1]).length;
      mount(summary,
        stat('kitchen', shown.filter((o) => COLUMNS.includes(o.status)).length, t('kitchen.active')),
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

    async function loadStations() {
      try {
        stations = await api.stations();
      } catch {
        stations = [];
      }
      // A station removed since this screen chose it: fall back to all of them.
      if (station && !stations.some((s) => s.code === station)) {
        station = '';
        saveStation('');
      }
      renderStations();
      if (orders) { render(); tickTimers(); }
    }

    render();
    load();
    loadStations();
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
