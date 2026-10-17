// Sign-in screen with one-click demo accounts.
import { h, initials } from '../core/dom.js';
import { icon, logoMark } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { api, errorMessage } from '../core/api.js';
import { langButton, themeButton } from '../core/ui.js';

const DEMO_ACCOUNTS = [
  { username: 'admin', password: 'admin123', name: 'Elif Yılmaz', role: 'ADMIN' },
  { username: 'waiter', password: 'waiter123', name: 'Can Demir', role: 'WAITER' },
  { username: 'chef', password: 'chef123', name: 'Mehmet Usta', role: 'KITCHEN' },
];

export default function login({ onSignedIn }) {
  const username = h('input', { class: 'input', name: 'username', autocomplete: 'username', required: true, autofocus: true });
  const password = h('input', { class: 'input', name: 'password', type: 'password', autocomplete: 'current-password', required: true });
  const error = h('div', { class: 'form-error', hidden: true, role: 'alert' });
  const submit = h('button', { class: 'btn primary lg block', type: 'submit' }, t('login.submit'), icon('arrowRight', 'sm'));

  async function signIn(user, pass) {
    error.hidden = true;
    submit.disabled = true;
    submit.replaceChildren(h('span', { class: 'spinner' }));
    try {
      onSignedIn(await api.login(user, pass));
    } catch (failure) {
      error.replaceChildren(icon('alert', 'sm'), errorMessage(failure));
      error.hidden = false;
      submit.disabled = false;
      submit.replaceChildren(t('login.submit'), icon('arrowRight', 'sm'));
    }
  }

  const form = h('form', {
    onsubmit: (event) => {
      event.preventDefault();
      signIn(username.value.trim(), password.value);
    },
  },
  h('div', { class: 'field' }, h('label', { for: 'username' }, t('login.username')), Object.assign(username, { id: 'username' })),
  h('div', { class: 'field' }, h('label', { for: 'password' }, t('login.password')), Object.assign(password, { id: 'password' })),
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
      h('div', { class: 'login-card' },
        h('div', null, h('h2', null, t('login.title')), h('p', { class: 'muted', style: { marginTop: '6px' } }, t('login.subtitle'))),
        form,
        h('div', { class: 'divider' }, `${t('login.demo')} — ${t('login.demoHint')}`),
        demo,
        h('p', { class: 'login-foot' }, h('a', { href: 'swagger-ui.html', target: '_blank', rel: 'noopener' }, t('login.api')),
          ' · Spring Boot 4 · Java 21'))));
}

function stat(value, label) {
  return h('div', { class: 'hero-stat' }, h('b', null, value), h('span', null, label));
}

function floatCard(emoji, title, sub) {
  return h('div', { class: 'float-card' }, h('div', { class: 'emoji' }, emoji), h('div', null, h('b', null, title), h('small', null, sub)));
}
