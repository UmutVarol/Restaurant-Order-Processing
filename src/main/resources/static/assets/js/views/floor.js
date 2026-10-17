// Floor plan: every table with its open check, updated live.
import { debounce, h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { elapsed, money, percent } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { toast } from '../core/ui.js';
import { canTakeOrders, openOrderDrawer, statusPill } from './shared.js';

const ZONES = ['INDOOR', 'WINDOW', 'TERRACE'];

export default {
  title: 'floor.title',
  subtitle: 'floor.sub',
  mount(container, { user, navigate }) {
    let tables = null;
    const summary = h('div', { class: 'floor-summary' });
    const zones = h('div');
    mount(container, summary, zones);

    function render() {
      if (!tables) {
        mount(summary, [1, 2, 3, 4].map(() => h('div', { class: 'skeleton', style: { height: '96px', borderRadius: '20px' } })));
        mount(zones, h('div', { class: 'zone-grid' }, Array.from({ length: 8 }, () => h('div', { class: 'skeleton', style: { height: '230px', borderRadius: '20px' } }))));
        return;
      }
      const occupied = tables.filter((table) => table.occupied);
      const seats = tables.reduce((sum, table) => sum + table.seats, 0);
      const seatsInUse = occupied.reduce((sum, table) => sum + table.seats, 0);
      const openTotal = occupied.reduce((sum, table) => sum + Number(table.openTotal), 0);
      mount(summary,
        kpi('floor', t('floor.occupancy'), percent(occupied.length / (tables.length || 1)), `${occupied.length} / ${tables.length}`),
        kpi('receipt', t('floor.openChecks'), money(openTotal), `${occupied.reduce((n, table) => n + table.openOrders.length, 0)} ×`),
        kpi('check', t('floor.freeTables'), tables.length - occupied.length, null),
        kpi('users', t('floor.seatsInUse'), `${seatsInUse}`, `/ ${seats}`));

      mount(zones, ZONES.map((zone) => {
        const inZone = tables.filter((table) => table.zone === zone);
        if (!inZone.length) return null;
        return h('section', { class: 'zone' },
          h('div', { class: 'section-title' }, h('h3', null, t(`zone.${zone}`)),
            h('span', { class: 'faint', style: { fontSize: '12px' } }, `${inZone.filter((x) => x.occupied).length}/${inZone.length}`)),
          h('div', { class: 'zone-grid' }, inZone.map(tableCard)));
      }));
    }

    function tableCard(table) {
      const order = table.openOrders[0];
      const served = table.occupied && table.openOrders.every((o) => o.status === 'SERVED');
      const card = h('button', {
        class: ['table-card', table.occupied ? 'occupied' : '', served ? 'attention' : ''],
        onclick: () => {
          if (order) openOrderDrawer(order.id, user);
          else if (canTakeOrders(user)) navigate('pos', { table: table.id });
        },
      },
      h('div', { class: 'table-viz' }, seatsAround(table.seats), h('div', { class: 'top' }, table.code)),
      h('div', { class: 't-info' }, table.occupied
        ? [
          h('div', { class: 'row', style: { gap: '6px' } }, statusPill(order.status),
            table.openOrders.length > 1 ? h('span', { class: 'pill plain' }, `+${table.openOrders.length - 1}`) : null),
          h('div', { class: 't-amount' }, money(table.openTotal)),
          h('div', { class: 'seats', dataset: { since: table.occupiedSince } }, icon('clock', 'sm'), ' ', elapsed(table.occupiedSince), ` · ${order.customerName}`),
        ]
        : [
          h('div', { class: 't-free' }, t('floor.available')),
          h('div', { class: 'seats' }, t('floor.seats', { n: table.seats })),
          canTakeOrders(user) ? h('span', { class: 'btn sm', style: { marginTop: '4px' } }, icon('plus', 'sm'), t('floor.seat')) : null,
        ]));
      return card;
    }

    function seatsAround(count) {
      const radius = 46;
      return Array.from({ length: count }, (_, i) => {
        const angle = (i / count) * Math.PI * 2 - Math.PI / 2;
        return h('span', {
          class: 'seat',
          style: { transform: `translate(${Math.cos(angle) * radius}px, ${Math.sin(angle) * radius}px) rotate(${angle}rad)` },
        });
      });
    }

    function kpi(iconName, label, value, sub) {
      return h('div', { class: 'card kpi' },
        h('div', { class: 'label' }, h('span', { class: 'ic' }, icon(iconName, 'sm')), label),
        h('div', { class: 'row', style: { alignItems: 'baseline' } }, h('div', { class: 'value' }, value),
          sub ? h('span', { class: 'muted' }, sub) : null));
    }

    async function load() {
      try {
        tables = await api.tables();
        render();
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    const reload = debounce(load, 200);
    const offOrder = bus.on('order', reload);
    const timer = setInterval(() => {
      zones.querySelectorAll('[data-since]').forEach((el) => {
        const node = el.childNodes[2];
        if (node) node.textContent = elapsed(el.dataset.since);
      });
    }, 15_000);

    render();
    load();
    return {
      destroy() {
        offOrder();
        reload.cancel();
        clearInterval(timer);
      },
    };
  },
};
