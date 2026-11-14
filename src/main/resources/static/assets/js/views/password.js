// First sign-in with a password someone else chose (the installer's, or a manager's reset): choose your own before
// anything else. The server refuses every other request until then.
import { h } from '../core/dom.js';
import { icon, logoMark } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { api, errorMessage } from '../core/api.js';
import { langButton, themeButton } from '../core/ui.js';

export default function passwordScreen({ user, onChanged, onSignOut }) {
  const current = h('input', { class: 'input', id: 'pw-current', type: 'password', autocomplete: 'current-password', required: true, autofocus: true });
  const next = h('input', { class: 'input', id: 'pw-new', type: 'password', autocomplete: 'new-password', required: true, minlength: 10, maxlength: 128 });
  const again = h('input', { class: 'input', id: 'pw-again', type: 'password', autocomplete: 'new-password', required: true, maxlength: 128 });
  const error = h('div', { class: 'form-error', hidden: true, role: 'alert' });
  const submit = h('button', { class: 'btn primary lg block', type: 'submit', 'data-testid': 'pw-save' }, t('password.save'), icon('check', 'sm'));
  const fail = (message, field) => { error.replaceChildren(icon('alert', 'sm'), message); error.hidden = false; field?.focus(); };

  // checked here rather than by the browser, so the explanation is in the user's language and in view
  const form = h('form', {
    novalidate: true,
    onsubmit: async (event) => {
      event.preventDefault();
      error.hidden = true;
      if (next.value.length < 10) return fail(t('error.PASSWORD_TOO_SHORT', { min: 10 }), next);
      if (next.value !== again.value) return fail(t('password.mismatch'), again);
      submit.disabled = true;
      try {
        onChanged(await api.changePassword(current.value, next.value));
      } catch (failure) {
        fail(errorMessage(failure), failure.code === 'PASSWORD_INCORRECT' ? current : next);
        submit.disabled = false;
      }
    },
  },
  h('div', { class: 'field' }, h('label', { for: 'pw-current' }, t('password.current')), current),
  h('div', { class: 'field' }, h('label', { for: 'pw-new' }, t('password.new')), next, h('small', { class: 'faint' }, t('password.hint'))),
  h('div', { class: 'field' }, h('label', { for: 'pw-again' }, t('password.again')), again),
  error,
  submit);

  return h('div', { class: 'login' },
    h('div', { class: 'login-hero' },
      h('div', { class: 'brand' }, logoMark(), h('div', null, h('div', { class: 'brand-name' }, 'Sofra'), h('div', { class: 'brand-sub' }, t('app.tagline')))),
      h('div', { style: { position: 'relative', zIndex: 1 } }, h('h1', null, t('password.heroA'), ' ', h('em', null, t('password.heroB'))),
        h('p', { class: 'lead' }, t('password.lead')))),
    h('div', { class: 'login-panel' },
      h('div', { class: 'login-tools' }, langButton(), themeButton()),
      h('div', { class: 'login-card' },
        h('div', null, h('h2', null, t('password.title')), h('p', { class: 'muted', style: { marginTop: '6px' } }, t('password.subtitle', { name: user.displayName }))),
        form,
        h('button', { class: 'btn ghost sm', type: 'button', onclick: onSignOut }, icon('logout', 'sm'), t('action.logout')))));
}
