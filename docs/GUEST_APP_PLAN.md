# Sofra misafir uygulaması: "restorana gir, uygulamayı aç, oradasın" (plan ve fizibilite)

Durum: fikir aşaması (22 Kasım 2026). Kullanıcının fikri; uygulamaya geçmeden önce kararlar ve tasarım adayları
onaylanacak.

## Fikir

Sofra herkesin telefonunda bulunan bir uygulama olur. Kişi bir restorana girip uygulamayı açtığında en yakın işletme
çıkar ("Sofra Bistro'dasın gibi görünüyor"), kişi onaylar ve o restoranın menüsünü, siparişini, ödemesini ve diğer
hizmetlerini kullanmaya başlar.

## Fizibilite özeti

**Teknik olarak yapılabilir; asıl zorluk teknoloji değil, yaygınlaşma.**

| Konu | Değerlendirme |
|---|---|
| Konumla en yakın işletmeyi bulmak | Yapılabilir, ama tek başına yeterince kesin değil: telefon GPS'i açık havada yaklaşık 5 m, bina içinde ve sıkışık sokaklarda (İstiklal, AVM) çok daha kötü. Yan yana iki kafe ayırt edilemeyebilir. **Bu yüzden onay adımı şart** (fikirde zaten var) ve birden çok aday listelenmeli. |
| Masayı bilmek | Sipariş masaya gider; restoranı bilmek yetmez. Masa, masadaki QR veya NFC etiketiyle (iPhone XS ve sonrası etiketi arka planda okur) ya da masa numarası + garson onayıyla belirlenir. Sofra'daki servis modu kuralları (ilk sepet garson onayı ister) uzaktan şaka siparişlerine karşı zaten koruma sağlıyor. |
| Uygulama indirmeden kullanım | Kritik. Masadaki QR bugün web menüsünü açıyor; uygulama yüklüyse aynı bağlantı uygulamayı açar (universal / app links), değilse web çalışmaya devam eder. iOS'ta App Clip ile kurulumsuz hafif sürüm de mümkün. **Uygulama hiçbir zaman zorunlu olmamalı.** |
| Konum ve gizlilik (KVKK, mağaza kuralları) | Konum yalnızca uygulama açıkken ve izinle alınır, saklanmaz, işletmeye gönderilmez. Arka planda konum (girince bildirim) "her zaman" izni ister; Apple ve Google bunu sıkı denetler ve kullanıcıların çoğu vermez, bu yüzden ilk sürümde yok, sonra isteğe bağlı. |
| Ödeme | Restoranda yenen yemek fiziksel hizmet olduğu için mağaza içi satın alma (Apple/Google payı) gerekmez; iyzico kullanılabilir. |
| Mevcut altyapı | Masa QR oturumları, misafir siparişi ve ödemesi, canlı sipariş takibi, alerjenler, FCM bildirimleri, çok işletmeli yapı ve Capacitor kabukları hazır. Eksik olanlar: misafir hesabı (işletmeler arası), işletme konumu ve rehberi, yakındakileri bulma servisi, "check-in" kaydı, misafir uygulaması. |

## Misafir neden indirsin? (değer önerisi)

Her restoranda aynı web menüsünü açmak indirme sebebi değil. Uygulamanın verdiği ek değer:

1. **Tek hesap, her restoranda:** kayıtlı kart (iyzico kart saklama), fatura bilgisi, dil ve alerjen profili bir kez girilir.
2. **Sofra puanı:** bütün Sofra restoranlarında geçerli sadakat puanı ve damga kartları (en güçlü indirme sebebi).
3. **Cüzdan:** geçmiş siparişler, e-Arşiv faturaları ve fişler tek yerde; "geçen seferki gibi" tekrar sipariş.
4. **Arkadaşlarla hesap bölme:** aynı masadaki kişiler kendi telefonundan kendi payını öder.
5. **Keşfet:** yakındaki Sofra restoranları, canlı doluluk / bekleme süresi, rezervasyon ve gelmeden önce sipariş.
6. **Bildirimler:** "siparişin hazır", rezervasyon hatırlatması, sadece izin verilirse restoran kampanyaları.

## Restoran için değer

Bugün anonim olan misafiri tanımak (izinli müşteri listesi), sadakat ve geri dönüş, daha az garson yükü, platform
komisyonu olmadan kendi müşterisine ulaşma.

## Akış (taslak)

1. Uygulama açılır, izin varsa konum alınır (yalnızca açıkken).
2. Sunucu, yakındaki ve rehberde görünmeyi seçmiş şubeleri mesafe ve konum doğruluğuna göre sıralar.
   - Tek ve net aday: "Sofra Bistro'dasın gibi görünüyor. **Evet, buradayım** / Başka bir yer".
   - Birden çok aday: en yakın 3–5 işletme listesi.
   - Hiç aday yok: "Yakında Sofra kullanan bir yer yok"; keşfet sayfası.
3. Onaydan sonra işletmenin sayfası açılır (menü, garson çağır, hesap iste, rezervasyon).
4. Sipariş için masa bağlanır: masadaki QR/NFC, ya da masa numarası (ilk sepet garson onayına düşer).
5. Sipariş, takip, ödeme ve fatura bugünkü misafir akışıyla aynı; hesapla bağlantılı olarak kaydedilir.

Kesinliği artırmak için ileride: restoranın Wi-Fi adı (Android'de okunabilir, iOS'ta kısıtlı), isteğe bağlı BLE
beacon (ucuz, iBeacon iOS'ta çalışır), son okutulan QR.

## Teknik plan

- **İşletme rehberi:** şubeye enlem/boylam, adres, rehberde görünme izni, çalışma saatleri. Yakındakileri bulma
  (başlangıçta sınırlayıcı kutu + mesafe hesabı; büyüyünce PostGIS).
- **Misafir hesabı:** işletmelerden bağımsız, platform düzeyinde (satır güvenliği dışında ayrı tablolar); telefonla
  tek kullanımlık kod (SMS sağlayıcısı gerekir: maliyet) veya Apple/Google ile giriş; KVKK açık rıza kayıtları,
  hesabı silme.
- **Check-in:** misafir, şube, zaman, yöntem (konum/QR/NFC), masa oturumuna bağlanma; restoran yalnızca izin verilen
  bilgileri görür.
- **Uygulama:** iki ayrı mağaza uygulaması önerilir: misafir için **Sofra**, personel için **Sofra İşletme**
  (izinler ve mağaza tanıtımı karışmaz). Capacitor + konum, NFC ve bildirim eklentileri; universal/app links.
- **Önce web:** tarayıcıdaki konum özelliği HTTPS'te telefonda çalışır; "yakındaki restoran" akışı uygulamadan önce
  PWA olarak denenebilir.

## Aşamalar

| Aşama | İçerik | Kaba süre |
|---|---|---|
| 1. Web prototip | Şube konumu ve rehber izni, yakındakileri bulma, "burada mısın?" onayı, işletme sayfası; PWA'da | 2–3 hafta |
| 2. Misafir hesabı | Telefonla giriş, profil (dil, alerjen, fatura), sipariş geçmişi, kayıtlı kart | 3–4 hafta |
| 3. Misafir uygulaması | Sofra uygulaması, derin bağlantılar, NFC masa okutma, bildirimler, App Clip | 4–6 hafta |
| 4. Ağ etkisi | Sofra puanı, arkadaşla hesap bölme, keşfet, gelmeden önce sipariş, rezervasyon | sürekli |

## Riskler

- **Tavuk–yumurta:** restoran yoksa misafir indirmez, misafir yoksa restoran ilgilenmez. Çözüm: web her zaman
  çalışır, uygulama bir yükseltmedir; işletmeler arası puan indirme sebebi olur; restoran masadaki QR'da uygulamayı
  önerir.
- **Konum yanılması:** onay adımı ve aday listesi; masa QR/NFC ile kesinleşir.
- **Gizlilik algısı:** konum yalnızca açıkken, saklanmadan; açık ve kısa izin metinleri.
- **Maliyet:** SMS kodları, mağaza hesapları, bildirim altyapısı.

## Karar bekleyenler

- Misafir uygulamasının adı ve işletme uygulamasından ayrı olup olmayacağı.
- Misafir girişi: telefonla SMS kodu mu (maliyetli, Türkiye'de alışılmış), Apple/Google ile giriş mi, ikisi mi?
- Sofra puanı ilk sürümde olacak mı?
- İlk adım: web prototip mi, önce tasarım adayları mı?
