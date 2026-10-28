// Making an order cheaper after it was placed: voiding or comping units of a line, and a manual discount.
// A waiter needs a manager standing by to approve with their PIN; a manager approves their own.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { money } from '../core/format.js';
import { api, ApiError, errorMessage } from '../core/api.js';
import { openModal, toast } from '../core/ui.js';

const REASONS = {
  VOID: ['adjust.r.mistake', 'adjust.r.changedMind', 'adjust.r.outOfStock'],
  COMP: ['adjust.r.longWait', 'adjust.r.quality', 'adjust.r.regular'],
  DISCOUNT: ['adjust.r.regular', 'adjust.r.staff', 'adjust.r.complaint'],
};
const LAST_MANAGER = 'sofra.lastManager';

/** Whether this change needs a manager's PIN, as the server decides it. */
export function needsApproval(user, kind, order) {
  if (user.role === 'ADMIN') return false;
  return kind !== 'VOID' || order.status !== 'PENDING';
}

/** Resolves to the updated order, or null when dismissed. */
export function adjustLine(order, line, kind, user) {
  const max = line.quantity - line.voidedQuantity - line.compedQuantity;
  let quantity = 1;
  const stepper = h('div', { class: 'stepper big' });
  const renderStepper = () => mount(stepper,
    h('button', { type: 'button', 'aria-label': t('adjust.less'), disabled: quantity <= 1, onclick: () => { quantity -= 1; renderStepper(); } }, icon('minus', 'sm')),
    h('span', { 'data-testid': 'adjust-quantity' }, quantity),
    h('button', { type: 'button', 'aria-label': t('adjust.more'), disabled: quantity >= max, onclick: () => { quantity += 1; renderStepper(); } }, icon('plus', 'sm')),
    h('span', { class: 'faint' }, `× ${money(line.unitPrice)} = ${money(line.unitPrice * quantity)}`));
  renderStepper();
  return dialog({
    title: t(`adjust.${kind}.title`, { name: line.name }),
    intro: t(`adjust.${kind}.text`),
    fields: [h('div', { class: 'field' }, h('span', { class: 'label' }, t('adjust.quantity')), stepper)],
    kind, order, user,
    send: (reason, approval) => api.adjustLine(order.id, line.id, { kind, quantity, reason, approval }),
    done: (updated) => t(`adjust.${kind}.done`, { n: quantity, name: line.name, id: updated.id }),
  });
}

export function discountOrder(order, user) {
  let mode = order.manualDiscount?.amount != null ? 'amount' : 'percent';
  const value = h('input', {
    class: 'input', id: 'discount-value', inputmode: 'decimal', autocomplete: 'off',
    value: order.manualDiscount ? String(order.manualDiscount.percent ?? order.manualDiscount.amount) : '',
  });
  const toggle = h('div', { class: 'segmented' });
  const chips = h('div', { class: 'chips' });
  const renderMode = () => {
    mount(toggle, ['percent', 'amount'].map((m) => h('button', {
      type: 'button', class: mode === m ? 'active' : '', role: 'radio', 'aria-checked': String(mode === m),
      onclick: () => { mode = m; renderMode(); },
    }, t(`adjust.${m}`))));
    mount(chips, mode === 'percent' ? [5, 10, 15, 20].map((p) => h('button', {
      type: 'button', class: ['chip', value.value === String(p) ? 'active' : ''], onclick: () => { value.value = String(p); renderMode(); },
    }, `%${p}`)) : null);
  };
  value.addEventListener('input', renderMode);
  renderMode();
  return dialog({
    title: t('adjust.DISCOUNT.title', { id: order.id }),
    intro: t('adjust.DISCOUNT.text'),
    fields: [toggle, h('div', { class: 'field' }, h('label', { for: 'discount-value' }, t('adjust.value')), value), chips],
    kind: 'DISCOUNT', order, user,
    extra: order.manualDiscount ? (finish) => h('button', {
      type: 'button', class: 'btn ghost danger-text', 'data-testid': 'remove-discount',
      onclick: () => finish(true),
    }, t('adjust.removeDiscount')) : null,
    validate: (removing) => {
      if (removing) return null;
      const number = Number(value.value.replace(',', '.'));
      if (!(number > 0) || (mode === 'percent' && number > 100)) { value.focus(); return t('adjust.valueInvalid'); }
      return null;
    },
    send: (reason, approval, removing) => {
      const number = Number(value.value.replace(',', '.'));
      return api.discount(order.id, removing ? { reason, approval }
        : { [mode]: number, reason, approval });
    },
    done: (updated) => (updated.manualDiscount ? t('adjust.DISCOUNT.done', { id: updated.id }) : t('adjust.DISCOUNT.removed', { id: updated.id })),
  });
}

function dialog({ title, intro, fields, kind, order, user, send, done, validate, extra }) {
  return new Promise((resolve) => {
    let settled = false;
    let approvalShown = needsApproval(user, kind, order);
    const reason = h('input', { class: 'input', id: 'adjust-reason', maxlength: 140, autocomplete: 'off', placeholder: t('adjust.reasonPlaceholder') });
    const quick = h('div', { class: 'chips' }, REASONS[kind].map((key) => h('button', {
      type: 'button', class: 'chip', onclick: () => { reason.value = t(key); reason.classList.remove('invalid'); },
    }, t(key))));
    const manager = h('input', { class: 'input', id: 'approve-manager', autocomplete: 'off', value: readLastManager() });
    const pin = h('input', { class: 'input pin-input', id: 'approve-pin', type: 'password', inputmode: 'numeric', autocomplete: 'off', maxlength: 6 });
    const approvalBox = h('div', { class: 'approval' });
    const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
    const confirm = h('button', { class: 'btn primary', 'data-testid': 'adjust-confirm', onclick: () => submit(false) }, icon('check', 'sm'), t(`adjust.${kind}.confirm`));

    const renderApproval = () => mount(approvalBox, approvalShown ? [
      h('div', { class: 'approval-head' }, icon('lock', 'sm'), h('b', null, t('adjust.approval')), h('span', { class: 'faint' }, t('adjust.approvalHint'))),
      h('div', { class: 'grid-2' },
        h('div', { class: 'field' }, h('label', { for: 'approve-manager' }, t('adjust.manager')), manager),
        h('div', { class: 'field' }, h('label', { for: 'approve-pin' }, t('adjust.managerPin')), pin)),
    ] : null);
    renderApproval();

    const modal = openModal({
      title,
      size: 'sm',
      body: [
        h('p', { class: 'muted' }, intro),
        ...fields,
        h('div', { class: 'field' }, h('label', { for: 'adjust-reason' }, t('adjust.reason')), reason, quick),
        approvalBox,
        problem,
      ],
      footer: (handle) => [
        extra ? extra(submit) : null,
        h('span', { style: { flex: 1 } }),
        h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')),
        confirm,
      ],
      onClose: () => { if (!settled) resolve(null); },
    });

    async function submit(removing) {
      problem.hidden = true;
      const invalid = validate?.(removing);
      if (invalid) return fail(invalid);
      if (!reason.value.trim()) { reason.classList.add('invalid'); reason.focus(); return fail(t('adjust.reasonRequired')); }
      let approval = null;
      if (approvalShown) {
        if (!manager.value.trim()) { manager.focus(); return fail(t('adjust.managerRequired')); }
        if (!/^\d{4,6}$/.test(pin.value)) { pin.focus(); return fail(t('settings.pinFormat')); }
        approval = { manager: manager.value.trim(), pin: pin.value };
      }
      confirm.disabled = true;
      try {
        const updated = await send(reason.value.trim(), approval, removing);
        if (approval) saveLastManager(approval.manager);
        settled = true;
        toast({ title: done(updated) });
        resolve(updated);
        modal.close();
      } catch (error) {
        confirm.disabled = false;
        pin.value = '';
        if (error instanceof ApiError && error.code === 'APPROVAL_REQUIRED' && !approvalShown) {
          approvalShown = true;
          renderApproval();
          manager.focus();
        }
        fail(errorMessage(error));
      }
    }

    function fail(message) {
      problem.replaceChildren(icon('alert', 'sm'), message);
      problem.hidden = false;
    }
  });
}

function readLastManager() {
  try { return localStorage.getItem(LAST_MANAGER) || ''; } catch { return ''; }
}

function saveLastManager(username) {
  try { localStorage.setItem(LAST_MANAGER, username); } catch { /* ignore */ }
}
