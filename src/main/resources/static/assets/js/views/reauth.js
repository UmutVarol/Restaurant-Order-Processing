// Step-up authentication: refunds and closing the day need a recent sign-in. When the server answers
// REAUTH_REQUIRED, ask for the password, sign in again (same lockout rules as the login screen) and retry once.
import { h } from '../core/dom.js';
import { t } from '../core/i18n.js';
import { api, ApiError, currentSession, errorMessage, saveSession } from '../core/api.js';
import { openModal } from '../core/ui.js';

export async function withRecentSignIn(action) {
  try {
    return await action();
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== 'REAUTH_REQUIRED') throw error;
    if (!(await confirmPassword())) return null;
    return action();
  }
}

function confirmPassword() {
  const username = currentSession()?.user?.username;
  return new Promise((resolve) => {
    let settled = false;
    const password = h('input', { class: 'input', id: 'reauth-password', type: 'password', autocomplete: 'current-password', autofocus: true });
    const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
    const modal = openModal({
      title: t('reauth.title'),
      size: 'sm',
      body: [
        h('p', { class: 'muted' }, t('reauth.text')),
        h('div', { class: 'field' }, h('label', { for: 'reauth-password' }, t('login.password')), password),
        problem,
      ],
      footer: (handle) => [
        h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')),
        h('button', { class: 'btn primary', 'data-testid': 'reauth-confirm', onclick: submit }, t('reauth.confirm')),
      ],
      onClose: () => { if (!settled) resolve(false); },
    });
    password.addEventListener('keydown', (event) => { if (event.key === 'Enter') submit(); });

    async function submit() {
      if (!password.value) { password.focus(); return; }
      try {
        saveSession(await api.login(username, password.value));
        settled = true;
        resolve(true);
        modal.close();
      } catch (error) {
        problem.hidden = false;
        problem.textContent = errorMessage(error);
        password.select();
      }
    }
  });
}
