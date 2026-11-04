// Internationalisation: Turkish, English, German, French, Russian and Arabic. Server error codes are translated here
// too, so the API can stay language-neutral and simply return stable codes.
//
// Each language is its own module under locales/, loaded only when chosen, so a guest's phone downloads one language,
// not six. English is always loaded: any text a language has not translated yet falls back to it.
import en from './locales/en.js';

/** The languages on offer, by their own names. Arabic is written right to left, with Western digits for prices. */
export const LANGUAGES = [
  { code: 'tr', name: 'Türkçe', locale: 'tr-TR' },
  { code: 'en', name: 'English', locale: 'en-GB' },
  { code: 'de', name: 'Deutsch', locale: 'de-DE' },
  { code: 'fr', name: 'Français', locale: 'fr-FR' },
  { code: 'ru', name: 'Русский', locale: 'ru-RU' },
  { code: 'ar', name: 'العربية', locale: 'ar-u-nu-latn', dir: 'rtl' },
];

const LOADERS = {
  tr: () => import('./locales/tr.js'),
  de: () => import('./locales/de.js'),
  fr: () => import('./locales/fr.js'),
  ru: () => import('./locales/ru.js'),
  ar: () => import('./locales/ar.js'),
};

const dictionaries = { en };
const STORAGE_KEY = 'sofra.lang';
let current = 'en';
const listeners = new Set();

const supported = (code) => LANGUAGES.some((language) => language.code === code);
const info = (code) => LANGUAGES.find((language) => language.code === code);

function preferredLanguage() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored && supported(stored)) return stored;
  } catch { /* storage unavailable */ }
  for (const wanted of navigator.languages ?? [navigator.language ?? 'en']) {
    const code = String(wanted).toLowerCase().split('-')[0];
    if (supported(code)) return code;
  }
  return 'en';
}

async function load(code) {
  if (!dictionaries[code]) dictionaries[code] = (await LOADERS[code]()).default;
}

function apply(code) {
  current = code;
  document.documentElement.lang = code;
  document.documentElement.dir = info(code).dir ?? 'ltr';
}

/** Resolves once the preferred language is loaded; the app renders after it. A failed load falls back to English. */
export const ready = (async () => {
  const code = preferredLanguage();
  try {
    await load(code);
    apply(code);
  } catch {
    apply('en');
  }
})();

export function lang() {
  return current;
}

export function locale() {
  return info(current).locale;
}

export function languageName(code = current) {
  return info(code)?.name ?? code;
}

export async function setLang(next) {
  if (!supported(next) || next === current) return;
  await load(next);
  apply(next);
  try { localStorage.setItem(STORAGE_KEY, next); } catch { /* ignore */ }
  listeners.forEach((fn) => fn(next));
}

export function onLangChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** Translates {@code key}, substituting {placeholders} from {@code params}. Falls back to English, then the key. */
export function t(key, params = {}) {
  const template = dictionaries[current]?.[key] ?? en[key] ?? key;
  return template.replace(/\{(\w+)}/g, (_, name) => (params[name] ?? `{${name}}`));
}

const pluralRules = new Map();

/**
 * Plural-aware translation: looks up {@code key.one}, {@code key.few}, {@code key.other} and so on through
 * Intl.PluralRules, so English gets "1 order" / "2 orders", Russian its one/few/many forms and Arabic its six, while
 * Turkish, which does not inflect, only needs {@code .other}.
 */
export function tn(key, n, params = {}) {
  const loc = locale();
  if (!pluralRules.has(loc)) pluralRules.set(loc, new Intl.PluralRules(loc));
  const rule = pluralRules.get(loc).select(Number(n));
  const dict = dictionaries[current] ?? en;
  const template = dict[`${key}.${rule}`] ?? dict[`${key}.other`] ?? en[`${key}.${rule}`] ?? en[`${key}.other`] ?? key;
  const values = { ...params, n: new Intl.NumberFormat(loc).format(n) };
  return template.replace(/\{(\w+)}/g, (_, name) => (values[name] ?? `{${name}}`));
}

export function has(key) {
  return key in (dictionaries[current] ?? {}) || key in en;
}
