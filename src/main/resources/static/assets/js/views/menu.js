// Menu management for managers: prices, availability, dietary tags; create, edit and archive dishes.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { money, number } from '../core/format.js';
import { api, ApiError, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { confirmDialog, openModal, toast } from '../core/ui.js';
import { optionGroupsEditor } from './options.js';
import { CATEGORIES } from './shared.js';

const TAGS = ['VEGETARIAN', 'VEGAN', 'GLUTEN_FREE', 'SPICY'];
// Where a new dish is made unless the manager picks another station (same defaults as the server).
const DEFAULT_STATION = { STARTER: 'COLD', MAIN: 'HOT', DESSERT: 'DESSERT', DRINK: 'BAR' };
// Food cost above these shares of the price is worth a look (amber), or a price change (red).
const FOOD_COST_WARN = 30;
const FOOD_COST_HIGH = 40;
const BULK = { GRAM: 1000, MILLILITRE: 1000, PIECE: 1 };

export default {
  title: 'menu.title',
  subtitle: 'menu.sub',
  mount(container, { actions }) {
    let items = null;
    let stations = [];
    let costs = new Map();
    let category = 'ALL';
    const chips = h('div', { class: 'chips' });
    const tableCard = h('div', { class: 'card table-wrap menu-admin' });

    mount(actions, h('button', { class: 'btn primary', 'aria-label': t('menu.add'), onclick: () => editItem(null) }, icon('plus', 'sm'), h('span', { class: 'btn-label' }, t('menu.add'))));
    mount(container, h('div', { class: 'toolbar' }, chips), tableCard);

    function render() {
      mount(chips, ['ALL', ...CATEGORIES].map((key) => h('button', {
        class: ['chip', category === key ? 'active' : ''],
        onclick: () => { category = key; render(); },
      }, t(`cat.${key}`), items ? h('span', { class: 'count' }, key === 'ALL' ? items.length : items.filter((i) => i.category === key).length) : null)));

      if (!items) {
        mount(tableCard, h('div', { style: { padding: '16px', display: 'grid', gap: '12px' } },
          Array.from({ length: 8 }, () => h('div', { class: 'skeleton', style: { height: '46px' } }))));
        return;
      }
      const visible = items.filter((item) => category === 'ALL' || item.category === category);
      mount(tableCard, h('table', { class: 'data-table' },
        h('thead', null, h('tr', null,
          h('th', null, t('menu.col.item')),
          h('th', { class: 'hide-sm' }, t('menu.col.category')),
          h('th', { class: 'hide-sm' }, t('menu.col.tags')),
          h('th', { class: 'hide-sm right' }, t('menu.col.prep')),
          h('th', { class: 'right' }, t('menu.col.price')),
          h('th', { class: 'hide-sm right' }, t('menu.col.foodCost')),
          h('th', null, t('menu.col.available')),
          h('th', null, h('span', { class: 'sr-only' }, t('menu.col.actions'))))),
        h('tbody', null, visible.map(row))));
    }

    function row(item) {
      return h('tr', { class: item.available ? '' : 'off' },
        h('td', null, h('div', { class: 'item-cell' }, h('span', { class: 'emoji' }, item.icon || '🍽️'),
          h('div', null, h('b', null, item.name, item.popular ? h('span', { class: 'pill plain', style: { marginLeft: '8px', height: '20px', fontSize: '10.5px', background: 'var(--brand-soft)', color: 'var(--brand)' } }, t('pos.popular')) : null,
            item.stockOut ? h('span', { class: 'pill stock-out', style: { marginLeft: '8px', height: '20px', fontSize: '10.5px' }, 'data-testid': 'stock-out' }, t('stock.outOfStock')) : null),
            h('small', null, item.description)))),
        h('td', { class: 'hide-sm' }, t(`cat.${item.category}`)),
        h('td', { class: 'hide-sm' }, h('div', { class: 'row', style: { gap: '4px', flexWrap: 'wrap' } },
          item.tags.map((tag) => h('span', { class: `tag t-${tag}` }, t(`tag.${tag}`))))),
        h('td', { class: 'hide-sm right muted' }, t('common.minutes', { n: item.prepTimeMinutes })),
        h('td', { class: 'right num', style: { fontWeight: 650 } }, money(item.price)),
        h('td', { class: 'hide-sm right' }, foodCost(item)),
        h('td', null, h('label', { class: 'switch', title: item.stockOut ? t('stock.outOfStockHint') : t('menu.col.available') },
          h('input', { type: 'checkbox', role: 'switch', checked: item.available, disabled: item.stockOut, 'aria-label': `${t('menu.col.available')}: ${item.name}`, onchange: (event) => toggle(item, event.target) }), h('span'))),
        h('td', { class: 'right' }, h('div', { class: 'row', style: { justifyContent: 'flex-end', gap: '4px' } },
          h('button', { class: 'icon-btn sm', title: t('recipe.title'), 'data-testid': 'edit-recipe', 'aria-label': `${t('recipe.title')}: ${item.name}`, onclick: () => editRecipe(item) }, icon('basket', 'sm')),
          h('button', { class: 'icon-btn sm', title: t('common.edit'), 'aria-label': `${t('common.edit')}: ${item.name}`, onclick: () => editItem(item) }, icon('edit', 'sm')),
          h('button', { class: 'icon-btn sm', title: t('menu.archive'), 'aria-label': `${t('menu.archive')}: ${item.name}`, onclick: () => archive(item) }, icon('archive', 'sm')))));
    }

    function foodCost(item) {
      const cost = costs.get(item.id);
      if (!cost) return h('span', { class: 'faint' }, '—');
      const share = Number(cost.foodCostPercent);
      return h('span', { class: ['food-cost', share >= FOOD_COST_HIGH ? 'high' : share >= FOOD_COST_WARN ? 'mid' : ''], 'data-testid': 'food-cost' },
        h('b', null, `%${number(share, 1)}`), h('small', { class: 'faint' }, money(cost.cost)));
    }

    // ------------------------------------------------------------------ recipes
    async function editRecipe(item) {
      let recipe;
      let ingredients;
      try {
        [recipe, ingredients] = await Promise.all([api.recipe(item.id), api.ingredients()]);
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
        return;
      }
      if (!ingredients.length) {
        toast({ title: t('recipe.noIngredients'), kind: 'info' });
        return;
      }
      const byId = new Map(ingredients.map((i) => [i.id, i]));
      const options = (item.optionGroups ?? []).flatMap((g) => g.choices.map((c) => ({ key: `${g.name}\u0000${c.name}`, group: g.name, choice: c.name })));
      const rows = recipe.lines.map((line) => ({ ...line, quantity: Number(line.quantity) }));
      const list = h('div', { class: 'recipe-lines' });
      const summary = h('div', { class: 'muted', 'data-testid': 'recipe-cost' });
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });

      const optionKey = (row) => (row.optionGroup ? `${row.optionGroup}\u0000${row.optionChoice}` : '');
      function renderCost() {
        const cost = rows.filter((r) => !r.optionGroup && byId.get(r.ingredientId))
          .reduce((sum, r) => sum + (Number(r.quantity) || 0) * Number(byId.get(r.ingredientId).cost) / BULK[byId.get(r.ingredientId).unit], 0);
        const share = Number(item.price) ? (cost * 100) / Number(item.price) : 0;
        summary.textContent = t('recipe.cost', { cost: money(cost), share: number(share, 1) });
      }
      function renderRows() {
        mount(list, rows.length ? rows.map((row, index) => {
          const ingredient = byId.get(row.ingredientId);
          const pick = h('select', { class: 'select', 'aria-label': t('recipe.ingredient'), 'data-testid': 'recipe-ingredient',
            onchange: (event) => { row.ingredientId = Number(event.target.value); renderRows(); renderCost(); } },
          ingredients.map((i) => h('option', { value: i.id, selected: i.id === row.ingredientId }, i.name)));
          const amount = h('input', { class: 'input', type: 'number', min: 0, step: 'any', value: row.quantity, 'aria-label': t('recipe.quantity'), 'data-testid': 'recipe-quantity',
            oninput: (event) => { row.quantity = event.target.value; renderCost(); } });
          const when = h('select', { class: 'select', 'aria-label': t('recipe.when'),
            onchange: (event) => {
              const option = options.find((o) => o.key === event.target.value);
              row.optionGroup = option?.group ?? null;
              row.optionChoice = option?.choice ?? null;
              if (!option) row.replacesIngredientId = null;
              renderRows();
              renderCost();
            } },
          h('option', { value: '' }, t('recipe.always')),
          options.map((o) => h('option', { value: o.key, selected: o.key === optionKey(row) }, `${o.group}: ${o.choice}`)));
          const own = rows.filter((r) => !r.optionGroup);
          const replaces = row.optionGroup ? h('select', { class: 'select', 'aria-label': t('recipe.replaces'),
            onchange: (event) => { row.replacesIngredientId = event.target.value ? Number(event.target.value) : null; } },
          h('option', { value: '' }, t('recipe.adds')),
          own.map((r) => h('option', { value: r.ingredientId, selected: r.ingredientId === row.replacesIngredientId }, t('recipe.instead', { name: byId.get(r.ingredientId)?.name ?? '?' })))) : null;
          return h('div', { class: 'recipe-line' },
            pick,
            h('div', null, amount, h('div', { class: 'unit-hint' }, ingredient ? t(`recipe.per.${ingredient.unit}`) : '')),
            h('div', { style: { display: 'grid', gap: '6px' } }, when, replaces),
            h('button', { class: 'icon-btn sm', type: 'button', 'aria-label': t('recipe.removeLine'), onclick: () => { rows.splice(index, 1); renderRows(); renderCost(); } }, icon('trash', 'sm')));
        }) : h('p', { class: 'muted' }, t('recipe.empty')));
      }
      renderRows();
      renderCost();
      openModal({
        title: t('recipe.titleFor', { name: item.name }),
        body: [
          h('p', { class: 'muted' }, t('recipe.hint')),
          list,
          h('div', null, h('button', { class: 'btn sm', type: 'button', 'data-testid': 'add-recipe-line', onclick: () => {
            rows.push({ ingredientId: ingredients[0].id, quantity: '', optionGroup: null, optionChoice: null, replacesIngredientId: null });
            renderRows();
          } }, icon('plus', 'sm'), t('recipe.addLine'))),
          summary,
          problem,
        ],
        footer: (handle) => [
          h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')),
          h('button', { class: 'btn primary', 'data-testid': 'save-recipe', onclick: async () => {
            problem.hidden = true;
            try {
              await api.saveRecipe(item.id, rows.map((r) => ({
                ingredientId: r.ingredientId, quantity: r.quantity === '' ? null : Number(r.quantity),
                optionGroup: r.optionGroup, optionChoice: r.optionChoice, replacesIngredientId: r.replacesIngredientId ?? null,
              })));
              toast({ title: t('recipe.saved', { name: item.name }) });
              handle.close();
              load();
            } catch (error) {
              problem.replaceChildren(icon('alert', 'sm'), errorMessage(error));
              problem.hidden = false;
            }
          } }, icon('check', 'sm'), t('common.save')),
        ],
      });
    }

    async function toggle(item, checkbox) {
      checkbox.disabled = true;
      try {
        const updated = await api.setAvailability(item.id, checkbox.checked);
        Object.assign(item, updated);
        toast({ title: t(updated.available ? 'menu.backOn' : 'menu.soldOut', { name: item.name }), kind: updated.available ? 'success' : 'info' });
      } catch (error) {
        checkbox.checked = !checkbox.checked;
        toast({ title: errorMessage(error), kind: 'error' });
      }
      render();
    }

    async function archive(item) {
      const confirmed = await confirmDialog({
        title: t('menu.archiveTitle', { name: item.name }), text: t('menu.archiveText'), confirmLabel: t('menu.archive'), danger: true,
      });
      if (!confirmed) return;
      try {
        await api.archiveMenuItem(item.id);
        items = items.filter((i) => i.id !== item.id);
        toast({ title: t('menu.archived', { name: item.name }), kind: 'info' });
        render();
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    function editItem(item) {
      const draft = item
        ? { ...item, tags: [...item.tags] }
        : { name: '', description: '', category: category === 'ALL' ? 'MAIN' : category, price: '', icon: '🍽️', prepTimeMinutes: 10, popular: false, tags: [] };
      const inputs = {
        name: h('input', { class: 'input', value: draft.name, maxlength: 80, autofocus: true }),
        description: h('textarea', { class: 'textarea', rows: 2, maxlength: 255 }, draft.description || ''),
        category: h('select', { class: 'select' }, CATEGORIES.map((c) => h('option', { value: c, selected: draft.category === c }, t(`cat.${c}`)))),
        price: h('input', { class: 'input', type: 'number', min: '0.01', step: '0.01', value: draft.price }),
        icon: h('input', { class: 'input emoji-input', value: draft.icon || '', maxlength: 4 }),
        prepTimeMinutes: h('input', { class: 'input', type: 'number', min: '0', max: '240', value: draft.prepTimeMinutes }),
        stationCode: h('select', { class: 'select', 'data-testid': 'menu-station' }),
      };
      // The station follows the category until the manager picks one by hand.
      let stationPicked = Boolean(item);
      const fillStations = (selected) => mount(inputs.stationCode,
        stations.map((s) => h('option', { value: s.code, selected: s.code === selected }, s.name)));
      fillStations(draft.stationCode ?? DEFAULT_STATION[draft.category]);
      inputs.stationCode.addEventListener('change', () => { stationPicked = true; });
      inputs.category.addEventListener('change', () => { if (!stationPicked) fillStations(DEFAULT_STATION[inputs.category.value]); });
      const popular = h('input', { type: 'checkbox', checked: draft.popular });
      const tags = TAGS.map((tag) => h('label', null, h('input', { type: 'checkbox', value: tag, checked: draft.tags.includes(tag) }), t(`tag.${tag}`)));
      const options = optionGroupsEditor(draft.optionGroups ?? []);
      const errors = {};
      const field = (name, label, control) => {
        errors[name] = h('div', { class: 'error' });
        return h('div', { class: 'field', dataset: { field: name } }, h('label', null, label), control, errors[name]);
      };

      openModal({
        title: item ? t('menu.edit') : t('menu.add'),
        body: [
          h('div', { style: { display: 'grid', gridTemplateColumns: '84px 1fr', gap: '12px' } },
            field('icon', t('menu.icon'), inputs.icon), field('name', t('menu.name'), inputs.name)),
          field('description', t('menu.description'), inputs.description),
          h('div', { class: 'grid-2' }, field('category', t('menu.category'), inputs.category), field('price', t('menu.price'), inputs.price)),
          h('div', { class: 'grid-2' }, field('prepTimeMinutes', t('menu.prep'), inputs.prepTimeMinutes),
            stations.length ? field('stationCode', t('stations.label'), inputs.stationCode) : h('span')),
          h('label', { class: 'checkbox' }, popular, t('menu.popular')),
          h('div', { class: 'field' }, h('label', null, t('menu.tags')), h('div', { class: 'tag-picker' }, tags)),
          h('div', { class: 'field' }, h('label', null, t('options.title')), h('small', { class: 'faint' }, t('options.editorHint')), options.element),
        ],
        footer: (handle) => {
          const save = h('button', {
            class: 'btn primary',
            onclick: async () => {
              const body = {
                name: inputs.name.value.trim(),
                description: inputs.description.value.trim() || null,
                category: inputs.category.value,
                price: inputs.price.value === '' ? null : Number(inputs.price.value),
                icon: inputs.icon.value.trim() || null,
                prepTimeMinutes: Number(inputs.prepTimeMinutes.value || 0),
                popular: popular.checked,
                tags: tags.map((label) => label.querySelector('input')).filter((box) => box.checked).map((box) => box.value),
                optionGroups: options.value(),
                stationCode: stations.length ? inputs.stationCode.value : null,
              };
              save.disabled = true;
              Object.values(errors).forEach((el) => { el.textContent = ''; el.parentElement.classList.remove('invalid'); });
              try {
                const saved = item ? await api.updateMenuItem(item.id, body) : await api.createMenuItem(body);
                items = item ? items.map((i) => (i.id === saved.id ? saved : i)) : [...items, saved];
                toast({ title: t('menu.saved', { name: saved.name }) });
                handle.close();
                render();
              } catch (error) {
                if (error instanceof ApiError && error.code === 'VALIDATION_FAILED') {
                  for (const [name, message] of Object.entries(error.fieldErrors())) {
                    if (errors[name]) {
                      errors[name].textContent = message;
                      errors[name].parentElement.classList.add('invalid');
                    }
                  }
                }
                toast({ title: errorMessage(error), kind: 'error' });
                save.disabled = false;
              }
            },
          }, icon('check', 'sm'), t('common.save'));
          return [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), save];
        },
      });
    }

    async function load() {
      const [menuResult, costResult] = await Promise.allSettled([api.menu(), api.dishCosts()]);
      items = menuResult.status === 'fulfilled' ? menuResult.value : [];
      if (menuResult.status === 'rejected') toast({ title: errorMessage(menuResult.reason), kind: 'error' });
      costs = new Map(costResult.status === 'fulfilled' ? costResult.value.map((c) => [c.menuItemId, c]) : []);
      render();
    }

    render();
    load();
    api.stations().then((list) => { stations = list; }, () => { stations = []; });
    // a dish selling out or coming back changes its row
    const offStock = bus.on('stock', load);
    return { destroy: offStock };
  },
};
