// The staff inbox: carts sent from tables to accept or turn down, and guests calling for a waiter or the bill.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { elapsed, money } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { confirmDialog, openDrawer, toast } from '../core/ui.js';
import { openOrderDrawer } from './shared.js';

const KIND_ICONS = { ORDER: 'basket', CALL_WAITER: 'bell', REQUEST_BILL: 'receipt' };

export function openInbox(user) {
  let requests = null;
  let busy = new Set();
  let unsubscribe = () => {};

  const drawer = openDrawer((handle) => [
    h('div', { class: 'drawer-head' },
      h('div', { style: { flex: 1 } }, h('h2', null, t('inbox.title')),
        h('div', { class: 'muted', style: { marginTop: '6px', fontSize: '13px' } }, t('inbox.sub'))),
      h('button', { class: 'icon-btn', 'aria-label': t('common.close'), onclick: handle.close }, icon('x'))),
    h('div', { class: 'drawer-body' }, content()),
  ], { onClose: () => unsubscribe() });

  function content() {
    if (!requests) return [1, 2].map(() => h('div', { class: 'skeleton', style: { height: '120px' } }));
    if (!requests.length) {
      return h('div', { class: 'empty' }, h('div', { class: 'art' }, '🛎️'), h('h4', null, t('inbox.empty')), h('p', null, t('inbox.emptyText')));
    }
    return requests.map((request) => h('article', { class: ['inbox-item', `k-${request.kind}`], 'data-testid': `inbox-${request.kind}` },
      h('div', { class: 'inbox-head' },
        h('span', { class: 'inbox-ic' }, icon(KIND_ICONS[request.kind], 'sm')),
        h('div', { style: { flex: 1 } }, h('b', null, `${t('pos.table')} ${request.tableCode} · ${t(`inbox.kind.${request.kind}`)}`),
          h('small', { class: 'faint' }, `${elapsed(request.createdAt)}${request.guestName ? ` · ${request.guestName}` : ''}`)),
        request.quotedTotal != null ? h('b', { class: 'num' }, money(request.quotedTotal)) : null),
      request.lines.length ? h('ul', { class: 'inbox-lines' }, request.lines.map((line) => h('li', null,
        h('span', null, `${line.quantity} × ${line.icon || ''} ${line.name}`,
          line.options ? h('small', null, line.options) : null,
          line.note ? h('small', { class: 'note' }, `“${line.note}”`) : null),
        h('span', { class: 'num' }, money(line.lineTotal))))) : null,
      request.note ? h('div', { class: 'sticky-note' }, icon('note', 'sm'), request.note) : null,
      h('div', { class: 'inbox-actions' }, request.kind === 'ORDER'
        ? [
          h('button', { class: 'btn ghost sm', disabled: busy.has(request.id), onclick: () => reject(request) }, t('inbox.reject')),
          h('button', { class: 'btn primary sm', disabled: busy.has(request.id), 'data-testid': 'inbox-accept', onclick: () => accept(request) },
            icon('check', 'sm'), t('inbox.accept')),
        ]
        : h('button', { class: 'btn primary sm', disabled: busy.has(request.id), 'data-testid': 'inbox-done', onclick: () => done(request) },
          icon('check', 'sm'), t('inbox.done')))));
  }

  async function act(request, action, message) {
    busy.add(request.id);
    drawer.render();
    try {
      const result = await action();
      toast({ title: message(result) });
    } catch (error) {
      toast({ title: errorMessage(error), kind: 'error' });
    } finally {
      busy.delete(request.id);
      load();
    }
  }

  const accept = (request) => act(request, () => api.acceptGuestRequest(request.id), (result) => {
    setTimeout(() => openOrderDrawer(result.orderId, user), 0);
    return t('inbox.accepted', { table: request.tableCode, id: result.orderId });
  });

  async function reject(request) {
    const reason = await confirmDialog({
      title: t('inbox.rejectTitle', { table: request.tableCode }), text: t('inbox.rejectText'), confirmLabel: t('inbox.reject'),
      danger: true, input: { placeholder: t('inbox.rejectPlaceholder'), maxlength: 140, required: true },
    });
    if (reason) act(request, () => api.rejectGuestRequest(request.id, reason), () => t('inbox.rejected', { table: request.tableCode }));
  }

  const done = (request) => act(request, () => api.doneGuestRequest(request.id), () => t('inbox.handled', { table: request.tableCode }));

  async function load() {
    try {
      requests = await api.guestRequests();
    } catch (error) {
      requests = [];
      toast({ title: errorMessage(error), kind: 'error' });
    }
    drawer.render();
  }

  unsubscribe = bus.on('guest', load);
  load();
  return drawer;
}
