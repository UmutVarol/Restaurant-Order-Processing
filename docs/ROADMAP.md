# Roadmap

What has been built, and what comes next. Priorities come from a review of competing restaurant POS products
(Toast, Square for Restaurants, Lightspeed, Adisyo, SambaPOS) and open-source projects (Floreant POS, uniCenta,
Odoo POS), weighed for a small to mid-size restaurant in Türkiye.

Every sprint uses the same **definition of done**:

- the money and security rules live on the server and are covered by unit and API integration tests;
- the screens are covered by a Playwright test, in both languages, at desktop and phone widths;
- the in-browser demo (`demo/mock-backend.js`) behaves like the real API;
- new endpoints are in Swagger, new settings are in the README, and new controls are in [SECURITY.md](../SECURITY.md);
- the CI pipeline (build, tests, CodeQL, Trivy, Gitleaks, OWASP ZAP) is green.

## Sprint 1: service at the table (done)

| | Feature | What it gives the restaurant | Where |
|---|---|---|---|
| S1.1 | **Product options** | Spice level, extras, sugar: option groups with a minimum, a maximum and extra prices. Required groups default to their first free choice; choices are snapshotted on the order line. | `menu`, `order`, V6 |
| S1.2 | **Staff PIN switching** | Registered tills open on staff tiles and a PIN pad; "switch user"; idle lock. A PIN never authorises refunds, closing the day or registering tills (`amr` claim). | `security`, V7 |
| S1.3 | **Table operations** | Void and comp single units, manual discount, move and merge tables. Manager PIN approval for waiters; repriced, audited, refused once paid beyond. | `order`, `payment`, V8 |
| S1.4 | **Ordering from the table** | Table QR codes open the menu on the guest's phone: cart with options, call the waiter, ask for the bill. Carts wait for a waiter to accept them. | `guest`, V9 |
| S1.5 | **Kitchen and bar printers** | ESC/POS network printers by menu category, Turkish code page, retries, failure alerts, reprint, preview. Private-network addresses only (SSRF guard). | `printing`, V10 |
| S1.6 | **Settings** | My PIN, this till, registered tills, table QR codes, printers. | `views/settings.js` |

## Sprint 2: run the business

Six epics, in the order they should be built. Estimates are in developer-days for one person and include tests,
documentation and the demo.

| Order | Epic | Why now | Depends on | Estimate |
|---|---|---|---|---|
| 1 | [S2.1 Two-factor sign-in for managers](#s21-two-factor-sign-in-for-managers) | Managers can refund and close the day; a password alone is the weakest link left | — | 3 |
| 2 | [S2.2 Courses and kitchen stations](#s22-courses-and-kitchen-stations) | Builds directly on printers and the kitchen display | S1.5 | 5 |
| 3 | [S2.3 Stock and recipe costing](#s23-stock-and-recipe-costing) | Food cost is the number owners ask for first | S1.1, S1.3 | 8 |
| 4 | [S2.4 Offline-capable till](#s24-offline-capable-till) | Restaurant internet drops; the till must keep taking orders | — | 6 |
| 5 | [S2.5 Delivery platform orders](#s25-delivery-platform-orders) | Yemeksepeti, Getir and Trendyol orders typed in by hand are slow and error-prone | S2.3 for stock | 10 |
| 6 | [S2.6 Fiscal receipts: ÖKC and e-Arşiv](#s26-fiscal-receipts-ökc-and-e-arşiv) | Legally required for a live restaurant; needs an integrator contract first | — | 8 |

### S2.1 Two-factor sign-in for managers

**Stories**

- As a manager I enrol an authenticator app by scanning a QR code and confirming one code, and I get ten
  one-time recovery codes.
- As a manager I type a six-digit code after my password; the till remembers nothing.
- As an owner I can require two-factor sign-in for every manager account.

**Acceptance criteria**

- TOTP per RFC 6238 (SHA-1, 30 s, 6 digits, ±1 step of drift); a code that was already used is refused.
- The TOTP secret is encrypted at rest with AES-GCM under a key from the environment (`TOTP_ENCRYPTION_KEY`);
  the app refuses to start with two-factor required and no key.
- Recovery codes are stored as bcrypt hashes and each works once.
- Step-up actions (refunds, closing the day, registering tills) need password **and** code when two-factor is on;
  the token's `amr` becomes `["pwd", "otp"]`.
- Code guessing shares the account lockout; five wrong codes lock the account like five wrong passwords.
- Settings: enrol, disable (needs a code), regenerate recovery codes.

**Security notes**: the enrolment QR is drawn with the existing SVG QR helper (no third party); the secret is shown
once; `security.audit` logs enrolment, disabling and recovery-code use. WebAuthn passkeys are a follow-up.

### S2.2 Courses and kitchen stations

**Stories**

- As a waiter I mark lines as starters, mains or desserts (defaulting from the menu category) and hold later
  courses; I tap "fire mains" when the table is ready.
- As a manager I define stations (grill, cold, bar, dessert) and which dishes each makes; printers and kitchen
  screens follow stations instead of categories.
- As a cook I see only my station's lines on the kitchen display and bump them line by line; the expo screen shows
  when a whole table's course is ready.

**Acceptance criteria**

- `order_lines.course` and `fired_at`; held lines do not print and do not appear on station screens until fired.
- Stations replace printer categories without losing existing printer settings (migration maps categories to
  default stations).
- Per-station preparation targets drive the SLA colours.
- A fired course cannot be un-fired; voids of fired units print a "VOID" slip at the station.

### S2.3 Stock and recipe costing

**Stories**

- As a manager I keep ingredients with units and costs, and recipes for each dish and each option
  (an option can add or replace ingredients).
- Stock goes down when an order is completed; voided units return it, comped units do not.
- I record waste with a reason, deliveries from suppliers, and counts; I see theoretical against actual use.
- A dish goes sold out automatically when an ingredient runs out, and low stock raises an alert.

**Acceptance criteria**

- Stock is an append-only ledger of movements (sale, void, waste, delivery, count adjustment); the balance is
  derived, never edited.
- Unit conversion (g/kg, ml/l, piece) with decimal quantities, never floating point.
- Food cost % per dish on the menu screen; a variance report per period.
- Concurrency: movements are inserted, never updated, so two tills cannot lose an update.

### S2.4 Offline-capable till

**Stories**

- As a waiter I keep taking orders and cash payments when the internet drops; they are sent when it comes back.
- I can see clearly that the till is offline and how many orders are waiting.

**Acceptance criteria**

- A same-origin service worker caches the app shell (compatible with the current CSP and Trusted Types).
- Orders queue in IndexedDB with a client-generated `Idempotency-Key`; `POST /orders` accepts it, so a retry
  never creates a second order.
- On reconnect, a cart whose prices or availability changed is re-quoted and shown to the waiter before sending.
- Card terminal and cash payments can be recorded offline with idempotency keys; QR and online payments are
  disabled offline.
- Signing out clears the queue only after warning that unsent orders would be lost.

**Security notes**: API responses with guest data are not cached; the queue is per signed-in user and per till.

### S2.5 Delivery platform orders

**Stories**

- As a manager I connect Yemeksepeti, Getir Yemek and Trendyol Go with the credentials each platform gives me,
  and map their menu items and options to mine.
- Platform orders arrive on the kitchen display and printers like any other delivery order, tagged with their
  channel; accepting, ready and handed-to-courier are sent back to the platform.
- Sold-out dishes are switched off on the platforms too.

**Acceptance criteria**

- One `ChannelAdapter` port (`fetchOrders`/webhook, `acknowledge`, `updateStatus`, `setAvailability`) with one
  implementation per platform and a simulated one for the demo, like the payment gateways.
- Idempotent ingestion keyed on the platform's order id; prices are taken from the platform but checked against
  the mapped menu, and differences are flagged.
- Unmapped items stop the order in an "attention" state instead of being guessed.

**Security notes**: webhook signatures verified in constant time, with a timestamp window against replays;
credentials only from the environment or encrypted settings; a separate rate limit for channel endpoints;
`channel.audit` log of every order and status push.

### S2.6 Fiscal receipts: ÖKC and e-Arşiv

**Stories**

- As a cashier, closing a bill issues the legal receipt: through the yeni nesil ÖKC at the till for cash and card,
  or an e-Arşiv invoice for online payments and guests who ask for one with their VKN/TCKN.
- As a manager the Z report reconciles Sofra's takings with the ÖKC's.

**Acceptance criteria**

- A `FiscalDevice` port with an e-Arşiv implementation through a GİB-authorised integrator (özel entegratör)
  and an ÖKC implementation through the device vendor's local bridge (GMP-3).
- Each bill gets at most one fiscal document; issuing is idempotent and retried; a failure keeps the bill open
  and visible on the cashier screen.
- Fiscal documents are immutable records with the integrator's reference and a link to the PDF.

**Security notes**: integrator credentials and certificates from the environment; no guest tax numbers in logs;
documents and their audit kept for the legal retention period.

## Later

Smaller items that came up in the research, not yet scheduled:

- Reservations and a waitlist with SMS.
- Loyalty: points and stamp cards on the guest's phone number.
- Happy-hour and time-based pricing.
- Tips pooled and shared by hours worked.
- A customer-facing display at the till.
- Multi-branch: menus and reports across locations.

## Türkçe özet

**Sprint 1 (tamamlandı):** ürün seçenekleri, kayıtlı kasada PIN ile personel geçişi, masa işlemleri (iptal, ikram,
indirim, taşıma, birleştirme; yönetici PIN onayı), masadan QR sipariş ve garson/hesap çağrısı, mutfak/bar ESC/POS
yazıcıları ve bunları yöneten Ayarlar ekranı.

**Sprint 2 (plan), sırasıyla:**

1. **Yöneticiler için iki adımlı doğrulama (TOTP):** kurtarma kodları, şifreli saklanan gizli anahtar, iade ve gün
   kapamada şifre + kod.
2. **Kurslar ve mutfak istasyonları:** başlangıç/ana yemek/tatlı sırası, "ana yemekleri ateşle", istasyon bazlı
   mutfak ekranı ve yazıcı, expo ekranı.
3. **Stok ve reçete maliyeti:** malzeme, reçete (seçenekler dahil), satışta stok düşümü, fire, alım, sayım;
   yemek başına maliyet yüzdesi, stok bitince otomatik "tükendi".
4. **Çevrimdışı çalışan kasa (PWA):** internet kesilince sipariş ve nakit kaydı kuyrukta bekler, idempotency
   anahtarıyla tekrar gönderilir.
5. **Yemeksepeti, Getir, Trendyol entegrasyonu:** siparişler doğrudan mutfağa düşer, durum ve stok platforma
   geri bildirilir; imzalı webhook ve tekrar saldırısı koruması.
6. **Yasal fiş: ÖKC ve e-Arşiv:** hesap kapanınca yeni nesil ÖKC fişi ya da özel entegratör üzerinden e-Arşiv
   fatura; Z raporu mutabakatı.
