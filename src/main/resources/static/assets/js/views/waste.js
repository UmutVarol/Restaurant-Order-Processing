// Recording waste in a few taps, from the kitchen, the prep list or the stock screen: a dish (its portions take the
// recipe's ingredients out of stock) or an ingredient, how much, and why. The toast can take it back for a few
// seconds before anything is sent; the same entry is never recorded twice, however often the tablet retries.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { api, errorMessage } from '../core/api.js';
import { openModal, toast, undoable } from '../core/ui.js';

export const REASONS = ['OVERPRODUCED', 'SPOILED', 'EXPIRED', 'RETURNED', 'DROPPED', 'OTHER'];
const UNITS = { GRAM: ['G', 'KG'], MILLILITRE: ['ML', 'L'], PIECE: ['PIECE'] };

const requestKey = () => (crypto.randomUUID ? crypto.randomUUID() : `w-${Date.now()}-${Math.random().toString(36).slice(2)}`);

/**
 * @param dish       start with this dish chosen
 * @param ingredient start on the ingredient side with this one chosen
 */
export async function openWaste({ dish = null, ingredient = null, onSaved } = {}) {
  let dishes = [];
  let ingredients = [];
  try {
    [dishes, ingredients] = await Promise.all([api.menu({ central: true }), api.ingredients().catch(() => [])]);
  } catch (error) {
    toast({ title: errorMessage(error), kind: 'error' });
    return null;
  }
  const state = {
    side: ingredient ? 'INGREDIENT' : 'DISH', dish: dish?.id ?? null, ingredient: ingredient?.id ?? null,
    portions: 1, amount: '', unit: null, reason: null, note: '', query: '',
  };
  const body = h('div', { class: 'waste', 'data-testid': 'waste-sheet' });
  const save = h('button', { class: 'btn primary', 'data-testid': 'waste-save', onclick: () => submit() });

  function chosenIngredient() {
    return ingredients.find((i) => i.id === state.ingredient) ?? null;
  }

  function render() {
    const list = state.side === 'DISH' ? dishes : ingredients;
    const needle = state.query.trim().toLocaleLowerCase();
    const matches = list.filter((item) => !needle || item.name.toLocaleLowerCase().includes(needle)).slice(0, 60);
    const selected = state.side === 'DISH' ? state.dish : state.ingredient;
    const unitChoices = UNITS[chosenIngredient()?.unit] ?? ['G'];
    if (!unitChoices.includes(state.unit)) state.unit = unitChoices[0];
    mount(body,
      ingredients.length ? h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': t('waste.what') }, ['DISH', 'INGREDIENT'].map((side) => h('button', {
        type: 'button', role: 'radio', 'aria-checked': String(state.side === side), class: state.side === side ? 'active' : '', 'data-testid': `waste-side-${side}`,
        onclick: () => { state.side = side; state.query = ''; render(); },
      }, t(`waste.side.${side}`)))) : null,
      h('input', { class: 'input', type: 'search', placeholder: t('waste.search'), value: state.query, 'aria-label': t('waste.search'), 'data-testid': 'waste-search',
        oninput: (event) => { state.query = event.target.value; render(); requestAnimationFrame(() => body.querySelector('[data-testid=waste-search]')?.focus()); } }),
      h('div', { class: 'waste-pick', role: 'listbox', 'aria-label': t(`waste.side.${state.side}`) }, matches.length ? matches.map((item) => h('button', {
        type: 'button', role: 'option', 'aria-selected': String(selected === item.id), class: ['waste-item', selected === item.id ? 'on' : ''], 'data-testid': 'waste-item',
        onclick: () => { if (state.side === 'DISH') state.dish = item.id; else state.ingredient = item.id; render(); },
      }, state.side === 'DISH' ? h('span', { class: 'waste-ic', 'aria-hidden': 'true' }, item.icon || '🍽️') : null, h('span', null, item.name)))
        : h('p', { class: 'faint' }, t('waste.nothing'))),
      h('div', { class: 'waste-amount' },
        h('span', { class: 'waste-label' }, t(state.side === 'DISH' ? 'waste.portions' : 'waste.amount')),
        state.side === 'DISH'
          ? h('div', { class: 'stepper' },
            h('button', { type: 'button', class: 'icon-btn', 'aria-label': t('waste.less'), disabled: state.portions <= 1, onclick: () => { state.portions -= 1; render(); } }, icon('minus', 'sm')),
            h('b', { 'data-testid': 'waste-portions', 'aria-live': 'polite' }, state.portions),
            h('button', { type: 'button', class: 'icon-btn', 'aria-label': t('waste.more'), disabled: state.portions >= 99, onclick: () => { state.portions += 1; render(); } }, icon('plus', 'sm')))
          : h('div', { class: 'input-group' },
            h('input', { class: 'input', type: 'number', min: 0, step: 'any', inputmode: 'decimal', value: state.amount, 'aria-label': t('waste.amount'), 'data-testid': 'waste-amount',
              oninput: (event) => { state.amount = event.target.value; updateSave(); } }),
            h('select', { class: 'select', 'aria-label': t('stock.unitLabel'), onchange: (event) => { state.unit = event.target.value; } },
              unitChoices.map((u) => h('option', { value: u, selected: state.unit === u }, t(`stock.input.${u}`)))))),
      h('div', null, h('span', { class: 'waste-label' }, t('waste.why')),
        h('div', { class: 'chips waste-reasons', role: 'radiogroup', 'aria-label': t('waste.why') }, REASONS.map((reason) => h('button', {
          type: 'button', role: 'radio', 'aria-checked': String(state.reason === reason), class: ['chip', state.reason === reason ? 'active' : ''], 'data-testid': `waste-reason-${reason}`,
          onclick: () => { state.reason = reason; render(); },
        }, t(`waste.reason.${reason}`))))),
      state.reason === 'OTHER' ? h('input', { class: 'input', maxlength: 200, placeholder: t('waste.note'), value: state.note, 'aria-label': t('waste.note'),
        oninput: (event) => { state.note = event.target.value; } }) : null);
    updateSave();
  }

  function ready() {
    const what = state.side === 'DISH' ? state.dish : state.ingredient;
    const amount = state.side === 'DISH' ? state.portions : Number(state.amount);
    return what != null && amount > 0 && state.reason != null;
  }

  function updateSave() {
    save.disabled = !ready();
    save.replaceChildren(icon('trash', 'sm'), t('waste.save'));
  }

  function submit() {
    if (!ready()) return;
    const item = state.side === 'DISH' ? dishes.find((d) => d.id === state.dish) : chosenIngredient();
    const request = state.side === 'DISH'
      ? { menuItemId: state.dish, quantity: state.portions, reason: state.reason, note: state.note || null, requestKey: requestKey() }
      : { ingredientId: state.ingredient, quantity: Number(state.amount), unit: state.unit, reason: state.reason, note: state.note || null, requestKey: requestKey() };
    modal.close();
    // nothing is sent until the undo window has passed; a retry carries the same key, so it is recorded once
    undoable({
      title: t('waste.recorded', { name: item?.name ?? '' }),
      commit: async () => { await api.recordWaste(request); onSaved?.(); },
      onError: (error) => toast({ title: errorMessage(error), kind: 'error' }),
    });
  }

  const modal = openModal({ title: t('waste.title'), size: 'sm waste-modal', body,
    footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), save] });
  render();
  return modal;
}
