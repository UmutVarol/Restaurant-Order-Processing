// Manager dashboard: KPIs with period-over-period trends, revenue, busiest hours, category mix and best sellers.
import { debounce, h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t, tn } from '../core/i18n.js';
import { dayLabel, durationMinutes, money, moneyShort, number, percent } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { areaChart, barChart, donutChart, sparkline } from '../core/charts.js';
import { toast } from '../core/ui.js';
import { STATUS_COLORS } from './shared.js';

const CATEGORY_COLORS = { MAIN: 'var(--brand)', STARTER: 'var(--saffron)', DESSERT: 'var(--violet)', DRINK: 'var(--teal)' };
const TYPE_COLORS = { DINE_IN: 'var(--brand)', TAKEAWAY: 'var(--saffron)', DELIVERY: 'var(--violet)' };
const PAY_COLORS = { CARD: 'var(--blue)', CASH: 'var(--green)', ONLINE: 'var(--violet)' };

let period = 7;

export default {
  title: 'dash.title',
  subtitle: 'dash.sub',
  mount(container, { actions }) {
    let data = null;
    const switcher = h('div', { class: 'segmented period-switch' });

    function renderSwitcher() {
      mount(switcher, [[1, 'dash.today'], [7, 'dash.week'], [30, 'dash.month']].map(([days, label]) => h('button', {
        class: period === days ? 'active' : '',
        onclick: () => { period = days; renderSwitcher(); load(); },
      }, t(label))));
    }
    renderSwitcher();
    mount(actions, switcher);

    function render() {
      if (!data) {
        mount(container,
          h('div', { class: 'kpis' }, [1, 2, 3, 4].map(() => h('div', { class: 'skeleton', style: { height: '132px', borderRadius: '20px' } }))),
          h('div', { class: 'dash-grid' },
            h('div', { class: 'skeleton span-8', style: { height: '340px', borderRadius: '20px' } }),
            h('div', { class: 'skeleton span-4', style: { height: '340px', borderRadius: '20px' } })));
        return;
      }
      const { current, previous } = data;
      const dailyRevenue = data.daily.map((d) => Number(d.revenue));
      mount(container,
        h('div', { class: 'kpis' },
          kpi('coins', t('kpi.revenue'), moneyShort(current.revenue), delta(current.revenue, previous.revenue),
            t('kpi.active', { n: data.activeOrders }), sparkline(dailyRevenue)),
          kpi('receipt', t('kpi.orders'), number(current.orders), delta(current.orders, previous.orders),
            t('kpi.items', { n: number(current.itemsSold) }), sparkline(data.daily.map((d) => d.orders), 'var(--saffron)')),
          kpi('basket', t('kpi.aov'), money(current.averageOrderValue), delta(current.averageOrderValue, previous.averageOrderValue),
            t('kpi.cancelled', { n: current.cancelledOrders })),
          kpi('timer', t('kpi.kitchen'), current.averageKitchenMinutes == null ? '—' : durationMinutes(current.averageKitchenMinutes),
            delta(current.averageKitchenMinutes, previous.averageKitchenMinutes, true), t('kpi.target'))),
        h('div', { class: 'dash-grid' },
          card('span-8', t('dash.trend'), t('dash.trendSub'), trendChart()),
          card('span-4', t('dash.categories'), null, categoryDonut()),
          card('span-8', t('dash.hours'), t('dash.hoursSub'), hoursChart()),
          card('span-4', t('dash.top'), null, topItems()),
          card('span-6', t('dash.channels'), null, h('div', { class: 'split-bars' },
            splitBar(t('orders.col.type'), data.orderTypes.map((x) => ({ key: x.type, label: t(`type.${x.type}`), value: x.orders, revenue: x.revenue, color: TYPE_COLORS[x.type] }))),
            splitBar(t('detail.payment'), data.payments.map((x) => ({ key: x.method, label: t(`pay.${x.method}`), value: x.orders, revenue: x.revenue, color: PAY_COLORS[x.method] }))))),
          card('span-6', t('orders.col.status'), null, statusMix())));
    }

    function trendChart() {
      if (period === 1) {
        const hours = visibleHours();
        let running = 0;
        return areaChart(hours.map((point) => {
          running += Number(point.revenue);
          return { label: `${String(point.hour).padStart(2, '0')}:00`, value: running, tip: money(running), sub: tn('dash.orders', point.orders) };
        }), { format: moneyShort });
      }
      return areaChart(data.daily.map((point) => ({
        label: dayLabel(point.date, period > 7 ? { day: 'numeric', month: 'short' } : { weekday: 'short' }),
        value: Number(point.revenue),
        tip: money(point.revenue),
        sub: `${dayLabel(point.date, { weekday: 'long', day: 'numeric', month: 'long' })} · ${tn('dash.orders', point.orders)}`,
      })), { format: moneyShort });
    }

    /** Opening hours (10:00–23:59), widened if orders were placed outside them. */
    function visibleHours() {
      const active = data.hourly.filter((p) => p.orders > 0).map((p) => p.hour);
      const from = Math.min(10, ...active);
      return data.hourly.filter((p) => p.hour >= from);
    }

    function hoursChart() {
      return barChart(visibleHours().map((point) => ({
        label: String(point.hour).padStart(2, '0'),
        value: point.orders,
        tip: tn('dash.orders', point.orders),
        sub: `${String(point.hour).padStart(2, '0')}:00–${String((point.hour + 1) % 24).padStart(2, '0')}:00 · ${money(point.revenue)}`,
      })));
    }

    function categoryDonut() {
      if (!data.categories.length) return empty();
      const total = data.categories.reduce((sum, c) => sum + Number(c.revenue), 0);
      return h('div', { class: 'donut-wrap' },
        donutChart(data.categories.map((c) => ({ label: t(`cat.${c.category}`), value: Number(c.revenue), color: CATEGORY_COLORS[c.category] })),
          { center: { value: moneyShort(total), label: t('kpi.revenue') } }),
        h('div', { class: 'legend' }, data.categories.map((c) => h('div', { class: 'legend-row' },
          h('i', { style: { background: CATEGORY_COLORS[c.category] } }),
          h('span', null, t(`cat.${c.category}`), h('small', null, t('dash.sold', { n: number(c.quantity) }))),
          h('b', null, percent(Number(c.revenue) / (total || 1)))))));
    }

    function topItems() {
      if (!data.topItems.length) return empty();
      const max = Math.max(...data.topItems.map((i) => i.quantity));
      return h('div', { class: 'top-items' }, data.topItems.map((item, index) => h('div', { class: 'top-item' },
        h('span', { class: 'rank' }, index + 1),
        h('span', { class: 'emoji' }, item.icon || '🍽️'),
        h('div', { class: 'bar-wrap' }, h('b', null, item.name), h('div', { class: 'meter' }, h('i', { style: { width: `${(item.quantity / max) * 100}%` } }))),
        h('div', { class: 'val' }, h('b', null, number(item.quantity)), h('span', { class: 'muted' }, moneyShort(item.revenue))))));
    }

    function splitBar(title, parts) {
      const total = parts.reduce((sum, p) => sum + p.value, 0) || 1;
      return h('div', { class: 'split-bar' },
        h('div', { class: 'head' }, h('b', null, title), h('span', { class: 'muted' }, tn('dash.orders', total))),
        h('div', { class: 'track' }, parts.filter((p) => p.value > 0).map((p) => h('i', {
          style: { width: `${(p.value / total) * 100}%`, background: p.color }, title: `${p.label}: ${p.value}`,
        }))),
        h('div', { class: 'legend', style: { marginTop: '12px' } }, parts.map((p) => h('div', { class: 'legend-row' },
          h('i', { style: { background: p.color } }),
          h('span', null, p.label, h('small', null, moneyShort(p.revenue))),
          h('b', null, percent(p.value / total))))));
    }

    function statusMix() {
      const total = data.statuses.reduce((sum, s) => sum + s.count, 0) || 1;
      return h('div', { class: 'legend' }, data.statuses.filter((s) => s.count > 0).map((s) => h('div', null,
        h('div', { class: 'legend-row' }, h('i', { style: { background: STATUS_COLORS[s.status] } }),
          h('span', null, t(`status.${s.status}`)), h('b', null, number(s.count))),
        h('div', { class: 'meter', style: { height: '6px', borderRadius: '99px', background: 'var(--surface-3)', marginTop: '6px', overflow: 'hidden' } },
          h('i', { style: { display: 'block', height: '100%', width: `${(s.count / total) * 100}%`, background: STATUS_COLORS[s.status], borderRadius: '99px' } })))));
    }

    function empty() {
      return h('div', { class: 'empty' }, h('div', { class: 'art' }, '📭'), h('p', null, t('dash.noData')));
    }

    async function load() {
      try {
        data = await api.dashboard(period);
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
      render();
    }

    const reload = debounce(load, 1500);
    const offOrder = bus.on('order', reload);
    render();
    load();
    return {
      destroy() {
        offOrder();
        reload.cancel();
      },
    };
  },
};

function kpi(iconName, label, value, change, foot, spark) {
  return h('div', { class: 'card kpi' },
    h('div', { class: 'label' }, h('span', { class: 'ic' }, icon(iconName, 'sm')), label),
    h('div', { class: 'value' }, value),
    h('div', { class: 'foot' }, change, h('span', null, foot)),
    spark);
}

/** Percentage change badge; for "lower is better" metrics the colours are inverted. */
function delta(current, previous, lowerIsBetter = false) {
  if (current == null || previous == null || Number(previous) === 0) return h('span', { class: 'delta flat' }, '—');
  const change = (Number(current) - Number(previous)) / Number(previous);
  const flat = Math.abs(change) < 0.005;
  const good = lowerIsBetter ? change < 0 : change > 0;
  return h('span', { class: ['delta', flat ? 'flat' : good ? 'up' : 'down'], title: t('kpi.vsPrev') },
    flat ? null : icon(change > 0 ? 'arrowUp' : 'arrowDown', 'sm'), percent(Math.abs(change)));
}

function card(span, title, sub, content) {
  return h('div', { class: `card ${span}` },
    h('div', { class: 'card-head' }, h('div', null, h('div', { class: 'card-title' }, title), sub ? h('div', { class: 'card-sub' }, sub) : null)),
    h('div', { class: 'card-body' }, content));
}
