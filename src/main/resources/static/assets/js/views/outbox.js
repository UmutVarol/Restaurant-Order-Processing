// Orders this till took while the server could not be reached. Most go out by themselves when it comes back; one
// whose prices or dishes changed in the meantime waits here for the waiter to send it at the new total or drop it.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { dateTime, money } from '../core/format.js';
import { errorMessage, ApiError } from '../core/api.js';
import { bus } from '../core/store.js';
import { confirmDialog, openModal } from '../core/ui.js';
import { decide, entries, flush, isOnline } from '../core/offline.js';

export function openOutbox() {
  const list = h('div', { class: 'outbox-list', 'data-testid': 'outbox-list' });
  const status = h('p', { class: 'muted' });

  async function render() {
    const waiting = await entries();
    status.textContent = isOnline() ? t('outbox.online') : t('outbox.offline');
    mount(list, waiting.length ? waiting.map(row) : h('div', { class: 'empty' }, h('div', { class: 'art' }, '✅'), h('h4', null, t('outbox.empty'))));
  }

  function row(entry) {
    const order = entry.order;
    const items = order.items.reduce((sum, item) => sum + item.quantity, 0);
    const where = order.type === 'DINE_IN' ? t('outbox.table', { code: entry.seen.tableCode ?? '?' }) : t(`type.${order.type}`);
    return h('div', { class: ['outbox-row', `s-${entry.status}`], dataset: { key: entry.key } },
      h('div', { class: 'outbox-info' },
        h('b', null, `${order.customerName} · ${where}`),
        h('small', null, `${t('outbox.items', { n: items })}${entry.seen.total != null ? ` · ${money(entry.seen.total)}` : ''}`
          + `${entry.payment ? ` · ${t('outbox.paid', { method: t(`pay.${entry.payment.method}`) })}` : ''}`),
        h('small', { class: 'faint' }, dateTime(new Date(entry.createdAt).toISOString())),
        entry.status === 'pending' ? h('span', { class: 'pill plain' }, t('outbox.state.pending')) : problem(entry)),
      entry.status === 'pending' ? null : h('div', { class: 'outbox-actions' },
        h('button', { class: 'btn sm ghost', 'data-testid': 'outbox-discard', onclick: async () => {
          if (!(await confirmDialog({ title: t('outbox.discardTitle'), text: t('outbox.discardText'), confirmLabel: t('outbox.discard'), danger: true }))) return;
          await decide(entry.key, false);
          render();
        } }, t('outbox.discard')),
        entry.status === 'review' ? h('button', { class: 'btn sm primary', 'data-testid': 'outbox-send', onclick: async () => {
          await decide(entry.key, true);
          render();
        } }, icon('check', 'sm'), t('outbox.sendAnyway')) : null));
  }

  function problem(entry) {
    const p = entry.problem ?? {};
    let text;
    if (p.code === 'PRICES_CHANGED' || p.code === 'COUPON_CHANGED') {
      text = p.oldTotal != null ? t('outbox.changedTotal', { old: money(p.oldTotal), now: money(p.newTotal) }) : t('outbox.changed', { now: money(p.newTotal) });
      if (p.code === 'COUPON_CHANGED') text = `${t('outbox.couponLost')} ${text}`;
    } else {
      text = errorMessage(new ApiError(422, { code: p.code, detail: p.message, parameters: p.parameters }));
    }
    return h('div', { class: 'form-error', 'data-testid': 'outbox-problem' }, icon('alert', 'sm'), text);
  }

  const off = bus.on('outbox', render);
  openModal({
    title: t('outbox.title'),
    body: [status, list],
    footer: (handle) => [
      h('button', { class: 'btn ghost', onclick: handle.close }, t('common.close')),
      h('button', { class: 'btn primary', 'data-testid': 'outbox-retry', onclick: () => flush() }, icon('refresh', 'sm'), t('outbox.retry')),
    ],
    onClose: off,
  });
  render();
}
