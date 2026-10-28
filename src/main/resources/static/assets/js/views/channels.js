// Delivery platforms for managers: how Yemeksepeti, Getir Yemek and Trendyol Go are connected, which of their items
// are which of our dishes, and the orders they sent. An order with an item nobody mapped waits here instead of being
// guessed: map the item and try again, or turn the order down.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { has, t } from '../core/i18n.js';
import { dateTime, money, time } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { confirmDialog, openModal, toast } from '../core/ui.js';
import { CHANNEL_LABELS, openOrderDrawer } from './shared.js';

export default {
  title: 'channels.title',
  subtitle: 'channels.sub',
  mount(container, { user }) {
    let channels = null;
    let orders = null;
    let menu = [];
    const cards = h('div', { class: 'channel-cards' });
    const list = h('div', { class: 'card table-wrap channel-orders' });
    mount(container, h('p', { class: 'muted channel-intro' }, t('channels.intro')), cards,
      h('div', { class: 'section-title' }, h('h3', null, t('channels.orders'))), list);

    function renderCards() {
      if (!channels) {
        mount(cards, [1, 2, 3].map(() => h('div', { class: 'skeleton', style: { height: '150px', borderRadius: '16px' } })));
        return;
      }
      mount(cards, channels.map((channel) => h('div', { class: `card channel-card ch-${channel.channel}`, 'data-testid': `channel-${channel.channel}` },
        h('div', { class: 'card-head' },
          h('div', null, h('div', { class: 'card-title' }, channel.label),
            h('span', { class: `pill mode-${channel.mode}` }, t(`channels.mode.${channel.mode}`))),
          channel.attention ? h('span', { class: 'pill stock-out', 'data-testid': 'channel-attention' }, t('channels.waiting', { n: channel.attention })) : null),
        h('div', { class: 'card-body' },
          h('p', { class: 'muted' }, t(`channels.modeHint.${channel.mode}`)),
          h('div', { class: 'faint' }, t('channels.mapped', { n: channel.mappings })),
          channel.recentUpdates.length ? h('ul', { class: 'channel-updates' }, channel.recentUpdates.slice(0, 3).map((u) => h('li', null,
            h('span', { class: 'faint' }, time(u.at)), ` ${t(`channels.update.${u.what}`, { target: u.target })} → `, h('b', null, u.value)))) : null,
          h('div', { class: 'row channel-actions' },
            h('button', { class: 'btn sm', 'data-testid': `mappings-${channel.channel}`, onclick: () => editMappings(channel) }, icon('edit', 'sm'), t('channels.mappings')),
            channel.mode === 'SIMULATED' ? [
              h('button', { class: 'btn sm primary', 'data-testid': `simulate-${channel.channel}`, onclick: () => simulate(channel, false) }, icon('plus', 'sm'), t('channels.simulate')),
              h('button', { class: 'btn sm ghost', 'data-testid': `simulate-unmapped-${channel.channel}`, onclick: () => simulate(channel, true) }, t('channels.simulateUnmapped')),
            ] : null)))));
    }

    function renderOrders() {
      if (!orders) {
        mount(list, h('div', { style: { padding: '16px' } }, h('div', { class: 'skeleton', style: { height: '160px' } })));
        return;
      }
      if (!orders.length) {
        mount(list, h('div', { class: 'empty' }, h('div', { class: 'art' }, '🛵'), h('h4', null, t('channels.none'))));
        return;
      }
      mount(list, h('table', { class: 'data-table' },
        h('thead', null, h('tr', null,
          h('th', null, t('channels.col.received')),
          h('th', null, t('channels.col.order')),
          h('th', { class: 'right' }, t('channels.col.total')),
          h('th', null, t('channels.col.state')))),
        h('tbody', null, orders.map(row))));
    }

    function row(o) {
      return h('tr', { dataset: { externalId: o.externalId }, class: o.status === 'ATTENTION' ? 'attention' : '' },
        h('td', { class: 'muted' }, dateTime(o.receivedAt)),
        h('td', null, h('b', null, `${CHANNEL_LABELS[o.channel]} #${o.externalId}`),
          h('small', { class: 'faint block' }, `${o.customerName} · ${t('outbox.items', { n: o.itemCount })}`)),
        h('td', { class: 'right num' }, money(o.platformTotal),
          o.priceDiffers ? h('small', { class: 'block danger-text', 'data-testid': 'price-differs', title: t('channels.priceDiffersHint') },
            t('channels.priceDiffers', { ours: money(o.ourTotal) })) : null),
        h('td', null, state(o)));
    }

    function state(o) {
      if (o.status === 'PLACED') {
        return h('div', { class: 'channel-state' },
          h('button', { class: 'pill s-READY link', 'data-testid': 'channel-placed', onclick: () => openOrderDrawer(o.orderId, user) }, t('channels.placed', { id: o.orderId })),
          o.syncedStatus ? h('small', { class: o.syncError ? 'danger-text' : 'faint' },
            o.syncError ? t('channels.syncFailed', { status: t(`channels.status.${o.syncedStatus}`) }) : t('channels.synced', { status: t(`channels.status.${o.syncedStatus}`) })) : null);
      }
      if (o.status === 'REJECTED') return h('span', { class: 'pill s-CANCELLED' }, t('channels.rejected'));
      return h('div', { class: 'channel-state', 'data-testid': 'channel-waiting' },
        h('span', { class: 'pill stock-out' }, t('channels.attention')),
        h('small', { class: 'danger-text' }, o.problemCode === 'CHANNEL_ITEM_UNMAPPED' ? t('channels.unmapped') : problemText(o)),
        o.unmapped.map((item) => h('button', { class: 'btn sm', 'data-testid': 'map-unmapped', onclick: () => mapItem(o.channel, item) },
          icon('edit', 'sm'), t('channels.mapItem', { name: item.name }))),
        h('div', { class: 'row', style: { gap: '6px' } },
          h('button', { class: 'btn sm primary', 'data-testid': 'channel-retry', onclick: () => act(() => api.retryChannelOrder(o.id)) }, icon('refresh', 'sm'), t('channels.retry')),
          h('button', { class: 'btn sm ghost danger-text', 'data-testid': 'channel-reject', onclick: async () => {
            if (!(await confirmDialog({ title: t('channels.rejectTitle'), text: t('channels.rejectText'), confirmLabel: t('channels.reject'), danger: true }))) return;
            act(() => api.rejectChannelOrder(o.id));
          } }, t('channels.reject'))));
    }

    function problemText(o) {
      return has(`error.${o.problemCode}`) ? t(`error.${o.problemCode}`) : o.problem ?? '';
    }

    async function act(call) {
      try {
        const result = await call();
        if (result?.status === 'ATTENTION') toast({ title: t('channels.stillWaiting'), kind: 'info' });
        else if (result?.status === 'PLACED') toast({ title: t('channels.placedToast', { id: result.orderId }) });
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
      load();
    }

    async function simulate(channel, withUnmapped) {
      try {
        const result = await api.simulateChannel(channel.channel, withUnmapped);
        toast(result.status === 'PLACED'
          ? { title: t('channels.simulated', { channel: channel.label, id: result.orderId }) }
          : { title: t('channels.simulatedWaiting', { channel: channel.label }), kind: 'info' });
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
      load();
    }

    const dishSelect = (selected) => h('select', { class: 'select', 'data-testid': 'mapping-dish', 'aria-label': t('channels.dish') },
      menu.map((m) => h('option', { value: m.id, selected: m.id === selected }, m.name)));

    function mapItem(channel, item) {
      const dish = dishSelect(null);
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      openModal({
        title: t('channels.mapTitle', { name: item.name }),
        size: 'sm',
        body: [h('p', { class: 'muted' }, t('channels.mapText', { channel: CHANNEL_LABELS[channel], id: item.externalId })),
          h('div', { class: 'field' }, h('label', null, t('channels.dish')), dish), problem],
        footer: (handle) => [
          h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')),
          h('button', { class: 'btn primary', 'data-testid': 'save-mapping', onclick: async () => {
            try {
              await api.mapChannelItem(channel, item.externalId, { externalName: item.name, menuItemId: Number(dish.value) });
              toast({ title: t('channels.mappedToast', { name: item.name }) });
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

    async function editMappings(channel) {
      let mappings;
      try {
        mappings = await api.channelMappings(channel.channel);
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
        return;
      }
      const table = h('div', { class: 'mapping-list' });
      const render = () => mount(table, mappings.length ? mappings.map((m) => h('div', { class: 'mapping-row' },
        h('div', null, h('b', null, m.externalName), h('small', { class: 'faint block' }, m.externalId)),
        h('span', { class: 'muted' }, `→ ${m.menuItemName}`),
        h('button', { class: 'icon-btn sm', 'aria-label': t('channels.unmap', { name: m.externalName }), onclick: async () => {
          try {
            await api.unmapChannelItem(channel.channel, m.externalId);
            mappings = mappings.filter((x) => x !== m);
            render();
          } catch (error) {
            toast({ title: errorMessage(error), kind: 'error' });
          }
        } }, icon('trash', 'sm')))) : h('p', { class: 'muted' }, t('channels.noMappings')));
      render();
      const externalId = h('input', { class: 'input', maxlength: 80, placeholder: t('channels.externalId'), 'aria-label': t('channels.externalId') });
      const externalName = h('input', { class: 'input', maxlength: 120, placeholder: t('channels.externalName'), 'aria-label': t('channels.externalName') });
      const dish = dishSelect(null);
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      openModal({
        title: t('channels.mappingsTitle', { channel: channel.label }),
        body: [h('p', { class: 'muted' }, t('channels.mappingsText')), table,
          h('div', { class: 'mapping-add' }, externalId, externalName, dish,
            h('button', { class: 'btn', 'data-testid': 'add-mapping', onclick: async () => {
              problem.hidden = true;
              try {
                const saved = await api.mapChannelItem(channel.channel, externalId.value.trim(), { externalName: externalName.value.trim() || externalId.value.trim(), menuItemId: Number(dish.value) });
                mappings = [...mappings.filter((m) => m.externalId !== saved.externalId), saved];
                externalId.value = '';
                externalName.value = '';
                render();
              } catch (error) {
                problem.replaceChildren(icon('alert', 'sm'), errorMessage(error));
                problem.hidden = false;
              }
            } }, icon('plus', 'sm'), t('channels.addMapping'))),
          problem],
        footer: (handle) => [h('button', { class: 'btn primary', onclick: () => { handle.close(); load(); } }, t('common.close'))],
      });
    }

    async function load() {
      const [channelResult, orderResult, menuResult] = await Promise.allSettled([api.channels(), api.channelOrders(), api.menu()]);
      channels = channelResult.status === 'fulfilled' ? channelResult.value : [];
      orders = orderResult.status === 'fulfilled' ? orderResult.value : [];
      menu = menuResult.status === 'fulfilled' ? menuResult.value : menu;
      const failed = [channelResult, orderResult].find((r) => r.status === 'rejected');
      if (failed) toast({ title: errorMessage(failed.reason), kind: 'error' });
      renderCards();
      renderOrders();
    }

    let timer = null;
    const offOrder = bus.on('order', () => { clearTimeout(timer); timer = setTimeout(load, 400); });
    renderCards();
    renderOrders();
    load();
    return { destroy: () => { offOrder(); clearTimeout(timer); } };
  },
};
