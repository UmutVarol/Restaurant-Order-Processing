// E3: one search box for the whole app (Ctrl/⌘ K, or the magnifier in the top bar): screens, tables, open orders and
// dishes together. Letters match however they were typed (İ/ı, ü/u, é/e), the matching part is marked, and only the
// screens this person may open are offered.
import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { lang, t } from '../core/i18n.js';
import { money } from '../core/format.js';
import { api } from '../core/api.js';
import { openModal } from '../core/ui.js';
import { openOrderDrawer } from './shared.js';

const MAX_RESULTS = 12;
const KIND_ORDER = { screen: 0, table: 1, order: 2, dish: 3 };

/** One character as it is compared: lower case, without accents; Turkish dotless and dotted i both count as i. */
function fold(ch) {
  const lower = ch.toLocaleLowerCase(lang() === 'tr' ? 'tr' : undefined);
  if (lower === 'ı' || lower === 'i̇') return 'i';
  return lower.normalize('NFD').replace(/\p{M}+/gu, '');
}
/** The folded text and, for each folded character, the index of the original one, so a match can be marked. */
function folded(text) {
  let out = '';
  const map = [];
  [...text].forEach((ch, i) => {
    for (const piece of fold(ch)) { out += piece; map.push(i); }
  });
  return { out, map, chars: [...text] };
}

/** How well {@code text} matches: 3 starts with it, 2 a word starts with it, 1 contains it, 0 not at all. */
function match(text, query) {
  const f = folded(text);
  const at = f.out.indexOf(query);
  if (at < 0) return null;
  const score = at === 0 ? 3 : /[\s#·\-/(]/.test(f.out[at - 1] || '') ? 2 : 1;
  const from = f.map[at];
  const to = f.map[at + query.length - 1] + 1;
  return { score, from, to, chars: f.chars };
}

function marked(text, hit) {
  if (!hit) return text;
  const { chars, from, to } = hit;
  return [chars.slice(0, from).join(''), h('mark', null, chars.slice(from, to).join('')), chars.slice(to).join('')];
}

/**
 * Opens the search. {@code routes} are the screens this person may open; {@code navigate} goes to one.
 */
export function openSearch({ user, routes, navigate }) {
  let entries = routes.map((route) => ({
    kind: 'screen', icon: route.icon, text: t(`nav.${route.id}`), run: () => navigate(route.id),
  }));
  let selected = 0;
  let shown = [];
  const input = h('input', {
    class: 'input search-input', type: 'search', id: 'quick-search', autocomplete: 'off', spellcheck: false,
    placeholder: t('search.placeholder'), 'aria-label': t('search.title'),
    role: 'combobox', 'aria-expanded': 'true', 'aria-controls': 'quick-search-list', 'aria-autocomplete': 'list',
  });
  const list = h('ul', { class: 'search-list', id: 'quick-search-list', role: 'listbox', 'aria-label': t('search.title') });

  let closed = false;
  const modal = openModal({
    title: t('search.title'),
    size: 'search-modal',
    onClose: () => { closed = true; },
    body: h('div', { class: 'search' },
      h('div', { class: 'search-field' }, icon('search', 'sm'), input),
      list,
      h('small', { class: 'search-hint faint' }, t('search.hint'))),
  });

  function choose(index) {
    const entry = shown[index];
    if (!entry) return;
    modal.close();
    entry.run();
  }

  function render() {
    const query = folded(input.value.trim()).out;
    shown = entries
      .map((entry) => {
        const onText = query ? match(entry.text, query) : null;
        return { entry, onText, hit: onText || (query && entry.extra ? match(entry.extra, query) : null) };
      })
      .filter(({ hit }) => !query || hit)
      .sort((a, b) => (query ? (b.hit?.score || 0) - (a.hit?.score || 0) : 0) || KIND_ORDER[a.entry.kind] - KIND_ORDER[b.entry.kind])
      .slice(0, query ? MAX_RESULTS : routes.length);
    selected = Math.min(selected, Math.max(0, shown.length - 1));
    if (!shown.length) {
      list.replaceChildren(h('li', { class: 'search-empty muted', role: 'presentation' }, t('search.none', { q: input.value.trim() })));
      input.removeAttribute('aria-activedescendant');
      return;
    }
    list.replaceChildren(...shown.map(({ entry, onText }, index) => h('li', {
      id: `search-option-${index}`, role: 'option', class: 'search-option', 'aria-selected': String(index === selected),
      'data-testid': 'search-result',
      onpointermove: () => { if (selected !== index) { selected = index; paintSelection(); } },
      onclick: () => choose(index),
    },
    h('span', { class: 'search-ic', 'aria-hidden': 'true' }, entry.emoji || icon(entry.icon, 'sm')),
    h('span', { class: 'search-text' }, h('bdi', null, marked(entry.text, onText)),
      entry.meta ? h('small', { class: 'faint' }, entry.meta) : null),
    h('span', { class: 'search-kind' }, t(`search.kind.${entry.kind}`)))));
    shown = shown.map(({ entry }) => entry);
    paintSelection();
  }

  function paintSelection() {
    [...list.children].forEach((li, index) => li.setAttribute?.('aria-selected', String(index === selected)));
    input.setAttribute('aria-activedescendant', `search-option-${selected}`);
    list.children[selected]?.scrollIntoView?.({ block: 'nearest' });
  }

  input.addEventListener('input', () => { selected = 0; render(); });
  input.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowDown') { event.preventDefault(); selected = (selected + 1) % Math.max(1, shown.length); paintSelection(); }
    if (event.key === 'ArrowUp') { event.preventDefault(); selected = (selected - 1 + shown.length) % Math.max(1, shown.length); paintSelection(); }
    if (event.key === 'Enter') { event.preventDefault(); choose(selected); }
  });

  render();
  setTimeout(() => input.focus(), 0);

  // tables, open orders and dishes arrive a moment later; screens can be searched at once
  const canSeeMenu = routes.some((route) => route.id === 'menu');
  const canSell = routes.some((route) => route.id === 'pos');
  Promise.allSettled([api.tables(), api.activeOrders(), api.menu({ includeUnavailable: true })]).then(([tables, orders, menu]) => {
    const open = orders.status === 'fulfilled' ? orders.value : [];
    const byTable = new Map(open.filter((order) => order.table).map((order) => [order.table.code, order]));
    const more = [];
    if (tables.status === 'fulfilled') {
      for (const table of tables.value) {
        const order = byTable.get(table.code);
        more.push({
          kind: 'table', icon: 'table', text: `${t('pos.table')} ${table.code}`, extra: table.code,
          meta: order ? `#${order.id} · ${order.customerName}` : t('search.free'),
          run: () => (order ? openOrderDrawer(order.id, user) : navigate('floor')),
        });
      }
    }
    for (const order of open) {
      more.push({
        kind: 'order', icon: 'receipt', text: `#${order.id} · ${order.customerName}`,
        meta: [order.table ? `${t('pos.table')} ${order.table.code}` : t(`type.${order.type}`), t(`status.${order.status}`)].join(' · '),
        run: () => openOrderDrawer(order.id, user),
      });
    }
    if (menu.status === 'fulfilled') {
      for (const item of menu.value) {
        more.push({
          kind: 'dish', emoji: item.icon || '🍽️', text: item.name,
          meta: [money(item.price), item.available ? null : t('search.soldOut')].filter(Boolean).join(' · '),
          run: () => navigate(canSeeMenu ? 'menu' : canSell ? 'pos' : 'orders'),
        });
      }
    }
    entries = [...entries, ...more];
    if (!closed) render();
  });
  return modal;
}
