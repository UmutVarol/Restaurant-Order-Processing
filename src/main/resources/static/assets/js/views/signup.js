// Sign-up for Sofra Cloud: a business name, an address, the owner's account. A minute later the owner is signed in
// to a working restaurant with sample tables and dishes, on a 14-day trial.
import { debounce, h } from '../core/dom.js';
import { icon, logoMark } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { api, errorMessage, setBusinessCode } from '../core/api.js';
import { langButton, themeButton } from '../core/ui.js';

export default function signup({ onSignedIn }) {
  const field = (id, label, control, hint) => h('div', { class: 'field' }, h('label', { for: id }, label), control, hint ?? null);
  const name = h('input', { class: 'input', id: 'su-name', maxlength: 80, required: true, autocomplete: 'organization', autofocus: true });
  const address = h('input', { class: 'input', id: 'su-address', maxlength: 40, autocapitalize: 'none', spellcheck: false, pattern: '[a-z0-9-]{3,40}' });
  const addressState = h('small', { class: 'faint', 'data-testid': 'su-address-state' }, t('signup.addressHint'));
  const owner = h('input', { class: 'input', id: 'su-owner', maxlength: 80, required: true, autocomplete: 'name' });
  const email = h('input', { class: 'input', id: 'su-email', type: 'email', maxlength: 120, required: true, autocomplete: 'email' });
  const username = h('input', { class: 'input', id: 'su-username', maxlength: 50, required: true, autocomplete: 'username', autocapitalize: 'none', spellcheck: false });
  const password = h('input', { class: 'input', id: 'su-password', type: 'password', maxlength: 128, required: true, autocomplete: 'new-password' });
  const error = h('div', { class: 'form-error', hidden: true, role: 'alert' });
  const submit = h('button', { class: 'btn primary lg block', type: 'submit', 'data-testid': 'su-submit' }, t('signup.submit'), icon('arrowRight', 'sm'));

  // the address follows the name until the owner types one; each change says whether it is free
  const check = debounce(async () => {
    if (!name.value.trim() && !address.value.trim()) return;
    try {
      const result = await api.checkAddress(name.value.trim() || address.value.trim(), address.dataset.touched ? address.value.trim() : '');
      if (!address.dataset.touched) address.value = result.address;
      addressState.textContent = result.free ? t('signup.addressFree', { address: result.address }) : t('signup.addressTaken');
      addressState.className = result.free ? 'faint' : 'form-error-text';
    } catch { /* the check is a convenience */ }
  }, 300);
  name.addEventListener('input', check);
  address.addEventListener('input', () => { address.dataset.touched = '1'; check(); });

  const form = h('form', {
    novalidate: true,
    onsubmit: async (event) => {
      event.preventDefault();
      error.hidden = true;
      if (password.value.length < 10) {
        error.replaceChildren(icon('alert', 'sm'), t('error.PASSWORD_TOO_SHORT', { min: 10 }));
        error.hidden = false;
        password.focus();
        return;
      }
      submit.disabled = true;
      try {
        const result = await api.signUp({
          businessName: name.value.trim(), address: address.value.trim() || null, email: email.value.trim(),
          ownerName: owner.value.trim(), username: username.value.trim().toLowerCase(), password: password.value,
        });
        setBusinessCode(result.address);
        // C2: the new restaurant is greeted once, on the setup screen
        try { sessionStorage.setItem('sofra.justOpened', '1'); } catch { /* private mode */ }
        location.hash = '#/setup';
        onSignedIn(result.session);
      } catch (failure) {
        error.replaceChildren(icon('alert', 'sm'), errorMessage(failure));
        error.hidden = false;
        submit.disabled = false;
      }
    },
  },
  field('su-name', t('signup.businessName'), name),
  field('su-address', t('signup.address'), address, addressState),
  h('div', { class: 'grid-2' }, field('su-owner', t('signup.ownerName'), owner), field('su-email', t('signup.email'), email)),
  h('div', { class: 'grid-2' }, field('su-username', t('login.username'), username), field('su-password', t('login.password'), password)),
  h('small', { class: 'faint' }, t('password.hint')),
  error,
  submit);

  return h('div', { class: 'login' },
    h('div', { class: 'login-hero' },
      h('div', { class: 'brand' }, logoMark(), h('div', null, h('div', { class: 'brand-name' }, 'Sofra'), h('div', { class: 'brand-sub' }, t('app.tagline')))),
      h('div', { style: { position: 'relative', zIndex: 1 } }, h('h1', null, t('signup.heroA'), ' ', h('em', null, t('signup.heroB'))),
        h('p', { class: 'lead' }, t('signup.lead')))),
    h('div', { class: 'login-panel' },
      h('div', { class: 'login-tools' }, langButton(), themeButton()),
      h('div', { class: 'login-card' },
        h('div', null, h('h2', null, t('signup.title')), h('p', { class: 'muted', style: { marginTop: '6px' } }, t('signup.subtitle'))),
        form,
        h('p', { class: 'login-foot' }, h('a', { href: '#/' }, t('signup.haveAccount'))))));
}
