// Round three, a restaurant's page in the guest app (#/v/{business}/{branch}): after "I am here". The four things a
// guest does at a table come first (H1), the menu follows at this branch's prices (H2), and anything that needs the
// table first binds the guest to it (H3): the code on the table opens the table page, where ordering and paying
// already work. What other guests said, and the restaurant's answers, close the page.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { dateTime, money, number } from '../core/format.js';
import { api } from '../core/api.js';
import { guestMark, guestName, useGuestBrand } from '../core/brand.js';
import { haptic, langButton, openModal, reducedMotion, themeButton } from '../core/ui.js';
import { canScan, canTap, readTag, scanCode } from '../core/scan.js';
import { allergenList } from './allergens.js';

const CATEGORIES = ['STARTER', 'MAIN', 'DESSERT', 'DRINK'];

export function renderVenue(root, business, branch, { navigate = (hash) => { location.hash = hash; } } = {}) {
  let page = null;
  let failed = false;
  let stopped = false;

  const top = h('header', { class: 'guest-top' });
  const body = h('main', { class: 'venue-page', 'data-testid': 'venue-page' });
  const dock = h('div', { class: 'venue-dock-slot' });
  const paintTop = () => mount(top,
    h('a', { class: 'icon-btn', href: '#/sofra', 'aria-label': t('gapp.back') }, icon('chevronLeft')),
    guestMark(), h('b', { 'data-testid': 'guest-name' }, h('bdi', null, guestName())), h('span', { class: 'spacer' }), langButton(), themeButton());
  paintTop();
  mount(root, h('div', { class: 'guest venue-guest' }, top, body, dock));

  async function load() {
    try {
      page = await api.venuePage(business, branch);
    } catch {
      failed = true;
    }
    if (stopped) return;
    if (page) {
      await useGuestBrand(page.name, { accent: page.accent, logoVersion: page.logo ? 1 : null }, `/public/venue/${business}/${branch}/logo`);
      paintTop();
    }
    render();
  }

  function render() {
    if (failed) {
      mount(body, h('div', { class: 'card guest-card' }, h('div', { class: 'empty' }, h('div', { class: 'art' }, '🔍'),
        h('h4', null, t('gapp.venueMissing')), h('p', null, t('gapp.venueMissingText')),
        h('a', { class: 'btn', href: '#/sofra' }, t('gapp.back')))));
      return;
    }
    if (!page) {
      mount(body, [150, 200, 260].map((height) => h('div', { class: 'skeleton', style: { height: `${height}px`, borderRadius: '20px' } })));
      return;
    }
    const enter = reducedMotion() ? '' : 'enter';
    mount(body,
      h('section', { class: ['venue-hero', enter] },
        h('div', { class: 'venue-id' },
          h('h1', { class: 'guest-title' }, h('bdi', null, page.name)),
          page.branchName ? h('span', { class: 'faint' }, h('bdi', null, page.branchName)) : null,
          page.address ? h('span', { class: 'faint venue-address' }, icon('pin', 'sm'), h('bdi', null, page.address)) : null,
          page.rating != null ? h('span', { class: 'venue-rating', 'data-testid': 'venue-rating' }, icon('star', 'sm'),
            h('b', null, number(page.rating, 1)), h('span', { class: 'faint' }, t('gapp.verifiedVisits', { n: number(page.ratings) }))) : null),
        h('div', { class: 'venue-actions' },
          action('menu', 'main', t('gapp.actMenu'), t('gapp.actMenuSub'), () => document.getElementById('venue-menu')?.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' })),
          action('bell', '', t('gapp.actCall'), t('gapp.needsTable'), bind),
          action('receipt', '', t('gapp.actBill'), t('gapp.needsTable'), bind),
          action('card', '', t('gapp.actPay'), t('gapp.needsTable'), bind))),
      menuSection(),
      reviewsSection());
    mount(dock, h('div', { class: 'venue-dock' },
      h('span', { class: 'venue-dock-text' }, t('gapp.dockText')),
      h('button', { class: 'btn primary', 'data-testid': 'bind-table', onclick: bind }, icon('table', 'sm'), t('gapp.bindTable'))));
  }

  function action(iconName, kind, title, sub, run) {
    return h('button', { class: ['venue-act', kind], type: 'button', onclick: run, 'data-testid': `venue-act-${iconName}` },
      h('span', { class: 'venue-act-ic', 'aria-hidden': 'true' }, icon(iconName)), h('b', null, title), h('small', null, sub));
  }

  // ------------------------------------------------------------------ H2: the menu, to browse
  function menuSection() {
    const items = page.menu.filter((item) => item.available);
    const sections = CATEGORIES.map((category) => [category, items.filter((item) => item.category === category)]).filter(([, list]) => list.length);
    return h('section', { class: 'venue-menu', id: 'venue-menu', 'aria-labelledby': 'venue-menu-title' },
      h('h2', { id: 'venue-menu-title', class: 'menu-section-title' }, t('gapp.menuTitle')),
      h('nav', { class: 'chips venue-cats', 'aria-label': t('table.categories') }, sections.map(([category]) => h('button', {
        class: 'chip', type: 'button', onclick: () => document.getElementById(`venue-cat-${category}`)?.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' }),
      }, t(`cat.${category}`)))),
      sections.map(([category, list]) => h('section', { class: 'menu-section', id: `venue-cat-${category}`, 'aria-label': t(`cat.${category}`) },
        h('h3', { class: 'venue-cat-title' }, t(`cat.${category}`)),
        list.map((item) => h('div', { class: 'venue-dish', 'data-testid': 'venue-dish' },
          h('span', { class: 'venue-dish-ic', 'aria-hidden': 'true' }, item.icon || '🍽️'),
          h('span', { class: 'venue-dish-text' }, h('b', null, h('bdi', null, item.name)),
            item.description ? h('small', { class: 'faint' }, item.description) : null,
            item.allergens?.length ? h('small', { class: 'venue-allergens' }, icon('wheat', 'sm'), allergenList(item.allergens)) : null),
          h('span', { class: 'venue-dish-price num' }, money(item.price)))))),
      h('p', { class: 'faint venue-price-note' }, t('gapp.priceNote')));
  }

  // ------------------------------------------------------------------ verified-visit reviews and the answers
  function reviewsSection() {
    if (!page.reviews.length) return null;
    return h('section', { class: 'venue-reviews', 'aria-labelledby': 'venue-reviews-title' },
      h('h2', { id: 'venue-reviews-title', class: 'menu-section-title' }, t('gapp.reviewsTitle')),
      page.reviews.map((review) => h('article', { class: 'venue-review' },
        h('div', { class: 'venue-review-head' },
          h('span', { class: 'stars-static', 'aria-label': t('rate.nStars', { n: review.stars }) }, '★'.repeat(review.stars), h('span', { class: 'off' }, '★'.repeat(5 - review.stars))),
          h('span', { class: 'verified' }, icon('check', 'sm'), t('rate.verifiedShort')),
          h('span', { class: 'faint' }, dateTime(review.at))),
        review.tags.length ? h('div', { class: 'chips static' }, review.tags.map((tag) => h('span', { class: 'chip' }, t(`rate.tag.${tag}`)))) : null,
        review.comment ? h('p', null, review.comment) : null,
        review.reply ? h('div', { class: 'venue-reply' }, h('b', null, t('gapp.replyFrom', { name: page.name })), h('p', null, review.reply)) : null)));
  }

  // ------------------------------------------------------------------ H3: binding to the table
  function bind() {
    haptic('tap');
    const ways = [];
    if (canTap()) ways.push(way('nfc', t('bind.tap'), t('bind.tapSub'), true, async () => { modal.close(); go(await readTag()); }));
    ways.push(way('qr', t('bind.scan'), canScan() ? t('bind.scanSub') : t('bind.cameraApp'), !canTap(), async () => {
      if (!canScan()) return;
      modal.close();
      go(await scanCode());
    }));
    const modal = openModal({
      title: t('bind.title'),
      size: 'sm bind-sheet',
      body: h('div', { class: 'bind', 'data-testid': 'bind-sheet' }, ways,
        h('p', { class: 'faint' }, t('bind.browseOnly'))),
    });
  }

  function way(iconName, title, sub, best, run) {
    return h('button', { class: ['bind-way', best ? 'best' : ''], type: 'button', onclick: run, 'data-testid': `bind-${iconName}` },
      h('span', { class: 'bind-ic', 'aria-hidden': 'true' }, icon(iconName)),
      h('span', null, h('b', null, title), h('small', { class: 'faint' }, sub)));
  }

  function go(route) {
    if (route) navigate(route);
  }

  load();
  return () => { stopped = true; };
}
