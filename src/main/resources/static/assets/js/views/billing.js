// Plan and billing, for the owner (Sofra Cloud): the plan and its state, what is used against what the plan allows,
// and choosing or managing a plan on the provider's own pages. Inside the store apps nothing here sells anything:
// Apple and Google allow a free app for a service bought elsewhere, but not a button that sends people to buy it.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { locale, t, tn } from '../core/i18n.js';
import { api, errorMessage, storeApp } from '../core/api.js';
import { toast } from '../core/ui.js';

const PLANS = ['STARTER', 'BUSINESS', 'CHAIN'];
const FEATURES = ['ONLINE_PAYMENTS', 'DELIVERY_PLATFORMS', 'STOCK', 'FISCAL', 'SHIFTS_AND_TIPS'];

export function usd(cents) {
  return new Intl.NumberFormat(locale(), { style: 'currency', currency: 'USD', maximumFractionDigits: cents % 100 ? 2 : 0 }).format(cents / 100);
}

export default {
  title: 'billing.title',
  subtitle: 'billing.sub',
  mount(container) {
    const selling = !storeApp();
    let interval = 'MONTHLY';
    let state = null;
    const root = h('div', { class: 'billing' });
    mount(container, root);

    async function load() {
      try {
        state = await api.billing();
      } catch (error) {
        mount(root, h('div', { class: 'empty' }, h('h4', null, errorMessage(error))));
        return;
      }
      render();
    }

    function limit(value) {
      return value < 0 ? t('billing.unlimited') : String(value);
    }

    function statusLine() {
      const s = state;
      if (s.status === 'TRIAL') {
        const days = Math.max(0, Math.ceil((new Date(s.trialEndsAt) - Date.now()) / 86_400_000));
        return s.writable ? tn('billing.trialLeft', days) : t('billing.trialOver');
      }
      if (s.status === 'PAST_DUE') return t('billing.pastDue', { days: s.graceDays });
      if (s.status === 'READ_ONLY' || s.status === 'CANCELLED') return t('billing.readOnly');
      return s.periodEndsAt ? t('billing.renews', { date: new Date(s.periodEndsAt).toLocaleDateString(locale(), { day: 'numeric', month: 'long', year: 'numeric' }) }) : t('billing.active');
    }

    function render() {
      const s = state;
      const usage = [
        ['branches', s.usage.openBranches, s.limits.branches],
        ['staff', s.usage.activeStaff, s.limits.activeStaff],
        ['tables', s.usage.tables, s.limits.tables],
      ];
      const current = h('section', { class: ['card', 'billing-current', s.writable ? '' : 'lapsed'], 'data-testid': 'billing-current' },
        h('div', { class: 'card-head' },
          h('span', { class: 'settings-ic' }, icon('card', 'sm')),
          h('div', { style: { flex: 1, minWidth: 0 } },
            h('div', { class: 'card-title' }, t(`billing.plan.${s.plan}`), ' ', h('span', { class: `pill plain b-${s.status}` }, t(`billing.status.${s.status}`))),
            h('div', { class: 'card-sub', 'data-testid': 'billing-status' }, statusLine())),
          selling && s.portal ? h('button', { class: 'btn sm', 'data-testid': 'billing-portal', onclick: portal }, icon('settings', 'sm'), t('billing.manage')) : null),
        h('div', { class: 'card-body billing-usage' }, usage.map(([key, used, max]) => h('div', { class: 'billing-meter' },
          h('div', { class: 'billing-meter-label' }, h('span', null, t(`billing.usage.${key}`)), h('b', null, `${used} / ${limit(max)}`)),
          h('div', { class: 'setup-bar', role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': max < 0 ? used : max, 'aria-valuenow': used },
            h('span', { style: { width: max < 0 ? '0%' : `${Math.min(100, (used / Math.max(1, max)) * 100)}%` } }))))));

      if (!selling) {
        mount(root, current, h('p', { class: 'faint' }, t('billing.storeNote')));
        return;
      }
      if (!s.billingEnabled) {
        mount(root, current, h('p', { class: 'faint' }, t('billing.off')));
        return;
      }

      const toggle = h('div', { class: 'segmented billing-interval', role: 'group', 'aria-label': t('billing.interval') },
        ['MONTHLY', 'YEARLY'].map((value) => h('button', {
          type: 'button', class: interval === value ? 'active' : '', 'aria-pressed': String(interval === value),
          'data-testid': `billing-${value.toLowerCase()}`, onclick: () => { interval = value; render(); },
        }, t(`billing.interval.${value}`))));

      const cards = h('div', { class: 'billing-plans' }, PLANS.map((plan) => {
        const offer = s.offers.find((o) => o.plan === plan);
        const perBranch = interval === 'YEARLY' ? offer.yearlyCentsPerBranch : offer.monthlyCentsPerBranch;
        const total = perBranch * offer.billedBranches;
        const fits = (offer.limits.branches < 0 || s.usage.openBranches <= offer.limits.branches)
          && (offer.limits.activeStaff < 0 || s.usage.activeStaff <= offer.limits.activeStaff)
          && (offer.limits.tables < 0 || s.usage.tables <= offer.limits.tables);
        const mine = s.plan === plan && s.status === 'ACTIVE' && s.interval === interval;
        return h('section', { class: ['card', 'billing-plan', mine ? 'mine' : ''], 'data-testid': `plan-${plan}` },
          h('div', { class: 'card-body' },
            h('h3', null, t(`billing.plan.${plan}`)),
            h('p', { class: 'muted' }, t(`billing.for.${plan}`)),
            h('div', { class: 'billing-price' }, h('b', null, usd(offer.monthlyCentsPerBranch)), h('span', null, t('billing.perBranchMonth'))),
            h('p', { class: 'faint', 'data-testid': `plan-${plan}-total` }, t(interval === 'YEARLY' ? 'billing.totalYear' : 'billing.totalMonth',
              { total: usd(total), branches: offer.billedBranches })),
            h('ul', { class: 'billing-features' },
              h('li', null, icon('check', 'sm'), t('billing.limits', { branches: limit(offer.limits.branches), staff: limit(offer.limits.activeStaff), tables: limit(offer.limits.tables) })),
              FEATURES.map((f) => h('li', { class: offer.features.includes(f) ? '' : 'off' }, icon(offer.features.includes(f) ? 'check' : 'x', 'sm'), t(`billing.feature.${f}`)))),
            mine ? h('div', { class: 'pill plain' }, t('billing.current'))
              : h('button', {
                class: ['btn', plan === 'BUSINESS' ? 'primary' : ''], disabled: !fits, 'data-testid': `choose-${plan}`,
                title: fits ? null : t('error.PLAN_TOO_SMALL'), onclick: (event) => choose(plan, event.currentTarget),
              }, t('billing.choose'))));
      }));

      mount(root, current,
        h('div', { class: 'billing-head' }, h('h3', null, t('billing.plans')), toggle),
        cards,
        h('p', { class: 'faint' }, t('billing.fineprint')));
    }

    async function choose(plan, button) {
      button.disabled = true;
      try {
        const { url } = await api.billingCheckout({ plan, interval });
        if (new URL(url, location.href).origin === location.origin) {
          // the plan changed at once (simulated provider): reload, so every screen follows what it includes
          try { sessionStorage.setItem('sofra.planChosen', plan); } catch { /* private mode */ }
          location.reload();
        } else {
          location.assign(url);
        }
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
        button.disabled = false;
      }
    }

    async function portal() {
      try {
        const { url } = await api.billingPortal();
        location.assign(url);
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    try {
      const chosen = sessionStorage.getItem('sofra.planChosen');
      if (chosen) {
        sessionStorage.removeItem('sofra.planChosen');
        toast({ title: t('billing.chosen', { plan: t(`billing.plan.${chosen}`) }) });
      }
    } catch { /* private mode */ }
    root.append(h('div', { class: 'skeleton', style: { height: '160px' } }));
    load();
    return {};
  },
};
