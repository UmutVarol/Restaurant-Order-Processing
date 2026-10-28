// Menu management for managers: prices, availability, dietary tags; create, edit and archive dishes.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { money } from '../core/format.js';
import { api, ApiError, errorMessage } from '../core/api.js';
import { confirmDialog, openModal, toast } from '../core/ui.js';
import { optionGroupsEditor } from './options.js';
import { CATEGORIES } from './shared.js';

const TAGS = ['VEGETARIAN', 'VEGAN', 'GLUTEN_FREE', 'SPICY'];
// Where a new dish is made unless the manager picks another station (same defaults as the server).
const DEFAULT_STATION = { STARTER: 'COLD', MAIN: 'HOT', DESSERT: 'DESSERT', DRINK: 'BAR' };

export default {
  title: 'menu.title',
  subtitle: 'menu.sub',
  mount(container, { actions }) {
    let items = null;
    let stations = [];
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
          h('th', null, t('menu.col.available')),
          h('th', null, h('span', { class: 'sr-only' }, t('menu.col.actions'))))),
        h('tbody', null, visible.map(row))));
    }

    function row(item) {
      return h('tr', { class: item.available ? '' : 'off' },
        h('td', null, h('div', { class: 'item-cell' }, h('span', { class: 'emoji' }, item.icon || '🍽️'),
          h('div', null, h('b', null, item.name, item.popular ? h('span', { class: 'pill plain', style: { marginLeft: '8px', height: '20px', fontSize: '10.5px', background: 'var(--brand-soft)', color: 'var(--brand)' } }, t('pos.popular')) : null),
            h('small', null, item.description)))),
        h('td', { class: 'hide-sm' }, t(`cat.${item.category}`)),
        h('td', { class: 'hide-sm' }, h('div', { class: 'row', style: { gap: '4px', flexWrap: 'wrap' } },
          item.tags.map((tag) => h('span', { class: `tag t-${tag}` }, t(`tag.${tag}`))))),
        h('td', { class: 'hide-sm right muted' }, t('common.minutes', { n: item.prepTimeMinutes })),
        h('td', { class: 'right num', style: { fontWeight: 650 } }, money(item.price)),
        h('td', null, h('label', { class: 'switch', title: t('menu.col.available') },
          h('input', { type: 'checkbox', role: 'switch', checked: item.available, 'aria-label': `${t('menu.col.available')}: ${item.name}`, onchange: (event) => toggle(item, event.target) }), h('span'))),
        h('td', { class: 'right' }, h('div', { class: 'row', style: { justifyContent: 'flex-end', gap: '4px' } },
          h('button', { class: 'icon-btn sm', title: t('common.edit'), 'aria-label': `${t('common.edit')}: ${item.name}`, onclick: () => editItem(item) }, icon('edit', 'sm')),
          h('button', { class: 'icon-btn sm', title: t('menu.archive'), 'aria-label': `${t('menu.archive')}: ${item.name}`, onclick: () => archive(item) }, icon('archive', 'sm')))));
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
      try {
        items = await api.menu();
      } catch (error) {
        items = [];
        toast({ title: errorMessage(error), kind: 'error' });
      }
      render();
    }

    render();
    load();
    api.stations().then((list) => { stations = list; }, () => { stations = []; });
    return null;
  },
};
