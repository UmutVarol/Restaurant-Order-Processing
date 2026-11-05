// The fourteen allergens a restaurant must declare (EU 1169/2011, and the Turkish Food Codex after it), named in the
// guest's language. Staff set them per dish; guests read them on the table's menu and can hide what they cannot eat.
import { h } from '../core/dom.js';
import { locale, t } from '../core/i18n.js';

export const ALLERGENS = ['GLUTEN', 'MILK', 'EGGS', 'NUTS', 'PEANUTS', 'SESAME', 'SOY', 'FISH', 'CRUSTACEANS', 'MOLLUSCS',
  'CELERY', 'MUSTARD', 'SULPHITES', 'LUPIN'];

/** "gluten, milk and eggs", in the language's own way of listing. */
export function allergenList(codes) {
  const names = ALLERGENS.filter((code) => codes.includes(code)).map((code) => t(`allergen.${code}`));
  try {
    return new Intl.ListFormat(locale(), { style: 'long', type: 'conjunction' }).format(names);
  } catch {
    return names.join(', ');
  }
}

/** Checkbox chips for picking allergens; returns the element and a reader of what is ticked. */
export function allergenPicker(selected = [], { onChange, testid = 'allergen' } = {}) {
  const boxes = ALLERGENS.map((code) => h('input', { type: 'checkbox', value: code, checked: selected.includes(code),
    'data-testid': `${testid}-${code}`, onchange: () => onChange?.(value()) }));
  const value = () => boxes.filter((box) => box.checked).map((box) => box.value);
  const element = h('div', { class: 'tag-picker allergen-picker' },
    boxes.map((box) => h('label', null, box, h('span', null, t(`allergen.${box.value}`)))));
  return { element, value };
}
