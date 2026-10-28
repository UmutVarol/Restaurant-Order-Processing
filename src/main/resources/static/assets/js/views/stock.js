// Stock for managers: what is on hand, deliveries, waste and counts, and the report of theoretical against actual
// use. Every change is a new line in the server's ledger; nothing here edits a balance.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { dateTime, money, number } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { confirmDialog, openDrawer, openModal, toast } from '../core/ui.js';

const UNITS = ['GRAM', 'MILLILITRE', 'PIECE'];
// The units a quantity can be typed in, per base unit; the first is the default.
const INPUT_UNITS = { GRAM: ['KG', 'G'], MILLILITRE: ['L', 'ML'], PIECE: ['PIECE'] };
const BULK = { GRAM: 1000, MILLILITRE: 1000, PIECE: 1 };
const PERIODS = [7, 30, 90];

/** A base quantity the way a cook reads it: 1.25 kg, 300 g, 12 pcs. */
export function quantity(base, unit) {
  const value = Number(base);
  if (unit === 'PIECE') return t('stock.pieces', { n: number(value, value % 1 ? 2 : 0) });
  const big = Math.abs(value) >= 1000;
  const shown = big ? value / 1000 : value;
  const label = unit === 'GRAM' ? (big ? 'kg' : 'g') : (big ? 'l' : 'ml');
  return `${number(shown, Number.isInteger(shown) ? 0 : 2)} ${label}`;
}

export default {
  title: 'stock.title',
  subtitle: 'stock.sub',
  mount(container, { actions }) {
    let ingredients = null;
    let report = null;
    let tab = 'stock';
    let period = 7;
    let onlyLow = false;
    const tabs = h('div', { class: 'chips', role: 'tablist' });
    const summary = h('div', { class: 'kds-summary' });
    const body = h('div', { class: 'card table-wrap stock-table' });

    mount(actions, h('button', { class: 'btn primary', 'data-testid': 'add-ingredient', 'aria-label': t('stock.add'), onclick: () => editIngredient(null) },
      icon('plus', 'sm'), h('span', { class: 'btn-label' }, t('stock.add'))));
    mount(container, h('div', { class: 'toolbar' }, tabs, h('span', { class: 'spacer' }), summary), body);

    function render() {
      mount(tabs, ['stock', 'report'].map((key) => h('button', {
        class: ['chip', tab === key ? 'active' : ''], role: 'tab', 'aria-selected': String(tab === key), 'data-testid': `stock-tab-${key}`,
        onclick: () => { tab = key; render(); if (key === 'report' && !report) loadReport(); },
      }, t(`stock.tab.${key}`))));
      if (tab === 'stock') renderStock(); else renderReport();
    }

    // ------------------------------------------------------------------ stock list
    function renderStock() {
      if (!ingredients) {
        mount(summary);
        mount(body, h('div', { style: { padding: '16px', display: 'grid', gap: '12px' } },
          Array.from({ length: 6 }, () => h('div', { class: 'skeleton', style: { height: '46px' } }))));
        return;
      }
      const low = ingredients.filter((i) => i.low || i.out);
      const value = ingredients.reduce((sum, i) => sum + Number(i.value), 0);
      mount(summary,
        stat('box', ingredients.length, t('stock.count')),
        h('button', { class: ['stat', 'stat-btn', onlyLow ? 'active' : ''], 'data-testid': 'only-low', 'aria-pressed': String(onlyLow),
          onclick: () => { onlyLow = !onlyLow; renderStock(); } },
        h('span', { style: { color: low.length ? 'var(--amber)' : 'var(--brand)' } }, icon('alert', 'sm')), h('b', null, low.length), t('stock.lowCount')),
        stat('coins', money(value), t('stock.value')));
      const shown = onlyLow ? low : ingredients;
      if (!shown.length) {
        mount(body, h('div', { class: 'empty' }, h('div', { class: 'art' }, '📦'), h('h4', null, t(onlyLow ? 'stock.noneLow' : 'stock.none'))));
        return;
      }
      mount(body, h('table', { class: 'data-table' },
        h('thead', null, h('tr', null,
          h('th', null, t('stock.col.ingredient')),
          h('th', { class: 'right' }, t('stock.col.onHand')),
          h('th', { class: 'hide-sm right' }, t('stock.col.low')),
          h('th', { class: 'hide-sm right' }, t('stock.col.cost')),
          h('th', { class: 'hide-sm right' }, t('stock.col.value')),
          h('th', null, h('span', { class: 'sr-only' }, t('menu.col.actions'))))),
        h('tbody', null, shown.map(row))));
    }

    function row(ingredient) {
      const state = !ingredient.trackStock ? null : ingredient.out ? 'out' : ingredient.low ? 'low' : null;
      return h('tr', { class: state === 'out' ? 'off' : '', dataset: { ingredient: ingredient.name } },
        h('td', null, h('b', null, ingredient.name),
          state ? h('span', { class: `pill stock-${state}`, 'data-testid': `stock-${state}` }, t(`stock.state.${state}`)) : null,
          ingredient.trackStock ? null : h('span', { class: 'pill plain', title: t('stock.untrackedHint') }, t('stock.untracked'))),
        h('td', { class: 'right num', 'data-testid': 'on-hand' }, ingredient.trackStock ? quantity(ingredient.onHand, ingredient.unit) : '—'),
        h('td', { class: 'hide-sm right muted num' }, ingredient.lowStockLevel != null ? quantity(ingredient.lowStockLevel, ingredient.unit) : '—'),
        h('td', { class: 'hide-sm right num' }, `${money(ingredient.cost)} / ${t(`stock.bulk.${ingredient.unit}`)}`),
        h('td', { class: 'hide-sm right num' }, ingredient.trackStock ? money(ingredient.value) : '—'),
        h('td', { class: 'right' }, h('div', { class: 'row stock-actions' },
          h('button', { class: 'btn sm', 'data-testid': 'record-delivery', onclick: () => recordMovement(ingredient, 'DELIVERY') }, icon('plus', 'sm'), h('span', { class: 'btn-label' }, t('stock.kind.DELIVERY'))),
          h('button', { class: 'btn sm ghost', 'data-testid': 'record-waste', onclick: () => recordMovement(ingredient, 'WASTE') }, icon('trash', 'sm'), h('span', { class: 'btn-label' }, t('stock.kind.WASTE'))),
          h('button', { class: 'btn sm ghost', 'data-testid': 'record-count', onclick: () => recordMovement(ingredient, 'COUNT') }, icon('check', 'sm'), h('span', { class: 'btn-label' }, t('stock.kind.COUNT'))),
          h('button', { class: 'icon-btn sm', title: t('stock.history'), 'aria-label': `${t('stock.history')}: ${ingredient.name}`, onclick: () => history(ingredient) }, icon('clock', 'sm')),
          h('button', { class: 'icon-btn sm', title: t('common.edit'), 'aria-label': `${t('common.edit')}: ${ingredient.name}`, onclick: () => editIngredient(ingredient) }, icon('edit', 'sm')))));
    }

    function stat(iconName, value, label) {
      return h('div', { class: 'stat' }, h('span', { style: { color: 'var(--brand)' } }, icon(iconName, 'sm')), h('b', null, value), label);
    }

    const field = (id, label, control, hint) => h('div', { class: 'field' }, h('label', { for: id }, label), control, hint ? h('small', { class: 'faint' }, hint) : null);
    const problemBox = () => h('div', { class: 'form-error', hidden: true, role: 'alert' });
    const fail = (box, error) => { box.replaceChildren(icon('alert', 'sm'), errorMessage(error)); box.hidden = false; };

    function editIngredient(ingredient) {
      const draft = ingredient ?? { name: '', unit: 'GRAM', cost: '', lowStockLevel: null, trackStock: true };
      const name = h('input', { class: 'input', id: 'ingredient-name', value: draft.name, maxlength: 60, autocomplete: 'off' });
      const unit = h('select', { class: 'select', id: 'ingredient-unit' }, UNITS.map((u) => h('option', { value: u, selected: draft.unit === u }, t(`stock.unit.${u}`))));
      const cost = h('input', { class: 'input', id: 'ingredient-cost', type: 'number', min: 0, step: '0.01', value: draft.cost });
      // the low level is typed in the bulk unit (kg), like the price
      const low = h('input', { class: 'input', id: 'ingredient-low', type: 'number', min: 0, step: 'any',
        value: draft.lowStockLevel != null ? Number(draft.lowStockLevel) / BULK[draft.unit] : '' });
      const track = h('input', { type: 'checkbox', checked: draft.trackStock });
      const costLabel = h('span');
      const lowLabel = h('span');
      const labels = () => {
        costLabel.textContent = t('stock.costPer', { unit: t(`stock.bulk.${unit.value}`) });
        lowLabel.textContent = t('stock.lowIn', { unit: t(`stock.bulk.${unit.value}`) });
      };
      unit.addEventListener('change', labels);
      labels();
      const problem = problemBox();
      openModal({
        title: ingredient ? t('stock.edit', { name: ingredient.name }) : t('stock.add'),
        size: 'sm',
        body: [
          field('ingredient-name', t('stock.name'), name),
          h('div', { class: 'grid-2' }, field('ingredient-unit', t('stock.unitLabel'), unit), h('div', { class: 'field' }, h('label', { for: 'ingredient-cost' }, costLabel), cost)),
          h('div', { class: 'field' }, h('label', { for: 'ingredient-low' }, lowLabel), low, h('small', { class: 'faint' }, t('stock.lowHint'))),
          h('label', { class: 'checkbox', title: t('stock.untrackedHint') }, track, t('stock.track')),
          problem,
        ],
        footer: (handle) => [
          ingredient ? h('button', { class: 'btn ghost danger-text', onclick: async () => {
            if (!(await confirmDialog({ title: t('stock.removeTitle', { name: ingredient.name }), text: t('stock.removeText'), confirmLabel: t('stock.remove'), danger: true }))) return;
            try { await api.deleteIngredient(ingredient.id); handle.close(); load(); } catch (error) { fail(problem, error); }
          } }, t('stock.remove')) : null,
          h('span', { style: { flex: 1 } }),
          h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')),
          h('button', { class: 'btn primary', 'data-testid': 'save-ingredient', onclick: async () => {
            problem.hidden = true;
            try {
              await api.saveIngredient(ingredient?.id, {
                name: name.value.trim(), unit: unit.value, cost: cost.value === '' ? null : Number(cost.value),
                lowStockLevel: low.value === '' ? null : Math.round(Number(low.value) * BULK[unit.value] * 1000) / 1000,
                trackStock: track.checked,
              });
              toast({ title: t('settings.saved') });
              handle.close();
              load();
            } catch (error) {
              fail(problem, error);
            }
          } }, icon('check', 'sm'), t('common.save')),
        ],
      });
    }

    function recordMovement(ingredient, kind) {
      const amount = h('input', { class: 'input', id: 'movement-quantity', type: 'number', min: 0, step: 'any', inputmode: 'decimal' });
      const unit = h('select', { class: 'select', id: 'movement-unit', 'aria-label': t('stock.unitLabel') },
        INPUT_UNITS[ingredient.unit].map((u) => h('option', { value: u }, t(`stock.input.${u}`))));
      const reason = h('input', { class: 'input', id: 'movement-reason', maxlength: 200, autocomplete: 'off' });
      const supplier = h('input', { class: 'input', id: 'movement-supplier', maxlength: 80, autocomplete: 'off' });
      const unitCost = h('input', { class: 'input', id: 'movement-cost', type: 'number', min: 0, step: '0.01', value: ingredient.cost });
      const problem = problemBox();
      const reasons = kind === 'WASTE' ? h('div', { class: 'quick-reasons' }, ['expired', 'spoiled', 'dropped', 'returned'].map((key) => h('button', {
        type: 'button', class: 'chip', onclick: () => { reason.value = t(`stock.reason.${key}`); },
      }, t(`stock.reason.${key}`)))) : null;
      openModal({
        title: t(`stock.dialog.${kind}`, { name: ingredient.name }),
        size: 'sm',
        body: [
          kind === 'COUNT' ? h('p', { class: 'muted' }, t('stock.countHint', { onHand: quantity(ingredient.onHand, ingredient.unit) })) : null,
          h('div', { class: 'field' }, h('label', { for: 'movement-quantity' }, t(kind === 'COUNT' ? 'stock.counted' : 'stock.quantity')),
            h('div', { class: 'input-group' }, amount, unit)),
          kind === 'DELIVERY' ? h('div', { class: 'grid-2' }, field('movement-supplier', t('stock.supplier'), supplier),
            field('movement-cost', t('stock.costPer', { unit: t(`stock.bulk.${ingredient.unit}`) }), unitCost)) : null,
          field('movement-reason', t(kind === 'WASTE' ? 'stock.reasonLabel' : 'stock.note'), reason),
          reasons,
          problem,
        ],
        footer: (handle) => [
          h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')),
          h('button', { class: 'btn primary', 'data-testid': 'save-movement', onclick: async (event) => {
            const button = event.currentTarget;
            problem.hidden = true;
            button.disabled = true;
            try {
              await api.recordMovement(ingredient.id, {
                kind, quantity: amount.value === '' ? null : Number(amount.value), unit: unit.value,
                reason: reason.value.trim() || null, supplier: kind === 'DELIVERY' ? supplier.value.trim() || null : null,
                unitCost: kind === 'DELIVERY' && unitCost.value !== '' ? Number(unitCost.value) : null,
              });
              toast({ title: t(`stock.recorded.${kind}`, { name: ingredient.name }) });
              handle.close();
              load();
              report = null;
            } catch (error) {
              fail(problem, error);
            }
            button.disabled = false;
          } }, icon('check', 'sm'), t('common.save')),
        ],
      });
      setTimeout(() => amount.focus(), 50);
    }

    function history(ingredient) {
      let movements = null;
      const drawer = openDrawer((handle) => [
        h('div', { class: 'drawer-head' },
          h('div', { style: { flex: 1 } }, h('h2', null, ingredient.name), h('div', { class: 'muted' }, t('stock.history'))),
          h('button', { class: 'icon-btn', 'aria-label': t('common.close'), onclick: handle.close }, icon('x'))),
        h('div', { class: 'drawer-body' }, movements == null ? h('div', { class: 'skeleton', style: { height: '200px' } })
          : movements.length ? h('ul', { class: 'adjustments', 'data-testid': 'movement-list' }, movements.map((m) => h('li', null,
            h('span', { class: `adj-kind k-${m.kind}` }, t(`stock.kind.${m.kind}`)),
            h('div', null,
              h('b', null, `${Number(m.quantity) > 0 ? '+' : ''}${quantity(m.quantity, ingredient.unit)}`),
              m.reason || m.supplier ? h('small', null, [m.supplier, m.reason].filter(Boolean).join(' · ')) : null,
              m.orderId ? h('small', null, t('stock.forOrder', { id: m.orderId })) : null,
              h('small', { class: 'faint' }, `${dateTime(m.at)} · ${m.by}`)),
            h('span', { class: 'num' }, money(Math.abs(Number(m.quantity)) * Number(m.unitCost) / BULK[ingredient.unit])))))
            : h('div', { class: 'empty' }, h('div', { class: 'art' }, '🗒️'), h('h4', null, t('stock.noMovements'))))]);
      api.movements(ingredient.id).then((list) => { movements = list; drawer.render(); },
        (error) => { movements = []; drawer.render(); toast({ title: errorMessage(error), kind: 'error' }); });
    }

    // ------------------------------------------------------------------ report
    function renderReport() {
      const choices = h('div', { class: 'chips' }, PERIODS.map((days) => h('button', {
        class: ['chip', period === days ? 'active' : ''], onclick: () => { period = days; loadReport(); },
      }, t('stock.lastDays', { n: days }))));
      if (!report) {
        mount(summary, choices);
        mount(body, h('div', { style: { padding: '16px' } }, h('div', { class: 'skeleton', style: { height: '200px' } })));
        return;
      }
      mount(summary, choices);
      const rows = report.rows.filter((r) => Number(r.sold) || Number(r.wasted) || Number(r.delivered) || Number(r.countDifference));
      mount(body,
        h('div', { class: 'report-totals', 'data-testid': 'stock-report' },
          total(t('stock.report.sold'), report.soldCost, t('stock.report.soldHint')),
          total(t('stock.report.waste'), report.wasteCost, t('stock.report.wasteHint')),
          total(t('stock.report.variance'), report.varianceCost, t('stock.report.varianceHint'), Number(report.varianceCost) < 0 ? 'var(--red)' : null),
          total(t('stock.report.delivered'), report.deliveredCost, t('stock.report.deliveredHint'))),
        rows.length ? h('table', { class: 'data-table' },
          h('thead', null, h('tr', null,
            h('th', null, t('stock.col.ingredient')),
            h('th', { class: 'right' }, t('stock.report.sold')),
            h('th', { class: 'right' }, t('stock.report.waste')),
            h('th', { class: 'right' }, t('stock.report.variance')),
            h('th', { class: 'hide-sm right' }, t('stock.report.delivered')))),
          h('tbody', null, rows.map((r) => h('tr', null,
            h('td', null, h('b', null, r.name)),
            h('td', { class: 'right num' }, quantity(r.sold, r.unit), h('small', { class: 'faint block' }, money(r.soldCost))),
            h('td', { class: 'right num' }, quantity(r.wasted, r.unit), h('small', { class: 'faint block' }, money(r.wasteCost))),
            h('td', { class: ['right', 'num', Number(r.countDifference) < 0 ? 'danger-text' : ''] }, quantity(r.countDifference, r.unit),
              h('small', { class: 'faint block' }, money(r.varianceCost))),
            h('td', { class: 'hide-sm right num' }, quantity(r.delivered, r.unit), h('small', { class: 'faint block' }, money(r.deliveredCost)))))))
          : h('div', { class: 'empty' }, h('div', { class: 'art' }, '📊'), h('h4', null, t('stock.report.empty'))));
    }

    function total(label, amount, hint, color) {
      return h('div', { class: 'report-total', title: hint }, h('small', null, label), h('b', { style: color ? { color } : null }, money(amount)), h('span', { class: 'faint' }, hint));
    }

    async function loadReport() {
      report = null;
      if (tab === 'report') renderReport();
      const to = new Date();
      const from = new Date(to.getTime() - (period - 1) * 86_400_000);
      const day = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      try {
        report = await api.stockReport(day(from), day(to));
      } catch (error) {
        report = { rows: [], soldCost: 0, wasteCost: 0, varianceCost: 0, deliveredCost: 0 };
        toast({ title: errorMessage(error), kind: 'error' });
      }
      if (tab === 'report') renderReport();
    }

    async function load() {
      try {
        ingredients = await api.ingredients();
      } catch (error) {
        ingredients = [];
        toast({ title: errorMessage(error), kind: 'error' });
      }
      if (tab === 'stock') renderStock();
    }

    const offStock = bus.on('stock', () => { load(); report = null; if (tab === 'report') loadReport(); });
    const offOrder = bus.on('order', (event) => { if (event.status === 'COMPLETED') load(); });
    render();
    load();
    return { destroy: () => { offStock(); offOrder(); } };
  },
};
