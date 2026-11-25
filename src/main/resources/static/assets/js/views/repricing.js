// Inflation tools, from the menu screen. "Bulk price" raises many prices at once: pick the dishes, say how much,
// see every new price (and what the ingredients will take of it) before anything changes, and take it back from the
// toast. "Margins" lists the dishes whose ingredients take more of the price than the target, with the price that
// brings each back, ready to apply in one tap.
import { debounce, h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { locale, t } from '../core/i18n.js';
import { money } from '../core/format.js';
import { api, ApiError, errorMessage } from '../core/api.js';
import { openModal, toast } from '../core/ui.js';
import { CATEGORIES } from './shared.js';

const ROUNDINGS = ['NONE', 'HALF', 'ONE', 'FIVE', 'TEN'];
const QUICK = { PERCENT: [5, 10, 15, 20, 25], AMOUNT: [5, 10, 20, 50] };
// a signed percentage in the reader's convention: +%10 in Turkish, +10% in English
const pct = (value) => new Intl.NumberFormat(locale(), { style: 'percent', signDisplay: 'exceptZero', maximumFractionDigits: 1 }).format(Number(value) / 100);

/** A food cost share, coloured against the target: green within it, amber a little over, red well over. */
function share(value, target) {
  if (value == null) return h('span', { class: 'faint' }, '—');
  const over = Number(value) > Number(target) * 1.25 ? 'high' : Number(value) > Number(target) ? 'mid' : 'ok';
  return h('span', { class: ['food-cost', over] }, h('b', null, new Intl.NumberFormat(locale(), { style: 'percent', maximumFractionDigits: 1 }).format(Number(value) / 100)));
}

/** Takes a bulk change back, from the toast or the history. */
async function revert(id, onChanged) {
  try {
    const result = await api.revertPriceChange(id);
    toast({ title: result.skipped ? t('bulk.revertedSome', { n: result.restored, skipped: result.skipped }) : t('bulk.reverted', { n: result.restored }) });
    onChanged?.();
  } catch (error) {
    toast({ title: errorMessage(error), kind: 'error' });
  }
}

export async function openBulkPricing({ branches = 1, onChanged } = {}) {
  let settings = { rounding: 'ONE', targetFoodCost: 30 };
  try { settings = await api.pricingSettings(); } catch { /* the defaults do */ }
  const state = { categories: [], mode: 'PERCENT', value: 10, rounding: settings.rounding, branches: false, excluded: new Set(), preview: null, busy: false, problem: null };
  const scope = h('div', { class: 'chips bulk-scope', role: 'group', 'aria-label': t('bulk.which') });
  const how = h('div', { class: 'bulk-how' });
  const summary = h('div', { class: 'bulk-summary', 'aria-live': 'polite', 'data-testid': 'bulk-summary' });
  const table = h('div', { class: 'bulk-table table-wrap' });
  const apply = h('button', { class: 'btn primary', 'data-testid': 'bulk-apply', onclick: () => run() });

  const request = () => ({
    mode: state.mode, value: Number(state.value), rounding: state.rounding,
    categories: state.categories, excludeItemIds: [...state.excluded], includeBranchPrices: state.branches,
  });

  function renderScope() {
    const chip = (key, label) => h('button', {
      type: 'button', class: ['chip', (key === 'ALL' ? !state.categories.length : state.categories.includes(key)) ? 'active' : ''],
      'aria-pressed': String(key === 'ALL' ? !state.categories.length : state.categories.includes(key)), 'data-testid': `bulk-cat-${key}`,
      onclick: () => {
        if (key === 'ALL') state.categories = [];
        else state.categories = state.categories.includes(key) ? state.categories.filter((c) => c !== key) : [...state.categories, key];
        state.excluded.clear();
        renderScope();
        refresh();
      },
    }, label);
    mount(scope, chip('ALL', t('cat.ALL')), CATEGORIES.map((c) => chip(c, t(`cat.${c}`))));
  }

  function renderHow() {
    const input = h('input', {
      class: 'input bulk-value', type: 'number', step: state.mode === 'PERCENT' ? '0.5' : '0.50', value: state.value, inputmode: 'decimal',
      'aria-label': t(state.mode === 'PERCENT' ? 'bulk.percent' : 'bulk.amount'), 'data-testid': 'bulk-value',
      oninput: (event) => { state.value = event.target.value; refresh(); },
    });
    mount(how,
      h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': t('bulk.how') }, ['PERCENT', 'AMOUNT'].map((mode) => h('button', {
        type: 'button', role: 'radio', 'aria-checked': String(state.mode === mode), class: state.mode === mode ? 'active' : '', 'data-testid': `bulk-mode-${mode}`,
        onclick: () => { state.mode = mode; state.value = mode === 'PERCENT' ? 10 : 10; renderHow(); refresh(); },
      }, t(`bulk.mode.${mode}`)))),
      h('div', { class: 'bulk-value-row' },
        h('div', { class: 'chips' }, QUICK[state.mode].map((v) => h('button', {
          type: 'button', class: ['chip', Number(state.value) === v ? 'active' : ''], onclick: () => { state.value = v; renderHow(); refresh(); },
        }, state.mode === 'PERCENT' ? pct(v) : `+${money(v)}`))),
        h('label', { class: 'bulk-input' }, input, h('span', { class: 'faint' }, state.mode === 'PERCENT' ? '%' : '₺'))),
      h('div', { class: 'bulk-options' },
        h('label', { class: 'field inline' }, h('span', null, t('bulk.rounding')),
          h('select', { class: 'select', 'data-testid': 'bulk-rounding', onchange: (event) => { state.rounding = event.target.value; refresh(); } },
            ROUNDINGS.map((r) => h('option', { value: r, selected: state.rounding === r }, t(`bulk.round.${r}`))))),
        branches > 1 ? h('label', { class: 'checkbox' }, h('input', { type: 'checkbox', checked: state.branches,
          onchange: (event) => { state.branches = event.target.checked; refresh(); } }), t('bulk.branches')) : null));
  }

  function renderPreview() {
    const p = state.preview;
    if (state.problem) {
      mount(summary, h('div', { class: 'form-error', role: 'alert' }, icon('alert', 'sm'), state.problem));
      mount(table);
      apply.disabled = true;
      apply.textContent = t('bulk.apply', { n: 0 });
      return;
    }
    if (!p) {
      mount(summary, h('div', { class: 'skeleton', style: { height: '20px', width: '50%' } }));
      return;
    }
    mount(summary, h('b', null, t('bulk.summary', { n: p.changed, average: pct(p.averagePercent) })),
      state.excluded.size ? h('span', { class: 'faint' }, ` · ${t('bulk.excluded', { n: state.excluded.size })}`) : null);
    const target = settings.targetFoodCost;
    mount(table, h('table', { class: 'data-table bulk-lines' },
      h('thead', null, h('tr', null, h('th', null, h('span', { class: 'sr-only' }, t('bulk.include'))), h('th', null, t('menu.col.item')),
        h('th', { class: 'right' }, t('bulk.now')), h('th', { class: 'right' }, t('bulk.new')), h('th', { class: 'right hide-sm' }, t('bulk.foodCost')))),
      h('tbody', null, p.lines.map((line) => h('tr', { 'data-testid': 'bulk-line' },
        h('td', null, line.branchId ? null : h('input', { type: 'checkbox', checked: true, 'aria-label': `${t('bulk.include')}: ${line.name}`,
          onchange: () => { state.excluded.add(line.itemId); refresh(); } })),
        h('td', null, h('b', null, line.name), line.branch ? h('small', { class: 'faint block' }, line.branch) : null),
        h('td', { class: 'right num faint' }, money(line.oldPrice)),
        h('td', { class: 'right num' }, h('b', null, money(line.newPrice)),
          h('small', { class: ['block', Number(line.change) > 0 ? 'up' : 'down'] }, `${Number(line.change) > 0 ? '+' : ''}${money(line.change)}`)),
        h('td', { class: 'right hide-sm' }, share(line.foodCostAfter, target)))))));
    apply.disabled = state.busy || p.changed === 0;
    apply.replaceChildren(icon('check', 'sm'), t('bulk.apply', { n: p.changed }));
  }

  let seq = 0;
  const refresh = debounce(async () => {
    const mine = ++seq;
    state.preview = null;
    state.problem = Number(state.value) ? null : t('bulk.giveValue');
    renderPreview();
    if (state.problem) return;
    try {
      const preview = await api.previewPrices(request());
      if (mine !== seq) return;
      state.preview = preview;
    } catch (error) {
      if (mine !== seq) return;
      state.problem = errorMessage(error);
    }
    renderPreview();
  }, 300);

  async function run() {
    if (!state.preview || state.busy) return;
    state.busy = true;
    apply.disabled = true;
    try {
      const change = await api.applyPrices({ ...request(), expected: state.preview.expected });
      modal.close();
      onChanged?.();
      toast({ title: t('bulk.applied', { n: change.itemCount }), timeout: 9000, action: { label: t('bulk.undo'), run: () => revert(change.id, onChanged) } });
    } catch (error) {
      state.busy = false;
      if (error instanceof ApiError && error.code === 'PRICES_CHANGED') {
        toast({ title: errorMessage(error), kind: 'error' });
        refresh();
        return;
      }
      state.problem = errorMessage(error);
      renderPreview();
    }
  }

  async function history() {
    let changes = [];
    try { changes = await api.priceChanges(); } catch (error) { toast({ title: errorMessage(error), kind: 'error' }); return; }
    const list = h('ul', { class: 'bulk-history', 'data-testid': 'bulk-history' }, changes.length ? changes.map((c) => h('li', null,
      h('div', null, h('b', null, c.mode === 'EXACT' ? t('bulk.suggestedApplied') : c.mode === 'PERCENT' ? pct(c.amount) : `${Number(c.amount) > 0 ? '+' : ''}${money(c.amount)}`),
        h('small', { class: 'faint block' }, t('bulk.historyLine', { n: c.itemCount, by: c.createdBy, when: new Date(c.createdAt).toLocaleString() }))),
      c.revertedAt ? h('span', { class: 'pill plain' }, t('bulk.wasReverted'))
        : c.revertible ? h('button', { class: 'btn sm', onclick: async () => { await revert(c.id, onChanged); history.modal?.close(); } }, icon('refresh', 'sm'), t('bulk.undo')) : null))
      : h('li', { class: 'muted' }, t('bulk.noHistory')));
    history.modal = openModal({ title: t('bulk.history'), size: 'sm', body: list });
  }

  const modal = openModal({
    title: t('bulk.title'),
    size: 'lg bulk-modal',
    body: h('div', { class: 'bulk', 'data-testid': 'bulk-pricing' },
      h('section', null, h('h3', null, t('bulk.which')), scope),
      h('section', null, h('h3', null, t('bulk.how')), how),
      h('section', null, h('div', { class: 'bulk-preview-head' }, h('h3', null, t('bulk.preview')),
        h('button', { class: 'btn ghost sm', type: 'button', 'data-testid': 'bulk-history-open', onclick: history }, icon('clock', 'sm'), t('bulk.history'))),
      summary, table)),
    footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), apply],
  });
  renderScope();
  renderHow();
  refresh();
  return modal;
}

/** The margin check: dishes over the target food cost, each with the price that brings it back. */
export async function openMargins({ onChanged } = {}) {
  let margins;
  try { margins = await api.margins(); } catch (error) { toast({ title: errorMessage(error), kind: 'error' }); return null; }
  const picked = new Set(margins.items.filter((m) => m.status !== 'OK' && Number(m.suggestedPrice) > Number(m.price)).map((m) => m.itemId));
  const body = h('div', { class: 'margins', 'data-testid': 'margins' });
  const apply = h('button', { class: 'btn primary', 'data-testid': 'margins-apply', onclick: () => run() });

  function render() {
    const target = h('input', { class: 'input sm', type: 'number', min: 5, max: 90, step: 1, value: Number(margins.target), 'aria-label': t('margins.target'),
      'data-testid': 'margins-target', onchange: async (event) => {
        try {
          await api.savePricingSettings({ targetFoodCost: Number(event.target.value), rounding: margins.rounding });
          margins = await api.margins();
          picked.clear();
          margins.items.filter((m) => m.status !== 'OK' && Number(m.suggestedPrice) > Number(m.price)).forEach((m) => picked.add(m.itemId));
          render();
        } catch (error) { toast({ title: errorMessage(error), kind: 'error' }); }
      } });
    mount(body,
      h('p', { class: 'muted' }, t('margins.explain')),
      h('label', { class: 'field inline margins-target' }, h('span', null, t('margins.target')), h('span', { class: 'bulk-input' }, target, h('span', { class: 'faint' }, '%'))),
      margins.items.length ? h('div', { class: 'table-wrap' }, h('table', { class: 'data-table bulk-lines' },
        h('thead', null, h('tr', null, h('th', null, h('span', { class: 'sr-only' }, t('bulk.include'))), h('th', null, t('menu.col.item')),
          h('th', { class: 'right' }, t('bulk.foodCost')), h('th', { class: 'right' }, t('bulk.now')), h('th', { class: 'right' }, t('margins.suggested')))),
        h('tbody', null, margins.items.map((m) => {
          const can = Number(m.suggestedPrice) > Number(m.price);
          return h('tr', { 'data-testid': 'margin-row', class: m.status === 'OK' ? 'faint-row' : '' },
            h('td', null, can ? h('input', { type: 'checkbox', checked: picked.has(m.itemId), 'aria-label': `${t('bulk.include')}: ${m.name}`,
              onchange: (event) => { if (event.target.checked) picked.add(m.itemId); else picked.delete(m.itemId); updateApply(); } }) : null),
            h('td', null, h('b', null, m.name), h('small', { class: 'faint block' }, t('margins.cost', { cost: money(m.cost) }))),
            h('td', { class: 'right' }, share(m.foodCost, margins.target)),
            h('td', { class: 'right num faint' }, money(m.price)),
            h('td', { class: 'right num' }, can ? h('b', null, money(m.suggestedPrice)) : h('span', { class: 'faint' }, t('margins.fine'))));
        }))))
        : h('div', { class: 'empty' }, h('div', { class: 'art' }, '🧾'), h('h4', null, t('margins.noRecipes')), h('p', null, t('margins.noRecipesText'))));
    updateApply();
  }

  // a tick only changes the button: the table stays as it is, so focus stays on the box just ticked
  function updateApply() {
    apply.disabled = !picked.size;
    apply.replaceChildren(icon('check', 'sm'), t('margins.apply', { n: picked.size }));
  }

  async function run() {
    const prices = margins.items.filter((m) => picked.has(m.itemId)).map((m) => ({ itemId: m.itemId, price: Number(m.suggestedPrice) }));
    if (!prices.length) return;
    apply.disabled = true;
    try {
      const preview = await api.previewPrices({ mode: 'EXACT', prices });
      const change = await api.applyPrices({ mode: 'EXACT', prices, expected: preview.expected });
      modal.close();
      onChanged?.();
      toast({ title: t('bulk.applied', { n: change.itemCount }), timeout: 9000, action: { label: t('bulk.undo'), run: () => revert(change.id, onChanged) } });
    } catch (error) {
      toast({ title: errorMessage(error), kind: 'error' });
      apply.disabled = false;
    }
  }

  const modal = openModal({ title: t('margins.title'), size: 'lg bulk-modal', body,
    footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), apply] });
  render();
  return modal;
}
