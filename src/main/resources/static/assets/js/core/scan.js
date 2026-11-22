// H3: binding a guest to their table. Location finds the restaurant; the table comes from the code on it. A phone that
// can read it here does (the camera with the browser's barcode reader, or an NFC tag on Android); any other phone
// opens the table's address with its own camera app, which lands on the same page.
import { h } from './dom.js';
import { icon } from './icons.js';
import { t } from './i18n.js';
import { openModal } from './ui.js';

/** The table page a scanned code or tag points to, if it is one of Sofra's. */
export function tableRoute(text) {
  const match = String(text || '').match(/#\/t\/([A-Za-z0-9_-]{20,100})/);
  return match ? `#/t/${match[1]}` : null;
}

export const canScan = () => 'BarcodeDetector' in window && !!navigator.mediaDevices?.getUserMedia;
export const canTap = () => 'NDEFReader' in window;

/** Opens the camera and resolves with the table route of the first Sofra code it sees; null when closed. */
export function scanCode() {
  return new Promise((resolve) => {
    let stream = null;
    let stopped = false;
    const video = h('video', { class: 'scan-video', playsinline: true, muted: true, autoplay: true, 'aria-hidden': 'true' });
    const status = h('p', { class: 'faint', role: 'status' }, t('bind.scanning'));
    const modal = openModal({
      title: t('bind.scanTitle'),
      size: 'scan-modal',
      body: h('div', { class: 'scan' }, h('div', { class: 'scan-frame' }, video, h('i', { class: 'scan-corners', 'aria-hidden': 'true' })), status),
      onClose: () => { stop(); resolve(null); },
    });
    function stop() {
      stopped = true;
      stream?.getTracks().forEach((track) => track.stop());
    }
    (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false });
        video.srcObject = stream;
        // eslint-disable-next-line no-undef
        const detector = new BarcodeDetector({ formats: ['qr_code'] });
        const tick = async () => {
          if (stopped) return;
          try {
            const codes = await detector.detect(video);
            const route = codes.map((code) => tableRoute(code.rawValue)).find(Boolean);
            if (route) { stop(); modal.close(); resolve(route); return; }
            if (codes.length) status.textContent = t('bind.notSofra');
          } catch { /* the frame was not ready */ }
          setTimeout(tick, 250);
        };
        tick();
      } catch {
        status.textContent = t('bind.noCamera');
      }
    })();
  });
}

/** Listens for a Sofra table tag (Android, Chrome); resolves with its table route, or null when closed. */
export function readTag() {
  return new Promise((resolve) => {
    const controller = new AbortController();
    const modal = openModal({
      title: t('bind.tapTitle'),
      size: 'sm',
      body: h('div', { class: 'tap-wait' }, h('span', { class: 'tap-mark', 'aria-hidden': 'true' }, icon('nfc')),
        h('p', null, t('bind.tapText'))),
      onClose: () => { controller.abort(); resolve(null); },
    });
    (async () => {
      try {
        // eslint-disable-next-line no-undef
        const reader = new NDEFReader();
        await reader.scan({ signal: controller.signal });
        reader.onreading = (event) => {
          const decoder = new TextDecoder();
          for (const record of event.message.records) {
            const route = tableRoute(decoder.decode(record.data));
            if (route) { controller.abort(); modal.close(); resolve(route); return; }
          }
        };
      } catch {
        /* permission refused or not supported: the guest closes the window */
      }
    })();
  });
}
