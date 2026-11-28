# Geliştirme listesi: yapılacaklar

Son güncelleme: 28 Kasım 2026. Kullanıcı bu listenin **hepsinin geliştirileceğini** söyledi. Bir maddeye geçince buradan
başlanır, bitince "Yapıldı" bölümüne taşınır. Kodla çözülemeyen, kullanıcının kendi işleri `YAPILACAKLAR.md`'de.

## 1. Sıradakiler (kullanıcının istediği sırayla)

- [ ] **Sadakat programı** (yarım). İşletmenin kendi damga kartı ya da puanı:
  - Telefonla üyelik, KVKK onayıyla; telefon uluslararası biçimde saklanır, personele maskeli gösterilir.
  - Ödenen hesap bir kez damga ya da puan kazandırır (en az harcama ayarı). Hesap ödenmeden ya da ödendikten sonra
    karta bağlanabilir.
  - Ödül kasada indirim olarak düşer, bir hesapta bir kez. Var olan bir indirimin üstüne binmez.
  - Misafir fiş sayfasında kendi kartını görür ve katılır; Ayarlar › Sadakat; panelde üye ve ödül sayısı.
  - Üye silme (KVKK talebi).
  - Taslaklar `docs/wip/loyalty/` altında: `V41__loyalty.sql.draft` (tablolar) ve `reward-discount.patch`
    (`OrderAdjustmentService.rewardDiscount`). Göç numarası kullanılmadan önce sıradaki boş numaraya göre ayarlanır.
- [ ] **Rezervasyonda kapora ve hatırlatma:**
  - Kapora kuralı (kişi sayısına göre, kişi başı ya da sabit).
  - Misafirin rezervasyon sayfası: kaporayı öde (iyzico), onayla, iptal et; iptal süresine göre iade.
  - Kapora oturulunca hesaptan düşer; gelmeyende tutulur.
  - Hatırlatma: WhatsApp ile tek dokunuş (hesap gerektirmez); SMS sağlayıcısı bağlanırsa otomatik (24 saat ve 2 saat
    önce). Misafirin onayı personele görünür.
- [ ] **Tedarikçiye otomatik sipariş:** hazırlık listesindeki eksik malzemelerden tek dokunuşla sipariş listesi;
  tedarikçi kartları; WhatsApp/e-posta ile gönderme; teslim alınınca stoğa giriş.
- [ ] **Komisyonsuz kendi online sipariş kanalı** (kullanıcı "aklımızda dursun" dedi): restoranın kendi sitesinden
  paket/gel-al siparişi; platformların %10–38 komisyonu yerine.

## 2. Son turdan kalan küçük eksikler

- [ ] Tahmin rezervasyonları ve özel günleri (bayram, maç, tatil) hesaba katsın.
- [ ] İsraf kaydı 5 saniyelik geri almadan sonra da düzeltilebilsin (yöneticinin düzeltme ekranı, stoğa ters kayıt).
- [ ] Fiyat listesi, toplu fiyat ve hazırlık ekranları için tasarım aday sayfası (aday → kullanıcı seçer → uygulanır).

## 3. Gerçek sorunlar listesinden

- [ ] **Muhasebe dışa aktarımı:** Logo, Mikro, Paraşüt biçimlerinde günlük satış, KDV ve ödeme dökümü.
- [ ] **Personel maliyeti yüzdesi ve vardiya önerisi:** vardiya ve saat verisinden ciroya oran; yoğun saatlere göre
  vardiya önerisi.
- [ ] **Yeni nesil ÖKC POS entegrasyonu:** kart ödemesi ve fiş aynı cihazda.

## 4. Misafir uygulamasından kalanlar (`docs/GUEST_APP_PLAN.md` › Durum)

- [ ] Ödemeden 30–60 dk sonra "nasıldı?" bildirimi (misafir uygulaması ve bildirim izni).
- [ ] Misafir hesabı: kayıtlı kart, fatura bilgisi, geçmiş siparişler, "geçen seferkinin aynısı".
- [ ] Sofra Puan: bütün Sofra restoranlarında geçen ortak puan.
- [ ] Keşfet sayfası: yakındaki restoranlar, doluluk, online rezervasyon.
- [ ] Masa numarasıyla bağlanma (konum doğrulaması ve garson onayıyla).
- [ ] iOS'ta NFC ile okutma ve App Clip (Apple Developer hesabı gerekir).

## 5. İsteğe bağlı

- [ ] iyzico ile TL abonelik (Paddle'a ek).
- [ ] Saatlik fiyat (happy hour).
- [ ] Müşteri ekranı (kasada misafire dönük ikinci ekran).

## 6. Belgeler

- [ ] Bitirme raporu (kullanıcının şablonu ile; ara rapor teslim edildi).

## Yapıldı

- 24 Kasım 2026: mevzuat (KDV dahil fiyat, ürün bazında KDV, fiyat listesi, Bakanlık dışa aktarımı, ek ücret engeli,
  e-Adisyon) — `docs/MEVZUAT.md`.
- 24 Kasım 2026: enflasyon araçları — Menü › Toplu fiyat (önizleme, yuvarlama, geri alma, geçmiş, V39) ve marj
  kontrolü (hedef maliyet, önerilen fiyat).
- 25 Kasım 2026: hazırlık listesi (geçmiş haftaların aynı gününe göre tahmin, eksik malzeme, hazırlandı işareti) ve
  israf takibi (yemek/malzeme, nedenler, Stok › İsraf raporu, V40).
- 25 Kasım 2026: kat planında uzun masa kodları sığıyor; demo yönetici girişiyle açılıyor.
