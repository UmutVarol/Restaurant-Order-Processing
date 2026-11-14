# Sofra Cloud: yayına alma, abonelik ve mağazalar

Bu belge Sofra'yı **ücretli bulut hizmeti** olarak çalıştırmak için gerekenleri anlatır. Restoranın kendi
sunucusundaki kurulum ([INSTALL.md](INSTALL.md)) bundan etkilenmez: orada abonelik yoktur.

## 1. Ne hazır (kodda)

| Parça | Durum |
|---|---|
| Birden çok işletme, işletme içinde şubeler, yalıtım testleri | hazır (S4.6) |
| Kayıt ol: 14 gün ücretsiz deneme, kart istemeden | hazır |
| Planlar: Başlangıç $19, İşletme $39, Zincir $29 (3+ şube), şube başına aylık; yıllıkta 2 ay bedava | hazır |
| Başlangıç planı sınırları: 1 şube, 5 etkin personel, 15 masa (aşılınca `PLAN_LIMIT`) | hazır |
| Deneme bitince / ödeme 14 günden fazla gecikince **yalnızca okuma**: veri silinmez; rapor, dışa aktarma, giriş ve ödeme çalışır; sipariş alınamaz (`402 SUBSCRIPTION_INACTIVE`). Ödeme gelince hemen açılır | hazır |
| İşletmenin kendi iyzico hesabı (misafir telefondan öder), şifreli saklanır | hazır |
| Paddle ile tahsilat: ödeme sayfası, kart/fatura sayfası, imzalı webhook, şube sayısı değişince otomatik fiyat güncelleme | hazır, **sandbox'ta denenmeli** |
| "Plan ve ödeme" ekranı, deneme bitiyor / ödeme gecikti bandı, 6 dil | hazır |
| Mağaza uygulamaları için kabuklar (`mobile/`), Android doğrulama dosyası | hazır, **derlenmesi sizde** |
| Lisans: bundan sonraki kod "tüm hakları saklıdır" | hazır |

## 2. Sunucu

Bulut, aynı Docker imajıyla ve `cloud` profiliyle çalışır:

```bash
SPRING_PROFILES_ACTIVE=postgres,cloud
CLOUD_BASE_DOMAIN=sofra.app          # işletmeler: <adres>.sofra.app, uygulama: app.sofra.app
BILLING_PROVIDER=PADDLE
BILLING_API_KEY=...                  # Paddle > Developer tools > Authentication
BILLING_WEBHOOK_SECRET=...           # Paddle > Notifications > (webhook) > secret key
BILLING_BASE_URL=https://sandbox-api.paddle.com   # canlıda https://api.paddle.com
PRICE_STARTER_MONTHLY=pri_...  PRICE_STARTER_YEARLY=pri_...
PRICE_BUSINESS_MONTHLY=pri_... PRICE_BUSINESS_YEARLY=pri_...
PRICE_CHAIN_MONTHLY=pri_...    PRICE_CHAIN_YEARLY=pri_...
```

- **DNS:** `sofra.app` ve `*.sofra.app` sunucuya (ya da yük dengeleyiciye) yönlenir; joker sertifika için Caddy'de
  DNS doğrulaması ya da Cloudflare kullanın.
- **Sağlık ve metrikler** bulutta ayrı bir porta (`9090`) taşınır ve dışarı açılmaz: işletme yöneticileri
  platformun sağlık ve metrik bilgilerini göremez. Yük dengeleyicinin sağlık kontrolü
  `http://<iç adres>:9090/actuator/health/readiness` olmalı.
- **Veritabanı:** yönetilen PostgreSQL (yedekli). Gece yedeği ve geri yükleme [OPERATIONS.md](OPERATIONS.md)'deki
  gibi.
- **Misafirin telefondan ödemesi (iyzico):** her işletme kendi iyzico üye işyeri hesabını **Ayarlar › Online ödeme
  hesabı** kartından bağlar (yalnızca tüm şubelerin yöneticisi, şifre onayıyla). Anahtarlar AES-GCM ile şifrelenip
  saklanır, bir daha gösterilmez; para doğrudan işletmenin hesabına gider. Şifreleme anahtarı olarak
  `TOTP_ENCRYPTION_KEY` kullanılır (`openssl rand -base64 32`); bulutta mutlaka ayarlanmalı ve yedeklenmeli, kaybolursa
  kayıtlı anahtarlar çözülemez ve işletmelerin yeniden girmesi gerekir. Sunucu genelinde iyzico hesabı yoktur
  (`PAYMENT_PROVIDER=NONE`).
- **e-Arşiv ve paket servis platformları** bugün hâlâ sunucu genelinde ayardır; işletme başına ekranları gelene kadar
  bulutta kapalıdır.
- **Yazıcılar:** buluttaki sunucu restoranın yerel ağındaki yazıcıya doğrudan ulaşamaz. Bulutta mutfak fişi için
  restoranda çalışacak küçük bir "yazıcı köprüsü" gerekir (sonraki iş).

## 3. Paddle (dolar ile tahsilat)

Paddle "satıcı" olarak çalışır: kartı o çeker, faturayı o keser, müşterinin ülkesindeki KDV'yi o öder. Sofra kart
bilgisini hiç görmez.

1. paddle.com'da hesap açın; Türkiye'den satıcı kabul ettiklerini **kendi ülke listelerinden teyit edin**.
2. Önce **sandbox** hesabında: Catalog > Products: "Sofra Başlangıç", "Sofra İşletme", "Sofra Zincir". Her birine
   iki fiyat: aylık ($19 / $39 / $29) ve yıllık ($190 / $390 / $290), "per unit" (adet = şube sayısı).
3. Checkout > Settings: **default payment link** olarak `https://app.sofra.app/` ekleyin ve alan adını onaylatın
   (Paddle ödeme sayfası açmak için bunu ister).
4. Notifications > New destination: `https://app.sofra.app/api/v1/public/billing/webhook/paddle`; olaylar:
   `subscription.created`, `subscription.updated`, `subscription.activated`, `subscription.past_due`,
   `subscription.canceled`, `subscription.paused`, `subscription.resumed`. Gizli anahtarı `BILLING_WEBHOOK_SECRET`
   olarak girin.
5. Sandbox'ta uçtan uca deneyin: kayıt ol > Plan ve ödeme > İşletme > test kartıyla öde > durum "Etkin" olmalı;
   Paddle'dan aboneliği "past due" yapın > bant görünmeli; iptal edin > yalnızca okuma.
6. Kod Paddle API'sinin 2026 belgelerine göre yazıldı; bu ortamdan Paddle belgelerine erişilemediği için istek
   biçimleri sandbox'ta mutlaka doğrulanmalı (`PaddleBillingProvider`). Sorun çıkarsa bana hata kaydını iletin.

**iyzico Abonelik (TL):** Türk kartlarında daha yüksek onay oranı için ikinci sağlayıcı olarak eklenebilir; kod
tarafında `BillingProvider` arayüzünün bir uygulaması daha olur. Türk şirketi ve iyzico üye işyeri sözleşmesi
gerekir.

## 4. App Store ve Google Play

Uygulama, mağazada **ücretsiz** ve giriş yapılan bir uygulamadır; abonelik yalnızca web sitesinden alınır. Uygulamanın
içinde satın alma düğmesi ya da "sitemizden alın" yönlendirmesi **yoktur** (Apple 3.1.3(c)/(f); Google Play
ödeme politikası). Sofra bunu kendisi yapar: mağaza uygulaması `?app=ios` / `?app=android` ile açılır, bu
cihazda "Plan ve ödeme" ekranı yalnızca planı gösterir, banttaki "Plan seç" düğmesi gizlenir.

**Google Play (TWA, en kolayı, tek seferlik $25):**

1. Google Play Console hesabı açın.
2. `npm i -g @bubblewrap/cli`, sonra `mobile/` klasöründe `bubblewrap init --manifest https://app.sofra.app/manifest.webmanifest`
   (değerler `mobile/twa-manifest.json`'da hazır) ve `bubblewrap build`.
3. İmzalama sertifikasının SHA-256 parmak izini sunucuya `ANDROID_CERT_SHA256` olarak girin; Sofra
   `/.well-known/assetlinks.json` dosyasını kendisi sunar (Play App Signing kullanıyorsanız Play Console'daki
   parmak izini de virgülle ekleyin).
4. `.aab` dosyasını Play Console'a yükleyin.

**App Store (Capacitor, yıllık $99):**

1. Apple Developer Program'a katılın; bir Mac ve Xcode gerekir.
2. `mobile/` klasöründe `npm install` ve `npm run ios`; Xcode'da imzalayıp arşivleyin.
3. Apple, yalnızca web sitesini gösteren uygulamaları reddeder (kural 4.2). Kabuk bu yüzden yerel bildirim ve
   titreşim eklentileriyle gelir; yeni sipariş bildirimi (APNs) bir sonraki iştir ve başvurudan önce eklenmelidir.
4. İncelemeye bir **demo hesabı** verin (ör. demo işletmesi + yönetici şifresi) ve notta "abonelik web'den
   alınır, uygulama içinde satış yok" yazın. Kuralların güncel metnini başvurudan önce yeniden okuyun.

Misafirlerin uygulama indirmesi gerekmez: masadaki QR tarayıcıda açılır.

## 5. Kodun kapatılması: sizin yapacaklarınız

1. **Depoyu gizli yapın:** GitHub > depo > Settings > General > en altta *Change repository visibility* > *Private*.
2. **Demo sitesi:** gizli depoda GitHub Pages ücretli plan ister; demoyu Vercel ya da Cloudflare Pages'e
   taşıyabiliriz (`demo/` klasörü statik, olduğu gibi yayımlanır).
3. **Tek satırlık kurulum** (`deploy/get.sh`) depo gizli olunca herkese açık çalışmaz; bulut ürünüyle gereksizleşir.
   Kurumsal (kendi sunucusunda) müşteriler için imaj özel kayıt deposundan (GHCR, gizli) ve sözleşmeyle verilir.
4. 14 Kasım 2026'ya kadar yayımlanan sürümler MIT lisanslıdır; o kopyaları almış olanların hakkı geri alınamaz.
   Bundan sonraki her şey `LICENSE` dosyasındaki "tüm hakları saklıdır" lisansıyla korunur.
