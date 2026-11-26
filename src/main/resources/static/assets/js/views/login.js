// Sign-in screen with one-click demo accounts. A registered till opens on the staff lock screen instead.
import { h, initials, mount } from '../core/dom.js';
import { icon, logoMark } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { api, businessCode, currentDevice, errorMessage, setBusinessCode } from '../core/api.js';
import { langButton, themeButton } from '../core/ui.js';
import { lockScreen } from './lock.js';

const DEMO_ACCOUNTS = [
  { username: 'admin', password: 'admin123', name: 'Elif Yılmaz', role: 'ADMIN' },
  { username: 'waiter', password: 'waiter123', name: 'Can Demir', role: 'WAITER' },
  { username: 'chef', password: 'chef123', name: 'Mehmet Usta', role: 'KITCHEN' },
];

export default function login({ onSignedIn }) {
  const card = h('div', { class: 'login-card' });
  const showPassword = () => mount(card, passwordForm({ onSignedIn, onPin: showLock }));
  const showLock = () => mount(card, lockScreen({ onSignedIn, onPassword: showPassword }));
  if (currentDevice()) showLock(); else showPassword();

  const hero = h('div', { class: 'login-hero' },
    h('div', { class: 'brand' }, logoMark(), h('div', null, h('div', { class: 'brand-name' }, 'Sofra'), h('div', { class: 'brand-sub' }, t('app.tagline')))),
    h('div', { style: { position: 'relative', zIndex: 1 } },
      h('h1', null, t('login.heroA'), ' ', h('em', null, t('login.heroB'))),
      h('p', { class: 'lead' }, t('login.lead'))),
    h('div', { class: 'hero-stats' },
      stat('SSE', t('login.stat1')),
      stat('3', t('login.stat2')),
      stat('₺0,01', t('login.stat3'))),
    h('div', { class: 'floating', 'aria-hidden': 'true' },
      floatCard('🍢', t('login.float1'), 'Adana Kebap × 2 · Ayran'),
      floatCard('🧀', t('login.float2'), 'Künefe · 4:12'),
      floatCard('🛵', t('login.float3'), 'Moda Cad. 12, Kadıköy')));

  return h('div', { class: 'login' },
    hero,
    h('div', { class: 'login-panel' },
      h('div', { class: 'login-tools' }, langButton(), themeButton()),
      card));
}

function passwordForm({ onSignedIn, onPin }) {
  // on a server shared by many businesses: which one this device signs in to, asked once and remembered
  const business = h('input', { class: 'input', id: 'business', name: 'business', autocomplete: 'organization', autocapitalize: 'none', spellcheck: false, value: businessCode() ?? '' });
  const businessField = h('div', { class: 'field', hidden: true }, h('label', { for: 'business' }, t('login.businessCode')), business,
    h('small', { class: 'faint' }, t('login.businessCodeHint')));
  const username = h('input', { class: 'input', name: 'username', autocomplete: 'username', required: true, autofocus: true });
  const password = h('input', { class: 'input', name: 'password', type: 'password', autocomplete: 'current-password', required: true });
  const error = h('div', { class: 'form-error', hidden: true, role: 'alert' });
  const submit = h('button', { class: 'btn primary lg block', type: 'submit' }, t('login.submit'), icon('arrowRight', 'sm'));
  // Two-step sign-in: shown once the server says the password was right and a code is needed.
  const code = h('input', { class: 'input pin-input', id: 'login-code', name: 'code', autocomplete: 'one-time-code', inputmode: 'numeric', maxlength: 11 });
  const codeField = h('div', { class: 'field', hidden: true }, h('label', { for: 'login-code' }, t('login.code')), code,
    h('small', { class: 'faint' }, t('login.codeHint')));

  async function signIn(user, pass) {
    error.hidden = true;
    submit.disabled = true;
    submit.replaceChildren(h('span', { class: 'spinner' }));
    try {
      onSignedIn(await api.login(user, pass, codeField.hidden ? null : code.value.trim()));
    } catch (failure) {
      if (failure.code === 'TOTP_REQUIRED') {
        codeField.hidden = false;
        submit.disabled = false;
        submit.replaceChildren(t('login.submit'), icon('arrowRight', 'sm'));
        code.focus();
        return;
      }
      if (failure.code === 'TOTP_INVALID') code.select();
      error.replaceChildren(icon('alert', 'sm'), errorMessage(failure));
      error.hidden = false;
      submit.disabled = false;
      submit.replaceChildren(t('login.submit'), icon('arrowRight', 'sm'));
    }
  }

  const form = h('form', {
    onsubmit: (event) => {
      event.preventDefault();
      if (!businessField.hidden) setBusinessCode(business.value.trim().toLowerCase());
      signIn(username.value.trim(), password.value);
    },
  },
  businessField,
  h('div', { class: 'field' }, h('label', { for: 'username' }, t('login.username')), Object.assign(username, { id: 'username' })),
  h('div', { class: 'field' }, h('label', { for: 'password' }, t('login.password')), Object.assign(password, { id: 'password' })),
  codeField,
  error,
  submit);

  const demo = h('div', { class: 'demo-accounts' }, DEMO_ACCOUNTS.map((account) => h('button', {
    class: 'demo-account',
    type: 'button',
    onclick: () => {
      username.value = account.username;
      password.value = account.password;
      signIn(account.username, account.password);
    },
  },
  h('div', { class: 'avatar' }, initials(account.name)),
  h('div', null, h('b', null, account.name), h('small', null, `${account.username} · ${account.password}`)),
  h('span', { class: 'pill plain role-tag' }, t(`role.${account.role}`)))));

  // The one-tap demo accounts exist only in the demo; a restaurant's own server never offers them.
  const demoBlock = h('div', { hidden: true }, h('div', { class: 'divider' }, `${t('login.demo')} — ${t('login.demoHint')}`), demo);
  const foot = h('p', { class: 'login-foot', hidden: true });
  const subtitle = h('p', { class: 'muted', style: { marginTop: '6px' } }, t('login.subtitle'));
  const trial = h('p', { class: 'login-foot', hidden: true }, h('a', { href: '#/signup', 'data-testid': 'try-free' }, t('login.tryFree')));
  publicConfig().then((config) => {
    if (config.businessCodeNeeded) businessField.hidden = false;
    if (config.signupEnabled) trial.hidden = false;
    if (config.demo) {
      demoBlock.hidden = false;
      foot.hidden = false;
      mount(foot, window.SOFRA_BACKEND?.demo
        ? [t('login.demoMode'), ' · ', h('a', { href: '#/sofra', 'data-testid': 'demo-guest' }, t('login.tryGuest')), ' · ',
          h('a', { href: 'https://github.com/UmutVarol/Restaurant-Order-Processing', target: '_blank', rel: 'noopener' }, t('login.source'))]
        : [h('a', { href: 'swagger-ui.html', target: '_blank', rel: 'noopener' }, t('login.api')), ' · Spring Boot 4 · Java 21']);
    } else if (config.restaurantName) {
      subtitle.textContent = config.restaurantName;
    }
  });

  return [
    h('div', null, h('h2', null, t('login.title')), subtitle),
    form,
    currentDevice() ? h('button', { class: 'btn ghost sm lock-alt', type: 'button', onclick: onPin }, icon('lock', 'sm'), t('lock.usePin')) : null,
    demoBlock,
    foot,
    trial,
  ];
}

let configRequest = null;
function publicConfig() {
  configRequest ??= api.publicConfig().catch(() => { configRequest = null; return { demo: false, restaurantName: null }; });
  return configRequest;
}

function stat(value, label) {
  return h('div', { class: 'hero-stat' }, h('b', null, value), h('span', null, label));
}

function floatCard(emoji, title, sub) {
  return h('div', { class: 'float-card' }, h('div', { class: 'emoji' }, emoji), h('div', null, h('b', null, title), h('small', null, sub)));
}
