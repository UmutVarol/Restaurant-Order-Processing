// Order history: filterable, searchable, paginated list with a detail drawer.
import { debounce, h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { dateTime, money, number } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { toast } from '../core/ui.js';
import { itemsSummary, openOrderDrawer, statusPill, typeBadge } from './shared.js';

const STATUS_FILTERS = {
  all: [],
  active: ['PENDING', 'PREPARING', 'READY', 'SERVED', 'OUT_FOR_DELIVERY'],
  completed: ['COMPLETED'],
  cancelled: ['CANCELLED'],
};
const PAGE_SIZE = 12;

const DEFAULTS = { status: 'all', range: 'week', type: '', query: '', page: 0 };
const RANGES = ['today', 'week', 'all'];
const TYPES = ['DINE_IN', 'TAKEAWAY', 'DELIVERY'];
const state = { ...DEFAULTS };

/** Filters live in the hash query (#/orders?status=active&q=ayşe) so a filtered list can be bookmarked or shared. */
function readParams(params) {
  state.status = params.status in STATUS_FILTERS ? params.status : DEFAULTS.status;
  state.range = RANGES.includes(params.range) ? params.range : DEFAULTS.range;
  state.type = TYPES.includes(params.type) ? params.type : DEFAULTS.type;
  state.query = (params.q || '').slice(0, 100);
  state.page = Math.max(0, Number.parseInt(params.page, 10) - 1 || 0);
}

function writeParams() {
  const query = new URLSearchParams();
  if (state.status !== DEFAULTS.status) query.set('status', state.status);
  if (state.range !== DEFAULTS.range) query.set('range', state.range);
  if (state.type) query.set('type', state.type);
  if (state.query.trim()) query.set('q', state.query.trim());
  if (state.page > 0) query.set('page', String(state.page + 1));
  const hash = `#/orders${query.size ? `?${query}` : ''}`;
  if (location.hash !== hash) history.replaceState(null, '', hash);
}

function isoDate(daysAgo = 0) {
  const date = new Date();
  date.setDate(date.getDate() - daysAgo);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export default {
  title: 'orders.title',
  subtitle: 'orders.sub',
  mount(container, { user, params }) {
    if (Object.keys(params).length) readParams(params);
    let page = null;
    let loading = false;

    const search = h('input', {
      class: 'input', type: 'search', value: state.query, placeholder: t('orders.search'), 'aria-label': t('orders.search'),
      oninput: debounce((event) => { state.query = event.target.value; state.page = 0; load(); }, 250),
    });
    const statusChips = h('div', { class: 'chips' });
    const rangeSwitch = h('div', { class: 'segmented', style: { width: '300px' } });
    const typeSelect = h('select', {
      class: 'select', style: { width: '170px' }, 'aria-label': t('orders.col.type'),
      onchange: (event) => { state.type = event.target.value; state.page = 0; load(); },
    },
    h('option', { value: '' }, `${t('orders.col.type')}: ${t('orders.all')}`),
    ['DINE_IN', 'TAKEAWAY', 'DELIVERY'].map((type) => h('option', { value: type, selected: state.type === type }, t(`type.${type}`))));
    const tableCard = h('div', { class: 'card table-wrap' });

    mount(container,
      h('div', { class: 'toolbar' },
        h('div', { class: 'input-icon', style: { flex: '1 1 320px', maxWidth: '440px' } }, icon('search'), search),
        h('div', { class: 'spacer' }),
        typeSelect,
        rangeSwitch),
      h('div', { class: 'toolbar' }, statusChips),
      tableCard);

    function renderFilters() {
      mount(statusChips, Object.keys(STATUS_FILTERS).map((key) => h('button', {
        class: ['chip', state.status === key ? 'active' : ''],
        'aria-pressed': String(state.status === key),
        onclick: () => { state.status = key; state.page = 0; renderFilters(); load(); },
      }, t(`orders.${key}`))));
      mount(rangeSwitch, [['today', 'orders.today'], ['week', 'orders.week'], ['all', 'orders.allTime']].map(([key, label]) => h('button', {
        class: state.range === key ? 'active' : '',
        'aria-pressed': String(state.range === key),
        onclick: () => { state.range = key; state.page = 0; renderFilters(); load(); },
      }, t(label))));
    }

    function render() {
      if (!page) {
        mount(tableCard, h('div', { style: { padding: '16px', display: 'grid', gap: '12px' } },
          Array.from({ length: 6 }, () => h('div', { class: 'skeleton', style: { height: '42px' } }))));
        return;
      }
      if (!page.content.length) {
        const filtered = state.status !== DEFAULTS.status || state.type || state.query.trim() || state.range !== 'all';
        mount(tableCard, h('div', { class: 'empty' }, h('div', { class: 'art' }, '🗂️'), h('h4', null, t('orders.none')), h('p', null, t('orders.noneHint')),
          filtered ? h('button', { class: 'btn sm', onclick: clearFilters }, t('orders.clearFilters')) : null));
        return;
      }
      const from = page.page * page.size + 1;
      const to = from + page.content.length - 1;
      mount(tableCard,
        h('table', { class: 'data-table', style: { opacity: loading ? 0.6 : 1 } },
          h('thead', null, h('tr', null,
            h('th', null, t('orders.col.order')),
            h('th', null, t('orders.col.time')),
            h('th', null, t('orders.col.guest')),
            h('th', { class: 'hide-sm' }, t('orders.col.type')),
            h('th', { class: 'hide-sm' }, t('orders.col.items')),
            h('th', { class: 'right' }, t('orders.col.total')),
            h('th', null, t('orders.col.status')))),
          h('tbody', null, page.content.map((order) => h('tr', {
            onclick: () => openOrderDrawer(order.id, user), tabindex: 0, 'aria-haspopup': 'dialog',
            onkeydown: (event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                openOrderDrawer(order.id, user);
              }
            },
          },
          h('td', { class: 'order-no' }, `#${order.id}`),
          h('td', { class: 'when' }, dateTime(order.createdAt)),
          h('td', { class: 'customer' }, h('b', null, order.customerName), order.customerPhone ? h('small', null, order.customerPhone) : null),
          h('td', { class: 'hide-sm' }, typeBadge(order)),
          h('td', { class: 'items hide-sm', title: itemsSummary(order) }, itemsSummary(order)),
          h('td', { class: 'right num', style: { fontWeight: 650 } }, money(order.pricing.total)),
          h('td', null, statusPill(order.status)))))),
        h('div', { class: 'pagination' },
          h('span', null, t('orders.range', { from: number(from), to: number(to), total: number(page.totalElements) })),
          h('div', { class: 'row' },
            h('button', { class: 'btn sm', disabled: page.first, onclick: () => { state.page -= 1; load(); } }, icon('chevronLeft', 'sm'), t('orders.prev')),
            h('button', { class: 'btn sm', disabled: page.last, onclick: () => { state.page += 1; load(); } }, t('orders.next'), icon('chevronRight', 'sm')))));
    }

    function clearFilters() {
      Object.assign(state, DEFAULTS, { range: 'all' });
      search.value = '';
      typeSelect.value = '';
      renderFilters();
      load();
    }

    async function load() {
      writeParams();
      loading = true;
      render();
      try {
        page = await api.orders({
          status: STATUS_FILTERS[state.status],
          type: state.type,
          q: state.query.trim(),
          from: state.range === 'today' ? isoDate(0) : state.range === 'week' ? isoDate(6) : null,
          page: state.page,
          size: PAGE_SIZE,
          sort: 'createdAt,desc',
        });
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      } finally {
        loading = false;
        render();
      }
    }

    const reload = debounce(load, 400);
    const offOrder = bus.on('order', reload);

    renderFilters();
    load();
    return {
      destroy() {
        offOrder();
        reload.cancel();
      },
    };
  },
};
