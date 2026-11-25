// The kitchen's prep list: how many of each dish today (or another day this week) will likely sell, from the same
// weekday of past weeks; a tap marks a dish prepared for everyone; what those portions need from stock, with what
// is short first; and waste in one tap.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { dayLabel, money, number, time } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { haptic, toast } from '../core/ui.js';
import { openWaste } from './waste.js';

const BULK = { GRAM: 1000, MILLILITRE: 1000, PIECE: 1 };
const BIG = { GRAM: 'kg', MILLILITRE: 'l', PIECE: '' };

/** "1.25 kg", "300 g", "4 pcs": the unit a cook reads at a glance. */
function amount(value, unit) {
  const v = Number(value);
  if (unit === 'PIECE') return t('prep.pieces', { n: number(v, v % 1 ? 1 : 0) });
  if (Math.abs(v) >= BULK[unit]) return `${number(v / BULK[unit], 2)} ${BIG[unit]}`;
  return `${number(v, 0)} ${unit === 'GRAM' ? 'g' : 'ml'}`;
}

const isoDay = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

export default {
  title: 'prep.title',
  subtitle: 'prep.sub',
  mount(container, { user, actions }) {
    const manager = user.role === 'ADMIN';
    const days = Array.from({ length: 7 }, (_, i) => { const d = new Date(); d.setDate(d.getDate() + i); return isoDay(d); });
    let day = days[0];
    let prep = null;
    let wasted = [];
    const chips = h('div', { class: 'chips prep-days', role: 'tablist', 'aria-label': t('prep.day') });
    const body = h('div', { class: 'prep' });
    mount(actions, h('button', { class: 'btn', 'data-testid': 'waste-open', onclick: () => openWaste({ onSaved: loadWaste }) },
      icon('trash', 'sm'), h('span', { class: 'btn-label' }, t('waste.open'))));
    mount(container, h('div', { class: 'toolbar' }, chips), body);

    function renderDays() {
      mount(chips, days.map((d, i) => h('button', {
        type: 'button', role: 'tab', 'aria-selected': String(d === day), class: ['chip', d === day ? 'active' : ''], 'data-testid': `prep-day-${i}`,
        onclick: () => { day = d; prep = null; renderDays(); render(); load(); },
      }, i === 0 ? t('prep.today') : i === 1 ? t('prep.tomorrow') : dayLabel(d, { weekday: 'long' }))));
    }

    function render() {
      if (!prep) {
        mount(body, [1, 2, 3].map(() => h('div', { class: 'skeleton', style: { height: '72px', borderRadius: '16px' } })));
        return;
      }
      const basis = !prep.dishes.length ? t('prep.noHistory')
        : prep.weeks >= 2 ? t('prep.basis', { n: prep.weeks, weekday: dayLabel(prep.day, { weekday: 'long' }) })
          : t('prep.rough');
      const trend = prep.trendPercent ? t(prep.trendPercent > 0 ? 'prep.busier' : 'prep.quieter', { n: Math.abs(prep.trendPercent) }) : null;
      const done = prep.dishes.filter((d) => d.prepared).length;
      mount(body,
        h('p', { class: 'muted prep-basis', 'data-testid': 'prep-basis' }, [basis, trend].filter(Boolean).join(' · ')),
        prep.shortages ? h('section', { class: 'card prep-short', 'data-testid': 'prep-shortages' },
          h('div', { class: 'prep-short-head' }, icon('alert', 'sm'), h('b', null, t('prep.shortTitle', { n: prep.shortages }))),
          h('ul', null, prep.ingredients.filter((n) => Number(n.shortBy) > 0).map((n) => h('li', null,
            h('b', null, n.name), h('span', null, t('prep.shortLine', { need: amount(n.needed, n.unit), have: amount(Math.max(0, n.onHand), n.unit), short: amount(n.shortBy, n.unit) })))))) : null,
        prep.dishes.length ? h('section', null,
          h('div', { class: 'prep-head' }, h('h2', null, t('prep.dishes')), h('span', { class: 'faint' }, t('prep.doneCount', { done, n: prep.dishes.length }))),
          h('ul', { class: 'prep-list', 'data-testid': 'prep-list' }, prep.dishes.map(dishRow)))
          : h('div', { class: 'card empty' }, h('div', { class: 'art' }, '📋'), h('h4', null, t('prep.emptyTitle')), h('p', null, t('prep.emptyText'))),
        prep.ingredients.length ? h('details', { class: 'card prep-needs', open: prep.ingredients.length <= 8 },
          h('summary', null, h('b', null, t('prep.needs')), h('span', { class: 'faint' }, t('prep.needsHint'))),
          h('table', { class: 'data-table' },
            h('thead', null, h('tr', null, h('th', null, t('prep.ingredient')), h('th', { class: 'right' }, t('prep.need')), h('th', { class: 'right' }, t('prep.have')))),
            h('tbody', null, prep.ingredients.map((n) => h('tr', { class: Number(n.shortBy) > 0 ? 'short' : '' },
              h('td', null, n.name), h('td', { class: 'right num' }, amount(n.needed, n.unit)),
              h('td', { class: 'right num' }, n.tracked ? amount(Math.max(0, n.onHand), n.unit) : h('span', { class: 'faint' }, '—'))))))) : null,
        wasted.length ? h('section', { class: 'card prep-waste', 'data-testid': 'prep-waste' },
          h('h2', null, t('prep.wasteToday')),
          h('ul', null, wasted.map((w) => h('li', null,
            h('span', null, h('b', null, w.unit === 'PORTION' ? t('waste.portionsOf', { n: number(w.quantity), name: w.name }) : `${w.name} · ${number(w.quantity, 2)} ${t(`stock.input.${w.unit}`)}`),
              h('small', { class: 'faint block' }, `${t(`waste.reason.${w.reason}`)} · ${time(w.at)} · ${w.by}`)),
            manager ? h('span', { class: 'num' }, money(w.cost)) : null)))) : null);
    }

    function dishRow(d) {
      return h('li', null, h('button', {
        type: 'button', class: ['prep-dish', d.prepared ? 'done' : ''], 'aria-pressed': String(d.prepared), 'data-testid': 'prep-dish',
        onclick: () => toggle(d),
      },
      h('span', { class: 'prep-check', 'aria-hidden': 'true' }, icon('check', 'sm')),
      h('span', { class: 'prep-ic', 'aria-hidden': 'true' }, d.icon || '🍽️'),
      h('span', { class: 'prep-name' }, h('b', null, d.name),
        d.prepared ? h('small', { class: 'faint block' }, t('prep.preparedBy', { name: d.preparedBy })) : null),
      h('span', { class: 'prep-qty' }, h('b', { class: 'num' }, t('prep.portions', { n: d.expected })),
        // how sure the estimate is is said once, above the list; each dish shows its range
        d.low !== d.high ? h('small', { class: 'faint' }, t('prep.range', { low: d.low, high: d.high })) : null)));
    }

    async function toggle(d) {
      const next = !d.prepared;
      d.prepared = next;
      d.preparedBy = next ? user.displayName || user.username : null;
      haptic('tap');
      render();
      try {
        await api.markPrepared(day, d.menuItemId, next);
      } catch (error) {
        d.prepared = !next;
        render();
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    async function load() {
      const asked = day;
      try {
        const result = await api.prep(asked);
        if (asked !== day) return;
        prep = result;
      } catch (error) {
        prep = { day, weekday: null, weeks: 0, trendPercent: 0, dishes: [], ingredients: [], shortages: 0 };
        toast({ title: errorMessage(error), kind: 'error' });
      }
      render();
    }

    async function loadWaste() {
      try { wasted = await api.wasteToday(); } catch { wasted = []; }
      render();
    }

    renderDays();
    render();
    load();
    loadWaste();
  },
};
