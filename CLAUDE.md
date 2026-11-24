# Sofra: çalışma notları (her oturumda okunur)

Kullanıcıyla **Türkçe** konuş. Geliştirme dalı: `claude/gracious-davinci-m3v8sk`; yalnızca oraya push et, istenmeden
PR açma. Tanıtım videosu commit'i (yerel `promo-video-v2` dalı) **push edilmez**; `main` üzerinde çalışılmaz.
Commit mesajları kullanıcının istediği imza satırlarıyla biter; depoya model adı yazılmaz.

## Durum (16 Kasım 2026)

Sprint 1–4 bitti; Sprint 5'in (Sofra Cloud, ücretli ürün) kodu bitti: kayıt ve deneme, planlar ve sınırlar,
Paddle aboneliği, yalnızca okuma modu, işletme başına iyzico, yazıcı köprüsü, mağaza kabukları, kapalı lisans.
Ayrıntı: `docs/ROADMAP.md`, `docs/SOFRA_CLOUD_PLAN.md`, `docs/CLOUD.md`, `docs/PRINT_BRIDGE.md`.
Fiyatlar (16 Kasım 2026, kullanıcının son kararı): şube başına aylık Başlangıç $29, İşletme $59, Zincir $79
(3+ şube; $99 liste fiyatından "zincire özel %20" gösterilir, İşletme'den yüksek kalır). `PlanCatalog` ile Paddle
fiyatları aynı olmalı.

## Kullanıcının yapacakları (kodla çözülemez)

Kullanıcının kendi takip ettiği ayrıntılı liste: `YAPILACAKLAR.md` (kullanıcı "bir sonraki aşamada" bakacak;
bir maddeye geçince oradan devam edilir, işaretler orada güncellenir).


- [ ] GitHub deposunu gizli yapmak; demoyu GitHub Pages'ten Vercel/Cloudflare'e taşımak (Claude hazırlar).
- [ ] Paddle hesabı açmak, Türkiye'den satıcı kabulünü teyit etmek; sandbox'ta 6 fiyat ($29/$59/$79 aylık,
  $290/$590/$790 yıllık) ve webhook tanımlamak.
- [ ] Alan adı ve bulut sunucusu (`sofra.app` belgelerde yer tutucu).
- [ ] Sunucuda `TOTP_ENCRYPTION_KEY` ayarlayıp yedeklemek (iyzico anahtarları bununla şifrelenir).
- [ ] iyzico üye işyeri ve e-Arşiv entegratörü sözleşmeleri.
- [ ] Google Play ($25) ve Apple Developer ($99/yıl) hesapları; iOS derlemesi için Mac.
- [ ] Firebase projesi (mağaza uygulaması bildirimleri): `google-services.json`, `GoogleService-Info.plist`, APNs anahtarı,
  sunucuda `FCM_SERVICE_ACCOUNT` (docs/OPERATIONS.md › Push notifications).
- [ ] DE/FR/RU/AR metinlerini ana dili bu diller olan birine okutmak.

## Claude'un yapacakları (sırayla önerilen)

1. [x] e-Arşiv ve paket servis platformları için işletme başına ayar ekranı (16 Kasım 2026, Ayarlar › Bağlantılar; V32).
   ÖKC yerel cihaz olduğundan bulutta yok; isteyen kendi sunucusunda çalıştırır.
2. [x] Yasal belge listesini şubeye göre süzmek (16 Kasım 2026: belge adisyonunun şubesine ait; V31).
3. [x] Yeni sipariş push bildirimi (16 Kasım 2026): tarayıcılara Web Push, mağaza uygulamalarına FCM. FCM için
   kullanıcının Firebase projesi ve `FCM_SERVICE_ACCOUNT` gerekir (docs/OPERATIONS.md); gerçek Firebase'e karşı denenmedi.
4. [x] Zincir planı vaatleri (16 Kasım 2026): merkezi menü, şube fiyatı/satışı (Menü › şube simgesi), şubeler arası rapor
   (Panel › Şubeler). Şube bazında ayrı stok sayımı yok (plan metninde "stok farkı" yalnızca satışa açık/kapalı olarak karşılandı).
5. [x] PostgreSQL satır güvenliği (RLS), ikinci yalıtım duvarı (16 Kasım 2026; `RowLevelSecurity`, her göçten sonra
   uygulanır; yerel PostgreSQL 16'da ve bulut modunda iki işletmeyle denendi).
6. [ ] Paddle'ı sandbox'ta kullanıcıyla birlikte doğrulamak (bu ortamdan Paddle'a erişim yok).
7. [ ] İsteğe bağlı: iyzico aboneliği (TL), sadakat, saatlik fiyat, müşteri ekranı.

## Bekleyen fikirler (22 Kasım 2026, kullanıcı "bir dursun, sonra düzeltip geliştireceğiz" dedi)

- **Misafir uygulaması fikri** (kullanıcının): restorana girip uygulamayı açınca en yakın işletme çıkar, onaylanır,
  menü/sipariş başlar. Plan, fizibilite ve eksikler: `docs/GUEST_APP_PLAN.md`; kararlar bekleniyor. Yön: Sofra misafir ve
  restoranın **ortak** platformu olacak; doğrulanmış ziyaret puanı ve "nasıldı?" bildirimi istendi.
  3. tur aday sayfası (G1–G3, H1–H3, P1–P3, R1): https://claude.ai/artifact/449CATSWPdLNyrXg54WddH. Kullanıcı **hepsini**
  seçti (22 Kasım 2026); web sürümü uygulandı (V36, `directory` paketi, `#/sofra`, `#/v/…`, P2/P3/R1; ayrıntı ve kalanlar
  `docs/GUEST_APP_PLAN.md` › Durum, `DESIGN.md` › Round three). Misafir akışı: `docs/GUEST_JOURNEY.md`.
- **Mevzuat: yapıldı (24 Kasım 2026, `docs/MEVZUAT.md`).** KDV dahil fiyat ve ürün bazında KDV oranı (V37; eski fiyatlar
  %10 artırılarak KDV dahil yapıldı, eski siparişler `taxIncluded=false` ile eski kuralla), oran bazında KDV satırları
  (kasa, fiş, ÖKC/e-Arşiv isteği), fiyat değişiklik tarihi, Yönetim › Fiyat listesi (kapı A4/masa A5, TL, KDV dahil,
  son değişiklik, QR), Bakanlık için CSV/JSON dışa aktarım (`sofra.price-list/1`; biçim yayımlanınca dönüştürülecek),
  servis/kuver/masa ücreti adıyla ürün engeli, e-Adisyon (V38, `compliance` paketi; Ayarlar › Bağlantılar, kasa kartı;
  gerçek entegratörle denenmedi). Mali müşavir teyidi kullanıcıda (`YAPILACAKLAR.md` › 3b).
- **Gerçek sorunlar:** komisyonsuz kendi online sipariş kanalı (platformlar %10–38), rezervasyonda kapora +
  SMS/WhatsApp hatırlatma (gelmeme %10–30), enflasyon araçları (toplu zam, marj uyarısı), personel maliyeti yüzdesi
  ve vardiya önerisi, talep tahmini/hazırlık listesi/israf, sadakat ve puanlama (seçici Google yorumu isteme Google kurallarına aykırı, yapılmayacak), yeni nesil ÖKC POS
  entegrasyonu, muhasebe dışa aktarımı (Logo/Mikro/Paraşüt), tedarikçiye otomatik sipariş.
- Önerilen sıra (mevzuat bitti): kendi online sipariş → kapora/hatırlatma → enflasyon araçları → sadakat → tahmin.

## Tasarım: kullanıcının "UI UX Tasarım Stüdyosu" (ikinci beyin)

Kaynak: kullanıcının notu "UI UX Tasarım Stüdyosu" (14 Kasım 2026). Bağlantılı notlar (43 kalıp, 24 tema × 12 hareket ×
18 anlatı, hareket token'ları, Sofra rehberi) bu ortamda **yok**; gerekirse kullanıcıdan istenir.

- İlke: gerçek bir kullanıcı sorununu yaratıcı, estetik, hızlı ve rahat çözmek. **"Wow" etkisi, kullanılabilirlik ve
  güvenlik kapılarından sonra** değerlendirilir.
- Üç aday yaratıcı yön (kullanıcı henüz seçmedi, marka/font/renk dayatmaz):
  1. **Akışın görünür hâli**: dağınık girdilerden okunur, kontrol edilebilir sonuca geçiş.
  2. **Hızın sakin hissi**: açık tipografi, güçlü eylem hiyerarşisi, küçük doğrulanmış durum tepkileri (sık
     kullanılan iş ekranları için).
  3. **Tek fikir, tek imza anı**: bir obje, tipografik motif ya da eşleşmeli kesmeyle yaratıcı anlatım.
- Çalışma şekli: her ekran için iki-üç aday; nedenleri, mobil karşılığı, hareket azaltma ve maliyetiyle sunulur;
  **kullanıcı seçer ve onaylar**, sonra uygulanır. Her tasarım 6 dilde (Arapça sağdan sola), açık/koyu temada,
  telefon/tablet/masaüstünde, `prefers-reduced-motion` ile ve dokunmatikte çalışmalı.
- Kullanıcının katalogları (15 Kasım 2026'da yüklendi, yalnızca isim/adres listeleri, içerikleri incelenmemiş):
  Impeccable (Apache-2.0; tasarım hatası denetim kuralları: geri sekmeli easing, sürekli yanıp sönen nokta, arka plan
  haresi, iç içe kart vb. **kalite kapısı olarak kullanılır**), ObsidianUI (MIT; flip-text, skeleton, status-bars,
  sonner, drawer gibi bileşen adları), v0 şablon adresleri (receipt, kitchen menu, order detail), iter0 ve
  designbookmark (site şablonları ve araç listeleri; lisansı belirsiz olanlardan kod alınmaz).
- Hareket adayları sayfası (A1–A4, B1–B2, C1–C2): https://claude.ai/artifact/57HPtoGUCVnQzq38iGbGoB. Kullanıcı
  15 Kasım 2026'da **sekizini de onayladı; hepsi uygulandı** (ayrıntı: `DESIGN.md` → Motion → Patterns in use).
  Sonraki tasarım işleri yine aynı yoldan: aday sayfası → kullanıcı seçer → uygulanır.
- 2. tur adaylar (D1 işletme rengi, D2 uzaktan okunur mutfak, D3 kategori şeridi, E1 geri al, E2 kaydırarak hazır,
  E3 hızlı arama, F1 masa bekleme halkası, F2 günün kartı): https://claude.ai/artifact/MAXCSVmBM4LJwjST84UgWa.
  Kullanıcı **sekizini de onayladı** (16 Kasım 2026); **hepsi uygulandı** (ayrıntı: `DESIGN.md` → Round two).
  2. turun eksikleri de tamamlandı (16 Kasım 2026): PIN gerekmeyen iptal, ikram ve indirim (yöneticinin kendi
  yaptıkları dahil) ve kat planında masa temizleme geri alınabilir; F1 eşikleri işletme ayarı (Ayarlar › Servis modu, V35); garson ekranı en uzun bekleyeni öne alır.
- Sofra'nın kendi tasarım dili: `DESIGN.md` (lokanta sıcaklığı, domates kırmızısı, safran; Fraunces + Inter;
  easing `cubic-bezier(.2,.8,.2,1)`; yalnızca transform/opacity animasyonu).
