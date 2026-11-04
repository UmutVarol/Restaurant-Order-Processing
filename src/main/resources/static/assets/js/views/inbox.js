// The staff inbox: carts sent from tables to accept or turn down, and guests calling for a waiter or the bill.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { elapsed, money } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { confirmDialog, haptic, openDrawer, toast } from '../core/ui.js';
import { openOrderDrawer } from './shared.js';

const KIND_ICONS = { ORDER: 'basket', CALL_WAITER: 'bell', REQUEST_BILL: 'receipt' };

/**
 * Accepting, turning down and closing table requests, shared by the inbox drawer and the service screen:
 * {@code render} redraws while a request is being handled, {@code reload} fetches the list once it is done.
 */
export function guestActions(user, { render, reload }) {
  const busy = new Set();
  async function act(request, action, message) {
    busy.add(request.id);
    render();
    try {
      const result = await action();
      haptic('success');
      toast({ title: message(result) });
    } catch (error) {
      haptic('error');
      toast({ title: errorMessage(error), kind: 'error' });
    } finally {
      busy.delete(request.id);
      reload();
    }
  }
  return {
    busy,
    accept: (request) => act(request, () => api.acceptGuestRequest(request.id), (result) => {
      setTimeout(() => openOrderDrawer(result.orderId, user), 0);
      return t('inbox.accepted', { table: request.tableCode, id: result.orderId });
    }),
    async reject(request) {
      const reason = await confirmDialog({
        title: t('inbox.rejectTitle', { table: request.tableCode }), text: t('inbox.rejectText'), confirmLabel: t('inbox.reject'),
        danger: true, input: { placeholder: t('inbox.rejectPlaceholder'), maxlength: 140, required: true },
      });
      if (reason) act(request, () => api.rejectGuestRequest(request.id, reason), () => t('inbox.rejected', { table: request.tableCode }));
    },
    done: (request) => act(request, () => api.doneGuestRequest(request.id), () => t('inbox.handled', { table: request.tableCode })),
  };
}

/** One cart or call from a table, with its actions. */
export function requestCard(request, actions) {
  const busy = actions.busy.has(request.id);
  return h('article', { class: ['inbox-item', `k-${request.kind}`], 'data-testid': `inbox-${request.kind}` },
    h('div', { class: 'inbox-head' },
      h('span', { class: 'inbox-ic' }, icon(KIND_ICONS[request.kind], 'sm')),
      h('div', { style: { flex: 1 } }, h('b', null, `${t('pos.table')} ${request.tableCode} · ${t(`inbox.kind.${request.kind}`)}`),
        h('small', { class: 'faint' }, `${elapsed(request.createdAt)}${request.guestName ? ` · ${request.guestName}` : ''}`)),
      request.quotedTotal != null ? h('b', { class: 'num' }, money(request.quotedTotal)) : null),
    request.lines.length ? h('ul', { class: 'inbox-lines' }, request.lines.map((line) => h('li', null,
      h('span', { dir: 'auto' }, `${line.quantity} × ${line.icon || ''} ${line.name}`,
        line.options ? h('small', null, line.options) : null,
        line.note ? h('small', { class: 'note' }, `“${line.note}”`) : null),
      h('span', { class: 'num' }, money(line.lineTotal))))) : null,
    request.note ? h('div', { class: 'sticky-note', dir: 'auto' }, icon('note', 'sm'), request.note) : null,
    request.holdReason ? h('div', { class: 'inbox-hold', 'data-testid': 'inbox-hold' }, icon('info', 'sm'), t(`inbox.hold.${request.holdReason}`)) : null,
    h('div', { class: 'inbox-actions' }, request.kind === 'ORDER'
      ? [
        h('button', { class: 'btn ghost sm', disabled: busy, onclick: () => actions.reject(request) }, t('inbox.reject')),
        h('button', { class: 'btn primary sm', disabled: busy, 'data-testid': 'inbox-accept', onclick: () => actions.accept(request) },
          icon('check', 'sm'), t('inbox.accept')),
      ]
      : h('button', { class: 'btn primary sm', disabled: busy, 'data-testid': 'inbox-done', onclick: () => actions.done(request) },
        icon('check', 'sm'), t('inbox.done'))));
}

export function openInbox(user) {
  let requests = null;
  let unsubscribe = () => {};

  const drawer = openDrawer((handle) => [
    h('div', { class: 'drawer-head' },
      h('div', { style: { flex: 1 } }, h('h2', null, t('inbox.title')),
        h('div', { class: 'muted', style: { marginTop: '6px', fontSize: '13px' } }, t('inbox.sub'))),
      h('button', { class: 'icon-btn', 'aria-label': t('common.close'), onclick: handle.close }, icon('x'))),
    h('div', { class: 'drawer-body' }, content()),
  ], { onClose: () => unsubscribe() });
  const actions = guestActions(user, { render: () => drawer.render(), reload: () => load() });

  function content() {
    if (!requests) return [1, 2].map(() => h('div', { class: 'skeleton', style: { height: '120px' } }));
    if (!requests.length) {
      return h('div', { class: 'empty' }, h('div', { class: 'art' }, '🛎️'), h('h4', null, t('inbox.empty')), h('p', null, t('inbox.emptyText')));
    }
    return requests.map((request) => requestCard(request, actions));
  }

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
