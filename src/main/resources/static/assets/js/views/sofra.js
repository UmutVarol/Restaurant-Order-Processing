// Round three, the guest app's home (#/sofra): open Sofra in a restaurant and be there. The phone's position is used
// once, never stored, to find listed restaurants nearby. One clear match greets the guest with that restaurant's
// sign (G2); otherwise a proximity view and the nearest places to choose from (G1 + G3). A paid visit still waiting
// for a rating sits on top (P3). Every step works without the app: this page is the web version of it.
import { h, mount } from '../core/dom.js';
import { icon, logoMark } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { number } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { fitAccent, leaveGuestBrand } from '../core/brand.js';
import { haptic, langButton, reducedMotion, themeButton, toast } from '../core/ui.js';
import { deviceKey, dismissVisit, ratingForm, visitToRate } from '../core/visits.js';
import { canScan, scanCode } from '../core/scan.js';

/** The rings of the proximity view, in metres. */
const RINGS = [25, 75, 150];

export function renderSofra(root, { navigate = (hash) => { location.hash = hash; } } = {}) {
  leaveGuestBrand();
  let stopped = false;
  let position = null;
  let found = null;
  let picked = 0;

  const top = h('header', { class: 'guest-top' },
    h('span', { class: 'sofra-word' }, logoMark(), h('b', null, 'Sofra')), h('span', { class: 'spacer' }), langButton(), themeButton());
  const stage = h('main', { class: 'sofra-stage', 'data-testid': 'sofra-home' });
  mount(root, h('div', { class: 'guest sofra-app' }, top, stage));

  /** One screen replaces another: the new one rises in, unless motion is unwelcome. */
  function show(...children) {
    mount(stage, h('div', { class: ['sofra-screen', reducedMotion() ? '' : 'enter'] }, ...children));
  }

  // ------------------------------------------------------------------ P3: the last visit, waiting for stars
  function visitCard() {
    const visit = visitToRate();
    if (!visit) return null;
    const holder = h('div', { class: 'visit-card', 'data-testid': 'visit-card' });
    const form = ratingForm({ token: visit.token, restaurant: visit.restaurant, testid: 'visit-rate', onDone: () => setTimeout(() => holder.remove(), 2400) });
    mount(holder,
      h('div', { class: 'visit-head' }, h('span', { class: 'eyebrow' }, t('rate.lastVisit')),
        h('button', { class: 'btn sm ghost', type: 'button', 'data-testid': 'visit-later', onclick: () => { dismissVisit(visit.token); holder.remove(); } }, t('rate.later'))),
      form);
    return holder;
  }

  // ------------------------------------------------------------------ asking for the position
  function ask() {
    show(visitCard(),
      h('section', { class: 'sofra-ask' },
        h('span', { class: 'sofra-ask-mark', 'aria-hidden': 'true' }, icon('locate')),
        h('h1', null, t('gapp.askTitle')),
        h('p', { class: 'muted' }, t('gapp.askText')),
        h('button', { class: 'btn primary lg block', 'data-testid': 'locate', onclick: locate }, icon('locate', 'sm'), t('gapp.useLocation')),
        scanButton(),
        h('small', { class: 'faint sofra-privacy' }, icon('lock', 'sm'), t('gapp.privacy'))));
  }

  function scanButton() {
    return h('button', {
      class: 'btn lg block', 'data-testid': 'scan-qr', onclick: async () => {
        if (!canScan()) { toast({ title: t('bind.cameraApp'), kind: 'info' }); return; }
        const route = await scanCode();
        if (route) navigate(route);
      },
    }, icon('qr', 'sm'), t('bind.scan'));
  }

  function locate() {
    if (!('geolocation' in navigator)) { denied(); return; }
    show(h('section', { class: 'sofra-ask', 'aria-busy': 'true' },
      h('span', { class: 'sofra-ask-mark searching', 'aria-hidden': 'true' }, icon('locate')),
      h('h1', null, t('gapp.locating')), h('p', { class: 'muted' }, t('gapp.locatingText'))));
    navigator.geolocation.getCurrentPosition(async (pos) => {
      if (stopped) return;
      position = { lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy };
      try {
        found = await api.nearbyVenues(position.lat, position.lng, position.accuracy);
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
        ask();
        return;
      }
      if (stopped) return;
      picked = 0;
      if (!found.venues.length) none();
      else if (found.confident) sign(found.venues[0]);
      else nearby();
    }, (error) => (error.code === 1 ? denied() : unknown()), { enableHighAccuracy: true, timeout: 12000, maximumAge: 15000 });
  }

  function denied() {
    show(h('section', { class: 'sofra-ask' },
      h('span', { class: 'sofra-ask-mark', 'aria-hidden': 'true' }, icon('lock')),
      h('h1', null, t('gapp.deniedTitle')), h('p', { class: 'muted' }, t('gapp.deniedText')),
      scanButton()));
  }

  function unknown() {
    show(h('section', { class: 'sofra-ask' },
      h('span', { class: 'sofra-ask-mark', 'aria-hidden': 'true' }, icon('alert')),
      h('h1', null, t('gapp.unknownTitle')), h('p', { class: 'muted' }, t('gapp.unknownText')),
      h('button', { class: 'btn primary lg block', onclick: locate }, icon('refresh', 'sm'), t('gapp.retry')),
      scanButton()));
  }

  function none() {
    show(visitCard(), h('section', { class: 'sofra-ask', 'data-testid': 'nearby-none' },
      h('span', { class: 'sofra-ask-mark', 'aria-hidden': 'true' }, icon('store')),
      h('h1', null, t('gapp.noneTitle')), h('p', { class: 'muted' }, t('gapp.noneText')),
      scanButton(),
      h('button', { class: 'btn ghost block', onclick: locate }, icon('refresh', 'sm'), t('gapp.retry'))));
  }

  // ------------------------------------------------------------------ G2: one clear match, its own sign
  function sign(venue) {
    const accent = venue.accent ? fitAccent(venue.accent) : null;
    const style = accent ? { '--sign': accent.light.brand, '--sign-dark': accent.dark.brand } : null;
    show(h('section', { class: 'venue-sign', style, 'data-testid': 'venue-sign' },
      h('div', { class: 'sign-inner' },
        logoOf(venue, 'sign-logo'),
        h('span', { class: 'sign-hello' }, t('gapp.welcome')),
        h('h1', { class: 'sign-name' }, h('bdi', null, venue.name)),
        venue.branchName ? h('span', { class: 'sign-branch' }, h('bdi', null, venue.branchName)) : null,
        venue.address ? h('span', { class: 'sign-address' }, icon('pin', 'sm'), h('bdi', null, venue.address)) : null,
        h('span', { class: 'sign-found' }, icon('locate', 'sm'), t('gapp.foundNear', { m: number(venue.distance) })),
        h('button', { class: 'btn lg block sign-yes', 'data-testid': 'here-yes', onclick: () => checkIn(venue, 'NEARBY') }, t('gapp.hereOpen')),
        h('button', { class: 'sign-no', type: 'button', 'data-testid': 'here-no', onclick: nearby }, t('gapp.notHere')))));
  }

  // ------------------------------------------------------------------ G1 + G3: proximity view and the nearest places
  function nearby() {
    const venues = found?.venues || [];
    const list = h('div', { class: 'near-list', role: 'radiogroup', 'aria-label': t('gapp.nearbyLabel') });
    const go = h('button', { class: 'btn primary lg block', 'data-testid': 'near-continue', onclick: () => checkIn(venues[picked], 'LIST') }, t('gapp.continue'));
    const view = proximity(venues);
    function paintList() {
      list.replaceChildren(...venues.map((venue, i) => h('button', {
        type: 'button', role: 'radio', 'aria-checked': String(i === picked), class: ['near-row', i === picked ? 'sel' : ''],
        style: { '--i': i }, 'data-testid': 'near-row',
        onclick: () => { picked = i; paintList(); paintPins(view, venues); haptic('tap'); },
      },
      logoOf(venue, 'near-logo'),
      h('span', { class: 'near-text' },
        i === 0 ? h('span', { class: 'near-likely' }, t('gapp.likely')) : null,
        h('b', null, h('bdi', null, venue.name)),
        h('small', { class: 'faint' }, [t('gapp.metres', { m: number(venue.distance) }), venue.branchName, venue.rating != null ? `${number(venue.rating, 1)} ★` : null].filter(Boolean).join(' · '))),
      h('span', { class: 'near-radio', 'aria-hidden': 'true' }))));
    }
    paintList();
    show(visitCard(), h('section', { class: 'nearby', 'data-testid': 'nearby' },
      view,
      h('div', { class: 'near-sheet' },
        h('span', { class: 'grab', 'aria-hidden': 'true' }),
        h('h1', { class: 'near-title' }, t('gapp.whereTitle')),
        h('p', { class: 'faint' }, t('gapp.whereText')),
        list, go, scanButton())));
  }

  /** The guest at the centre, rings at 25, 75 and 150 m, each place where it is. A view of distance, not a map. */
  function proximity(venues) {
    const view = h('div', { class: 'prox', role: 'img', 'aria-label': t('gapp.proxLabel', { n: venues.length }) });
    const far = farOf(venues);
    view.append(...RINGS.filter((m) => m <= far).map((m, i) => h('i', { class: 'prox-ring', style: { '--r': `${(m / far) * 40}%`, '--i': i } },
      h('span', { class: 'prox-label' }, t('gapp.metres', { m })))));
    if (position?.accuracy) view.append(h('i', { class: 'prox-accuracy', style: { '--r': `${Math.min(40, (position.accuracy / far) * 40)}%` } }));
    view.append(h('i', { class: 'prox-me', 'aria-hidden': 'true' }));
    paintPins(view, venues);
    return view;
  }

  function paintPins(view, venues) {
    view.querySelectorAll('.prox-pin').forEach((pin) => pin.remove());
    const far = farOf(venues);
    venues.forEach((venue, i) => {
      const dy = (venue.latitude - position.lat) * 111_320;
      const dx = (venue.longitude - position.lng) * 111_320 * Math.cos((position.lat * Math.PI) / 180);
      const d = Math.max(1, Math.hypot(dx, dy));
      const r = (Math.min(venue.distance, far) / far) * 40;
      view.append(h('span', {
        class: ['prox-pin', i === picked ? 'sel' : ''], style: { left: `${50 + (dx / d) * r}%`, top: `${50 - ((dy / d) * r) / 0.86}%`, '--i': i },
        'aria-hidden': 'true',
      }, h('span', null, initials(venue.name))));
    });
  }

  // ------------------------------------------------------------------ confirming
  async function checkIn(venue, method) {
    if (!venue) return;
    haptic('success');
    try {
      await api.venueCheckin(venue.business, venue.branch, { device: deviceKey(), method });
    } catch {
      /* the page still opens; a lost check-in only misses a count */
    }
    navigate(`#/v/${venue.business}/${venue.branch}`);
  }

  function logoOf(venue, cls) {
    if (venue.logo) return h('img', { class: cls, src: api.venueLogoUrl(venue.business, venue.branch), alt: '', loading: 'lazy' });
    return h('span', { class: [cls, 'initials'], 'aria-hidden': 'true' }, initials(venue.name));
  }

  // a phone that already said yes is not asked again
  (async () => {
    let state = 'prompt';
    try { state = (await navigator.permissions?.query({ name: 'geolocation' }))?.state || 'prompt'; } catch { /* Safari */ }
    if (stopped) return;
    if (state === 'granted') locate(); else if (state === 'denied') denied(); else ask();
  })();

  return () => { stopped = true; };
}

/** The distance the view's edge stands for: a little past the farthest place, never less than the middle ring. */
function farOf(venues) {
  return Math.max(RINGS[1], ...venues.map((v) => v.distance * 1.2));
}

function initials(name) {
  return String(name || '?').split(/\s+/).filter(Boolean).slice(0, 2).map((word) => [...word][0]).join('').toLocaleUpperCase();
}
