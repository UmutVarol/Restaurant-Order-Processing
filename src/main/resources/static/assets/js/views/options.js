// Product options (modifiers): choosing them for a line, describing them, and editing them in the menu.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { money } from '../core/format.js';
import { openModal } from '../core/ui.js';

const groupsOf = (item) => item?.optionGroups ?? [];

/** What the server picks when nothing is chosen: the first choices of each required group. */
export function defaultOptionIds(item) {
  return groupsOf(item).flatMap((group) => group.choices.slice(0, group.minSelect).map((choice) => choice.id));
}

/** A stable key for "this dish with these options", so equal lines merge on the ticket. */
export function optionKey(ids = []) {
  return [...ids].sort((a, b) => a - b).join(',');
}

export function chosen(item, ids = []) {
  const wanted = new Set(ids);
  return groupsOf(item).flatMap((group) => group.choices.filter((choice) => wanted.has(choice.id))
    .map((choice) => ({ group: group.name, choice: choice.name, priceDelta: Number(choice.priceDelta) })));
}

export function optionsPrice(item, ids = []) {
  return chosen(item, ids).reduce((sum, option) => sum + option.priceDelta, 0);
}

/** "Bol acılı · Ekstra lavaş" — the choices a line was ordered with. */
export function optionsText(options = []) {
  return options.map((option) => option.choice).join(' · ');
}

/**
 * Lets the waiter (or guest) pick options for a dish. Resolves to the chosen ids, or null when dismissed.
 * Required groups must be filled; groups never take more than they allow.
 */
export function chooseOptions(item, ids = defaultOptionIds(item), { confirmLabel = t('options.apply') } = {}) {
  const selected = new Set(ids);
  return new Promise((resolve) => {
    let settled = false;
    const body = h('div', { class: 'options-picker' });
    const total = h('b', { class: 'options-total' });

    function toggle(group, choice) {
      const inGroup = group.choices.filter((c) => selected.has(c.id));
      if (selected.has(choice.id)) {
        if (inGroup.length > group.minSelect || group.maxSelect === 1 && group.minSelect === 0) selected.delete(choice.id);
      } else if (group.maxSelect === 1) {
        inGroup.forEach((c) => selected.delete(c.id));
        selected.add(choice.id);
      } else if (inGroup.length < group.maxSelect) {
        selected.add(choice.id);
      }
      render();
    }

    function render() {
      mount(body, groupsOf(item).map((group) => {
        const count = group.choices.filter((c) => selected.has(c.id)).length;
        return h('fieldset', { class: 'option-group' },
          h('legend', null, group.name,
            h('span', { class: 'faint' }, group.minSelect > 0
              ? (group.maxSelect === 1 ? t('options.pickOne') : t('options.pickRange', { min: group.minSelect, max: group.maxSelect }))
              : t('options.upTo', { max: group.maxSelect }))),
          h('div', { class: 'chips' }, group.choices.map((choice) => h('button', {
            class: ['chip', selected.has(choice.id) ? 'active' : ''],
            role: group.maxSelect === 1 ? 'radio' : 'checkbox',
            'aria-checked': String(selected.has(choice.id)),
            disabled: !selected.has(choice.id) && group.maxSelect > 1 && count >= group.maxSelect,
            onclick: () => toggle(group, choice),
          }, choice.name, Number(choice.priceDelta) > 0 ? h('span', { class: 'count' }, `+${money(choice.priceDelta)}`) : null))));
      }));
      total.textContent = money(Number(item.price) + optionsPrice(item, [...selected]));
    }

    render();
    openModal({
      title: item.name,
      size: 'sm',
      body: [body, h('div', { class: 'row options-sum' }, h('span', { class: 'muted' }, t('options.unitPrice')), total)],
      footer: (handle) => [
        h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')),
        h('button', {
          class: 'btn primary', 'data-testid': 'options-apply',
          onclick: () => { settled = true; resolve([...selected]); handle.close(); },
        }, icon('check', 'sm'), confirmLabel),
      ],
      onClose: () => { if (!settled) resolve(null); },
    });
  });
}

/** The option groups part of the menu editor. {@code value()} gives what the API expects. */
export function optionGroupsEditor(initial = []) {
  const groups = initial.map((group) => ({
    name: group.name, minSelect: group.minSelect, maxSelect: group.maxSelect,
    choices: group.choices.map((choice) => ({ name: choice.name, priceDelta: Number(choice.priceDelta) || 0 })),
  }));
  const element = h('div', { class: 'options-editor' });

  const number = (value, min, max, onChange, label) => h('input', {
    class: 'input sm', type: 'number', min, max, value, 'aria-label': label,
    oninput: (event) => onChange(Number(event.target.value || 0)),
  });

  function render() {
    mount(element,
      groups.map((group, gi) => h('div', { class: 'option-group-edit' },
        h('div', { class: 'row', style: { gap: '8px', flexWrap: 'wrap' } },
          h('input', { class: 'input sm', placeholder: t('options.groupName'), value: group.name, maxlength: 60,
            'aria-label': t('options.groupName'), oninput: (event) => { group.name = event.target.value; } }),
          h('label', { class: 'faint' }, t('options.min'), number(group.minSelect, 0, 12, (v) => { group.minSelect = v; }, t('options.min'))),
          h('label', { class: 'faint' }, t('options.max'), number(group.maxSelect, 1, 12, (v) => { group.maxSelect = v; }, t('options.max'))),
          h('button', { class: 'icon-btn sm', 'aria-label': t('options.removeGroup'), onclick: () => { groups.splice(gi, 1); render(); } }, icon('trash', 'sm'))),
        group.choices.map((choice, ci) => h('div', { class: 'row option-choice-edit' },
          h('input', { class: 'input sm', placeholder: t('options.choiceName'), value: choice.name, maxlength: 60,
            'aria-label': t('options.choiceName'), oninput: (event) => { choice.name = event.target.value; } }),
          h('input', { class: 'input sm price', type: 'number', min: '0', step: '0.01', value: choice.priceDelta,
            'aria-label': t('options.priceDelta'), oninput: (event) => { choice.priceDelta = Number(event.target.value || 0); } }),
          h('button', { class: 'icon-btn sm', 'aria-label': t('options.removeChoice'), disabled: group.choices.length === 1,
            onclick: () => { group.choices.splice(ci, 1); render(); } }, icon('x', 'sm')))),
        group.choices.length < 12 ? h('button', { class: 'btn sm ghost', onclick: () => { group.choices.push({ name: '', priceDelta: 0 }); render(); } },
          icon('plus', 'sm'), t('options.addChoice')) : null)),
      groups.length < 8 ? h('button', {
        class: 'btn sm', onclick: () => { groups.push({ name: '', minSelect: 0, maxSelect: 1, choices: [{ name: '', priceDelta: 0 }] }); render(); },
      }, icon('plus', 'sm'), t('options.addGroup')) : null);
  }

  render();
  return {
    element,
    value: () => groups.map((group) => ({
      name: group.name.trim(), minSelect: group.minSelect, maxSelect: group.maxSelect,
      choices: group.choices.map((choice) => ({ name: choice.name.trim(), priceDelta: choice.priceDelta })),
    })),
  };
}
