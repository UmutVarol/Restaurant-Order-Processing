// The waiter's service screen, made for a phone in the hand: plates the kitchen has ready and the table they go to,
// guests calling or holding a cart, and tables to clear once their guests have paid and left. Everything updates live,
// and each item is one tap.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { elapsed } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { haptic, toast } from '../core/ui.js';
import { optionsText } from './options.js';
import { guestActions, requestCard } from './inbox.js';

export default {
  title: 'service.screen',
  subtitle: 'service.screenSub',
  mount(container, { user }) {
    let ready = null;
    let requests = null;
    let toClear = null;
    const busy = new Set();
    const summary = h('div', { class: 'service-summary', 'data-testid': 'service-summary' });
    const readyList = h('div', { class: 'service-list' });
    const callList = h('div', { class: 'service-list' });
    const clearList = h('div', { class: 'service-list' });
    const section = (iconName, title, list, testId) => h('section', { class: 'service-section', 'data-testid': testId },
      h('div', { class: 'section-title' }, h('h3', null, icon(iconName, 'sm'), title)), list);
    mount(container, summary,
      section('flash', t('service.ready'), readyList, 'service-ready'),
      section('bell', t('service.calls'), callList, 'service-calls'),
      section('table', t('service.toClear'), clearList, 'service-clear'));

    const actions = guestActions(user, { render: () => renderCalls(), reload: () => load() });

    function render() {
      renderSummary();
      renderReady();
      renderCalls();
      renderClear();
    }

    function renderSummary() {
      if (!ready || !requests || !toClear) {
        mount(summary);
        return;
      }
      const chip = (n, label, kind) => h('span', { class: ['service-count', n ? kind : 'none'] }, h('b', null, n), label);
      mount(summary,
        chip(ready.length, t('service.countReady'), 'ready'),
        chip(requests.length, t('service.countCalls'), 'calls'),
        chip(toClear.length, t('service.countClear'), 'clear'));
    }

    const skeleton = () => h('div', { class: 'skeleton', style: { height: '92px', borderRadius: '16px' } });
    const empty = (text) => h('p', { class: 'service-empty muted' }, icon('check', 'sm'), text);
    const tableBadge = (code) => h('span', { class: 'service-table', 'aria-label': `${t('pos.table')} ${code}` }, code);

    function renderReady() {
      if (!ready) return mount(readyList, skeleton());
      if (!ready.length) return mount(readyList, empty(t('service.readyNone')));
      mount(readyList, ready.map((order) => h('article', { class: 'runner-card ready', 'data-testid': 'ready-plate', dataset: { order: order.id } },
        tableBadge(order.table.code),
        h('div', { class: 'service-body' },
          h('div', { class: 'service-head' }, h('b', null, `#${order.id}`), h('span', { class: 'faint', dir: 'auto' }, order.customerName),
            h('span', { class: 'service-since' }, icon('clock', 'sm'), elapsed(order.statusSince))),
          // what the kitchen has sent; dishes held for a later course stay out of the carry list
          h('ul', { class: 'service-items' }, order.lines.filter((line) => line.firedAt !== null && line.quantity - line.voidedQuantity > 0).map((line) => h('li', { dir: 'auto' },
            h('b', null, `${line.quantity - line.voidedQuantity}×`), ` ${line.icon || ''} ${line.name}`,
            line.options?.length ? h('small', null, optionsText(line.options)) : null))),
          order.heldCourses?.length ? h('small', { class: 'faint' }, t('courses.waiting', { list: order.heldCourses.join(', ') })) : null),
        h('button', {
          class: 'btn primary service-act', disabled: busy.has(`o${order.id}`), 'data-testid': 'serve',
          onclick: () => act(`o${order.id}`, () => api.advance(order.id), t('service.served', { table: order.table.code })),
        }, icon('check', 'sm'), t('service.serve')))));
    }

    function renderCalls() {
      if (!requests) return mount(callList, skeleton());
      if (!requests.length) return mount(callList, empty(t('service.callsNone')));
      mount(callList, requests.map((request) => requestCard(request, actions)));
    }

    function renderClear() {
      if (!toClear) return mount(clearList, skeleton());
      if (!toClear.length) return mount(clearList, empty(t('service.clearNone')));
      mount(clearList, toClear.map((table) => h('article', { class: 'runner-card clear', 'data-testid': 'table-to-clear' },
        tableBadge(table.code),
        h('div', { class: 'service-body' },
          h('b', null, `${t('pos.table')} ${table.code}`),
          h('span', { class: 'faint' }, t('service.leftAgo', { time: elapsed(table.needsClearingSince) }))),
        h('button', {
          class: 'btn service-act', disabled: busy.has(`t${table.id}`), 'data-testid': 'cleared',
          onclick: () => act(`t${table.id}`, () => api.clearTable(table.id), t('service.clearedToast', { table: table.code })),
        }, icon('sparkle', 'sm'), t('service.cleared')))));
    }

    async function act(key, call, message) {
      busy.add(key);
      render();
      try {
        await call();
        haptic('success');
        toast({ title: message });
      } catch (error) {
        haptic('error');
        toast({ title: errorMessage(error), kind: 'error' });
      }
      busy.delete(key);
      load();
    }

    async function load() {
      const [orders, calls, tables] = await Promise.allSettled([api.activeOrders(), api.guestRequests(), api.tables()]);
      ready = orders.status === 'fulfilled'
        ? orders.value.filter((o) => o.type === 'DINE_IN' && o.status === 'READY' && o.table)
          .sort((a, b) => new Date(a.statusSince) - new Date(b.statusSince))
        : [];
      requests = calls.status === 'fulfilled' ? calls.value : [];
      toClear = tables.status === 'fulfilled'
        ? tables.value.filter((table) => !table.occupied && table.needsClearingSince)
          .sort((a, b) => new Date(a.needsClearingSince) - new Date(b.needsClearingSince))
        : [];
      const failed = [orders, calls, tables].find((r) => r.status === 'rejected');
      if (failed) toast({ title: errorMessage(failed.reason), kind: 'error' });
      render();
    }

    // one reload for a burst of events (a kitchen marking several plates ready)
    let timer = null;
    const soon = () => { clearTimeout(timer); timer = setTimeout(load, 250); };
    const offs = ['order', 'guest', 'payment', 'table'].map((name) => bus.on(name, soon));
    // waiting times keep counting
    const tick = setInterval(() => { if (ready && toClear) { renderReady(); renderClear(); } }, 30_000);
    render();
    load();
    return { destroy: () => { offs.forEach((off) => off()); clearTimeout(timer); clearInterval(tick); } };
  },
};
