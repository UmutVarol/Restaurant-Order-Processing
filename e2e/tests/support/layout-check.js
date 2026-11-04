// Finds what a long word or a right-to-left language can break: text that spills out of its box or is cut off without an
// ellipsis, controls in one row that overlap, anything that leaves its card or the screen, and a donut's centre that
// outgrows its hole. Runs inside the page (page.evaluate), so it is plain DOM code with no imports.
function findLayoutProblems() {
  const issues = [];
  const vw = document.documentElement.clientWidth;
  const name = (el) => {
    const parts = [];
    for (let e = el; e && e !== document.body && parts.length < 4; e = e.parentElement) {
      let p = e.tagName.toLowerCase();
      if (e.dataset?.testid) p += `[${e.dataset.testid}]`;
      else if (e.classList.length) p += '.' + [...e.classList].slice(0, 2).join('.');
      parts.unshift(p);
    }
    return parts.join(' > ');
  };
  const shown = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    for (let e = el; e; e = e.parentElement) {
      const cs = getComputedStyle(e);
      if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0) return false;
    }
    return true;
  };
  const scroller = (el) => {
    for (let e = el.parentElement; e; e = e.parentElement) {
      const cs = getComputedStyle(e);
      if (/(auto|scroll)/.test(cs.overflowX)) return e;
    }
    return null;
  };
  const ownText = (el) => [...el.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent).join('').trim();
  const all = [...document.querySelectorAll('body *')].filter((el) => !el.closest('svg') && !el.closest('.skeleton') && !el.closest('.sr-only'));
  for (const el of all) {
    if (!shown(el)) continue;
    const cs = getComputedStyle(el);
    const text = ownText(el);
    if (text && cs.display !== 'inline' && el.clientWidth > 0) {
      const over = el.scrollWidth - el.clientWidth;
      if (over > 1) {
        if (cs.overflowX === 'visible') issues.push({ kind: 'spills', where: name(el), by: over, text: text.slice(0, 50) });
        else if (cs.textOverflow !== 'ellipsis' && !/(auto|scroll)/.test(cs.overflowX)) issues.push({ kind: 'clipped', where: name(el), by: over, text: text.slice(0, 50) });
      }
      const overY = el.scrollHeight - el.clientHeight;
      if (overY > 2 && cs.overflowY === 'hidden' && cs.webkitLineClamp === 'none' && !/(auto|scroll)/.test(cs.overflowY)) {
        issues.push({ kind: 'clipped-y', where: name(el), by: overY, text: text.slice(0, 50) });
      }
    }
    const r = el.getBoundingClientRect();
    if ((r.right > vw + 1 || r.left < -1) && !scroller(el) && getComputedStyle(el).position !== 'fixed') {
      issues.push({ kind: 'offscreen', where: name(el), by: Math.round(Math.max(r.right - vw, -r.left)), text: (el.textContent || '').trim().slice(0, 40) });
    }
  }
  // controls in one row must not overlap
  const rows = document.querySelectorAll('.table-actions, .row, .card-head, .modal-foot, .drawer-foot, .kpi-head, .chips, .inbox-actions, .topbar-tools, .tabbar, .segmented, .pay-methods, .stock-actions, .channel-actions, .printer-actions, .bill-actions, .kds-col-head, .legend, .hero-stats, .period-switch');
  for (const row of rows) {
    if (!shown(row)) continue;
    const kids = [...row.children].filter(shown);
    for (let i = 0; i < kids.length; i++) {
      for (let j = i + 1; j < kids.length; j++) {
        const a = kids[i].getBoundingClientRect(); const b = kids[j].getBoundingClientRect();
        const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (w > 2 && h > 2) issues.push({ kind: 'overlap', where: name(kids[i]) + ' ⟷ ' + name(kids[j]), by: Math.round(w), text: (kids[i].textContent || '').trim().slice(0, 30) });
      }
    }
  }
  // nothing escapes its card, tile, ticket, chip or button
  const boxes = document.querySelectorAll('.card, .kpi, .table-card, .ticket, .menu-card, .modal, .drawer, .btn, .chip, .pill, .tab, .stat, .inbox-item, .table-item, .lang-option, .more-row, .service-mode, .donut-wrap');
  for (const box of boxes) {
    if (!shown(box)) continue;
    const br = box.getBoundingClientRect();
    for (const el of box.querySelectorAll('*')) {
      if (el.closest('svg') && el.tagName !== 'svg') continue;
      if (!shown(el) || scroller(el) && box.contains(scroller(el)) && scroller(el) !== box) continue;
      if (getComputedStyle(el).position === 'absolute' && el.classList.contains('badge')) continue;
      const r = el.getBoundingClientRect();
      const by = Math.max(br.left - r.left, r.right - br.right);
      if (by > 1.5 && (el.textContent || '').trim()) {
        issues.push({ kind: 'escapes', where: name(el) + ' ⊄ ' + name(box), by: Math.round(by), text: el.textContent.trim().slice(0, 40) });
        break;
      }
    }
  }
  // the centre of a donut stays inside its hole
  for (const centre of document.querySelectorAll('.donut-center')) {
    const hole = centre.closest('.donut-label').parentElement.getBoundingClientRect().width * 0.56;
    const width = centre.scrollWidth;
    if (width > hole) issues.push({ kind: 'donut', where: name(centre), by: Math.round(width - hole), text: centre.textContent.trim().slice(0, 30) });
  }
  const seen = new Set();
  return issues.filter((i) => { const k = i.kind + i.where; if (seen.has(k)) return false; seen.add(k); return true; });
}

module.exports = { findLayoutProblems };
