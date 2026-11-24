// The price list the Fiyat Etiketi Yönetmeliği asks for, at the door and on the tables: every dish with its price in
// Turkish lira, VAT included, the date the prices last changed, and no service, cover or table charge. The sheet
// itself is printed in Turkish whatever language the screen is in; the files below it are for the Ministry of Trade's
// electronic price list.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { api, errorMessage } from '../core/api.js';
import { toast } from '../core/ui.js';
import { qrCode } from './qr.js';

const CATEGORIES = ['STARTER', 'MAIN', 'DESSERT', 'DRINK'];
// the printed sheet is a legal notice: Turkish, whatever the screen's language
const SHEET = {
  title: 'Fiyat Listesi',
  category: { STARTER: 'Başlangıçlar', MAIN: 'Ana Yemekler', DESSERT: 'Tatlılar', DRINK: 'İçecekler' },
  vat: 'Fiyatlarımıza KDV dahildir.',
  noCharge: 'Servis, kuver veya masa ücreti alınmaz.',
  changed: 'Son fiyat değişikliği',
  scan: 'Menü ve sipariş için okutun',
};
const lira = new Intl.NumberFormat('tr-TR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export default {
  title: 'prices.title',
  subtitle: 'prices.sub',
  mount(container) {
    let list = null;
    let branches = [];
    let branchId = null;
    let size = 'a4';

    const toolbar = h('div', { class: 'toolbar prices-tools' });
    const preview = h('div', { class: 'prices-preview', 'data-testid': 'price-sheet-preview' });
    const side = h('aside', { class: 'card prices-side' });
    mount(container, toolbar, h('div', { class: 'prices-layout' }, preview, side));

    async function load() {
      try {
        [list, branches] = await Promise.all([api.priceList(branchId), branches.length ? branches : api.branches().catch(() => [])]);
        branchId = list.branchId;
      } catch (error) {
        mount(preview, h('div', { class: 'card' }, h('p', { class: 'muted' }, errorMessage(error))));
        return;
      }
      render();
    }

    function render() {
      const open = branches.filter((branch) => branch.active !== false);
      mount(toolbar,
        open.length > 1 ? h('label', { class: 'field inline' }, h('span', null, t('prices.branch')),
          h('select', {
            class: 'select', 'data-testid': 'prices-branch',
            onchange: (event) => { branchId = Number(event.target.value); load(); },
          }, open.map((branch) => h('option', { value: branch.id, selected: branch.id === branchId }, branch.name)))) : null,
        h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': t('prices.size') },
          ['a4', 'a5'].map((option) => h('button', {
            type: 'button', role: 'radio', 'aria-checked': String(size === option), class: size === option ? 'active' : '',
            'data-testid': `prices-size-${option}`, onclick: () => { size = option; render(); },
          }, t(`prices.size.${option}`)))),
        h('span', { class: 'spacer' }),
        h('button', {
          class: 'btn primary', 'data-testid': 'prices-print',
          onclick: () => { document.getElementById('print-root').replaceChildren(sheet()); window.print(); },
        }, icon('printer', 'sm'), t('prices.print')));
      mount(preview, sheet());
      mount(side,
        h('h3', null, t('prices.rulesTitle')),
        h('ul', { class: 'prices-rules' }, ['vat', 'tl', 'date', 'noCharge'].map((rule) => h('li', null, icon('check', 'sm'), t(`prices.rule.${rule}`)))),
        h('p', { class: 'faint' }, t('prices.hint')),
        h('hr'),
        h('h3', null, t('prices.exportTitle')),
        h('p', { class: 'faint' }, t('prices.exportHint')),
        h('div', { class: 'prices-files' },
          ['csv', 'json'].map((format) => h('button', { class: 'btn', 'data-testid': `prices-export-${format}`, onclick: () => download(format) },
            icon('arrowDown', 'sm'), t(`prices.export.${format}`)))));
    }

    /** The sheet as it is printed: A4 for the door, A5 for a table. */
    function sheet() {
      const zone = list.timeZone || 'Europe/Istanbul';
      const day = (iso) => new Intl.DateTimeFormat('tr-TR', { timeZone: zone, day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(iso));
      const sections = CATEGORIES.map((category) => [category, list.items.filter((item) => item.category === category)]).filter(([, items]) => items.length);
      const link = list.listed ? `${location.origin}${location.pathname}#/v/${list.slug}/${list.branchCode}` : null;
      return h('article', { class: ['price-sheet', size], lang: 'tr', dir: 'ltr', 'data-testid': 'price-sheet' },
        h('header', { class: 'price-sheet-head' },
          h('h1', null, list.business),
          list.chain ? h('p', { class: 'price-sheet-branch' }, list.branch) : null,
          list.address ? h('p', { class: 'price-sheet-address' }, list.address) : null,
          h('h2', null, SHEET.title)),
        h('div', { class: 'price-sheet-body' }, sections.map(([category, items]) => h('section', null,
          h('h3', null, SHEET.category[category]),
          items.map((item) => h('div', { class: 'price-row', 'data-testid': 'price-row' },
            h('span', { class: 'price-name' }, item.name),
            h('span', { class: 'price-dots', 'aria-hidden': 'true' }),
            h('span', { class: 'price-amount' }, `${lira.format(item.price)} TL`)))))),
        h('footer', { class: 'price-sheet-foot' },
          h('div', null,
            h('p', { class: 'price-sheet-rule' }, SHEET.vat),
            h('p', { class: 'price-sheet-rule' }, SHEET.noCharge),
            h('p', { 'data-testid': 'price-sheet-changed' }, `${SHEET.changed}: ${list.lastChangedAt ? day(list.lastChangedAt) : day(list.generatedAt)}`)),
          link ? h('div', { class: 'price-sheet-qr' }, qrCode(link, { label: SHEET.scan }), h('small', null, SHEET.scan)) : null));
    }

    async function download(format) {
      const blob = await api.priceListFile(branchId, format);
      if (!blob) {
        toast({ title: t('error.UNKNOWN'), kind: 'error' });
        return;
      }
      const name = `fiyat-listesi-${list.branchCode}.${format}`;
      const link = h('a', { href: URL.createObjectURL(blob), download: name });
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
      toast({ title: t('prices.downloaded', { name }) });
    }

    load();
  },
};
