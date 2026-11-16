# Roadmap

What has been built, and what comes next. Priorities come from a review of competing restaurant POS products
(Toast, Square for Restaurants, Lightspeed, Adisyo, SambaPOS) and open-source projects (Floreant POS, uniCenta,
Odoo POS), weighed for a small to mid-size restaurant in Türkiye.

Every sprint uses the same **definition of done**:

- the money and security rules live on the server and are covered by unit and API integration tests;
- the screens are covered by a Playwright test at desktop and phone widths, and pass the layout audit in all six
  languages (from Sprint 3);
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

## Sprint 2: run the business (done)

| | Feature | What it gives the restaurant | Where |
|---|---|---|---|
| S2.1 | **Two-step sign-in for managers** | TOTP with recovery codes, the secret sealed with AES-GCM, replays refused; step-up actions need password and code | `security`, V11 |
| S2.2 | **Courses and kitchen stations** | Hold and fire courses; stations instead of categories for printers and kitchen screens; tick-off per dish; station time targets; VOID slips | `menu`, `order`, `printing`, V12 |
| S2.3 | **Stock and recipe costing** | Append-only stock ledger, recipes per dish and option, deduction on completion, automatic sell-out, low-stock alerts, food cost %, use report | `inventory`, V13 |
| S2.4 | **Offline-capable till** | Service worker app shell, IndexedDB outbox per user and till, idempotent order placement, re-pricing before sending, offline cash and card | `sw.js`, `core/offline.js`, V14 |
| S2.5 | **Delivery platform orders** | Signed webhooks, idempotent ingestion, item mapping, attention queue, status and availability back to the platform | `channel`, V15 |
| S2.6 | **Fiscal receipts** | One legal document per bill (ÖKC receipt or e-Arşiv invoice), retried until issued, VKN/TCKN checks, Z report reconciliation | `fiscal`, V16 |

**What a live restaurant still needs.** The platform and fiscal integrations are built as ports with a generic
JSON adapter and a simulation. Connecting to the real services takes the partners' contracts and credentials:

- **Yemeksepeti, Getir Yemek, Trendyol Go:** each platform's order and menu API (partner or integrator access)
  mapped onto the canonical webhook and the adapter's status and availability calls.
- **Yeni nesil ÖKC:** the device vendor's GMP-3 bridge software on the till's network.
- **e-Arşiv:** a contract with a GİB-authorised özel entegratör and its API keys; a certified PDF comes back from
  the integrator.

The plan the sprint was built from follows.

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

## Sprint 3: the guest orders, the waiter serves (done)

Sprint 3 set out to make an evening take as little effort as possible, for guests and staff alike. Rather than
removing the waiter, it uses a **mixed model**: guests order and pay from the table's QR code, and the waiter
carries plates, looks after the table and steps in where a person is needed. Most shifts are worked from a phone,
so the staff app became phone-first along the way.

| | Feature | What it gives the restaurant | Where |
|---|---|---|---|
| S3.1 | **Service mode and cart rules** | Classic (waiters take orders), mixed (guests order; the first cart at an empty table, dishes marked "needs a waiter", carts over a limit and bursts wait for a waiter) or QR first (straight to the kitchen). Each held cart says why. | `guest`, V17 |
| — | **Phone-first staff app** | Tab bar per role with a More sheet, bottom sheets you drag away, the waiter's ticket as a sheet, orders and menus as cards, haptics, safe areas, motion on transform and opacity only | `app.js`, `core/ui.js`, `app.css` |
| — | **Numbered tables** | Tables 1 to 12 instead of zone letters; the zone stays its own column | V18 |
| — | **Six languages** | Turkish, English, German, French, Russian and Arabic (right to left), loaded per language, real plural rules, Western digits for Arabic prices; a test keeps every language complete and a layout audit keeps every screen tidy in each | `core/locales`, `LocaleFilesTest` |
| S3.2 | **Waiter service screen** | Plates ready to carry (one tap: served), guests waiting for a waiter, tables to clear once paid; a badge on the tab | `views/service.js`, `floor`, V19 |
| S3.3 | **Guests pay their own bill** | From the table's QR code with no waiter: everything, split equally, only what they had, or another amount; a tip; e-Arşiv invoice details given once and never shown back | `payment`, `fiscal` |
| S3.4 | **Live order status** | Every order at the table on a four-step bar, about when it will be ready (station targets), each dish held, ready or served; a buzz when it is ready | `guest` |
| S3.5 | **Allergens and suggestions** | The 14 allergens per dish (EU 1169/2011, Turkish Food Codex), a guest's "I can't eat" filter remembered on their phone, and a drink or dessert suggested with the cart | `menu`, V20 |

**What a live restaurant still needs.**

- **Paying from the phone** needs an iyzico merchant account (API key and secret); the demo uses the simulated
  provider. Invoices need the e-Arşiv integrator from Sprint 2.
- **Allergens** are only as good as the recipes: the restaurant declares them per dish, and the seeded values are
  examples. The guest's screen says so and asks guests with a serious allergy to tell their waiter.
- **Translations** of German, French, Russian and Arabic were written for the app; a native speaker should review
  them before a restaurant relies on them, as with any menu text.

## Sprint 4: ready for a real restaurant (in progress)

An assessment after Sprint 3 scored Sofra 6.5 out of 10 as a restaurant system: the table-side flow is strong,
but a restaurant could not run on it alone. This sprint takes the gaps in order of urgency, the ones that hurt
first in a live restaurant at the top. Work that needs a partner's contract or keys is listed, but cannot be
finished in code.

| Order | | Item | Why first | Status |
|---|---|---|---|---|
| 1 | S4.1 | **Operations: backups, restore, silent failures** | Losing a day's bills or not noticing that receipts stopped being issued is the costliest failure; it must exist before the first real evening | done |
| 2 | S4.2 | **Reservations and a waitlist** | The first thing an owner asks for; the floor plan only knows "now" | done |
| 3 | S4.3 | **Staff shifts, time clock and tip pooling** | Payroll and tips are a weekly argument without them; PINs and tip data already exist | done |
| 4 | S4.4 | **First-run setup and one-command install** | Today a new restaurant enters menu, tables and staff by hand through several screens | done |
| 5 | S4.5 | **Load test of a busy evening** | 40 tables, 12 phones and 3 kitchen screens at once have never been measured | done |
| 6 | S4.6 | **Many businesses and chains of branches** | Every table assumed one restaurant; the change is cheap now and expensive later. Decision record first, code after | done |
| — | — | **Live partners** (iyzico merchant, e-Arşiv integrator, ÖKC bridge, delivery platforms) | Needs contracts and credentials from the owner; adapters and simulations already exist | waiting on the owner |

### S4.1 Operations

- Nightly PostgreSQL backups with retention, a checksum and a check that each one can be read; a restore script
  that refuses to overwrite a live database by accident; both tested against a real PostgreSQL.
- Gauges for what fails without anyone seeing it: legal documents not issued, print jobs failed, online payments
  left unfinished, each with the age of the oldest.
- A health contributor that reports those as `DEGRADED` to managers, without taking the readiness probe down.
- Prometheus alert rules for them, plus errors and the app going away, checked with `promtool`.
- An operations runbook: what each alert means and what to do ([OPERATIONS.md](OPERATIONS.md)).
- A `backup` service in `docker-compose.yml` (profile `backup`) that runs the backup nightly.

### S4.2 Reservations and a waitlist

- Bookings for a time (table chosen ahead or on arrival) and a walk-in waitlist with the quoted wait, in one model:
  a party that will sit at a table.
- No double booking: every change that touches a table locks its row, then checks the other parties for the whole
  visit. Seating a party at a table that is booked soon is refused unless the host overrides it.
- The floor plan shows the seated party and each table's next booking (names and sizes only, never phone numbers).
- Late bookings become no-shows after 20 minutes; a seated party finishes when the last order at its table is paid.
- Phone numbers are erased 30 days after the visit (`restaurant.reservations.keep-contact-for`); the kitchen cannot
  see reservations at all.
- A Reservations screen in six languages, in the demo too, with browser tests.

### S4.4 First-run setup and one-command install

- `deploy/`: one command on a Linux computer (`get.sh` installs Docker and Git, `install.sh` asks two questions)
  starts Sofra, PostgreSQL, Caddy with HTTPS (Let's Encrypt for a domain, a local authority on the restaurant's
  network) and nightly backups, with fresh secrets and the demo switched off. Tested end to end with Docker.
- On the local network, `http://<server>` is a welcome page that hands each device the certificate and links to
  Sofra: no USB sticks. `update.sh` backs up first and checks health after; `reset-admin.sh` for a lost password.
- A Setup screen for managers: checklist before the first evening, staff accounts, tables.
- Released images for amd64 and arm64 (Raspberry Pi) on version tags (`.github/workflows/release.yml`).
- Owner's guide in Turkish: [INSTALL.md](INSTALL.md).

### S4.3 Staff shifts, time clock and tip pooling

- Everyone clocks themselves in and out (on a shared till, as the person signed in with their PIN); a unique
  column makes a double clock-in impossible even for two taps at once.
- Managers plan the rota (no one booked for two shifts at once, at most 16 hours), see every entry, clock out who
  forgot, and correct times with a reason that stays on the entry. Hours per person for payroll.
- Tip pooling: a day's tips (card, cash, online) are shared by minutes on the clock times a role weight (waiter 1,
  kitchen 0.5, manager 0; `restaurant.tips.weight.*`), to the kuruş with the largest-remainder method. Closing
  the day fixes the shares for payroll and needs everyone clocked out; each person sees their own tips.
- A Shifts screen for every role, six languages, demo backend, API and browser tests.

### S4.5 Load test of a busy evening

- `ops/loadtest/evening.mjs`: 40 tables, 12 waiters, 3 kitchen screens and QR guests at 10× a real evening.
  0 errors, worst p95 191 ms on PostgreSQL; no errors at 40× either. Results: [LOAD_TEST.md](LOAD_TEST.md).
- It found a deadlock between virtual threads and the connection pool (a database call inside a cache's lock
  pinned every carrier thread); fixed.

### S4.6 Many businesses and chains of branches

Decision record: [adr/0001-multi-business.md](adr/0001-multi-business.md).

- **Businesses (V24):** a `business_id` on 35 tables through Hibernate's `@TenantId`, uniqueness per business, the
  business taken from the signed token, the address or the business code; background jobs run once per business;
  live events, caches and settings per business. Sign-up for Sofra Cloud with a 14-day trial. Isolation tests
  prove, endpoint by endpoint, that one business cannot read or change another's data.
- **Branches (V26):** tables, orders, bills, printers, tills, reservations, shifts, time entries, day closings and
  tip pools belong to a branch; the menu, stock and staff accounts to the business. A person works in one branch
  or in all; a device of an owner picks the branch at the top. Queries see only the request's branch (a Hibernate
  filter), records fetched by id from another branch are not found, live events go to their own branch. The day
  report, its closing and the tip pool are per branch. Branches are opened and closed from Setup.

### Security review (with S4.4)

- Sessions end at once when an account is switched off, changes role or has its password reset or changed.
- Demo accounts are offered only in the demo; the installer's password is temporary and replaced at the first
  sign-in; a password policy; client addresses behind the proxy are the real ones; Java and OS versions are no
  longer public; API docs are off on a restaurant's server.

## Sprint 5: Sofra Cloud, the paid product (in progress)

Plan and prices: [SOFRA_CLOUD_PLAN.md](SOFRA_CLOUD_PLAN.md); going live: [CLOUD.md](CLOUD.md).

- **Sign-up** with a 14-day trial, no card (S4.6).
- **Plans** per open branch and month: Starter $39 (one branch, five staff, fifteen tables), Business $79, Chain
  $89 from three branches (shown as 25% off a $119 list price, still above Business); a year costs ten months. The Starter limits are enforced (`PLAN_LIMIT`).
- **Subscriptions** through a `BillingProvider`: Paddle (merchant of record, in dollars: checkout, customer portal,
  signed webhooks, the billed branches follow the open ones) and a simulation for development and the demo.
- **Never deleted for being unpaid:** an ended trial or a payment late beyond 14 days makes the business read-only
  (`402 SUBSCRIPTION_INACTIVE`); reading, exporting, signing in and paying keep working, and paying opens it at once.
- A **Plan and billing** screen and a reminder banner for the owner; inside the store apps nothing sells.
- **Store apps:** a Capacitor shell for the App Store and a Trusted Web Activity for Google Play (`mobile/`), with
  Digital Asset Links served by the app.
- A `cloud` profile: platform health and metrics on a private port, per-restaurant integrations off.
- **Plans hold to what they include** (402 `PLAN_FEATURE`), and the app hides what the plan leaves out.
- **Each business's own iyzico account** for guests' phone payments, keys sealed with AES-GCM.
- **Print bridge** (`bridge/sofra-print-bridge.mjs`): the cloud never connects to an address a business typed in;
  a program in the restaurant fetches the branch's tickets and prints them on the local network only.
- **Closed source from now on:** a proprietary licence; versions published before stay MIT.

## Later

Smaller items that came up in the research, not yet scheduled:

- Reservations and a waitlist with SMS.
- Loyalty: points and stamp cards on the guest's phone number.
- Happy-hour and time-based pricing.
- A customer-facing display at the till.
- Chains: per-branch menu availability and prices, and reports comparing branches.

## Türkçe özet

**Sprint 1 (tamamlandı):** ürün seçenekleri, kayıtlı kasada PIN ile personel geçişi, masa işlemleri (iptal, ikram,
indirim, taşıma, birleştirme; yönetici PIN onayı), masadan QR sipariş ve garson/hesap çağrısı, mutfak/bar ESC/POS
yazıcıları ve bunları yöneten Ayarlar ekranı.

**Sprint 2 (tamamlandı):** yöneticiler için iki adımlı giriş, kurslar ve mutfak istasyonları, stok ve reçete
maliyeti, çevrimdışı kasa, Yemeksepeti/Getir/Trendyol siparişleri ve yasal belge (ÖKC fişi, e-Arşiv fatura).
Platform ve mali entegrasyonlar port + genel JSON adaptör + simülasyon olarak hazır; canlıya bağlamak için
platformların, ÖKC üreticisinin ve özel entegratörün sözleşme ve erişim bilgileri gerekir.

**Sprint 3 (tamamlandı): misafir sipariş verir, garson servis eder.** Garsonu kaldırmak yerine **karma model**:
misafir masadaki QR koddan sipariş verir ve öder; garson tabakları taşır, masayla ilgilenir ve insan gereken yerde
devreye girer. Bu sprintte:

- **Servis modu ve sepet kuralları:** klasik, karma (ilk sepet, "garson onayı gerekir" işaretli yemekler, limit
  üstü ve art arda gelen sepetler garsonu bekler) ve önce QR; bekleyen sepet nedenini söyler.
- **Önce telefon:** role göre alt sekme çubuğu ve "Daha fazla" paneli, sürüklenerek kapanan alt paneller, garsonun
  adisyonu alt panelde, kart görünümleri, titreşim geri bildirimi.
- **Masa numaraları** 1'den 12'ye; **altı dil** (Arapça sağdan sola) ve her dilde taşma denetimi.
- **Garsonun servis ekranı:** taşınacak hazır tabaklar, garson bekleyen misafirler, temizlenecek masalar.
- **Misafir hesabını kendisi öder:** hepsi, eşit bölüşüm, yalnızca yedikleri ya da başka tutar; bahşiş ve bir kez
  verilen e-Arşiv fatura bilgisi.
- **Canlı sipariş takibi:** dört adımlı çubuk, yaklaşık hazır olma süresi, yemek yemek durum; hazır olunca titreşim.
- **Alerjenler ve öneriler:** yemek başına 14 alerjen, misafirin "yiyemediklerim" filtresi, sepete uygun içecek ve
  tatlı önerisi.

Canlıya almak için: telefondan ödeme için iyzico üye işyeri hesabı, fatura için e-Arşiv entegratörü; alerjenleri
restoran kendi tariflerine göre girmeli; Almanca, Fransızca, Rusça ve Arapça metinler ana dili bu diller olan biri
tarafından gözden geçirilmeli.

**Sprint 4 – S4.6 (tamamlandı): birden çok işletme ve zincirler.** Tek sunucuda birçok işletme (her kayıtta
işletme kimliği, işletmeler arası yalıtım testleri, 14 gün denemeli kayıt) ve işletme içinde şubeler: masalar,
siparişler, adisyonlar, yazıcılar, rezervasyonlar, vardiyalar, gün sonu ve bahşiş havuzu şubeye ait; menü, stok ve
personel hesapları işletmenin. Personel tek şubede ya da tüm şubelerde çalışır; tüm şubelerin yöneticisi cihazın
çalıştığı şubeyi üstten seçer. Şubeler Kurulum ekranından açılır ve kapatılır.

**Sprint 5 (sürüyor): Sofra Cloud, ücretli ürün.** Planlar (şube başına aylık Başlangıç $39, İşletme $79, 3+
şubede Zincir $89, liste fiyatı $119'dan indirimli; yıllıkta 2 ay bedava), Başlangıç sınırları, Paddle ile abonelik (ödeme sayfası, kart/fatura
sayfası, imzalı webhook), deneme bitince ya da ödeme 14 günden fazla gecikince yalnızca okuma (veri silinmez),
Plan ve ödeme ekranı, mağaza uygulaması kabukları ve kapalı lisans. Yayına alma: [CLOUD.md](CLOUD.md).

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
