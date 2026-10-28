// Building blocks shared by several screens: status pills, order type badges, the order detail drawer
// and the printable receipt.
import { h } from '../core/dom.js';
import { icon, logoMark } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { dateTime, money, time } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { confirmDialog, openDrawer, openModal, toast } from '../core/ui.js';
import { openBill } from './bill.js';
import { optionsText } from './options.js';

export const CATEGORIES = ['STARTER', 'MAIN', 'DESSERT', 'DRINK'];
export const TYPE_ICONS = { DINE_IN: 'dinein', TAKEAWAY: 'takeaway', DELIVERY: 'delivery' };
export const PAY_ICONS = { CARD: 'card', CASH: 'cash', ONLINE: 'online' };
export const STATUS_COLORS = {
  PENDING: 'var(--amber)', PREPARING: 'var(--blue)', READY: 'var(--green)', SERVED: 'var(--teal)',
  OUT_FOR_DELIVERY: 'var(--violet)', COMPLETED: 'var(--slate)', CANCELLED: 'var(--red)',
};
const PATHS = {
  DINE_IN: ['PENDING', 'PREPARING', 'READY', 'SERVED', 'COMPLETED'],
  TAKEAWAY: ['PENDING', 'PREPARING', 'READY', 'COMPLETED'],
  DELIVERY: ['PENDING', 'PREPARING', 'READY', 'OUT_FOR_DELIVERY', 'COMPLETED'],
};

export function statusPill(status) {
  return h('span', { class: `pill s-${status}` }, t(`status.${status}`));
}

export function typeBadge(order) {
  const where = order.type === 'DINE_IN' && order.table ? `${t('pos.table')} ${order.table.code}` : t(`type.${order.type}`);
  return h('span', { class: 'type-badge' }, icon(TYPE_ICONS[order.type], 'sm'), where);
}

export function advanceLabel(order) {
  if (!order.nextStatus) return null;
  return order.nextStatus === 'COMPLETED' ? t(`advance.COMPLETED.${order.type}`) : t(`advance.${order.nextStatus}`);
}

export function canTakeOrders(user) {
  return user.role === 'ADMIN' || user.role === 'WAITER';
}

/**
 * Advances an order and reports the outcome with a toast. Resolves to the updated order, or null. An order whose
 * bill is not paid yet cannot be completed: staff who take payments get the bill opened instead.
 */
export async function advanceOrder(order, user) {
  try {
    const updated = await api.advance(order.id);
    toast({ title: t('advanced', { id: updated.id, status: t(`status.${updated.status}`) }), kind: 'success', timeout: 2500 });
    return updated;
  } catch (error) {
    if (error.code === 'BILL_NOT_PAID' && user && canTakeOrders(user)) {
      toast({ title: errorMessage(error), kind: 'info' });
      openBill({ orderId: order.id }, user);
    } else {
      toast({ title: errorMessage(error), kind: 'error' });
    }
    return null;
  }
}

// ---------------------------------------------------------------------------- order drawer

export function openOrderDrawer(orderId, user) {
  let order = null;
  let failed = false;
  let unsubscribe = () => {};

  const drawer = openDrawer((handle) => {
    if (failed) {
      return [
        h('div', { class: 'drawer-head' }, h('div', { style: { flex: 1 } }),
          h('button', { class: 'icon-btn', 'aria-label': t('common.close'), onclick: handle.close }, icon('x'))),
        h('div', { class: 'empty' }, h('div', { class: 'art' }, '😕'), h('h4', null, t('common.loadFailed'))),
      ];
    }
    if (!order) return skeletonDrawer(handle);
    return drawerContent(order, user, handle, async (action) => {
      const updated = await action();
      if (updated) {
        order = updated;
        handle.render();
      }
    });
  }, { onClose: () => unsubscribe() });

  const load = async () => {
    try {
      order = await api.order(orderId);
    } catch {
      failed = true;
    }
    drawer.render();
  };
  unsubscribe = bus.on('order', (event) => { if (event.orderId === orderId) load(); });
  load();
  return drawer;
}

function skeletonDrawer(handle) {
  return [
    h('div', { class: 'drawer-head' }, h('div', { class: 'skeleton', style: { width: '180px', height: '30px' } }),
      h('button', { class: 'icon-btn', style: { marginLeft: 'auto' }, 'aria-label': t('common.close'), onclick: handle.close }, icon('x'))),
    h('div', { class: 'drawer-body' }, [120, 200, 160].map((height) => h('div', { class: 'skeleton', style: { height: `${height}px` } }))),
  ];
}

function drawerContent(order, user, handle, run) {
  const staff = canTakeOrders(user);
  return [
    h('div', { class: 'drawer-head' },
      h('div', { style: { flex: 1 } },
        h('h2', { id: `order-${order.id}-title` }, t('detail.title', { id: order.id })),
        h('div', { class: 'row', style: { marginTop: '8px', flexWrap: 'wrap' } }, statusPill(order.status), typeBadge(order),
          h('span', { class: 'muted', style: { fontSize: '12.5px' } }, dateTime(order.createdAt)))),
      h('button', { class: 'icon-btn', 'aria-label': t('common.close'), onclick: handle.close }, icon('x'))),

    h('div', { class: 'drawer-body' },
      section(t('detail.guest'), h('dl', { class: 'kv' },
        kv(t('pos.customer'), order.customerName),
        order.customerPhone ? kv(t('pos.phone'), order.customerPhone) : null,
        order.deliveryAddress ? kv(t('pos.address'), order.deliveryAddress) : null,
        kv(t('detail.placedBy'), order.createdBy),
        kv(t('detail.payment'), h('span', { class: 'row', style: { justifyContent: 'flex-end', gap: '6px' } },
          icon(PAY_ICONS[order.paymentMethod], 'sm'), t(`pay.${order.paymentMethod}`))),
        order.couponCode ? kv(t('detail.coupon'), h('span', { class: 'tag' }, icon('tag', 'sm'), order.couponCode)) : null)),
      order.note ? h('div', { class: 'sticky-note' }, icon('note', 'sm'), order.note) : null,
      order.cancellationReason ? h('div', { class: 'form-error' }, icon('alert', 'sm'),
        `${t('detail.reason')}: ${order.cancellationReason}`) : null,
      section(t('detail.timeline'), timeline(order)),
      section(t('detail.items'), h('div', { class: 'line-items' }, order.lines.map(lineItem))),
      section(null, totals(order.pricing))),

    h('div', { class: 'drawer-foot' },
      staff && order.cancellable ? h('button', {
        class: 'btn danger', onclick: async () => {
          const reason = await confirmDialog({
            title: t('detail.cancelTitle', { id: order.id }), text: t('detail.cancelText'), confirmLabel: t('detail.cancel'),
            danger: true, input: { label: t('detail.cancelReason'), placeholder: t('detail.cancelPlaceholder'), required: true },
          });
          if (!reason) return;
          run(async () => {
            try {
              const updated = await api.cancel(order.id, reason);
              toast({ title: t('detail.cancelled', { id: order.id }), kind: 'info' });
              return updated;
            } catch (error) {
              toast({ title: errorMessage(error), kind: 'error' });
              return null;
            }
          });
        },
      }, icon('x', 'sm'), t('detail.cancel')) : null,
      h('button', { class: 'btn', onclick: () => openReceipt(order) }, icon('printer', 'sm'), t('detail.print')),
      staff ? h('button', { class: 'btn', 'data-testid': 'open-bill', onclick: () => openBill({ orderId: order.id }, user) },
        icon('receipt', 'sm'), t('bill.open')) : null,
      order.nextStatus ? h('button', { class: 'btn primary', onclick: () => run(() => advanceOrder(order, user)) },
        advanceLabel(order), icon('arrowRight', 'sm')) : null),
  ];
}

function section(title, content) {
  return h('section', null, title ? h('div', { class: 'section-title' }, h('h3', null, title)) : null, content);
}

function kv(key, value) {
  return [h('dt', null, key), h('dd', null, value)];
}

function lineItem(line) {
  return h('div', { class: 'line-item' },
    h('div', { class: 'emoji' }, line.icon || '🍽️'),
    h('div', null,
      h('div', { class: 'n' }, `${line.quantity} × ${line.name}`),
      h('div', { class: 'sub' }, money(line.unitPrice)),
      line.options?.length ? h('div', { class: 'opts' }, optionsText(line.options)) : null,
      line.note ? h('div', { class: 'note' }, `“${line.note}”`) : null),
    h('div', { class: 't' }, money(line.lineTotal)));
}

export function totals(pricing, { loading = false } = {}) {
  const discount = Number(pricing.discount) > 0;
  const fee = Number(pricing.deliveryFee) > 0;
  return h('div', { class: ['totals', loading ? 'loading' : ''] },
    h('div', { class: 'line' }, h('span', null, t('pos.subtotal')), h('span', null, money(pricing.subtotal))),
    discount ? h('div', { class: 'line discount' }, h('span', null, t('pos.discount')), h('span', null, `−${money(pricing.discount)}`)) : null,
    h('div', { class: 'line' }, h('span', null, t('pos.tax')), h('span', null, money(pricing.tax))),
    fee ? h('div', { class: 'line' }, h('span', null, t('pos.deliveryFee')), h('span', null, money(pricing.deliveryFee))) : null,
    h('div', { class: 'grand' }, h('span', null, t('pos.total')), h('span', null, money(pricing.total))));
}

function timeline(order) {
  const reached = new Map(order.history.map((change) => [change.status, change]));
  const path = PATHS[order.type];
  const steps = order.status === 'CANCELLED'
    ? [...order.history.map((change) => change.status)]
    : path;
  return h('div', { class: 'timeline' }, steps.map((status) => {
    const change = reached.get(status);
    const state = status === 'CANCELLED' ? 'cancelled'
      : status === order.status && order.status !== 'COMPLETED' ? 'current'
        : change ? 'done' : 'todo';
    return h('div', { class: `step ${state}` },
      h('div', { class: 'bullet' }, icon(state === 'cancelled' ? 'x' : state === 'todo' ? 'clock' : 'check')),
      h('div', null,
        h('div', { class: 'title' }, t(`status.${status}`)),
        change?.by ? h('div', { class: 'by' }, t('detail.by', { name: change.by })) : null),
      h('div', { class: 'at' }, change ? time(change.at) : ''));
  }));
}

// ---------------------------------------------------------------------------- receipt

export function openReceipt(order) {
  const receipt = receiptElement(order);
  openModal({
    title: t('detail.receipt'),
    size: 'sm',
    body: receipt,
    footer: (handle) => [
      h('button', { class: 'btn ghost', onclick: handle.close }, t('common.close')),
      window.SOFRA_BACKEND?.print === false ? null : h('button', {
        class: 'btn primary', onclick: () => {
          document.getElementById('print-root').replaceChildren(receiptElement(order));
          window.print();
        },
      }, icon('printer', 'sm'), t('detail.printNow')),
    ],
  });
}

function receiptElement(order) {
  const row = (left, right, cls = '') => h('div', { class: `r ${cls}` }, h('span', null, left), h('span', null, right));
  const p = order.pricing;
  return h('div', { class: 'receipt' },
    h('div', { class: 'c' }, h('div', { style: { display: 'flex', justifyContent: 'center', marginBottom: '6px' } }, logoMark('logo-mark')),
      h('div', { class: 'brand-line' }, 'Sofra Bistro'),
      h('div', null, 'Moda Cad. No:1, Kadıköy / İstanbul'),
      h('div', null, 'VKN 1234567890')),
    h('hr'),
    row(`#${order.id}`, dateTime(order.createdAt)),
    row(t(`type.${order.type}`), order.table ? `${t('pos.table')} ${order.table.code}` : order.customerName),
    h('hr'),
    order.lines.map((line) => [
      row(`${line.quantity} × ${line.name}`, money(line.lineTotal)),
      line.options?.length ? h('div', { style: { paddingLeft: '14px' } }, `+ ${optionsText(line.options)}`) : null,
      line.note ? h('div', { style: { paddingLeft: '14px', fontStyle: 'italic' } }, `– ${line.note}`) : null,
    ]),
    h('hr'),
    row(t('pos.subtotal'), money(p.subtotal)),
    Number(p.discount) > 0 ? row(`${t('pos.discount')}${order.couponCode ? ` (${order.couponCode})` : ''}`, `−${money(p.discount)}`) : null,
    row(t('pos.tax'), money(p.tax)),
    Number(p.deliveryFee) > 0 ? row(t('pos.deliveryFee'), money(p.deliveryFee)) : null,
    h('hr'),
    row(t('pos.total'), money(p.total), 'big'),
    row(t('detail.payment'), t(`pay.${order.paymentMethod}`)),
    h('hr'),
    h('div', { class: 'c' }, 'Afiyet olsun! · Thank you!'));
}

export function itemsSummary(order) {
  return order.lines.map((line) => `${line.quantity}× ${line.name}`).join(', ');
}
