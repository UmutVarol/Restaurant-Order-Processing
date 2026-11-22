// Round three: what the guest's own phone remembers between restaurants. A random device key (only its hash ever
// reaches a server, to count returning guests), the last visits for the "how was it?" card (P3), and the rating form
// shared by the paid receipt (P2) and that card. Nothing here identifies the guest.
import { h } from './dom.js';
import { icon } from './icons.js';
import { t } from './i18n.js';
import { api, errorMessage } from './api.js';
import { haptic, reducedMotion, toast } from './ui.js';

const DEVICE_KEY = 'sofra.device';
const VISITS_KEY = 'sofra.visits';
const TAGS = ['TASTE', 'SPEED', 'FRIENDLY', 'CLEAN', 'VALUE'];
/** A visit is offered for rating for this long after it was paid. */
const RATE_WINDOW_MS = 3 * 24 * 3600 * 1000;

/** The phone's random key; made once, kept on the phone. */
export function deviceKey() {
  try {
    let key = localStorage.getItem(DEVICE_KEY);
    if (!key || !/^[A-Za-z0-9_-]{8,64}$/.test(key)) {
      const bytes = crypto.getRandomValues(new Uint8Array(18));
      key = btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
      localStorage.setItem(DEVICE_KEY, key);
    }
    return key;
  } catch {
    return null;
  }
}

function readVisits() {
  try {
    const list = JSON.parse(localStorage.getItem(VISITS_KEY) || '[]');
    return Array.isArray(list) ? list.filter((v) => v && typeof v.token === 'string').slice(-10) : [];
  } catch {
    return [];
  }
}

function saveVisits(list) {
  try { localStorage.setItem(VISITS_KEY, JSON.stringify(list.slice(-10))); } catch { /* private mode */ }
}

/** A bill this phone paid: offered for rating on the guest app's home until rated or three days have passed. */
export function rememberVisit({ token, restaurant, at }) {
  const list = readVisits().filter((v) => v.token !== token);
  list.push({ token, restaurant, at: at || new Date().toISOString(), rated: false });
  saveVisits(list);
}

export function markRated(token) {
  saveVisits(readVisits().map((v) => (v.token === token ? { ...v, rated: true } : v)));
}

export function dismissVisit(token) {
  saveVisits(readVisits().map((v) => (v.token === token ? { ...v, dismissed: true } : v)));
}

/** The latest paid visit still waiting for a rating, if any. */
export function visitToRate(now = Date.now()) {
  return readVisits().reverse().find((v) => !v.rated && !v.dismissed && now - new Date(v.at).getTime() < RATE_WINDOW_MS) || null;
}

/**
 * The rating form: five stars first; once a star is chosen, what was good, the dishes liked and a comment open below.
 * Sends once. {@code onDone(stars)} after the restaurant has it.
 */
export function ratingForm({ token, dishes = [], restaurant, onDone, testid = 'rating' }) {
  let stars = 0;
  const tags = new Set();
  const liked = new Set();
  let sending = false;

  const starRow = h('div', { class: 'rate-stars', role: 'radiogroup', 'aria-label': t('rate.starsLabel') });
  const extra = h('div', { class: 'rate-extra', hidden: true });
  const comment = h('textarea', { class: 'input', id: `${testid}-comment`, rows: 2, maxlength: 500, placeholder: t('rate.commentHint') });
  const send = h('button', { class: 'btn primary block', type: 'button', 'data-testid': `${testid}-send`, disabled: true, onclick: submit }, t('rate.send'));
  const root = h('section', { class: 'rate-card', 'data-testid': testid, 'aria-labelledby': `${testid}-title` },
    h('h3', { id: `${testid}-title` }, restaurant ? t('rate.howWasAt', { name: restaurant }) : t('rate.howWas')),
    starRow, extra, send,
    h('small', { class: 'faint rate-fine' }, icon('check', 'sm'), t('rate.verified')));

  function paintStars(bumped) {
    starRow.replaceChildren(...[1, 2, 3, 4, 5].map((n) => h('button', {
      type: 'button', role: 'radio', 'aria-checked': String(n === stars), 'aria-label': t('rate.nStars', { n }),
      class: ['rate-star', n <= stars ? 'on' : '', n === bumped ? 'bump' : ''], 'data-testid': `${testid}-star-${n}`,
      onclick: () => choose(n),
    }, icon('star'))));
  }

  function choose(n) {
    stars = n;
    haptic('tap');
    paintStars(reducedMotion() ? 0 : n);
    send.disabled = false;
    if (extra.hidden) {
      extra.hidden = false;
      paintExtra();
    }
  }

  function chip(label, set, value, id) {
    return h('button', {
      type: 'button', class: ['chip', set.has(value) ? 'active' : ''], 'aria-pressed': String(set.has(value)), 'data-testid': id,
      onclick: (event) => {
        if (set.has(value)) set.delete(value); else set.add(value);
        event.currentTarget.classList.toggle('active', set.has(value));
        event.currentTarget.setAttribute('aria-pressed', String(set.has(value)));
      },
    }, label);
  }

  function paintExtra() {
    extra.replaceChildren(
      h('div', { class: 'rate-group' }, h('span', { class: 'label' }, t('rate.good')),
        h('div', { class: 'chips' }, TAGS.map((tag) => chip(t(`rate.tag.${tag}`), tags, tag, `${testid}-tag-${tag}`)))),
      dishes.length ? h('div', { class: 'rate-group' }, h('span', { class: 'label' }, t('rate.liked')),
        h('div', { class: 'chips' }, dishes.slice(0, 8).map((dish, i) => chip(h('bdi', null, dish), liked, dish, `${testid}-dish-${i}`)))) : null,
      h('div', { class: 'field' }, h('label', { for: `${testid}-comment` }, t('rate.comment')), comment));
  }

  async function submit() {
    if (!stars || sending) return;
    sending = true;
    send.disabled = true;
    try {
      await api.ratePay(token, { stars, tags: [...tags], liked: [...liked], comment: comment.value.trim() || null });
      markRated(token);
      haptic('success');
      root.replaceChildren(h('div', { class: 'rate-thanks', 'data-testid': `${testid}-thanks`, role: 'status' },
        h('span', { class: 'rate-thanks-mark', 'aria-hidden': 'true' }, icon('check')),
        h('b', null, t('rate.thanks')), h('span', { class: 'faint' }, t('rate.thanksText'))));
      onDone?.(stars);
    } catch (error) {
      if (error?.code === 'RATING_ALREADY_GIVEN') markRated(token);
      toast({ title: errorMessage(error), kind: 'error' });
      sending = false;
      send.disabled = false;
    }
  }

  paintStars(0);
  return root;
}
