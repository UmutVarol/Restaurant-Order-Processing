# Sofra Cloud: ücretli ürün planı

Yayına alma adımları: [CLOUD.md](CLOUD.md).

Hedef: Sofra'yı restoranların aylık abonelikle kullandığı, kurulum gerektirmeyen bir bulut hizmetine çevirmek.
Zincirler ve birçok bağımsız işletme aynı sistemde, birbirinin verisini görmeden çalışacak. Kaynak kodu kapalı
olacak.

## Sıra

| # | İş | Ne zaman |
|---|---|---|
| 1 | **S4.3** Vardiya, mesai saati, bahşiş havuzu | tamamlandı |
| 2 | **S4.5** Yoğun akşam yük testi (40 masa, 12 telefon, 3 mutfak ekranı) | tamamlandı |
| 3 | **S4.6** Çok işletme ve zincir altyapısı: işletme (abonelik birimi) › şubeler; her işletmenin verisi ayrı | tamamlandı |
| 4 | **S5.1** Kayıt ol ve 1 dakikada kullanmaya başla (kurulum yok) | tamamlandı |
| 5 | **S5.2** Abonelik ve ödeme, plan sınırları, deneme süresi | kodda hazır; Paddle sandbox denemesi bekliyor |
| 6 | **S5.3** Android (Google Play) ve iOS (App Store) uygulamaları | kabuklar hazır; derleme ve mağaza hesapları sizde |
| 7 | **S5.4** Kodun kapatılması (aşağıda; bir kısmı sizin yapacağınız ayarlar) | lisans değişti; depoyu gizlemek sizde |

## Fiyat (dolar, pahalı olmayan)

Her şube için aylık. Yıllık ödemede 2 ay bedava. **14 gün ücretsiz deneme, kart istemeden.**

| Plan | Aylık / şube | Kimin için | İçindekiler |
|---|---|---|---|
| **Başlangıç** | **$19** | Kafe, küçük lokanta | 1 şube, 5 personel, 15 masa; QR menü ve sipariş, mutfak ekranı, kasa, rezervasyon, 6 dil |
| **İşletme** | **$39** | Tam servis restoran | Sınırsız personel ve masa; QR ile online ödeme, paket servis platformları, stok ve reçete maliyeti, ÖKC / e-Arşiv bağlantısı, vardiya ve bahşiş, ayrıntılı raporlar |
| **Zincir** | **$29** (3+ şube) | 3 ve üzeri şube | İşletme planındaki her şey + merkezi menü, şubeler arası raporlar, şube yöneticileri |

- Türkiye'deki müşteriler için aynı fiyatların TL karşılığı, kur değiştikçe çeyrek dönemde bir güncellenir.
- Ödeme komisyonları (iyzico vb.) ve donanım fiyata dahil değildir.
- Ödeme gecikirse veri silinmez: 14 gün uyarı, sonra yalnızca okuma modu (rapor ve dışa aktarma çalışır, yeni
  sipariş alınmaz). Ödeme yapılınca hemen açılır.

Karşılaştırma için: Türkiye'deki bulut adisyon programları genelde aylık birkaç yüz ile birkaç bin TL arasında;
$19–39 bu aralığın içinde kalır.

## Abonelik tahsilatı

İki seçenek var. İkisi de abonelik ve otomatik yenileme destekler:

| | Kimden tahsil | Artısı | Dikkat |
|---|---|---|---|
| **Paddle** (önerilen, dolar) | Dünyanın her yerinden | Satıcı sıfatıyla (merchant of record) KDV ve vergileri o öder; ABD şirketi kurmak gerekmez. Türk yazılım şirketleri kullanıyor. | Yaklaşık %5 + 50¢ komisyon (doğrulanmadı). Türkiye'den hesap açılabildiğini kendi ülke listelerinden teyit edin. |
| **iyzico Abonelik** (TL) | Türkiye'deki kartlar | Yerel kartlarda yüksek onay oranı, taksit | Türk şirketi gerekir; yurt dışı müşteri için uygun değil. |

Stripe'ın Türkiye'deki durumu kaynaklara göre çelişkili. Stripe'ın kendi ülke listesinde görmeden ona
güvenmeyin. Kod tarafında tahsilat bir arayüzün arkasında olacak (Sofra zaten iyzico ve simüle ödeme için bu
yapıyı kullanıyor). Yani sağlayıcı sonradan değiştirilebilir.

## Kurulum: hiç kurulum olmayacak

- **Bulut (ana ürün):** restoran sitede "14 gün ücretsiz dene"ye basar. İşletme adı, e-posta ve şifre girer.
  Örnek menü ve masalarla hazır restoran açılır. Tablet ve telefonlar `isletme.sofra.app` adresini açar ya da
  uygulamayı mağazadan indirir. Sertifika, sunucu, Docker gibi adımların hiçbiri yok.
- **Kendi sunucusunda (yalnızca kurumsal):** bugünkü `deploy/` kurulumu korunur, ancak imaj özel bir kayıt
  deposundan ve lisans anahtarıyla gelir. İnternetsiz çalışması gereken büyük zincirler için.

## App Store ve Google Play

Evet, eklenebilir. Sofra zaten telefona uygulama gibi yüklenen bir web uygulaması (PWA). Mağazalar için:

| | Nasıl | Maliyet | Not |
|---|---|---|---|
| **Google Play** | PWA'yı TWA (Trusted Web Activity) ile paketlemek | Tek seferlik $25 | En kolayı; birkaç günlük iş. |
| **App Store** | Capacitor ile yerel kabuk + yerel özellikler: yeni sipariş bildirimi, Bluetooth fiş yazıcısı, kamera ile QR | Yıllık $99 | Apple, web sitesini sadece paketleyen uygulamaları reddeder (kural 4.2). Yerel özellikler bu yüzden gerekli. |

**Ödeme App Store'da olmayacak.** Uygulama yalnızca giriş yapılan, ücretsiz bir uygulama olacak; abonelik web
sitesinden alınacak. Apple bu modele işletmelere satılan hizmetler ve web tabanlı ücretli araçların ücretsiz
uygulamaları için izin veriyor (kural 3.1.3(c) ve (f)). Koşulu da var: uygulamanın içinde satın alma düğmesi ya da
"sitemizden satın alın" yönlendirmesi olmayacak. Böylece Apple'a %15–30 komisyon ödenmez. Kuralların güncel
metni başvurudan önce yeniden okunmalı.

Misafirlerin uygulama indirmesi gerekmez: masadaki QR tarayıcıda açılır.

## Kodun kapatılması

Sizin yapmanız gerekenler (ben GitHub ayarlarını değiştiremem):

1. **Depoyu gizli yapın:** GitHub › depo › Settings › General › en altta "Change repository visibility" › Private.
2. **Demo sitesi:** gizli depoda GitHub Pages ücretli plan ister. Demoyu ayrı bir yere (Vercel, Cloudflare Pages)
   taşıyabiliriz; ben hazırlarım.
3. **Fork'lar:** depoyu daha önce fork'layan ya da indiren varsa o kopyalar onlarda kalır. Gizli yapmak sadece
   bundan sonrasını korur.

Benim yapacaklarım:

- **Lisans:** bugüne kadar yayımlanan kod MIT lisanslı. O sürümleri alanların hakkı geri alınamaz. Bundan sonraki
  kod için `LICENSE` dosyasını "tüm hakları saklıdır" lisansıyla değiştiririm.
- **Tek satırlık kurulum** (`get.sh`) gizli depoda çalışmaz. Bulut ürünüyle zaten gereksiz kalıyor. Kurumsal
  kurulum lisans anahtarı ve özel imaj deposuyla çalışacak şekilde değişir.
- **Asıl koruma bulutun kendisi:** kod müşterinin bilgisayarına hiç gitmez; tarayıcıya giden yalnızca arayüz
  dosyalarıdır.

## S4.6: çok işletme ve zincir (teknik özet)

- **İşletme** (abonelik ve fatura birimi) › **Şube** (masalar, siparişler, kasa, mutfak) › **Personel** (bir ya da
  birden çok şubede yetkili).
- Her veri satırı bir işletmeye ait. Veritabanı katmanında her sorgu otomatik olarak giriş yapan kişinin
  işletmesiyle sınırlanır (Hibernate'in işletme kimliği desteği). Ek güvence olarak PostgreSQL satır güvenliği
  (RLS) de kullanılır.
- Testler, bir işletmenin diğerinin hiçbir verisini göremediğini ve değiştiremediğini her uç nokta için kanıtlar.
- Zincir için: merkezi menü (şube bazında fiyat ve stok farkı), şubeler arası satış raporu, şube seçici.
- Misafir QR'ı ve ödeme bağlantıları işletmeyi kendi belirteçlerinden bulur; misafir hiçbir zaman başka
  işletmeye ulaşamaz.
