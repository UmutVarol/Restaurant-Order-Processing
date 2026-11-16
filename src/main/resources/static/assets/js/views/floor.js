// Floor plan: every table with its open check, updated live.
import { debounce, h, mount, s } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t, tn } from '../core/i18n.js';
import { elapsed, minutesSince, money, percent, time } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { openModal, reducedMotion, toast } from '../core/ui.js';
import { canTakeOrders, openOrderDrawer, statusPill } from './shared.js';

const ZONES = ['INDOOR', 'WINDOW', 'TERRACE'];

/** What each table looked like at the last render (A3), kept while the app runs so a state change can be shown. */
const lastStates = new Map();

// F1: how long the guests at each table have been without anyone seeing to them, as a ring that fills; amber and red
// at the limits chosen on this device. Crossing a limit rings the table once; it never blinks.
const WAIT_KEY = 'sofra.floor.wait';
const WAIT_CHOICES = { '8-15': [8, 15], '12-20': [12, 20], '15-25': [15, 25], off: null };
const RING_MINUTES = 30;
const lastWait = new Map();

function waitLimits() {
  let saved = '12-20';
  try { saved = localStorage.getItem(WAIT_KEY) || saved; } catch { /* private window */ }
  return saved in WAIT_CHOICES ? saved : '12-20';
}

function waitState(minutes, limits) {
  if (!limits) return 'ok';
  return minutes >= limits[1] ? 'long' : minutes >= limits[0] ? 'look' : 'ok';
}

/** The ring, the minutes and a word; the word and the colour change together, so it reads without colour too. */
function waitRing(since, limits) {
  const minutes = minutesSince(since);
  const state = waitState(minutes, limits);
  const r = 9;
  const length = 2 * Math.PI * r;
  const share = Math.min(1, minutes / RING_MINUTES);
  return h('span', { class: `wait wait-${state}`, dataset: { attention: since }, 'data-testid': 'table-wait' },
    s('svg', { viewBox: '0 0 24 24', class: 'wait-ring', 'aria-hidden': 'true' },
      s('circle', { class: 'wait-track', cx: 12, cy: 12, r }),
      s('circle', { class: 'wait-arc', cx: 12, cy: 12, r, 'stroke-dasharray': length.toFixed(2), 'stroke-dashoffset': (length * (1 - share)).toFixed(2) })),
    h('span', { class: 'wait-text' }, t(`floor.wait.${state}`)),
    h('span', { class: 'wait-min' }, elapsed(since)));
}

export default {
  title: 'floor.title',
  subtitle: 'floor.sub',
  mount(container, { user, navigate }) {
    let tables = null;
    const summary = h('div', { class: 'floor-summary' });
    const zones = h('div');
    let limitsKey = waitLimits();
    const limitsSelect = h('select', {
      class: 'select', id: 'floor-wait', 'data-testid': 'floor-wait', 'aria-label': t('floor.waitLabel'),
      onchange: (event) => {
        limitsKey = event.target.value;
        try { localStorage.setItem(WAIT_KEY, limitsKey); } catch { /* this visit only */ }
        render();
      },
    }, Object.keys(WAIT_CHOICES).map((key) => h('option', { value: key, selected: key === limitsKey },
      key === 'off' ? t('floor.waitOff') : t('floor.waitLimits', { look: WAIT_CHOICES[key][0], long: WAIT_CHOICES[key][1] }))));
    const tools = h('div', { class: 'toolbar floor-tools' }, h('span', { class: 'spacer' }),
      h('label', { class: 'floor-wait-label', for: 'floor-wait' }, icon('clock', 'sm'), h('span', null, t('floor.waitLabel'))), limitsSelect);
    mount(container, summary, tools, zones);

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
      // A3: a table whose state changed since the last look fills with its new colour from below, once; one that now
      // needs someone (served, or to be cleared) also rings once. No endless blinking.
      const state = served ? 'attention' : table.occupied ? 'occupied' : table.seatedParty ? 'seated' : table.needsClearingSince ? 'dirty' : 'free';
      const before = lastStates.get(table.id);
      lastStates.set(table.id, state);
      const changed = before !== undefined && before !== state && !reducedMotion();
      // F1: crossing a waiting limit rings the table once (the same ring as A3, never a blink)
      const waited = table.attentionSince && WAIT_CHOICES[limitsKey] ? waitState(minutesSince(table.attentionSince), WAIT_CHOICES[limitsKey]) : 'ok';
      const waitedBefore = lastWait.get(table.id);
      lastWait.set(table.id, waited);
      const rang = waitedBefore !== undefined && waited !== 'ok' && waited !== waitedBefore && !reducedMotion();
      const card = h('button', {
        class: ['table-card', table.occupied ? 'occupied' : '', table.seatedParty && !table.occupied ? 'seated' : '', served ? 'attention' : '',
          changed ? `state-changed fill-${state}` : '', (changed && (state === 'attention' || state === 'dirty')) || rang ? 'ring-once' : '', waited !== 'ok' ? `waiting-${waited}` : ''], 'data-testid': `table-${table.code}`,
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
          table.attentionSince && WAIT_CHOICES[limitsKey] ? waitRing(table.attentionSince, WAIT_CHOICES[limitsKey]) : null,
          h('div', { class: 'seats since-line' }, icon('clock', 'sm'),
            h('span', { class: 'since-time', dataset: { since: table.occupiedSince } }, elapsed(table.occupiedSince)),
            h('span', { class: 'since-who', dir: 'auto' }, `· ${order.customerName}`)),
        ]
        : [
          table.seatedParty
            ? h('div', { class: 't-seated', dir: 'auto' }, t('floor.seatedParty', { name: table.seatedParty.guestName, n: table.seatedParty.partySize }))
            : table.needsClearingSince ? h('div', { class: 't-dirty' }, t('service.dirty')) : h('div', { class: 't-free' }, t('floor.available')),
          h('div', { class: 'seats' }, tn('floor.seats', table.seats)),
          canTakeOrders(user) ? h('span', { class: 'btn sm', style: { marginTop: '4px' } }, icon('plus', 'sm'), t(table.seatedParty ? 'floor.takeOrder' : 'floor.seat')) : null,
        ],
      table.nextBooking ? h('div', { class: 't-booking', dir: 'auto', 'data-testid': `booking-${table.code}` }, icon('calendar', 'sm'),
        t('floor.nextBooking', { time: time(table.nextBooking.at), name: table.nextBooking.guestName, n: table.nextBooking.partySize })) : null));
      if (!table.occupied && table.needsClearingSince && canTakeOrders(user)) {
        return h('div', { class: 'table-slot' }, card, h('div', { class: 'table-actions' },
          h('button', { class: 'btn sm ghost', 'data-testid': `clear-${table.code}`, onclick: () => clearTable(table) },
            icon('sparkle', 'sm'), h('span', null, t('service.cleared')))));
      }
      if (!table.occupied || !canTakeOrders(user)) return card;
      // the card itself opens the order, so moving and merging sit next to it rather than inside it
      return h('div', { class: 'table-slot' }, card, h('div', { class: 'table-actions' },
        h('button', { class: 'btn sm ghost', 'data-testid': `move-${table.code}`, title: t('floor.move'), onclick: () => transfer(table, 'move') }, icon('arrowRight', 'sm'), h('span', null, t('floor.move'))),
        h('button', { class: 'btn sm ghost', 'data-testid': `merge-${table.code}`, title: t('floor.merge'), onclick: () => transfer(table, 'merge') }, icon('users', 'sm'), h('span', null, t('floor.merge')))));
    }

    /** Moving needs a free table; merging needs another seated one. */
    function transfer(from, kind) {
      const options = tables.filter((table) => table.id !== from.id && (kind === 'move' ? !table.occupied : table.occupied));
      let picked = null;
      const confirm = h('button', { class: 'btn primary', disabled: true, 'data-testid': 'transfer-confirm' }, icon('check', 'sm'), t(`floor.${kind}Confirm`));
      const list = h('div', { class: 'table-picker transfer-picker' });
      const render = () => mount(list, options.length ? options.map((table) => h('button', {
        type: 'button', class: picked === table ? 'active' : '', 'aria-pressed': String(picked === table), 'data-testid': `pick-${table.code}`,
        onclick: () => { picked = table; confirm.disabled = false; render(); },
      }, table.code, h('small', null, kind === 'move' ? tn('floor.seats', table.seats) : money(table.openTotal))))
        : h('p', { class: 'muted' }, t(`floor.${kind}None`)));
      render();
      const modal = openModal({
        title: t(`floor.${kind}Title`, { table: from.code }),
        size: 'sm',
        body: [h('p', { class: 'muted' }, t(`floor.${kind}Text`)), list],
        footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), confirm],
      });
      confirm.addEventListener('click', async () => {
        confirm.disabled = true;
        try {
          await (kind === 'move' ? api.moveTable(from.id, picked.id) : api.mergeTable(from.id, picked.id));
          toast({ title: t(`floor.${kind}Done`, { from: from.code, to: picked.code }) });
          modal.close();
          load();
        } catch (error) {
          confirm.disabled = false;
          toast({ title: errorMessage(error), kind: 'error' });
        }
      });
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
        h('div', { class: 'row kpi-line' }, h('div', { class: 'value' }, value),
          sub ? h('span', { class: 'muted kpi-sub' }, sub) : null));
    }

    async function clearTable(table) {
      try {
        await api.clearTable(table.id);
        toast({ title: t('service.clearedToast', { table: table.code }) });
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
      reload();
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
    const offTable = bus.on('table', reload);
    const offReservation = bus.on('reservation', reload);
    const offGuest = bus.on('guest', reload);
    const timer = setInterval(() => {
      zones.querySelectorAll('[data-since]').forEach((el) => { el.textContent = elapsed(el.dataset.since); });
      // the rings move on with the clock; a table crossing a limit is drawn again, so it rings once
      if (zones.querySelector('[data-attention]')) render();
    }, 15_000);

    render();
    load();
    return {
      destroy() {
        offOrder();
        offTable();
        offReservation();
        offGuest();
        reload.cancel();
        clearInterval(timer);
      },
    };
  },
};
