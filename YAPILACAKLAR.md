# Senin yapacakların (Sofra'yı yayına almak için)

Bu liste, kodla çözülemeyen, hesap, sözleşme ya da karar gerektiren işleri tutar. Sırası önerilen sıradır;
bir adımı bitirince işaretle (`[x]`). Bir adıma geçtiğinde Claude'a "YAPILACAKLAR'daki N. adıma geçelim" demen yeter.

Son güncelleme: 16 Kasım 2026.

## 1. Önce karar ve hesaplar

- [ ] **Alan adı.** Belgelerde `sofra.app` yer tutucu olarak geçiyor. Alacağın adı Claude'a söyle; belgeler ve
  ayarlar ona göre güncellenir. Gerekenler: `app.<alan-adı>` (uygulama), `*.<alan-adı>` (işletme adresleri).
- [ ] **Bulut sunucusu.** Docker çalıştıran bir Linux sunucu ve PostgreSQL 16. Kurulum adımları:
  `docs/CLOUD.md` › 2. Sunucu ve `docs/OPERATIONS.md`.
- [ ] **GitHub deposunu gizli yapmak.** Depo › Settings › General › Danger Zone › Change visibility › Private.
  Lisans zaten kapalı (`LICENSE`).
- [ ] **Demoyu taşımak.** Demo şu an GitHub Pages için hazır (`.github/workflows/demo.yml`); depo gizli olunca
  Pages ücretsiz planda kapanır. Vercel ya da Cloudflare Pages hesabı açınca Claude taşımayı hazırlar.

## 2. Ödeme (abonelik geliri)

- [ ] **Paddle hesabı.** paddle.com'da satıcı hesabı aç; Türkiye'den satıcı kabul ettiklerini kendi ülke
  listelerinden teyit et.
- [ ] **Sandbox'ta ürün ve fiyatlar.** Üç ürün: Sofra Başlangıç, Sofra İşletme, Sofra Zincir. Her birine iki
  fiyat, "per unit" (adet = şube sayısı):

  | Plan | Aylık | Yıllık |
  |---|---|---|
  | Başlangıç | $29 | $290 |
  | İşletme | $59 | $590 |
  | Zincir | $79 | $790 |

  Zincir'in $99 liste fiyatı yalnızca uygulamada üstü çizili gösterilir; Paddle'da tanımlanmaz.
- [ ] **Webhook ve ayarlar.** Adım adım: `docs/CLOUD.md` › 3. Paddle (fiyat kimlikleri ve webhook gizli anahtarı
  sunucu ayarlarına girilir).
- [ ] **Claude ile birlikte sandbox denemesi.** Bu adım için oturumda Paddle'a erişim gerekir.

## 3. Restoranların ödemeleri ve yasal belgeler

- [ ] **iyzico üye işyeri sözleşmesi** (misafirin telefondan ödemesi için). Sofra Cloud'da her işletme kendi iyzico
  hesabını Ayarlar'dan bağlar.
- [ ] **e-Arşiv entegratörü sözleşmesi** (fatura/e-Arşiv için). Her işletme Ayarlar › Bağlantılar'dan kendi
  entegratörünü bağlar.
- [ ] **Sunucuda `TOTP_ENCRYPTION_KEY`.** iyzico ve entegratör anahtarları bununla şifrelenir; kaybolursa
  kaydedilmiş anahtarlar açılamaz. Üret, sunucuya gir ve güvenli bir yerde yedekle. Ayrıntı: `docs/OPERATIONS.md`.

## 3b. Mevzuat (kod hazır, teyit ve sözleşme sende) — ayrıntı: `docs/MEVZUAT.md`

- [ ] **Mali müşavir teyidi.** Menü fiyatları artık KDV dahil ve her ürünün kendi oranı var (yemek ve alkolsüz
  içecek %10, alkollü içecek %20). Teyit ettirilecekler: bu oranlar, paket teslimat ücretinin %10 sayılması,
  indirimin oranlara orantılı dağıtılması, fişte/faturada oran bazında KDV satırları.
- [ ] **Kapı önü ve masa fiyat listesi.** Yönetim › Fiyat listesi'nden kapıya A4, masalara A5 yazdırılıp asılır;
  fiyat her değiştiğinde yeniden yazdırılır (liste kendiliğinden "son fiyat değişikliği" tarihini taşır).
- [ ] **Bakanlığın elektronik fiyat listesi.** Gönderim biçimi yayımlanınca haber ver; Sofra şimdiden aynı bilgiyi
  CSV/JSON olarak veriyor (Fiyat listesi › CSV/JSON indir), biçim gelince ona dönüştürülür.
- [ ] **e-Adisyon** (yalnızca GİB bir işletmeye zorunluluk bildirirse): o işletme GİB yetkili bir e-Adisyon
  entegratörüyle anlaşır ve Ayarlar › Bağlantılar › e-Adisyon'a adresini ve anahtarını girer. Gerçek bir
  entegratöre karşı henüz denenmedi (bu ortamdan erişim yok); entegratörün adaptörü `docs/MEVZUAT.md`'deki biçimi
  karşılamalı.

## 4. Mağaza uygulamaları

- [ ] **Google Play geliştirici hesabı** ($25, bir kez).
- [ ] **Apple Developer hesabı** ($99/yıl) ve iOS derlemesi için bir Mac.
- [ ] **Firebase projesi** (uygulamalara bildirim için): `google-services.json`, `GoogleService-Info.plist`,
  APNs anahtarı ve sunucuda `FCM_SERVICE_ACCOUNT`. Ayrıntı: `docs/OPERATIONS.md` › Push notifications.
  Kod hazır, gerçek Firebase'e karşı henüz denenmedi.

## 5. Metinler

- [ ] **Almanca, Fransızca, Rusça ve Arapça metinleri** ana dili bu diller olan birine okut. Metinler
  `src/main/resources/static/assets/js/core/locales/` altında; düzeltmeleri Claude'a iletmen yeterli.

## Claude'un bekleyen işleri (senin kararını bekleyenler)

- Paddle sandbox denemesi (yukarıdaki 2. bölüm bitince).
- Claude'un geliştireceği her şey tek listede: `docs/BACKLOG.md` (sadakat, kapora ve hatırlatma, tedarikçiye
  otomatik sipariş, kendi online sipariş kanalı, muhasebe dışa aktarımı ve diğerleri). Hepsi yapılacak.
- 3. tur tasarım adayları: üç yaratıcı yönden birini seçersen (`CLAUDE.md` › Tasarım) aday sayfası hazırlanır.

## Sofra'yı bir kullanıcı gibi denemek

- **Tarayıcıda demo** (sunucu gerekmez, veriler sahte ve yalnızca senin tarayıcında tutulur):
  https://claude.ai/artifact/S7TWaNWspyevYSfo7h5Nzn
- **Hesaplar:** yönetici `admin` / `admin123`, garson `waiter` / `waiter123`, mutfak `chef` / `chef123`
  (PIN'ler: 2468, 1357, 9024).
